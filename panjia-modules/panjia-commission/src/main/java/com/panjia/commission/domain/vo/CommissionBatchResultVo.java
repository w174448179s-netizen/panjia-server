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
    /** 实际生成的结佣申请单数（按新签归属月拆单，可能多于成功合同数） */
    private int generatedApplications;
    private List<String> successContracts = new ArrayList<>();
    private List<String> skippedContracts = new ArrayList<>();
    private List<String> failedContracts = new ArrayList<>();
}
