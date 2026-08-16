package com.wh.baldr.core.analyzer.provider;

/**
 * 本地私有大模型 Provider。
 * 适配本地部署的 OpenAI 兼容推理服务，如 Ollama、vLLM、LM Studio、LocalAI 等，
 * 数据不出内网，适合对隐私/合规有要求的场景。
 *
 * <p><b>约定</b>：</p>
 * <ul>
 *   <li>endpoint：默认 {@code http://localhost:11434/v1/chat/completions}（Ollama），
 *       可通过 {@code --endpoint} 或环境变量 {@code LOCAL_LLM_ENDPOINT} 覆盖；</li>
 *   <li>model：默认 {@code qwen2.5-coder}，通过 {@code --model} 指定实际已加载的模型；</li>
 *   <li>apiKey：本地服务通常无需鉴权，可不填；若服务要求鉴权，
 *       通过 {@code --api-key} 或环境变量 {@code LOCAL_LLM_API_KEY} 传入。</li>
 * </ul>
 *
 * <p>常见本地服务的 endpoint 示例：</p>
 * <ul>
 *   <li>Ollama：{@code http://localhost:11434/v1/chat/completions}</li>
 *   <li>vLLM：{@code http://localhost:8000/v1/chat/completions}</li>
 *   <li>LM Studio：{@code http://localhost:1234/v1/chat/completions}</li>
 * </ul>
 *
 * @author rubant
 * @date 2026-08-16
 */
public class LocalProvider extends OpenAiCompatibleProvider {

    public static final String NAME = "local";

    private static final String DEFAULT_MODEL = "qwen2.5-coder";
    private static final String ENV_API_KEY = "LOCAL_LLM_API_KEY";
    private static final String ENV_ENDPOINT = "LOCAL_LLM_ENDPOINT";
    private static final String BRAND = "本地模型";

    /** Ollama 默认 OpenAI 兼容端点。 */
    private static final String FALLBACK_ENDPOINT = "http://localhost:11434/v1/chat/completions";

    public LocalProvider(String apiKey) {
        this(apiKey, null, null, 10_000, 120_000);
    }

    public LocalProvider(String apiKey, String endpoint, String model,
                         int connectTimeoutMs, int readTimeoutMs) {
        super(apiKey, endpoint, model, connectTimeoutMs, readTimeoutMs);
    }

    /** 本地服务通常无需 API Key，允许为空。 */
    @Override
    protected boolean requireApiKey() {
        return false;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    protected String defaultEndpoint() {
        String env = System.getenv(ENV_ENDPOINT);
        return (env != null && !env.trim().isEmpty()) ? env.trim() : FALLBACK_ENDPOINT;
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