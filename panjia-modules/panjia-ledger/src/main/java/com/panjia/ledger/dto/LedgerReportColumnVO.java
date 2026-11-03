package com.panjia.ledger.dto;

import lombok.Data;

/**
 * 报表部门列。
 */
@Data
public class LedgerReportColumnVO {
    private Long deptId;
    private String deptName;

    public LedgerReportColumnVO() {
    }

    public LedgerReportColumnVO(Long deptId, String deptName) {
        this.deptId = deptId;
        this.deptName = deptName;
    }
}
