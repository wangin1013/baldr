package com.wh.baldr.core.config;

import lombok.Builder;
import lombok.Data;

/**
 * 大模型调用配置。
 * 支持本地（如 vLLM / Ollama）与云端（如阿里云百炼 / 火山引擎）两类。
 * 敏感信息（apiKey）建议通过环境变量注入，避免硬编码。
 *
 * @author rubant
 * @date 2026-08-14
 */
@Data
@Builder
public class LlmConfig {

    /** 是否使用本地模型 */
    @Builder.Default
    private boolean useLocal = true;

    /** 模型服务地址，例如 http://localhost:8000/v1/chat/completions */
    private String endpoint;

    /** 模型名称，例如 qwen2.5-coder */
    private String model;

    /** API Key（云端调用需要），建议来自环境变量 */
    private String apiKey;

    /** 请求超时（毫秒） */
    @Builder.Default
    private int timeoutMillis = 60_000;

    /**
     * 从环境变量加载配置。
     * BALDR_LLM_ENDPOINT / BALDR_LLM_MODEL / BALDR_LLM_API_KEY / BALDR_LLM_USE_LOCAL
     */
    public static LlmConfig fromEnv() {
        return LlmConfig.builder()
                .useLocal(!"false".equalsIgnoreCase(System.getenv("BALDR_LLM_USE_LOCAL")))
                .endpoint(System.getenv("BALDR_LLM_ENDPOINT"))
                .model(System.getenv("BALDR_LLM_MODEL"))
                .apiKey(System.getenv("BALDR_LLM_API_KEY"))
                .build();
    }
}