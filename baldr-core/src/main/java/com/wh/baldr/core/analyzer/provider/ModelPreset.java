package com.wh.baldr.core.analyzer.provider;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 大模型预设组合。
 * 把常用的「provider + model」搭配收敛为一个简短别名，避免命令行同时书写
 * {@code --provider claude --model claude-opus-4-20250514} 这类冗长参数，
 * 使用方只需 {@code --use claude-opus} 即可。
 *
 * <p>别名大小写不敏感，且 {@code -} 与 {@code _} 等价（{@code claude-opus} == {@code claude_opus}）。</p>
 * <p>预设只是默认值来源：命令行若同时显式指定 {@code --provider}/{@code --model}，以显式值为准。</p>
 *
 * @author rubant
 * @date 2026-08-23
 */
public enum ModelPreset {

    /** DeepSeek 推理旗舰 R1（deepseek-reasoner），最适合代码/性能分析。 */
    DEEPSEEK("deepseek", DeepSeekProvider.NAME, "deepseek-reasoner"),

    /** 豆包/火山方舟（deepseek-v4-pro-ga-260813），账号已开通的模型。 */
    DOUBAO("doubao", DoubaoProvider.NAME, "deepseek-v4-pro-ga-260813"),

    /** Claude Sonnet（claude 的均衡款，也是 claude provider 的默认模型）。 */
    CLAUDE_SONNET("claude-sonnet", ClaudeProvider.NAME, "claude-sonnet-4-20250514"),

    /** Claude Opus（claude 的高能力款）。 */
    CLAUDE_OPUS("claude-opus", ClaudeProvider.NAME, "claude-opus-4-20250514"),

    /** Claude Haiku（claude 的轻量快速款）。 */
    CLAUDE_HAIKU("claude-haiku", ClaudeProvider.NAME, "claude-haiku-4-20250514");

    private final String alias;
    private final String provider;
    private final String model;

    ModelPreset(String alias, String provider, String model) {
        this.alias = alias;
        this.provider = provider;
        this.model = model;
    }

    /** 预设别名，如 {@code claude-opus}。 */
    public String alias() {
        return alias;
    }

    /** 该预设对应的 provider 名称，如 {@code claude}。 */
    public String provider() {
        return provider;
    }

    /** 该预设对应的模型名；为 {@code null} 表示沿用 provider 内置默认模型。 */
    public String model() {
        return model;
    }

    /**
     * 按别名解析预设。别名大小写不敏感，{@code -}/{@code _} 视为等价。
     *
     * @param alias 预设别名，如 {@code claude-opus}
     * @return 匹配的预设
     * @throws IllegalArgumentException 别名不存在时抛出，异常信息附带全部可选值
     */
    public static ModelPreset fromAlias(String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            throw new IllegalArgumentException("预设名不能为空");
        }
        String normalized = normalize(alias);
        for (ModelPreset preset : values()) {
            if (normalize(preset.alias).equals(normalized)) {
                return preset;
            }
        }
        throw new IllegalArgumentException(
                "未知的预设: " + alias + "，可选值: " + supportedAliases());
    }

    /** 逗号分隔的全部可选别名，用于帮助与错误提示。 */
    public static String supportedAliases() {
        return Arrays.stream(values())
                .map(ModelPreset::alias)
                .collect(Collectors.joining(" / "));
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase().replace('_', '-');
    }
}