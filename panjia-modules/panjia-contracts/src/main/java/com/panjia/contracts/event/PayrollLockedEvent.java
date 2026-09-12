package com.panjia.contracts.event;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工资批次锁定事件（跨域契约事件，panjia-contracts 叶子模块）。
 * <p>
 * 触发时机：{@code panjia-payroll} 工资批次进入 LOCKED 状态时，在<b>同一业务事务内</b>
 * 经 EventPort / Outbox 原子发布。
 * <p>
 * 消费方：
 * <ul>
 *   <li>{@code panjia-ledger}：按 {@link DeptCostSummary} 归集部门人工成本（V4.2 §14.2/§14.3/§15.2），
 *       以 {@link #eventId} 幂等；如需穿透到个人明细，调 PayrollQueryPort（事件只做通知）；</li>
 *   <li>{@code panjia-performance}：按 {@link #period} 自动把业绩期间封账为 CLOSED
 *       （工资已发即不可追溯变更，结佣窗口同步关闭）。</li>
 * </ul>
 * <p>
 * 契约依据：架构设计 V1.9.2 §7.3.1。
 * <p>
 * <b>红线</b>：{@code employerSocialTotal} 是公司视角成本，不得混入员工工资项；
 * 事件只传门店汇总数，不传逐人金额。
 * <p>
 * payload 约束（CI: check-event-payload）：基础类型 / 不可变 POJO / List，禁止持有 @Entity。
 */
@Data
public class PayrollLockedEvent implements DomainEvent {

    /** 事件类型常量（路由与序列化） */
    public static final String EVENT_TYPE = "payroll.locked";

    /**
     * 事件唯一标识（UUID，Outbox event_id 唯一约束）。
     * <p>
     * 消费方（ledger/performance）必须以此字段做消费幂等，重复投递不得重复归集 / 重复封账。
     */
    private String eventId;

    /** 工资归属期间 YYYY-MM（业绩封账、台账归集的匹配键） */
    private String period;

    /** 工资批次 ID（业务主键） */
    private Long batchId;

    /** 本批次锁定的工资明细 ID 列表（ledger 如需穿透再调 PayrollQueryPort 拉取） */
    private List<Long> itemIds;

    /** 按门店汇总的人工成本（ledger 部门收支台账归集所需，只传汇总不传逐人） */
    private List<DeptCostSummary> deptCosts;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public long businessId() {
        return batchId == null ? 0L : batchId;
    }

    /**
     * 部门人工成本汇总（事件内嵌 POJO，归属于事件 payload）。
     * <p>
     * 字段依据 V4.2：
     * <ul>
     *   <li>{@link #netPayTotal}＝门店全部人员最终发放合计（§14.5，来自 PayrollDetail.net）；</li>
     *   <li>{@link #employerSocialTotal}＝公司承担社保合计 = 1637.15 ×（1 − 职级比例）
     *       （§14.2，个人工资中不存在此金额，由 payroll 算薪时一并算出并随事件冻结）；</li>
     *   <li>{@link #headcount}＝该门店本批次计薪人数。</li>
     * </ul>
     * 职工福利 / 考试费（§14.4）不由 payroll 产出，不在本事件内，由 ledger 提供独立录入入口。
     */
    @Data
    public static class DeptCostSummary {

        /** 门店（部门）ID */
        private Long deptId;

        /** 工资：门店全部人员最终发放 SUM（税后实发） */
        private BigDecimal netPayTotal;

        /** 社保：公司承担部分 SUM（1637.15 ×（1 − 职级比例）），★ 工资里没有这个数 */
        private BigDecimal employerSocialTotal;

        /** 计薪人数 */
        private Integer headcount;

        public DeptCostSummary() {
        }

        public DeptCostSummary(Long deptId, BigDecimal netPayTotal,
                               BigDecimal employerSocialTotal, Integer headcount) {
            this.deptId = deptId;
            this.netPayTotal = netPayTotal;
            this.employerSocialTotal = employerSocialTotal;
            this.headcount = headcount;
        }
    }
}
