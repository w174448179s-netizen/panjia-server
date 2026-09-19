package com.panjia.payroll.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 提成点调整命中项（算薪溯源，写入 pj_payroll_detail.rate_adjust_json）。
 */
@Data
public class RateAdjustItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调整类型（字典 rate_adjust_type）：NO_SOCIAL/PHONE_CHECK/PERSONAL */
    private String type;

    /** 调整点数（负值=扣点） */
    private BigDecimal rate;

    /** 调整原因 */
    private String reason;

    /** 来源：APPROVAL=审批通过的人工调整单；AUTO=档案参保事实自动判断 */
    private String source;

    /** 人工调整单 ID（AUTO 项为 null） */
    private Long adjustId;
}
