package com.wh.baldr.core.appender;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.wh.baldr.core.analyzer.AIDiagnosis;
import com.wh.baldr.core.alert.AlertSender;
import com.wh.baldr.core.collector.ProfilerCollector;
import com.wh.baldr.core.model.DiagnosisResult;
import com.wh.baldr.core.model.ProfileReport;
import com.wh.baldr.core.parser.BaldrProfileParser;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于日志事件触发的 AI 性能诊断 Appender。
 * 当捕获到性能相关异常时，自动采集性能数据、解析报告并调用 AI 诊断。
 *
 * @author rubant
 * @date 2026-08-14 21:36
 */
@Slf4j
public class AIDiagnosisAppender extends AppenderBase<ILoggingEvent> {

    private final ProfilerCollector profiler = new ProfilerCollector();
    private final BaldrProfileParser parser = new BaldrProfileParser();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @Override
    protected void append(ILoggingEvent event) {
        // 只处理特定性能相关异常
        if (!isPerformanceException(event)) {
            return;
        }

        executor.submit(() -> {
            try {
                // 1. 获取目标进程PID
                int pid = getCurrentPid();

                // 2. 自动采集30秒性能数据
                String reportFile = profiler.collect(pid, 30, "cpu");

                // 3. 解析报告
                String mdContent = new String(
                        Files.readAllBytes(Paths.get(reportFile)), StandardCharsets.UTF_8);
                ProfileReport report = parser.parse(mdContent);

                // 4. 获取异常上下文
                Throwable ex = ((ThrowableProxy) event.getThrowableProxy()).getThrowable();

                // 5. AI诊断
                String prompt = AIDiagnosis.buildPrompt(report, ex);
                DiagnosisResult result = AIDiagnosis.diagnose(prompt, true);

                // 6. 输出报告
                outputDiagnosis(event, report, result);

            } catch (Exception e) {
                log.error("自动性能诊断失败", e);
            }
        });
    }

    private boolean isPerformanceException(ILoggingEvent event) {
        if (!(event.getThrowableProxy() instanceof ThrowableProxy)) {
            return false;
        }
        Throwable ex = ((ThrowableProxy) event.getThrowableProxy()).getThrowable();
        String msg = ex.getMessage();
        if (msg == null) {
            return false;
        }

        // 性能相关异常关键词
        return msg.contains("timeout")
                || msg.contains("Timeout")
                || msg.contains("GC overhead")
                || msg.contains("OutOfMemory")
                || msg.contains("Connection pool is full")
                || msg.contains("thread pool is full")
                || ex instanceof java.util.concurrent.TimeoutException;
    }

    private void outputDiagnosis(ILoggingEvent event, ProfileReport report, DiagnosisResult result) {
        if (result == null) {
            log.warn("AI性能诊断无结果, 异常: {}", event.getFormattedMessage());
            return;
        }
        // 输出到日志
        log.warn("\n========== AI性能诊断报告 ==========\n" +
                        "异常: {}\n" +
                        "瓶颈: {}\n" +
                        "严重程度: {}\n" +
                        "根因: {}\n" +
                        "建议: {}\n" +
                        "预期提升: {}\n" +
                        "=====================================",
                event.getFormattedMessage(),
                result.getSummary(),
                result.getSeverity(),
                result.getRootCause(),
                result.getQuickWins(),
                result.getOptimizations().stream()
                        .map(o -> o.getTarget() + ": " + o.getExpectedGain())
                        .collect(Collectors.joining(", "))
        );

        // 发送告警（包含优化建议）
        AlertSender.sendPerformanceAlert(event, result);
    }

    private int getCurrentPid() {
        // 获取当前Java进程PID（JDK8 兼容写法）
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        try {
            return at > 0 ? Integer.parseInt(name.substring(0, at)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}