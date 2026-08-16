package com.wh.baldr.core.analyzer.provider;

/**
 * 大模型 Provider 工厂。
 * 按名称创建对应的 {@link LLMProvider} 实现，统一管理各厂商接入。
 *
 * @author rubant
 * @date 2026-08-16
 */
public final class LLMProviderFactory {

    /** 默认 provider 名称 */
    public static final String DEFAULT_PROVIDER = DeepSeekProvider.NAME;

    private LLMProviderFactory() {
    }

    /**
     * 按名称创建 provider（使用默认超时）。
     *
     * @param provider provider 名称，如 {@code deepseek}、{@code joyai}；为空时用默认
     * @param apiKey   API Key，可为 null 由各 provider 的环境变量提供
     * @param endpoint 自定义 endpoint，可为 null 使用 provider 默认
     * @param model    模型名，可为 null 使用 provider 默认
     * @return 对应的 provider 实例
     */
    public static LLMProvider create(String provider, String apiKey, String endpoint, String model) {
        return create(provider, apiKey, endpoint, model, 10_000, 120_000);
    }

    /**
     * 按名称创建 provider。
     *
     * @param provider         provider 名称；为空时使用 {@link #DEFAULT_PROVIDER}
     * @param apiKey           API Key
     * @param endpoint         自定义 endpoint
     * @param model            模型名
     * @param connectTimeoutMs 连接超时（毫秒）
     * @param readTimeoutMs    读取超时（毫秒）
     * @return 对应的 provider 实例
     * @throws IllegalArgumentException provider 名称不支持时抛出
     */
    public static LLMProvider create(String provider, String apiKey, String endpoint, String model,
                                     int connectTimeoutMs, int readTimeoutMs) {
        String name = (provider == null || provider.trim().isEmpty())
                ? DEFAULT_PROVIDER
                : provider.trim().toLowerCase();

        switch (name) {
            case DeepSeekProvider.NAME:
                return new DeepSeekProvider(apiKey, endpoint, model, connectTimeoutMs, readTimeoutMs);
            case DoubaoProvider.NAME:
                return new DoubaoProvider(apiKey, endpoint, model, connectTimeoutMs, readTimeoutMs);
            case JoyAiProvider.NAME:
                return new JoyAiProvider(apiKey, endpoint, model, connectTimeoutMs, readTimeoutMs);
            case LocalProvider.NAME:
                return new LocalProvider(apiKey, endpoint, model, connectTimeoutMs, readTimeoutMs);
            default:
                throw new IllegalArgumentException(
                        "不支持的大模型 provider: " + provider
                                + "，可选值: " + DeepSeekProvider.NAME + " / " + DoubaoProvider.NAME
                                + " / " + JoyAiProvider.NAME + " / " + LocalProvider.NAME);
        }
    }
}