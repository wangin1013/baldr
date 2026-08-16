package com.wh.baldr.core.alert;

import com.wh.baldr.core.model.DiagnosisResult;

import ch.qos.logback.classic.spi.ILoggingEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 性能告警发送器。
 * 当前为最小占位实现，后续可接入钉钉 / 邮件 / 企业微信等渠道。
 *
 * @author rubant
 * @date 2026-08-14
 */
@Slf4j
public class AlertSender {

    private AlertSender() {
    }

    public static void sendPerformanceAlert(ILoggingEvent event, DiagnosisResult result) {
        // TODO: 接入实际告警渠道
        log.info("Performance alert triggered, severity={}",
                result != null ? result.getSeverity() : "UNKNOWN");
    }
}