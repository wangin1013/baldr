package com.wh.baldr.core.analyzer.provider;

/**
 * DeepSeek 大模型 Provider。
 * 基于 DeepSeek 官方 Chat Completions API（OpenAI 兼容）。
 *
 * <p>API Key 通过构造参数或环境变量 {@code DEEPSEEK_API_KEY} 提供。</p>
 *
 * @author rubant
 * @date 2026-08-16
 */
public class DeepSeekProvider extends OpenAiCompatibleProvider {

    public static final String NAME = "deepseek";

    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-v4-pro";
    private static final String ENV_API_KEY = "DEEPSEEK_API_KEY";
    private static final String BRAND = "DeepSeek";

    public DeepSeekProvider(String apiKey) {
        this(apiKey, null, null, 10_000, 120_000);
    }

    public DeepSeekProvider(String apiKey, String endpoint, String model,
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
}