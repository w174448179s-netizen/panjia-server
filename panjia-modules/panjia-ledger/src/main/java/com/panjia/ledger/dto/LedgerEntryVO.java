package com.panjia.ledger.dto;

import com.panjia.ledger.domain.LedgerEntry;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 录入明细展示对象（补充科目路径与部门名称，避免前端二次翻译）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LedgerEntryVO extends LedgerEntry {

    private String subjectName;

    /** 一级/二级/.../科目名，便于列表直接展示 */
    private String subjectPath;

    private String deptName;
}
