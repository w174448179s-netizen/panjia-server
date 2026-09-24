package com.panjia.people.service;

import com.panjia.contracts.port.PeopleScoreApprovalQueryPort;
import com.panjia.people.dto.ScoreApprovalVO;

import java.util.Collection;
import java.util.Set;

/**
 * 积分审批服务：人事提交当月积分 → warm-flow 积分月度审批（score_approval）
 * → 总监「我的待办」办理（24h 超时自动通过）→ 办结回写状态。
 * <p>
 * 同时实现 {@link PeopleScoreApprovalQueryPort} 供薪酬域算薪卡点查询。
 * 审批动作全部收敛到工作流，本服务只负责发起/重提/状态回写，不含业务直批。
 */
public interface ScoreApprovalService extends PeopleScoreApprovalQueryPort {

    /** 查询期间审批单（无则返回未提交状态 VO） */
    ScoreApprovalVO getByPeriod(String period);

    /** 按审批单 ID 查询（工作流办理弹窗详情用，含扣点行快照） */
    ScoreApprovalVO getByBizId(Long bizId);

    /** 人事提交当月积分审批（首发起流程；驳回后重提走同实例） */
    void submit(String period, Long operatorId);

    /**
     * 工作流回调（ScoreWorkflowListener 转发）：
     * finish → APPROVED；back → REJECTED；cancel/invalid/termination → DRAFT。
     *
     * @param bizId   审批单 ID（流程 businessId）
     * @param status  流程状态（ApprovalEvent.status）
     * @param handler 办理人（字符串用户 ID）
     * @param message 办理意见（驳回原因）
     */
    void handleWorkflowEvent(Long bizId, String status, String handler, String message);

    /**
     * 积分数据变更后失效审批：在途流程撤销 + SUBMITTED/APPROVED 回 DRAFT。
     * 由积分导入同步（syncScoreSummaries）与批次撤销消费调用。
     */
    void invalidateOnDataChange(String period);

    /**
     * 历史工资导入：审批单直接置 APPROVED 终态（一期一审；不存在则插入，
     * 存在非 APPROVED 则收敛），无流程实例/操作人（历史补录语义）。
     */
    void approveForHistory(String period);

    /**
     * 历史工资导入批次撤销：删除本期间历史导入产生的审批单
     * （APPROVED 且无流程实例）；有流程实例的正常单据不动。
     */
    void deleteHistoryApproval(String period);

    /**
     * 期间是否锁定（审批 SUBMITTED/APPROVED）：导入同步走 invalidateOnDataChange
     * 失效重提路径，不受此限制。
     *
     * @param period 归属期间（yyyy-MM）
     */
    boolean isPeriodLocked(String period);

    /**
     * 批量查询锁定期间集合（列表行级锁定标记用）。
     *
     * @param periods 归属期间集合（yyyy-MM）
     * @return 其中处于 SUBMITTED/APPROVED 状态的期间
     */
    Set<String> lockedPeriods(Collection<String> periods);
}
