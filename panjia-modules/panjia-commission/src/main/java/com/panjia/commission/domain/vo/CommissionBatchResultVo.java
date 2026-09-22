package com.panjia.commission.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量操作结果（批量发起 / 批量审批，CompletableFuture 同步返回给前端）。
 */
@Data
public class CommissionBatchResultVo {
    private int total;
    private int success;
    private int skipped;
    private int failed;
    private List<String> successContracts = new ArrayList<>();
    private List<String> skippedContracts = new ArrayList<>();
    private List<String> failedContracts = new ArrayList<>();
}
