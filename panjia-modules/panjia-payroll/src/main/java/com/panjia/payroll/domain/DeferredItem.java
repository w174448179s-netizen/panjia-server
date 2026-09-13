package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 递延发放台账（店长个人新签提成） */
@Data
@TableName("pj_payroll_deferred_item")
public class DeferredItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long employeeId;
    private Long deptId;
    private String period;
    private String dealKey;
    private Long sourceFactId;
    private BigDecimal deferredAmount;
    private BigDecimal finalRate;
    private String status;
    private String releasedPeriod;
    private Long releasedBatchId;
    private Long ruleSnapshotId;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
