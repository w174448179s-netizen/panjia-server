package com.panjia.received.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 驳回后调整并重提入参。
 * <p>
 * 仅允许修改审批单既有实收明细的实收金额与角色占比；新增/删除角色人仍走实收调整单。
 */
@Data
public class ReceivedReviseBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调整原因（必填） */
    private String reason;

    /** 明细调整目标 */
    private List<Item> detailTargets;

    /** 单行调整目标：factId = pj_received_detail.id */
    @Data
    public static class Item implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 实收明细 ID */
        private Long factId;

        /** 调整后实收金额；null=不修改 */
        private BigDecimal amount;

        /** 角色占比（0~1）；null=不修改 */
        private BigDecimal shareRatio;
    }
}
