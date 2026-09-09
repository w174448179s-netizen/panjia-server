package com.panjia.people.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工变更记录视图（时间线）。
 */
@Data
public class ChangeLogVO {

    /** 日志 ID */
    private Long logId;

    /** 变更项（fact_type / ALL / DEPT / POSTS） */
    private String changeField;

    /** 变更项中文名 */
    private String changeFieldName;

    /** 变更前值（展示用文案） */
    private String beforeValue;

    /** 变更后值（展示用文案） */
    private String afterValue;

    /** 生效日期 */
    private LocalDate effectiveDate;

    /** 结束日期 */
    private LocalDate expireDate;

    /** 操作人 ID */
    private Long operatorId;

    /** 操作人姓名 */
    private String operatorName;

    /** 记录时间 */
    private LocalDateTime createTime;
}
