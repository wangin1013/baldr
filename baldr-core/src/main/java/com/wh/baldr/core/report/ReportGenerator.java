package com.wh.baldr.core.report;

import com.wh.baldr.core.model.DiagnosisResult;
import com.wh.baldr.core.model.ProfileReport;

/**
 * 性能分析报告生成器。
 * 将采样数据（ProfileReport）与 AI 诊断结果（DiagnosisResult）渲染为可读报告。
 *
 * @author rubant
 * @date 2026-08-14
 */
public interface ReportGenerator {

    /**
     * 渲染报告文本。
     *
     * @param profile   采样报告，可为空
     * @param diagnosis AI 诊断结果，可为空
     * @return 渲染后的报告内容
     */
    String render(ProfileReport profile, DiagnosisResult diagnosis);

    /** 报告格式标识，如 markdown / html。 */
    String format();
}