package com.panjia.performance.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel 批量审批结果。
 */
@Data
public class ReceivedBatchApproveResult {

    /** 成功条数 */
    private int successCount;

    /** 失败行（合同号 + 原因） */
    private List<FailedRow> failedRows = new ArrayList<>();

    public void addSuccess() {
        successCount++;
    }

    public void addFailure(String contractNo, String amount, String reason) {
        FailedRow row = new FailedRow();
        row.setContractNo(contractNo);
        row.setAmount(amount);
        row.setReason(reason);
        failedRows.add(row);
    }

    @Data
    public static class FailedRow {
        private String contractNo;
        private String amount;
        private String reason;
    }
}
