package com.panjia.ledger.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 理房通流水按合同汇总与系统实收的匹配结果。
 */
@Data
@TableName("pj_lf_match_result")
public class LfMatchResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String contractNo;
    private BigDecimal flowAmount;
    private BigDecimal receivedAmount;
    private BigDecimal diffAmount;
    private Integer flowRowCount;
    private String matchStatus;
    private LocalDateTime lastMatchTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
