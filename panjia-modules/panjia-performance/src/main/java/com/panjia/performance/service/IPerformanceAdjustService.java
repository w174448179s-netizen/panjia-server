package com.panjia.performance.service;

import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.bo.PerformanceAdjustCreateBo;
import com.panjia.performance.domain.vo.AdjustDetailVo;
import com.panjia.performance.domain.bo.PerformanceAdjustBo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

/**
 * 业绩调整单服务。
 * <p>
 * 负责调整单的全生命周期管理，包括发起、审批、取消、执行等操作。
 * 调整类型支持：金额调整（AMOUNT）、业绩冲销（VOID）、部门划转（TRANSFER）。
 */
public interface IPerformanceAdjustService {

    /**
     * 分页查询调整单列表。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 调整单分页结果
     */
    PageResult<PerformanceAdjust> listAdjusts(PerformanceAdjustBo query, PageQuery pageQuery);

    /**
     * 查询调整单详情。
     *
     * @param id 调整单 ID
     * @return 调整单详情；不存在时返回 null
     */
    PerformanceAdjust getAdjust(Long id);

    /**
     * 查询调整单完整详情（含合同信息 + 受影响明细）。
     * <p>
     * 审批办理页使用，让审批人能看清调整的标的合同和影响范围。
     *
     * @param id 调整单 ID
     * @return 完整详情；不存在时返回 null
     */
    AdjustDetailVo getAdjustDetail(Long id);

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
    PerformanceAdjust createAdjust(PerformanceAdjustCreateBo dto, Long applicantId);

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
     * 标记工作流回调失败：在独立事务中把异常摘要追加到 reason 字段。
     * <p>
     * 监听器 catch 块中调用，避免主事务回滚导致失败信息也丢失；
     * 运维可在列表页看到 reason 中带 [回调失败] 前缀的摘要，便于介入排查。
     * <p>幂等：重复调用会追加多条失败摘要（保留最近几次失败上下文）。
     *
     * @param adjustId    调整单 ID
     * @param errorSummary 异常摘要（可空）
     */
    void markCallbackFailure(Long adjustId, String errorSummary);

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

    /**
     * 状态自愈：比对调整单状态与工作流实例状态，不一致时自动对齐。
     * <p>
     * 当工作流监听器回调异常（如事务不一致）时，可能出现"工作流已终态
     * 但调整单仍为 SUBMITTED"的卡住状态。本方法在查询详情时自动检测并修复。
     * <p>
     * 对齐规则：
     * <ul>
     *   <li>工作流 cancel → 调整单 CANCELLED</li>
     *   <li>工作流 finish → 调整单 EXECUTED（触发执行调整）</li>
     *   <li>工作流 back → 调整单 REJECTED</li>
     *   <li>工作流 invalid / termination → 调整单 REJECTED</li>
     * </ul>
     *
     * @param adjust 调整单（必须是 SUBMITTED 状态才会检查）
     * @return 修复后的调整单；无需修复时返回原对象
     */
    PerformanceAdjust syncStatusWithWorkflow(PerformanceAdjust adjust);
}
