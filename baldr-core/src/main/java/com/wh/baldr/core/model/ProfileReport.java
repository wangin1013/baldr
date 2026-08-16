package com.wh.baldr.core.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 性能采样报告聚合模型。
 *
 * @author rubant
 * @date 2026-08-14
 */
@Data
public class ProfileReport {

    private List<Hotspot> hotspots = new ArrayList<>();
    private CallTreeNode callTree;
}