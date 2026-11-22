package com.panjia.ledger.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 理房通流水导入批次（仅操作日志）。
 */
@Data
@TableName("pj_lf_import_batch")
public class LfImportBatch implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long accountId;
    private Long storeDeptId;
    private String fileName;
    private String storagePath;
    private Integer totalRows;
    private Integer successRows;
    private Integer failRows;
    private String status;
    private String errorMsg;
    private String resultDetail;
    private Long operatorId;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}
