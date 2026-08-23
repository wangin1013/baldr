package com.wh.baldr.core.report;

import java.util.List;

import com.wh.baldr.core.model.DiagnosisResult;
import com.wh.baldr.core.model.Hotspot;
import com.wh.baldr.core.model.ProfileReport;

/**
 * Markdown 格式的性能分析报告生成器。
 *
 * @author rubant
 * @date 2026-08-14
 */
public class MarkdownReportGenerator implements ReportGenerator {

    private static final int TOP_HOTSPOTS = 20;

    @Override
    public String render(ProfileReport profile, DiagnosisResult diagnosis) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Baldr 性能分析报告\n\n");

        renderHotspots(sb, profile);
        renderDiagnosis(sb, diagnosis);

        return sb.toString();
    }

    @Override
    public String format() {
        return "markdown";
    }

    private void renderHotspots(StringBuilder sb, ProfileReport profile) {
        sb.append("## CPU 热点 Top ").append(TOP_HOTSPOTS).append("\n\n");
        if (profile == null || profile.getHotspots() == null || profile.getHotspots().isEmpty()) {
            sb.append("_无热点数据_\n\n");
            return;
        }
        sb.append("| 方法 | 占比 | 样本数 |\n");
        sb.append("| --- | ---: | ---: |\n");
        List<Hotspot> hotspots = profile.getHotspots();
        int limit = Math.min(TOP_HOTSPOTS, hotspots.size());
        for (int i = 0; i < limit; i++) {
            Hotspot h = hotspots.get(i);
            sb.append(String.format("| %s | %.1f%% | %d |\n",
                    h.getFunction(), h.getPercent(), h.getSamples()));
        }
        sb.append("\n");
    }

    private void renderDiagnosis(StringBuilder sb, DiagnosisResult diagnosis) {
        sb.append("## AI 诊断结论\n\n");
        if (diagnosis == null) {
            sb.append("_暂无诊断结果_\n");
            return;
        }
        sb.append("- **瓶颈总结**: ").append(nullSafe(diagnosis.getSummary())).append("\n");
        sb.append("- **严重程度**: ").append(nullSafe(diagnosis.getSeverity())).append("\n");
        sb.append("- **根因分析**: ").append(nullSafe(diagnosis.getRootCause())).append("\n\n");

        if (diagnosis.getOptimizations() != null && !diagnosis.getOptimizations().isEmpty()) {
            sb.append("### 优化建议\n\n");
            for (DiagnosisResult.Optimization o : diagnosis.getOptimizations()) {
                sb.append("#### ").append(nullSafe(o.getTarget())).append("\n\n");
                sb.append("- **问题**: ").append(nullSafe(o.getIssue())).append("\n");
                sb.append("- **方案**: ").append(nullSafe(o.getSolution())).append("\n");
                sb.append("- **预期收益**: ").append(nullSafe(o.getExpectedGain())).append("\n");
                if (o.getCodeExample() != null && !o.getCodeExample().trim().isEmpty()) {
                    sb.append("- **示例代码**:\n\n```java\n")
                            .append(o.getCodeExample()).append("\n```\n");
                }
                sb.append("\n");
            }
        }

        if (diagnosis.getQuickWins() != null && !diagnosis.getQuickWins().isEmpty()) {
            sb.append("### 快速改进（Quick Wins）\n\n");
            for (String win : diagnosis.getQuickWins()) {
                sb.append("- ").append(nullSafe(win)).append("\n");
            }
            sb.append("\n");
        }

        if (diagnosis.getJvmTuning() != null && !diagnosis.getJvmTuning().trim().isEmpty()) {
            sb.append("### JVM 调优建议\n\n");
            sb.append(diagnosis.getJvmTuning()).append("\n\n");
        }
    }

    private String nullSafe(String s) {
        return s == null ? "-" : s;
    }
}