package com.panjia.payroll.service;

import com.panjia.payroll.domain.RateAdjust;
import com.panjia.payroll.dto.RateAdjustItem;

import java.util.List;
import java.util.Map;

/** 提成点调整服务（登记 → warm-flow 总监审批 → 按生效区间扣点） */
public interface IRateAdjustService {

    /** 登记调整单（DRAFT） */
    Long create(RateAdjust item, Long operatorId);

    /** 修改（仅 DRAFT/REJECTED） */
    void update(RateAdjust item, Long operatorId);

    /** 删除（仅 DRAFT/REJECTED） */
    void delete(Long id, Long operatorId);

    /** 提交审批（DRAFT 首次发起流程；REJECTED 办理在途任务重新进入总监审核） */
    void submit(Long id, Long operatorId);

    /**
     * 撤销：SUBMITTED → 撤销在途流程回 DRAFT 可改；APPROVED → CANCELLED 终态不再生效。
     */
    void cancel(Long id, Long operatorId);

    RateAdjust getById(Long id);

    /** 列表查询（employeeId/adjustType/status/period 命中生效区间） */
    List<RateAdjust> list(Long employeeId, String adjustType, String status, String period);

    /**
     * 取某期间生效（APPROVED 且 start_month ≤ period ≤ end_month）的调整项，按员工分组（算薪用）。
     */
    Map<Long, List<RateAdjustItem>> loadEffectiveForPeriod(String period);

    /** 工作流回调（RateAdjustWorkflowListener）：finish/back/cancel 状态回写 */
    void handleWorkflowEvent(Long bizId, String status, String handler, String message);
}
