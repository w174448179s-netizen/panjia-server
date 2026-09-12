package com.panjia.commission.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 审批回调请求 DTO。
 * <p>
 * 简化审批实现（项目暂无 Warm-Flow）：approve = true 时单事务完成
 * SUBMITTED → LOCKED（落 approved_month、明细 PENDING → APPROVED、发布 CommissionApprovedEvent）。
 */
@Data
public class CallbackDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 是否通过（true=通过并锁定 / false=驳回） */
    @NotNull(message = "审批结论不能为空")
    private Boolean approve;
}
