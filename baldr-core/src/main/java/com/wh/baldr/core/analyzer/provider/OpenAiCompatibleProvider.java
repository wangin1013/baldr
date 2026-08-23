package com.wh.baldr.core.analyzer.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI 兼容格式的大模型 Provider 抽象基类。
 * 封装 Chat Completions API 的通用逻辑——请求体构造、Bearer 鉴权、
 * JSON 输出模式、响应解析、错误提示，使用 JDK 原生 {@link HttpURLConnection}，无第三方 HTTP 依赖。
 *
 * <p>DeepSeek、豆包 等采用 OpenAI 兼容接口的厂商均可继承本类，
 * 只需通过构造参数提供各自的 endpoint、model、API Key 与品牌信息。</p>
 *
 * @author rubant
 * @date 2026-08-16
 */
@Slf4j
public abstract class OpenAiCompatibleProvider implements LLMProvider {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    /**
     * @param apiKey           API Key，为空时回退到环境变量 {@link #envApiKeyName()}
     * @param endpoint         API 端点，为空时使用 {@link #defaultEndpoint()}
     * @param model            模型名，为空时使用 {@link #defaultModel()}
     * @param connectTimeoutMs 连接超时（毫秒）
     * @param readTimeoutMs    读取超时（毫秒）
     */
    protected OpenAiCompatibleProvider(String apiKey, String endpoint, String model,
                                       int connectTimeoutMs, int readTimeoutMs) {
        String key = (apiKey == null || apiKey.trim().isEmpty())
                ? System.getenv(envApiKeyName())
                : apiKey;
        if ((key == null || key.trim().isEmpty()) && requireApiKey()) {
            throw new IllegalArgumentException(
                    brand() + " API Key 未提供，请通过参数或环境变量 " + envApiKeyName() + " 设置");
        }
        this.apiKey = key == null ? "" : key.trim();
        this.endpoint = (endpoint == null || endpoint.trim().isEmpty()) ? defaultEndpoint() : endpoint;
        this.model = (model == null || model.trim().isEmpty()) ? defaultModel() : model;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    // ---- 子类需提供的 provider 特有配置 ----

    /** 默认 API 端点（Chat Completions 完整 URL）。 */
    protected abstract String defaultEndpoint();

    /** 默认模型名。 */
    protected abstract String defaultModel();

    /** 读取 API Key 的环境变量名。 */
    protected abstract String envApiKeyName();

    /** 品牌名称，用于错误提示文案，如 "DeepSeek"、"豆包"。 */
    protected abstract String brand();

    /** 实际生效的模型名（构造时已回退默认值），供子类构造请求体使用。 */
    protected String model() {
        return model;
    }

    /**
     * 是否强制要求 API Key。默认 true；本地私有服务（无需鉴权）可覆写为 false。
     *
     * @return 是否强制要求 API Key
     */
    protected boolean requireApiKey() {
        return true;
    }

    /**
     * 是否在请求体中启用 OpenAI 的 JSON 输出模式
     * （{@code "response_format":{"type":"json_object"}}）。
     *
     * <p>默认 true。但部分厂商/模型（如火山方舟上的某些 deepseek 模型）
     * 不支持该字段，会直接返回 400；这类 provider 可覆写为 false，
     * 改由 system prompt 约束模型输出 JSON。</p>
     *
     * @return 是否启用 JSON 输出模式
     */
    protected boolean supportsJsonMode() {
        return true;
    }

    /**
     * 应用鉴权请求头。默认使用 OpenAI 规范的 {@code Authorization: Bearer <key>}。
     * 非 Bearer 鉴权的厂商（如 Anthropic 的 {@code x-api-key}）可覆写此方法。
     *
     * @param conn   已建立的连接
     * @param apiKey 处理后的 API Key（可能为空字符串）
     */
    protected void applyAuthHeaders(HttpURLConnection conn, String apiKey) {
        if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
    }

    // ---- 通用实现 ----

    @Override
    public String chatJson(String systemPrompt, String userPrompt) throws IOException {
        String payload = buildPayload(systemPrompt, userPrompt);
        String responseBody = doPost(payload);
        return extractContent(responseBody);
    }

    /**
     * 构造符合 OpenAI 规范的请求体，启用 JSON 输出模式。
     */
    protected String buildPayload(String systemPrompt, String userPrompt) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            root.put("stream", false);
            if (supportsJsonMode()) {
                ObjectNode responseFormat = MAPPER.createObjectNode();
                responseFormat.put("type", "json_object");
                root.set("response_format", responseFormat);
            }

            ArrayNode messages = MAPPER.createArrayNode();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                ObjectNode sys = MAPPER.createObjectNode();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
                messages.add(sys);
            }
            ObjectNode user = MAPPER.createObjectNode();
            user.put("role", "user");
            user.put("content", userPrompt);
            messages.add(user);
            root.set("messages", messages);

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build " + brand() + " request payload", e);
        }
    }

    /**
     * 发送 POST 请求并返回响应体。
     */
    protected String doPost(String payload) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json");
            applyAuthHeaders(conn, apiKey);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String body = readBody(conn, code);
            if (code != 200) {
                throw new IOException(explainError(code, body));
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private String readBody(HttpURLConnection conn, int code) throws IOException {
        InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 针对常见 HTTP 状态码给出清晰的中文错误提示。
     */
    protected String explainError(int code, String body) {
        String reason = extractErrorMessage(body);
        String hint;
        switch (code) {
            case 401:
                hint = brand() + " API Key 无效或未授权，请检查密钥配置或环境变量 " + envApiKeyName();
                break;
            case 402:
                hint = brand() + " 账户余额不足，请充值后重试";
                break;
            case 429:
                hint = brand() + " 请求过于频繁或达到速率上限，请稍后重试";
                break;
            case 400:
                hint = brand() + " 请求参数有误";
                break;
            case 404:
                hint = brand() + " 端点或模型不存在，请检查 endpoint/model 配置";
                break;
            case 500:
            case 503:
                hint = brand() + " 服务端异常，请稍后重试";
                break;
            default:
                hint = "调用 " + brand() + " 失败";
        }
        return hint + "（http=" + code + (reason == null ? "" : ", " + reason) + "）";
    }

    /**
     * 尽力从错误响应体中提取 error.message 字段。
     */
    protected String extractErrorMessage(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode msg = MAPPER.readTree(body).path("error").path("message");
            if (!msg.isMissingNode() && !msg.isNull()) {
                return msg.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 响应，返回截断的原文
        }
        return body.length() > 200 ? body.substring(0, 200) : body.trim();
    }

    /**
     * 从 API 响应中提取 {@code choices[0].message.content}。
     *
     * <p>推理型模型（如 deepseek-reasoner 系）可能把正文放在 {@code content}，
     * 而思考过程放在 {@code reasoning_content}；个别情况下 {@code content} 为空。
     * 为稳健起见，{@code content} 缺失或为空白时回退到 {@code reasoning_content}。</p>
     */
    protected String extractContent(String responseBody) throws IOException {
        JsonNode root = MAPPER.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException(brand() + " response has no choices: " + responseBody);
        }
        JsonNode message = choices.get(0).path("message");
        JsonNode content = message.path("content");
        if (!content.isMissingNode() && !content.isNull()) {
            String text = content.asText();
            if (text != null && !text.trim().isEmpty()) {
                return text;
            }
        }
        // content 为空时回退到 reasoning_content
        JsonNode reasoning = message.path("reasoning_content");
        if (!reasoning.isMissingNode() && !reasoning.isNull()) {
            String text = reasoning.asText();
            if (text != null && !text.trim().isEmpty()) {
                return text;
            }
        }
        throw new IOException(brand() + " response missing message content: " + responseBody);
    }
}