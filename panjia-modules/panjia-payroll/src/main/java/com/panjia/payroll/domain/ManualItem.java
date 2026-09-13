package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 手工录入项（奖金/其他收入/其他支出） */
@Data
@TableName("pj_payroll_manual_item")
public class ManualItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String period;
    private Long employeeId;
    private ManualItemType itemType;
    private String subType;
    private BigDecimal amount;
    private String reason;
    private String status;
    private Long applyBy;
    private Long approveBy;
    private Long batchId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
