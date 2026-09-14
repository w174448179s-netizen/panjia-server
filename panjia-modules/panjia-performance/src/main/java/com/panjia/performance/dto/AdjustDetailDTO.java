package com.panjia.performance.dto;

import com.panjia.performance.domain.PerformanceAdjust;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 业绩调整单详情（审批办理页展示用）。
 * <p>
 * 包含调整单基础信息 + 合同信息 + 受影响的明细列表，
 * 让审批人能一眼看清这张调整单改的是哪个合同、影响哪些人的业绩。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class AdjustDetailDTO extends PerformanceAdjust implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 合同号（合同级=调整标的，明细级=所属合同） */
    private String contractNo;

    /** 订单号 */
    private String orderNo;

    /** 房源地址 */
    private String propertyAddress;

    /** 签约时间 */
    private String businessDate;

    /** 合同下明细行数（合同级=全部明细，明细级=1） */
    private Integer detailCount;

    /** 合同应收合计 */
    private java.math.BigDecimal expectedTotal;

    /** 合同实收合计 */
    private java.math.BigDecimal receivedTotal;

    /** 受影响的明细列表 */
    private List<AdjustFactDetailDTO> details;

    /** 调整后目标总金额（= 原合计 + deltaAmount） */
    private java.math.BigDecimal targetAmount;
}
