package com.wh.baldr.core.analyzer.provider;

/**
 * 豆包（Doubao / 火山引擎方舟）大模型 Provider。
 * 基于火山方舟 Chat Completions API（OpenAI 兼容）。
 *
 * <p>API Key 通过构造参数或环境变量 {@code ARK_API_KEY} 提供。</p>
 *
 * <p>默认模型为 {@code deepseek-v4-pro-ga-260813}。火山方舟的 {@code model} 字段
 * 也可填写「推理接入点 ID」（Endpoint ID，形如 {@code ep-xxxxxxxxxxxx}），
 * 通过 {@code --model} 参数覆盖默认值即可。</p>
 *
 * @author rubant
 * @date 2026-08-16
 */
public class DoubaoProvider extends OpenAiCompatibleProvider {

    public static final String NAME = "doubao";

    /** 火山方舟 Chat Completions 端点（北京区）。 */
    private static final String DEFAULT_ENDPOINT =
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions";

    /**
     * 默认模型名。火山方舟支持直接使用模型名（非仅接入点 ID），
     * 此处取账号已开通的 deepseek-v4-pro-ga-260813；如需指定推理接入点 ID
     * （形如 ep-xxxx）或其他模型，通过 --model 覆盖即可。
     */
    private static final String DEFAULT_MODEL = "deepseek-v4-pro-ga-260813";

    private static final String ENV_API_KEY = "ARK_API_KEY";
    private static final String BRAND = "豆包(火山方舟)";

    public DoubaoProvider(String apiKey) {
        this(apiKey, null, null, 10_000, 120_000);
    }

    public DoubaoProvider(String apiKey, String endpoint, String model,
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

    /**
     * 火山方舟上的部分模型（如 deepseek-v4-pro-ga-260813）不支持
     * {@code response_format:json_object}，发送会返回 400；此处关闭 JSON 模式，
     * 改由 system prompt 约束模型输出 JSON。
     */
    @Override
    protected boolean supportsJsonMode() {
        return false;
    }
}