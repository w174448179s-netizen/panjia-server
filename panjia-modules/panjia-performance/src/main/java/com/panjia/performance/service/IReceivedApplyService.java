package com.panjia.performance.service;

import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.vo.BatchApproveResultVo;
import com.panjia.performance.domain.bo.ReceivedApplyBo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 实收业绩审批单服务（§2 实收业绩流程）。
 */
public interface IReceivedApplyService {

    /**
     * 导入批次归档后自动建单并提交（§2.1：有实收 → 自动提交，流转财务→总监）。
     * <p>幂等：仅处理批次内 received_apply_id 尚未绑定的实收事实；同合同已有审批单时合并。
     *
     * @param batchId    导入批次 ID
     * @param period     归属期间
     * @param operatorId 操作人 ID（归档操作发起人，用于设置审批单创建人；为空时兜底取登录用户）
     * @return 新建审批单数量（合并不计）
     */
    int autoCreateForBatch(Long batchId, String period, Long operatorId);

    /**
     * 手工提交（店长/财务/总监，§2.2 发起人路由）：无单则按合同实收事实自动建单并提交；
     * 财务发起直达总监；总监发起直接通过；店长发起走 财务→总监。
     *
     * @param period     结算月
     * @param contractNo 合同号
     * @return 审批单
     */
    ReceivedApply manualSubmit(String period, String contractNo);

    /**
     * 已驳回/草稿单重新提交。
     *
     * @param id 审批单 ID
     * @return 审批单
     */
    ReceivedApply resubmit(Long id);

    /**
     * 作废审批单（DRAFT/SUBMITTED；SUBMITTED 同步终止流程）。
     *
     * @param id 审批单 ID
     */
    void cancel(Long id);

    /**
     * 按合同号批量审批（线程池异步执行，CompletableFuture 让 Spring MVC 挂起请求等待完成）。
     * <p>同步阶段去重合同号 + 捕获操作人信息；异步线程逐单办理当前待办节点。
     * 前端请求超时设 5 分钟，期间显示 loading；完成后返回每张单的处理结果。
     * <ul>
     *   <li>去重：相同合同号只处理一次；</li>
     *   <li>跳过已审批：非 SUBMITTED 状态或无待办任务的合同号直接跳过；</li>
     *   <li>权限由 @SaCheckPermission 前置保障，异步线程用系统身份办理。</li>
     * </ul>
     *
     * @param period     结算月（必填）
     * @param contractNos 合同号列表（允许重复，内部去重）
     * @return 批量审批结果（成功/跳过/失败 + 合同号列表）
     */
    CompletableFuture<BatchApproveResultVo> batchApproveByContractAsync(String period, List<String> contractNos);

    /**
     * 工作流回调（ReceivedWorkflowListener 调用）。
     */
    void handleWorkflowEvent(Long applyId, String status, String handler, String message);

    /**
     * 流程进入总监节点时回填最近审批人/审批时间（ReceivedWorkflowListener 任务级事件调用）。
     * <p>财务节点办理完成只会触发任务级事件（下一节点任务创建），不触发实例级事件；
     * 此前 approverId/approveTime 仅在 finish（终审）时写入，
     * 导致总监待审期间审批单上审批人/审批时间显示为空。
     *
     * @param applyId   审批单 ID
     * @param handlerId 财务节点办理人 ID（任务事件 params.handler）
     */
    void stampApproverOnDirectorNode(Long applyId, Long handlerId);

    /** 分页查询 */
    PageResult<ReceivedApply> list(ReceivedApplyBo query, PageQuery pageQuery);

    /** 详情（含合同下每人实收明细，列口径对齐合同业绩明细） */
    ReceivedApplyDetail getDetail(Long id);

    /** 按审批单 ID 查流程实例 ID（供前端「业务明细直批」绕过 workflow:instance:query 权限） */
    Long getInstanceId(Long id);

    /** 实收审批单明细视图 */
    record ReceivedApplyDetail(ReceivedApply apply,
                               List<com.panjia.performance.domain.vo.ReceivedFactDetailVo> facts) {
    }
}
