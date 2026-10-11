package com.panjia.performance.service;

import com.panjia.performance.domain.ReceivedAdjust;
import com.panjia.performance.domain.bo.ReceivedAdjustBo;
import com.panjia.performance.domain.bo.ReceivedAdjustCreateBo;
import com.panjia.performance.domain.vo.AdjustDetailVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 实收调整单服务（pj_perf_received_adjust，2026-10-12 与新签调整物理拆表）。
 * <p>
 * 审批走工作流 bizType=PERF_RECEIVED_ADJUST（flowCode=perf_received_adjust）：
 * 发起即启动审批，{@code ReceivedAdjustWorkflowListener} 回调
 * {@link #handleWorkflowEvent} 更新状态并在审批通过后执行调整。
 */
public interface IReceivedAdjustService {

    /**
     * 分页查询实收调整单（数据权限：登录用户仅本部门及下级）。
     */
    PageResult<ReceivedAdjust> list(ReceivedAdjustBo query, PageQuery pageQuery);

    /**
     * 实收调整列表「业务类型」下拉（数据范围与列表一致）。
     */
    List<String> distinctBizTypes(ReceivedAdjustBo query);

    /**
     * 发起实收调整单并启动审批流程，回填流程实例 ID。
     *
     * @return 已落库的调整单（ID 供前端跳转）
     */
    ReceivedAdjust create(ReceivedAdjustCreateBo dto, Long applicantId);

    /**
     * 查询实收调整单完整详情（含合同信息 + 受影响实收明细与分摊预演）。
     */
    AdjustDetailVo getDetail(Long id);

    /**
     * 申请人撤回审批中的实收调整单（仅发起人；工作流实例一并删除，单据置 CANCELLED）。
     */
    void withdraw(Long id, Long operatorId);

    /**
     * 工作流审批事件回调：finish→执行并置 EXECUTED；back/invalid/termination→REJECTED；cancel→CANCELLED。
     * <p>
     * 新老流程共用本入口：2026-10-12 前创建的在途实收单其流程实例挂在 perf_adjust 下，
     * 审批事件经新签 listener 查无新签单后委托本方法处理（实例按 businessId 定位，与 bizType 无关）。
     */
    void handleWorkflowEvent(Long adjustId, String status, String handler, String message);

    /**
     * 前端预检：该合同该实收期间是否存在审批中的实收调整单。
     */
    boolean hasInFlight(String contractNo, String period);
}
