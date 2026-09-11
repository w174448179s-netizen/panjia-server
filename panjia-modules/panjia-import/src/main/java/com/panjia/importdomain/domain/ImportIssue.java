package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 导入问题（V1.4 §3.4）。
 * <p>
 * 批次级校验/归一化失败的问题清单，不阻塞批次落库。
 */
@Data
@TableName("pj_import_issue")
public class ImportIssue implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long batchId;
    private Integer rowNo;
    private ImportIssueType issueType;
    private String fieldName;
    private String rawValue;
    private String message;

    /** 0 OPEN 1 RESOLVED 2 IGNORED */
    private ImportIssueStatus status;

    /** 来源阶段：PARSE=解析/基础校验，NORMALIZE=归一化 */
    private ImportIssuePhase phase;
}
