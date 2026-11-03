package com.panjia.ledger.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门收支导入结果：全部校验通过才落库，任一行失败则整批不导入。
 */
@Data
public class LedgerImportResultVO {

    /** 解析到的有效数据行数 */
    private int total;
    /** 成功写入行数 */
    private int successCount;
    /** 失败行数 */
    private int failCount;
    /** 逐行错误信息（第 N 行：原因） */
    private List<String> errors = new ArrayList<>();
}
