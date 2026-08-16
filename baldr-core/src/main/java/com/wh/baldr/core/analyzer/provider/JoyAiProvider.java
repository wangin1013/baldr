package com.wh.baldr.core.analyzer.provider;

/**
 * JoyAI 大模型 Provider（预留）。
 * 按 OpenAI 兼容格式接入，实际 endpoint 与鉴权信息待补充。
 *
 * <p><b>使用前置条件</b>：JoyAI 的 API endpoint 与 API Key 尚未确定。
 * 待获取后，通过以下任一方式提供，即可直接启用，无需改动代码：</p>
 * <ul>
 *   <li>endpoint：CLI {@code --endpoint} 参数，或环境变量 {@code JOYAI_ENDPOINT}；</li>
 *   <li>apiKey：CLI {@code --api-key} 参数，或环境变量 {@code JOYAI_API_KEY}；</li>
 *   <li>model：CLI {@code --model} 参数，默认见 {@link #DEFAULT_MODEL}。</li>
 * </ul>
 *
 * <p>若 JoyAI 的请求/响应格式与 OpenAI 不完全一致，只需覆写
 * {@link #buildPayload}、{@link #extractContent} 等方法即可。</p>
 *
 * @author rubant
 * @date 2026-08-16
 */
public class JoyAiProvider extends OpenAiCompatibleProvider {

    public static final String NAME = "joyai";

    /**
     * 默认 endpoint 占位。JoyAI 正式 endpoint 确定后替换此常量，
     * 或在运行时通过 {@code --endpoint} / 环境变量 {@code JOYAI_ENDPOINT} 覆盖。
     */
    private static final String DEFAULT_ENDPOINT = resolveDefaultEndpoint();

    /** 默认模型名，待 JoyAI 提供后调整。 */
    private static final String DEFAULT_MODEL = "joyai-chat";

    private static final String ENV_API_KEY = "JOYAI_API_KEY";
    private static final String ENV_ENDPOINT = "JOYAI_ENDPOINT";
    private static final String BRAND = "JoyAI";

    public JoyAiProvider(String apiKey) {
        this(apiKey, null, null, 10_000, 120_000);
    }

    public JoyAiProvider(String apiKey, String endpoint, String model,
                         int connectTimeoutMs, int readTimeoutMs) {
        super(apiKey, endpoint, model, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 优先取环境变量 {@code JOYAI_ENDPOINT}，未设置则返回占位地址。
     * 占位地址会在实际请求时因 404/连接失败给出明确提示，提醒用户配置。
     */
    private static String resolveDefaultEndpoint() {
        String env = System.getenv(ENV_ENDPOINT);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        // 占位：JoyAI 正式 endpoint 确定后替换
        return "https://api.joyai.example/v1/chat/completions";
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