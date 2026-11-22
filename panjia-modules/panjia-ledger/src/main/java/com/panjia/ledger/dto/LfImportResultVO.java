package com.panjia.ledger.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 理房通流水导入结果。
 */
@Data
public class LfImportResultVO {

    private Long batchId;
    private String fileName;
    /** PROCESSING / SUCCESS / PARTIAL / FAILED */
    private String status;
    private int totalRows;
    private int successRows;
    private int failRows;
    private int insertRows;
    private int updateRows;
    private int skippedRows;
    private int matchContractCount;
    private int matchedCount;
    private int diffCount;
    private int notFoundCount;
    private int pendingSignRoleCount;
    private int pendingAllocCount;
    private int pendingSubjectCount;

    /** 未匹配到收支科目的费用描述汇总 */
    private List<String> unmappedSubjects = new ArrayList<>();

    /** 导入过程中的行级错误 */
    private List<String> errors = new ArrayList<>();
}
