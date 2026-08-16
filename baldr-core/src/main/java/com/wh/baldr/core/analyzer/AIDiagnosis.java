package com.wh.baldr.core.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.wh.baldr.core.analyzer.provider.LLMProvider;
import com.wh.baldr.core.analyzer.provider.LLMProviderFactory;
import com.wh.baldr.core.analyzer.provider.LocalProvider;
import com.wh.baldr.core.model.CallTreeNode;
import com.wh.baldr.core.model.DiagnosisResult;
import com.wh.baldr.core.model.ProfileReport;

import lombok.extern.slf4j.Slf4j;

/**
 * AI 性能诊断器。
 * 基于 Baldr 性能分析数据，构建诊断 Prompt 并调用 AI（DeepSeek 云端 / 本地）生成优化建议。
 *
 * @author rubant
 * @date 2026-08-14 21:35
 */
@Slf4j
public class AIDiagnosis {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 系统提示词：角色设定 */
    public static final String SYSTEM_PROMPT =
            "你是一位资深 Java 性能优化专家，拥有20年JVM调优经验。"
                    + "请根据用户提供的arthas性能分析数据，给出具体、可落地的优化建议和代码修改方案。"
                    + "返回的结果中，修改方案要表明修改点的引入路径，是一个markdown的文档格式，要让人清楚的知道修改哪里，怎么改。";

    /**
     * 构建诊断用户 Prompt（不含角色设定，角色设定见 {@link #SYSTEM_PROMPT}）。
     *
     * @param report           性能分析报告
     * @param contextException 触发诊断的上下文异常，可为 null
     * @return 用户提示词文本
     */
    public static String buildPrompt(ProfileReport report, Throwable contextException) {
        StringBuilder prompt = new StringBuilder();

        // 上下文异常信息
        if (contextException != null) {
            prompt.append("【异常上下文】\n");
            prompt.append("异常类型: ").append(contextException.getClass().getName()).append("\n");
            prompt.append("异常消息: ").append(contextException.getMessage()).append("\n");
            prompt.append("\n");
        }

        // 热点分析
        prompt.append("【CPU热点Top 10】\n");
        if (report != null && report.getHotspots() != null) {
            report.getHotspots().stream()
                    .limit(10)
                    .forEach(h -> prompt.append(String.format(
                            "- %s: %.1f%% (%d samples)\n",
                            h.getFunction(), h.getPercent(), h.getSamples()
                    )));
        }

        // 调用树（只取关键路径）
        prompt.append("\n【关键调用路径】\n");
        if (report != null) {
            prompt.append(formatCallTree(report.getCallTree(), 0, 5));
        }

        // 输出要求
        prompt.append("\n【输出要求】\n");
        prompt.append("请用中文输出 JSON 格式，包含以下字段：\n");
        prompt.append("- summary: 一句话总结性能瓶颈\n");
        prompt.append("- rootCause: 根因分析（2-3句话）\n");
        prompt.append("- severity: 严重程度 CRITICAL/HIGH/MEDIUM/LOW\n");
        prompt.append("- optimizations: 优化建议数组，每个建议包含：\n");
        prompt.append("  * target: 目标方法/类\n");
        prompt.append("  * issue: 问题描述\n");
        prompt.append("  * solution: 具体解决方案\n");
        prompt.append("  * codeExample: 优化后的代码示例（Java）\n");
        prompt.append("  * expectedGain: 预期性能提升（如\"减少50%CPU\"）\n");
        prompt.append("- quickWins: 可以立即执行的3个最小改动（字符串数组）\n");
        prompt.append("- jvmTuning: 如果有JVM参数调整建议，在此列出（字符串）\n");

        return prompt.toString();
    }

