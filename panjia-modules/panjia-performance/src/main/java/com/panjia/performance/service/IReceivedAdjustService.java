package com.panjia.performance.service;

import com.panjia.performance.domain.ReceivedAdjust;
import com.panjia.performance.domain.bo.ReceivedAdjustBo;
import com.panjia.performance.domain.bo.ReceivedAdjustCreateBo;
import com.panjia.performance.domain.vo.AdjustDetailVo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.Collection;
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
     * 驳回后调整：以原实收审批单的明细范围为快照创建调整单，状态置 PENDING_APPLY，
     * 不单独发起审批流程，待原单审批通过时由 {@link #autoApproveAndExecuteByApply} 自动审批并执行。
     *
     * @param dto         调整入参（合同/期间/类型/指定行/新角色人）
     * @param applyId     被驳回的实收审批单 ID
     * @param scopeFactIds 原单可调整的实收明细 ID 集合
     * @param applicantId 申请人
     * @return 待原单审批的调整单
     */
    ReceivedAdjust createPendingForApply(ReceivedAdjustCreateBo dto, Long applyId,
                                         Collection<Long> scopeFactIds, Long applicantId);

    /**
     * 原实收单审批通过时：自动审批并执行挂靠在该单上的待处理调整单（须在结佣事件发布前调用）。
     */
    void autoApproveAndExecuteByApply(Long applyId, Long approverId);

    /**
     * 查询挂靠某实收审批单的待生效调整单（PENDING_APPLY）；无则返回 null。
     */
    ReceivedAdjust findPendingByApply(Long applyId);

    /**
     * 原实收单被驳回时：同步将挂靠的待处理调整单置 REJECTED，便于下次重新调整。
     */
    void rejectPendingByApply(Long applyId, Long approverId, String message);

    /**
     * 原实收单作废/终止时：同步将挂靠的待处理调整单置 CANCELLED。
     */
    void cancelPendingByApply(Long applyId);

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

    /** 认领当前节点待办（实收调整财务/总监节点：事后补赋角色）。 */
    boolean claim(Long id);
}
