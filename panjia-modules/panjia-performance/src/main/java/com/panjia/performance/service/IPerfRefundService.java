package com.panjia.performance.service;

import com.panjia.performance.domain.PerfRefund;
import com.panjia.performance.domain.bo.PerfRefundBo;
import com.panjia.performance.domain.bo.PerfRefundCreateBo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;
import java.util.Map;

/**
 * 退单服务（pj_perf_refund）。
 * <p>
 * 退单 ≠ 手工冲正：退单以合同为单位全额冲退，审批通过后同时生成
 * 负新签（PERF_EXPECT）与负实收（PERF_REAL）流水，单据本身即「合同已退单」状态源。
 * 已封账（已发薪）月份禁止退单；退单不可撤销，录错只能另发业绩调整单补正。
 */
public interface IPerfRefundService {

    /** 分页查询退单列表 */
    PageResult<PerfRefund> list(PerfRefundBo query, PageQuery pageQuery);

    /** 按 ID 查退单详情（审批弹窗用，含部门名称回填） */
    PerfRefund getById(Long id);

    /** 退单汇总（列表页顶部：冲减新签/冲减实收/笔数） */
    Map<String, Object> summary(String refundPeriod);

    /** 批量查合同最近退单落账月（合同列表「已退单」标记） */
    Map<String, String> refundedPeriods(List<String> contractNos);

    /** 退单预览：返回将冲减的新签/实收金额与明细笔数，并给出拦截原因 */
    Map<String, Object> preview(String contractNo, String orderNo, String refundPeriod);

    /** 发起退单并启动审批 */
    PerfRefund create(PerfRefundCreateBo dto, Long applicantId);

    /** 申请人撤回审批中的退单（仅 SUBMITTED、仅发起人） */
    void withdraw(Long id, Long userId);

    /** 工作流回调（finish → 审批通过并执行；back/invalid → REJECTED） */
    void handleWorkflowEvent(Long id, String status, String handler, String message);

    /** 认领当前节点待办（事后补赋角色） */
    boolean claim(Long id);
}