    private static String formatCallTree(CallTreeNode node, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) {
            return "";
        }
        StringBuilder indentBuilder = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indentBuilder.append("  ");
        }
        String indent = indentBuilder.toString();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s- %.1f%% %s\n", indent, node.getPercent(), node.getFunction()));
        if (node.getChildren() != null) {
            for (CallTreeNode child : node.getChildren()) {
                sb.append(formatCallTree(child, depth + 1, maxDepth));
            }
        }
        return sb.toString();
    }

    /**
     * 调用 AI 进行诊断（多 provider）。
     *
     * @param prompt      用户提示词
     * @param useLocalLLM 是否使用本地大模型；false 时走云端 provider
     * @param provider    云端 provider 名称，如 deepseek / joyai；为空用默认
     * @param apiKey      API Key，可传 null 由对应 provider 的环境变量提供
     * @param endpoint    自定义 endpoint，可为 null 使用 provider 默认
     * @param model       模型名，可为 null 使用 provider 默认
     * @return 诊断结果
     * @throws Exception 调用或解析失败时抛出
     */
    public static DiagnosisResult diagnose(String prompt, boolean useLocalLLM, String provider,
                                           String apiKey, String endpoint, String model) throws Exception {
        if (useLocalLLM) {
            return callLocalLLM(prompt, apiKey, endpoint, model);
        }
        return callCloudLLM(prompt, provider, apiKey, endpoint, model);
    }

    /**
     * 兼容旧签名：走默认 provider（DeepSeek），API Key 由环境变量提供。
     */
    public static DiagnosisResult diagnose(String prompt, boolean useLocalLLM,
                                           String apiKey, String model) throws Exception {
        return diagnose(prompt, useLocalLLM, null, apiKey, null, model);
    }

    /**
     * 兼容旧签名：走默认 provider，API Key 由环境变量提供。
     */
    public static DiagnosisResult diagnose(String prompt, boolean useLocalLLM) throws Exception {
        return diagnose(prompt, useLocalLLM, null, null, null, null);
    }

    /**
     * 调用本地私有大模型（Ollama / vLLM / LM Studio 等 OpenAI 兼容服务），
     * 并将返回的 JSON 解析为 {@link DiagnosisResult}。
     */
    private static DiagnosisResult callLocalLLM(String prompt, String apiKey,
                                                String endpoint, String model) throws Exception {
        LLMProvider llm = LLMProviderFactory.create(LocalProvider.NAME, apiKey, endpoint, model);
        log.info("{} 诊断请求 {} 字符", llm.name(), prompt == null ? 0 : prompt.length());
        String json = llm.chatJson(SYSTEM_PROMPT, prompt);
        log.info("{} 诊断返回 {} 字符", llm.name(), json == null ? 0 : json.length());
        return parseDiagnosis(json);
    }

    /**
     * 调用云端 provider，并将返回的 JSON 解析为 {@link DiagnosisResult}。
     */
    private static DiagnosisResult callCloudLLM(String prompt, String provider, String apiKey,
                                                String endpoint, String model) throws Exception {
        LLMProvider llm = LLMProviderFactory.create(provider, apiKey, endpoint, model);
        log.info("{} 诊断请求 {} 字符", llm.name(), prompt == null ? 0 : prompt.length());

        String json = llm.chatJson(SYSTEM_PROMPT, prompt);
        log.info("{} 诊断返回 {} 字符", llm.name(), json == null ? 0 : json.length());
        return parseDiagnosis(json);
    }

    /**
     * 将 DeepSeek 返回的 JSON 文本解析为 {@link DiagnosisResult}。
     * 容忍模型偶尔在 JSON 外包裹 Markdown 代码块的情况。
     *
     * @param json 模型返回的 JSON 文本
     * @return 诊断结果；解析失败时返回带原始文本的降级结果
     */
    static DiagnosisResult parseDiagnosis(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        String cleaned = stripCodeFence(json.trim());
        try {
            JsonNode root = MAPPER.readTree(cleaned);
            DiagnosisResult result = new DiagnosisResult();
            result.setSummary(text(root, "summary"));
            result.setRootCause(text(root, "rootCause"));
            result.setSeverity(text(root, "severity"));
            result.setJvmTuning(text(root, "jvmTuning"));

            JsonNode opts = root.path("optimizations");
            if (opts.isArray()) {
                for (JsonNode o : opts) {
                    DiagnosisResult.Optimization opt = new DiagnosisResult.Optimization();
                    opt.setTarget(text(o, "target"));
                    opt.setIssue(text(o, "issue"));
                    opt.setSolution(text(o, "solution"));
                    opt.setCodeExample(text(o, "codeExample"));
                    opt.setExpectedGain(text(o, "expectedGain"));
                    result.getOptimizations().add(opt);
                }
            }

            JsonNode wins = root.path("quickWins");
            if (wins.isArray()) {
                for (JsonNode w : wins) {
                    result.getQuickWins().add(w.asText());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析 DeepSeek 返回 JSON 失败，降级为原文摘要: {}", e.getMessage());
            DiagnosisResult fallback = new DiagnosisResult();
            fallback.setSummary("AI 返回内容无法解析为结构化结果");
            fallback.setRootCause(cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned);
            fallback.setSeverity("UNKNOWN");
            return fallback;
        }
    }

    /** 去除模型可能包裹的 ```json ... ``` 代码块标记。 */
    private static String stripCodeFence(String s) {
        String t = s;
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}