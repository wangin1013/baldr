package com.wh.baldr.core.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 调用树节点。
 *
 * @author rubant
 * @date 2026-08-14
 */
@Data
public class CallTreeNode {

    private String function;
    private double percent;
    private List<CallTreeNode> children = new ArrayList<>();
}