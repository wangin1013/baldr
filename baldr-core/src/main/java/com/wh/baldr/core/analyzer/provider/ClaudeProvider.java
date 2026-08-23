package com.wh.baldr.core.analyzer.provider;

import java.io.IOException;
import java.net.HttpURLConnection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Claude（Anthropic）大模型 Provider。
 * 基于 Anthropic Messages API。虽非 OpenAI 兼容格式，但复用
 * {@link OpenAiCompatibleProvider} 的 HTTP/错误处理骨架，仅覆写三处差异：
 * 鉴权头（x-api-key + anthropic-version）、请求体（system 顶层 + max_tokens）、
 * 响应解析（content[].text）。
 *
 * <p>API Key 通过构造参数或环境变量 {@code ANTHROPIC_API_KEY} 提供。</p>
 * <p>默认模型：claude-sonnet-4-20250514；可通过 --model 指定 opus / haiku 等系列。</p>
 *
 * @author rubant
 * @date 2026-08-23
 */
public class ClaudeProvider extends OpenAiCompatibleProvider {

    public static final String NAME = "claude";

    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-20250514";
    private static final String ENV_API_KEY = "ANTHROPIC_API_KEY";
    private static final String BRAND = "Claude";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    public ClaudeProvider(String apiKey) {
        this(apiKey, null, null, 10_000, 120_000);
    }

    public ClaudeProvider(String apiKey, String endpoint, String model,
                          int connectTimeoutMs, int readTimeoutMs) {
        super(apiKey, endpoint, model, connectTimeoutMs, readTimeoutMs);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected String defaultEndpoint() {
        return DEFAULT_ENDPOINT;
    }

    @Override
    protected String defaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    protected String envApiKeyName() {
        return ENV_API_KEY;
    }

    @Override
    protected String brand() {
        return BRAND;
    }

    /** Anthropic 使用 x-api-key + anthropic-version 头，而非 Bearer。 */
    @Override
    protected void applyAuthHeaders(HttpURLConnection conn, String apiKey) {
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
    }

    /**
     * 构造 Anthropic Messages API 请求体。
     * 差异：system 是顶层参数（非 messages 内 role），max_tokens 必填，无 response_format。
     */
    @Override
    protected String buildPayload(String systemPrompt, String userPrompt) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model());
            root.put("max_tokens", DEFAULT_MAX_TOKENS);
            root.put("stream", false);

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                root.put("system", systemPrompt);
            }

            ArrayNode messages = MAPPER.createArrayNode();
            ObjectNode user = MAPPER.createObjectNode();
            user.put("role", "user");
            user.put("content", userPrompt);
            messages.add(user);
            root.set("messages", messages);

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build " + BRAND + " request payload", e);
        }
    }

    /**
     * 从 Anthropic 响应提取 content[0].text。
     * 结构：{ "content": [{"type":"text","text":"..."}], "stop_reason": "end_turn", ... }
     * Claude 无原生 JSON 模式，做 ```json fence 容错剥离。
     */
    @Override
    protected String extractContent(String responseBody) throws IOException {
        JsonNode content = MAPPER.readTree(responseBody).path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new IOException(BRAND + " response has no content: " + responseBody);
        }
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                JsonNode text = block.path("text");
                if (!text.isMissingNode() && !text.isNull()) {
                    return stripJsonFence(text.asText());
                }
            }
        }
        throw new IOException(BRAND + " response missing text content: " + responseBody);
    }

    /** 剥离模型偶尔外包的 ```json ... ``` 代码块，保证下游拿到纯 JSON。 */
    private String stripJsonFence(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            return trimmed.trim();
        }
        return text;
    }
}