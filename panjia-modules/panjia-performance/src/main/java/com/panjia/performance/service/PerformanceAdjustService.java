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
     * 发起调整单。
     * <p>
     * 生成调整单号，状态初始化为 SUBMITTED。
     *
     * @param dto          调整单创建请求
     * @param applicantId  申请人 ID
     * @return 创建后的调整单
     */
    PerformanceAdjust createAdjust(AdjustCreateDTO dto, Long applicantId);

    /**
     * 审批通过调整单。
     * <p>
     * 状态流转：SUBMITTED → APPROVED。
     *
     * @param id          调整单 ID
     * @param approverId  审批人 ID
     */
    void approveAdjust(Long id, Long approverId);

    /**
     * 审批拒绝调整单。
     * <p>
     * 状态流转：SUBMITTED → REJECTED。
     *
     * @param id          调整单 ID
     * @param approverId  审批人 ID
     * @param reason      拒绝原因
     */
    void rejectAdjust(Long id, Long approverId, String reason);

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
     * 执行调整单（审批通过后执行）。
     * <p>
     * 状态流转：APPROVED → EXECUTED。
     * 根据调整类型执行不同逻辑：
     * <ul>
     *   <li>AMOUNT：金额调整 → 旧事实冲销 + 新事实生成</li>
     *   <li>VOID：业绩冲销 → 事实冲销</li>
     *   <li>TRANSFER：部门划转 → 旧事实冲销 + 新事实（新部门）生成</li>
     * </ul>
     *
     * @param id          调整单 ID
     * @param operatorId  执行人 ID
     */
    void executeAdjust(Long id, Long operatorId);
}
