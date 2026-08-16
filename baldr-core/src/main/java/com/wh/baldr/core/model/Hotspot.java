package com.wh.baldr.core.model;

import lombok.Data;

/**
 * 单个热点方法采样条目。
 *
 * @author rubant
 * @date 2026-08-14
 */
@Data
public class Hotspot {

    private String function;
    private double percent;
    private long samples;
}