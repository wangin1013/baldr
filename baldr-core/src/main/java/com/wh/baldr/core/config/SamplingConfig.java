package com.wh.baldr.core.config;

import lombok.Builder;
import lombok.Data;

/**
 * 采样配置。
 *
 * @author rubant
 * @date 2026-08-14
 */
@Data
@Builder
public class SamplingConfig {

    /** 目标进程 PID；小于等于 0 表示采集当前进程 */
    @Builder.Default
    private int pid = -1;

    /** 采样时长（秒） */
    @Builder.Default
    private int durationSeconds = 30;

    /** 采样事件类型：cpu / alloc / lock */
    @Builder.Default
    private String event = "cpu";

    /** 报告输出目录 */
    @Builder.Default
    private String outputDir = "/tmp/baldr-ai";
}