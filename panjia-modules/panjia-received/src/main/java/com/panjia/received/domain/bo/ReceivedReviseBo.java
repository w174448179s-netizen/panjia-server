package com.panjia.received.domain.bo;

import com.panjia.performance.domain.bo.AddMemberItemBo;
import com.panjia.performance.domain.bo.AdjustDetailTargetBo;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 驳回后调整入参（界面与实收调整单一致）。
 * <p>
 * 驳回单不直接改原明细：按同一调整单逻辑创建一张挂靠原单的调整单（状态 PENDING_APPLY），
 * 随后重新提交原审批单；原单审批通过时调整单自动审批并执行，全程仅一个审批。
 */
@Data
public class ReceivedReviseBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调整原因（必填） */
    private String reason;

    /** 调整类型：AMOUNT=业绩调整（总额不变）/ DEDUCT=业绩扣减 / INCREASE=业绩增加 */
    private String adjustType;

    /** 调整后实收合计 */
    private BigDecimal targetAmount;

    /** 明细指定值（与原单明细范围一致，须覆盖全部明细） */
    private List<AdjustDetailTargetBo> detailTargets;

    /** 新增角色人（可选） */
    private List<AddMemberItemBo> newMembers;
}
