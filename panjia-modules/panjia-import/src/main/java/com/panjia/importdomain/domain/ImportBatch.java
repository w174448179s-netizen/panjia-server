package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 导入批次聚合根（V1.4 §3.1）。
 * <p>
 * 统领 RawData / NormalizedRecord / ImportIssue。批次是"导入事务"的原子单位。
 * 状态转换收口在本聚合根方法，禁止业务代码直接 setStatus。
 */
@Data
@TableName("pj_import_batch")
public class ImportBatch implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 批次号 IMP+yyyyMMdd+序列 */
    private String batchNo;

    private ImportSourceType sourceType;

    /** 创建/解析时快照冻结的模板版本 */
    private String templateVersion;

    private String fileName;
    private String originalFileName;
    private String storagePath;

    /** 归属月 YYYY-MM */
    private String period;

    private Integer totalRows;
    private Integer successRows;
    private Integer failedRows;

    private ImportBatchStatus status;

    private Integer archiveStatus;

    private Long operatorId;
    private Long deptId;
    private String remark;

    @Version
    private Integer version;

    /** 被新批次废弃后回填，一经设置不可改 */
    private Long supersededByBatchId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ===== 状态机方法（收口，禁止外部直接 setStatus） =====

    /** 归档：PENDING_CONFIRM/NORMALIZING → ARCHIVED */
    public void archive() {
        transitTo(ImportBatchStatus.ARCHIVED);
    }

    /** 失败：任意运行态 → FAILED（终态） */
    public void fail() {
        transitTo(ImportBatchStatus.FAILED);
    }

    /** 重归一化：PENDING_CONFIRM → NORMALIZING */
    public void reNormalize() {
        transitTo(ImportBatchStatus.NORMALIZING);
    }

    /** 归一化完成（无 issue）：NORMALIZING → ARCHIVED；有 issue → PENDING_CONFIRM */
    public void finishNormalize(boolean hasIssue) {
        transitTo(hasIssue ? ImportBatchStatus.PENDING_CONFIRM : ImportBatchStatus.ARCHIVED);
    }

    private void transitTo(ImportBatchStatus target) {
        if (!status.canTransitTo(target)) {
            throw new IllegalStateTransitionException(status, target);
        }
        this.status = target;
    }
}
