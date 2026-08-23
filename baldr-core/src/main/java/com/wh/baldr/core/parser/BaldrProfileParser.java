package com.wh.baldr.core.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import com.wh.baldr.core.model.CallTreeNode;
import com.wh.baldr.core.model.ProfileReport;

/**
 * Baldr 采样报告解析器。
 * 解析 {@code ProfilerCollector} 生成的 collapsed（折叠堆栈）格式报告，
 * 聚合热点方法及其采样占比，并构建简化调用树，供后续 AI 分析使用。
 *
 * <p>collapsed 格式每行形如：{@code com.foo.Bar.method;com.foo.Baz.inner 123}，
 * 即分号分隔的堆栈帧 + 空格 + 采样数。</p>
 *
 * @author rubant
 * @date 2026-08-14 21:26
 */
@Slf4j
public class BaldrProfileParser {

    /**
     * 匹配形如 {@code 12.34% (123 samples) com.foo.Bar.method} 的热点行（兼容旧格式）。
     * group(1)=百分比，group(2)=样本数（可选），group(3)=方法签名。
     */
    private static final Pattern HOTSPOT_PATTERN = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)?)%\\s*(?:\\((\\d+)\\s*samples?\\))?\\s*(.+?)\\s*$");

    /** 单个热点方法采样条目。 */
    @Getter
    public static class Hotspot {
        private final String method;
        private final double percent;
        private final long samples;

        public Hotspot(String method, double percent, long samples) {
            this.method = method;
            this.percent = percent;
            this.samples = samples;
        }

        @Override
        public String toString() {
            return String.format("%.2f%% (%d samples) %s", percent, samples, method);
        }
    }

    /**
     * 从报告文件解析热点列表。
     *
     * @param reportFile 报告文件路径，不能为空且必须存在
     * @return 按采样占比降序排列的热点列表（不可变）
     * @throws IOException 文件读取失败时抛出
     */
    public List<Hotspot> parseFile(String reportFile) throws IOException {
        if (reportFile == null || reportFile.trim().isEmpty()) {
            throw new IllegalArgumentException("reportFile must not be blank");
        }
        Path path = Paths.get(reportFile);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Report file not found: " + reportFile);
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        return parseLines(lines);
    }

    /**
     * 解析报告文本内容。自动检测格式：
     * collapsed 格式（分号分隔堆栈帧 + 采样数）或旧的热点百分比格式。
     *
     * @param content 完整报告文本，可为空
     * @return 按采样占比降序排列的热点列表（不可变）
     */
    public List<Hotspot> parseContent(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        String[] rawLines = content.split("\\r?\\n", -1);
        List<String> lines = new ArrayList<>(rawLines.length);
        Collections.addAll(lines, rawLines);
        return parseLines(lines);
    }

    private List<Hotspot> parseLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }

        // 检测是否为 collapsed 格式：存在包含分号和尾部数字的行
        boolean collapsed = lines.stream()
                .filter(l -> l != null && !l.trim().isEmpty())
                .anyMatch(l -> l.contains(";") && l.matches(".*\\s+\\d+\\s*$"));

        if (collapsed) {
            return parseCollapsed(lines);
        }
        return parseHotspotLines(lines);
    }

    /**
     * 解析 collapsed（折叠堆栈）格式。
     * 每行形如 {@code frame1;frame2;frame3 123}，按顶层帧聚合采样数。
     */
    /**
     * 判断一个栈帧是否为 Java 方法帧。
     * async-profiler collapsed 中 Java 帧形如 {@code com/foo/Bar.method} 或 {@code com.foo.Bar.method}；
     * native/内核帧形如 {@code __psynch_cvwait}、{@code CompressedWriteStream::CompressedWriteStream(int)}、
     * {@code write}。判定规则：含 C++ 作用域符 {@code ::} 视为 native；否则要求含类型分隔（{@code /} 或 {@code .}）
     * 且不以下划线开头（排除 libc/内核符号）。
     */
    private boolean isJavaFrame(String frame) {
        if (frame == null || frame.isEmpty()) {
            return false;
        }
        if (frame.contains("::")) {
            return false;
        }
        if (frame.startsWith("_")) {
            return false;
        }
        return frame.indexOf('/') >= 0 || frame.indexOf('.') >= 0;
    }

    /**
     * 从栈(栈底→栈顶)中自顶向下取最近的一个 Java 帧作为热点方法。
     * 采样时刻栈顶可能停在 native 调用(如锁等待/系统调用)，此时应归因到调用它的 Java 方法，
     * 而非丢弃整个样本，以保证热点百分比不失真。
     *
     * @param frames 分号切分后的栈帧数组(栈底→栈顶)
     * @return 栈顶方向最近的 Java 帧；若整条栈无 Java 帧返回 {@code null}
     */
    private String topJavaFrame(String[] frames) {
        for (int i = frames.length - 1; i >= 0; i--) {
            if (isJavaFrame(frames[i])) {
                return frames[i];
            }
        }
        return null;
    }

    private List<Hotspot> parseCollapsed(List<String> lines) {
        // 按方法名聚合采样数
        Map<String, Long> methodSamples = new LinkedHashMap<>();
        long totalSamples = 0;

        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            line = line.trim();
            // collapsed 格式：stackframes count
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace <= 0) {
                continue;
            }
            String stackPart = line.substring(0, lastSpace);
            String countPart = line.substring(lastSpace + 1).trim();
            long count;
            try {
                count = Long.parseLong(countPart);
            } catch (NumberFormatException e) {
                continue;
            }

            // 取栈顶方向最近的 Java 帧作为热点方法。
            // collapsed 堆栈自栈底到栈顶排列；采样时刻栈顶可能停在 native 帧(锁等待/系统调用/JIT)，
            // 此时归因到调用它的最近 Java 方法，既反映真实 self-time，又避免 native 符号混入火焰图。
            String[] frames = stackPart.split(";", -1);
            String topFrame = topJavaFrame(frames);
            if (topFrame == null) {
                // 整条栈无 Java 帧(纯 native 线程)，跳过，不计入 Java 热点
                continue;
            }

            methodSamples.merge(topFrame, count, Long::sum);
            totalSamples += count;
        }

        if (totalSamples == 0) {
            return Collections.emptyList();
        }

        List<Hotspot> hotspots = new ArrayList<>(methodSamples.size());
        for (Map.Entry<String, Long> entry : methodSamples.entrySet()) {
            long samples = entry.getValue();
            double percent = (samples * 100.0) / totalSamples;
            hotspots.add(new Hotspot(entry.getKey(), percent, samples));
        }

        hotspots.sort((a, b) -> Double.compare(b.getPercent(), a.getPercent()));
        log.info("Parsed {} hotspot entries from collapsed format (total samples: {})", hotspots.size(), totalSamples);
        return Collections.unmodifiableList(hotspots);
    }

    /**
     * 解析旧的热点百分比格式（兼容）。
     * 每行形如 {@code 12.34% (123 samples) com.foo.Bar.method}。
     */
    private List<Hotspot> parseHotspotLines(List<String> lines) {
        List<Hotspot> hotspots = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            Matcher matcher = HOTSPOT_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            try {
                double percent = Double.parseDouble(matcher.group(1));
                long samples = matcher.group(2) != null
                        ? Long.parseLong(matcher.group(2))
                        : 0L;
                String method = matcher.group(3).trim();
                if (!method.isEmpty()) {
                    hotspots.add(new Hotspot(method, percent, samples));
                }
            } catch (NumberFormatException e) {
                log.debug("Skip malformed hotspot line: {}", line);
            }
        }
        hotspots.sort((a, b) -> Double.compare(b.getPercent(), a.getPercent()));
        log.info("Parsed {} hotspot entries", hotspots.size());
        return Collections.unmodifiableList(hotspots);
    }

    /**
     * 提取采样占比最高的前 N 个热点方法。
     *
     * @param hotspots 热点列表
     * @param topN     数量上限，必须大于 0
     * @return 前 N 个热点（不可变）
     */
    public List<Hotspot> topN(List<Hotspot> hotspots, int topN) {
        if (topN <= 0) {
            throw new IllegalArgumentException("topN must be positive, but was: " + topN);
        }
        Objects.requireNonNull(hotspots, "hotspots must not be null");
        int limit = Math.min(topN, hotspots.size());
        return Collections.unmodifiableList(new ArrayList<>(hotspots.subList(0, limit)));
    }

    /**
     * 将报告文本解析为聚合模型 {@link ProfileReport}。
     * 支持 collapsed 格式和旧热点百分比格式，同时构建简化调用树。
     *
     * @param content 报告文本
     * @return 聚合报告模型（不为 null）
     */
    public ProfileReport parse(String content) {
        ProfileReport report = new ProfileReport();

        // 解析热点
        List<Hotspot> parsed = parseContent(content);
        List<com.wh.baldr.core.model.Hotspot> mapped = new ArrayList<>(parsed.size());
        for (Hotspot h : parsed) {
            com.wh.baldr.core.model.Hotspot model = new com.wh.baldr.core.model.Hotspot();
            model.setFunction(h.getMethod());
            model.setPercent(h.getPercent());
            model.setSamples(h.getSamples());
            mapped.add(model);
        }
        report.setHotspots(mapped);

        // 从 collapsed 格式构建调用树
        if (content != null && !content.isEmpty()) {
            CallTreeNode callTree = buildCallTree(content);
            report.setCallTree(callTree);
        }

        return report;
    }

    /**
     * 从 collapsed 格式构建完整多级调用树。
     * collapsed 每行形如 {@code frame0;frame1;frame2 count}，frame0 为栈底（线程入口），
     * frameN 为栈顶（实际执行帧）。本方法按路径逐帧合并，构造与 async-profiler HTML 火焰图
     * 一致的层级结构：root → frame0 → frame1 → … → frameN，同路径前缀共享节点。
     */
    private CallTreeNode buildCallTree(String content) {
        // 虚根节点，samples 累加后用于计算百分比
        Map<String, long[]> rootSamplesMap = new LinkedHashMap<>();
        // 用 Map<path, node> 共享同路径前缀的节点，key 为 "frame0\0frame1\0..." 路径串
        Map<String, CallTreeNode> nodeIndex = new LinkedHashMap<>();

        CallTreeNode root = new CallTreeNode();
        root.setFunction("root");
        root.setPercent(100.0);
        long[] totalHolder = {0};

        String[] lines = content.split("\\r?\\n", -1);
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace <= 0) {
                continue;
            }
            String stackPart = line.substring(0, lastSpace);
            String countPart = line.substring(lastSpace + 1).trim();
            long count;
            try {
                count = Long.parseLong(countPart);
            } catch (NumberFormatException e) {
                continue;
            }

            totalHolder[0] += count;

            // collapsed 格式：frame0;frame1;...;frameN，frame0 为栈底，frameN 为栈顶
            String[] frames = stackPart.split(";", -1);

            CallTreeNode parent = root;
            StringBuilder pathKey = new StringBuilder();
            for (String frame : frames) {
                if (frame.isEmpty()) {
                    continue;
                }
                // 只保留 Java 帧，跳过 native/内核帧(如 __psynch_cvwait、CppClass::method)，
                // 使 HTML 火焰图仅呈现 Java 调用路径；跳过后 Java 父子帧仍直接相连。
                if (!isJavaFrame(frame)) {
                    continue;
                }
                if (pathKey.length() > 0) {
                    pathKey.append('\0');
                }
                pathKey.append(frame);
                String key = pathKey.toString();

                CallTreeNode node = nodeIndex.get(key);
                if (node == null) {
                    node = new CallTreeNode();
                    node.setFunction(frame);
                    nodeIndex.put(key, node);
                    parent.getChildren().add(node);
                }
                // 累加该节点的样本数（借用 percent 字段暂存，最后统一换算）
                node.setPercent(node.getPercent() + count);
                parent = node;
            }
        }

        if (totalHolder[0] == 0) {
            return null;
        }

        // 将暂存在 percent 字段的 samples 换算为百分比，并对每层子节点按采样数降序排列
        convertSamplesToPercent(root, totalHolder[0]);
        return root;
    }

    /**
     * 递归将节点 percent 字段从「样本数」换算为「百分比」，并对子节点按采样数降序排列。
     */
    private void convertSamplesToPercent(CallTreeNode node, long total) {
        if (node.getChildren() != null && !node.getChildren().isEmpty()) {
            // 先排序（此时 percent 存的还是 samples，可直接比较）
            node.getChildren().sort((a, b) -> Double.compare(b.getPercent(), a.getPercent()));
            for (CallTreeNode child : node.getChildren()) {
                // 换算子节点百分比后递归
                double samples = child.getPercent();
                child.setPercent((samples * 100.0) / total);
                convertSamplesToPercent(child, total);
            }
        }
    }
}