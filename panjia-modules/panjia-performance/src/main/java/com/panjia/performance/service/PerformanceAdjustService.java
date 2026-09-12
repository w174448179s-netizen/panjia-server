package com.panjia.performance.service;

import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.dto.AdjustCreateDTO;
import com.panjia.performance.dto.AdjustQuery;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

/**
 * 业绩调整单服务。
 * <p>
 * 负责调整单的全生命周期管理，包括发起、审批、取消、执行等操作。
 * 调整类型支持：金额调整（AMOUNT）、业绩冲销（VOID）、部门划转（TRANSFER）。
 */
public interface PerformanceAdjustService {

    /**
     * 分页查询调整单列表。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 调整单分页结果
     */
    PageResult<PerformanceAdjust> listAdjusts(AdjustQuery query, PageQuery pageQuery);

    /**
     * 查询调整单详情。
     *
     * @param id 调整单 ID
     * @return 调整单详情；不存在时返回 null
     */
    PerformanceAdjust getAdjust(Long id);

    /**
     * 发起调整单并启动审批流程。
     * <p>
     * 生成调整单号，状态初始化为 SUBMITTED，随后调用 RuoYi 工作流启动审批
     * （flowCode = perf_adjust），回填 process_instance_id。
     *
     * @param dto          调整单创建请求
     * @param applicantId  申请人 ID
     * @return 创建后的调整单
     */
    PerformanceAdjust createAdjust(AdjustCreateDTO dto, Long applicantId);

    /**
     * 工作流审批回调：根据流程状态更新调整单并执行调整。
     * <p>
     * - finish（审批通过）→ 执行调整（冲销旧事实 + 生成新事实），状态 EXECUTED
     * - invalid / termination（作废/终止）→ 状态 REJECTED
     * - cancel（撤销）→ 状态 CANCELLED
     *
     * @param adjustId 调整单 ID
     * @param status   工作流状态（BusinessStatusEnum 的 status）
     * @param handler  办理人 ID（审批人）
     * @param message  办理意见
     */
    void handleWorkflowEvent(Long adjustId, String status, String handler, String message);

    /**
     * 取消调整单。
     * <p>
     * 状态流转：SUBMITTED → CANCELLED。
     *
     * @param id          调整单 ID
     * @param operatorId  操作人 ID
     */
    void cancelAdjust(Long id, Long operatorId);

    /**
     * 执行调整单（审批通过后由工作流回调自动触发，一般不手动调用）。
     * <p>
     * 状态流转：SUBMITTED → EXECUTED。
     * 根据调整范围执行不同逻辑：
     * <ul>
     *   <li>DETAIL + AMOUNT：单条事实金额调整 → 旧事实冲销 + 新事实生成</li>
     *   <li>DETAIL + VOID：单条事实冲销</li>
     *   <li>DETAIL + TRANSFER：单条事实部门划转</li>
     *   <li>CONTRACT + AMOUNT：合同级金额调整 → 按各明细业绩占比分摊，逐条 supersede</li>
     * </ul>
     *
     * @param id          调整单 ID
     * @param operatorId  执行人 ID
     */
    void executeAdjust(Long id, Long operatorId);
}
