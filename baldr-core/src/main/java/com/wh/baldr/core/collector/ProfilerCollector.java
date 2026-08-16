package com.wh.baldr.core.collector;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.wh.baldr.core.arthas.ArthasAgent;

import lombok.extern.slf4j.Slf4j;

/**
 * 基于嵌入式 Arthas 的性能采样收集器。
 * 通过 arthas-agent-attach 将 Arthas 加载进当前 JVM，暴露 HTTP API，
 * 再经 profiler 命令进行采样并生成报告，无需外部安装 Arthas。
 *
 * <p>注意：async-profiler 只能对 Arthas agent 所在的 JVM 采样，
 * 因此嵌入式方式采集的始终是「当前进程」；传入的 pid 仅用于校验/记录。</p>
 *
 * @author rubant
 * @date 2026-08-14
 */
@Slf4j
public class ProfilerCollector {

    /** 报告输出目录 */
    private static final String REPORT_DIR = "/tmp/baldr-ai";

    /** Arthas HTTP API 端口 */
    private static final int HTTP_PORT = 8563;

    /** Arthas telnet 端口 */
    private static final int TELNET_PORT = 3658;

    /** 异常触发采样时长（秒） */
    private static final int EXCEPTION_SAMPLE_DURATION = 30;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 支持的采样事件类型白名单 */
    private static final Set<String> SUPPORTED_EVENTS =
            new HashSet<>(Arrays.asList("cpu", "alloc", "lock"));

    /** 保证 Arthas 只初始化一次 */
    private static volatile boolean arthasStarted = false;

    private final String apiUrl = "http://127.0.0.1:" + HTTP_PORT + "/api";

    /**
     * 采集并生成报告。
     *
     * @param pid      目标进程 ID（嵌入式下应为当前进程），必须大于 0
     * @param duration 采样时长（秒），必须大于 0
     * @param event    事件类型：cpu / alloc / lock
     * @return 报告文件路径
     * @throws Exception 采样过程发生错误时抛出
     */
    public String collect(int pid, int duration, String event) throws Exception {
        validate(pid, duration, event);
        ensureArthasStarted();

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String reportFile = String.format("%s/profile-%s.collapsed", REPORT_DIR, timestamp);

        File reportDir = new File(REPORT_DIR);
        if (!reportDir.exists() && !reportDir.mkdirs()) {
            throw new IOException("Failed to create report directory: " + REPORT_DIR);
        }

        // 1. 启动采样
        executeCommand("profiler start --event " + event);
        log.info("Profiler started for pid={}, event={}", pid, event);

        // 2. 等待采样
        TimeUnit.SECONDS.sleep(duration);

        // 3. 停止并生成报告
        executeCommand("profiler stop --format collapsed --file " + reportFile);
        log.info("Profile report generated: {}", reportFile);

        return reportFile;
    }

    /**
     * 异常触发的短周期采样。
     *
     * @param pid 目标进程 ID
     * @param ex  触发采样的异常
     * @return CPU 采样报告文件路径
     * @throws Exception 采样过程发生错误时抛出
     */
    public String collectOnException(int pid, Throwable ex) throws Exception {
        String reportFile = collect(pid, EXCEPTION_SAMPLE_DURATION, "cpu");
        if (ex != null && ex.getMessage() != null && ex.getMessage().contains("GC")) {
            collect(pid, EXCEPTION_SAMPLE_DURATION, "alloc");
        }
        return reportFile;
    }

    private void validate(int pid, int duration, String event) {
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive, but was: " + pid);
        }
        if (duration <= 0) {
            throw new IllegalArgumentException("duration must be positive, but was: " + duration);
        }
        if (event == null || !SUPPORTED_EVENTS.contains(event)) {
            throw new IllegalArgumentException("Unsupported event: " + event
                    + ", supported: " + SUPPORTED_EVENTS);
        }
    }

    /**
     * 幂等地将 Arthas attach 到当前 JVM 并启动 HTTP API。
     */
    private void ensureArthasStarted() {
        if (arthasStarted) {
            return;
        }
        synchronized (ProfilerCollector.class) {
            if (arthasStarted) {
                return;
            }
            java.util.Map<String, String> configMap = new java.util.HashMap<>();
            configMap.put("arthas.telnetPort", String.valueOf(TELNET_PORT));
            configMap.put("arthas.httpPort", String.valueOf(HTTP_PORT));
            configMap.put("arthas.ip", "127.0.0.1");

            String arthasHome = resolveArthasHome();
            if (arthasHome != null) {
                // 使用内置发行包，运行期零下载
                new ArthasAgent(configMap, arthasHome, false, null).init();
                log.info("Arthas attached with bundled home: {}", arthasHome);
            } else {
                // 回退：由 Arthas 自行下载发行包
                ArthasAgent.attach(configMap);
                log.info("Arthas attached (network download fallback)");
            }
            arthasStarted = true;
            log.info("Arthas ready, httpPort={}, telnetPort={}", HTTP_PORT, TELNET_PORT);
        }
    }

    /**
     * 通过 Arthas HTTP API 执行一条命令（同步一次性执行）。
     *
     * @param command Arthas 命令，如 {@code profiler start --event cpu}
     * @return API 响应体
     * @throws IOException 网络或响应异常
     */
    private String executeCommand(String command) throws IOException {
        String payload = "{\"action\":\"exec\",\"command\":\""
                + command.replace("\"", "\\\"") + "\"}";

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String body = readBody(conn, code);
            if (code != 200) {
                throw new IOException("Arthas API failed (http=" + code
                        + ") for command [" + command + "]: " + body);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private String readBody(HttpURLConnection conn, int code) throws IOException {
        java.io.InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * 解析本地 Arthas home 目录，实现运行期零下载。
     * 优先使用系统属性 {@code baldr.arthasHome} 或环境变量 {@code BALDR_ARTHAS_HOME}；
     * 否则从 classpath 内置的发行包释放到临时目录。
     *
     * @return 本地 arthas home 绝对路径，无法定位时返回 null（回退为网络下载）
     */
    private String resolveArthasHome() {
        String configured = System.getProperty("baldr.arthasHome");
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv("BALDR_ARTHAS_HOME");
        }
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        try {
            return extractBundledArthas();
        } catch (Exception e) {
            log.warn("Failed to extract bundled Arthas, fall back to network download", e);
            return null;
        }
    }

    /**
     * 将 classpath 下 {@code baldr-arthas/arthas-bin/} 内置发行包释放到临时目录。
     *
     * @return 释放后的 arthas home 绝对路径；找不到内置资源时返回 null
     */
    private String extractBundledArthas() throws IOException {
        java.io.File targetDir = new java.io.File(
                System.getProperty("java.io.tmpdir"), "baldr-arthas-bin");
        java.io.File coreJar = new java.io.File(targetDir, "arthas-core.jar");
        if (coreJar.isFile()) {
            return targetDir.getAbsolutePath();
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create arthas home dir: " + targetDir);
        }
        try (java.io.InputStream in = getClass().getClassLoader()
                .getResourceAsStream("baldr-arthas/arthas-bin.zip")) {
            if (in == null) {
                return null;
            }
            ArthasAgent.unzip(in, targetDir);
        }
        log.info("Bundled Arthas extracted to {}", targetDir);
        return targetDir.getAbsolutePath();
    }

}