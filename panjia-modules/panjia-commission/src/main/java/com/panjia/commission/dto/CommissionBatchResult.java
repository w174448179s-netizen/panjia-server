package com.panjia.commission.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 结佣 Excel 批量操作结果（批量发起 / 批量审批）。
 */
@Data
public class CommissionBatchResult {

    /** 成功条数 */
    private int successCount;

    /** 失败行明细 */
    private List<FailedRow> failedRows = new ArrayList<>();

    public void addSuccess() {
        successCount++;
    }

    public void addFailure(String contractNo, String amount, String reason) {
        failedRows.add(new FailedRow(contractNo, amount, reason));
    }

    /** 单行失败原因 */
    public record FailedRow(String contractNo, String amount, String reason) {
    }
}
