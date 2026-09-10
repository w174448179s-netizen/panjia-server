package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 员工导入问题清单（对应 pj_people_import_issue 表，V6.0 §1.7）。
 */
@Data
@TableName("pj_people_import_issue")
public class PeopleImportIssue implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 问题 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属批次 ID */
    private Long batchId;

    /** 数据行号（1-based，文件级问题可空） */
    private Integer rowNo;

    /** 问题类型（{@link PeopleImportIssueType}） */
    private PeopleImportIssueType issueType;

    /** 字段名（ParsedRow 的 field key） */
    private String fieldName;

    /** 原始值 */
    private String rawValue;

    /** 问题说明 */
    private String message;

    /** 处理状态：OPEN/RESOLVED/IGNORED */
    private PeopleImportIssueStatus status;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
