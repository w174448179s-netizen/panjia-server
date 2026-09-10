package com.panjia.people.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 员工导入批次（对应 pj_people_import_batch 表，V6.0 §1.5）。
 * <p>
 * people 域独立状态机（{@link PeopleImportBatchStatus}），与单据导入批次无关；
 * 主数据重复导入不物理删除，被替代批次回填 {@link #supersededByBatchId}。
 */
@Data
@TableName("pj_people_import_batch")
public class PeopleImportBatch implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 批次 ID，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 批次号 PEIMP+yyyyMMddHHmmss */
    private String batchNo;

    /** 模板编码（EMPLOYEE） */
    private String templateCode;

    /** 模板版本（批次创建时快照冻结） */
    private String templateVersion;

    /** 原始文件名 */
    private String fileName;

    /** 归档存储路径（工具层返回） */
    private String storagePath;

    /** 文件 SHA-256 摘要 */
    private String fileHash;

    /** 总行数 */
    private Integer totalRows;

    /** 成功行数 */
    private Integer successRows;

    /** 失败/问题行数 */
    private Integer failedRows;

    /** 批次状态：PARSING/VALIDATING/IMPORTING/SUCCESS/FAILED */
    private PeopleImportBatchStatus status;

    /** 操作人 ID（上传人） */
    private Long operatorId;

    /** 备注 */
    private String remark;

    /** 替代本批次的新批次 ID（主数据不物理删除） */
    private Long supersededByBatchId;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 更新时间（DB 默认填充） */
    private LocalDateTime updateTime;
}
