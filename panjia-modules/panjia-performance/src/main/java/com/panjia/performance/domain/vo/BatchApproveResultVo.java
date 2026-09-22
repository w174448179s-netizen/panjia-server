package com.panjia.performance.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量审批结果（同步返回，让发起人知道每张单的处理情况）。
 */
@Data
public class BatchApproveResultVo {
    private int total;
    private int success;
    private int skipped;
    private int failed;
    private List<String> successContracts = new ArrayList<>();
    private List<String> skippedContracts = new ArrayList<>();
    private List<String> failedContracts = new ArrayList<>();
}
