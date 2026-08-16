package com.wh.baldr.core.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * AI 诊断结果模型。
 *
 * @author rubant
 * @date 2026-08-14
 */
@Data
public class DiagnosisResult {

    private String summary;
    private String rootCause;
    private String severity;
    private List<Optimization> optimizations = new ArrayList<>();
    private List<String> quickWins = new ArrayList<>();
    private String jvmTuning;

    /** 单条优化建议。 */
    @Data
    public static class Optimization {
        private String target;
        private String issue;
        private String solution;
        private String codeExample;
        private String expectedGain;
    }
}