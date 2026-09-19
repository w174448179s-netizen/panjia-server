package com.panjia.contracts.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 积分审批状态查询结果（薪酬域卡点消费）。
 */
@Data
public class ScoreApprovalStatusDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 归属期间（YYYY-MM） */
    private String period;

    /** 审批状态：DRAFT-待提交 SUBMITTED-待总监审批 APPROVED-总监已通过 REJECTED-已驳回；无审批单为 null */
    private String status;

    /** 该期间是否存在积分数据（pj_people_performance_score 有记录） */
    private boolean dataExists;

    /** 该期间积分是否已通过总监审批（无数据视为通过，不卡点） */
    public boolean isApproved() {
        return !dataExists || "APPROVED".equals(status);
    }
}
