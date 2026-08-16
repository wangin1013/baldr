package com.wh.baldr.core.analyzer.provider;

import java.io.IOException;

/**
 * 大模型 Provider 抽象。
 * 不同厂商（DeepSeek / JoyAI / 本地模型等）实现本接口，
 * 屏蔽各自的鉴权、请求/响应格式差异，对上层提供统一的对话补全能力。
 *
 * @author rubant
 * @date 2026-08-16
 */
public interface LLMProvider {

    /**
     * Provider 的唯一名称（小写），用于工厂按名选择，如 {@code deepseek}、{@code joyai}。
     *
     * @return provider 名称
     */
    String name();

    /**
     * 以强制 JSON 输出模式发起一次对话补全。
     *
     * @param systemPrompt 系统提示词（角色设定），可为 null
     * @param userPrompt   用户提示词（实际问题与数据）
     * @return 模型返回的 content 文本（应为合法 JSON 字符串）
     * @throws IOException 网络或响应异常
     */
    String chatJson(String systemPrompt, String userPrompt) throws IOException;
}