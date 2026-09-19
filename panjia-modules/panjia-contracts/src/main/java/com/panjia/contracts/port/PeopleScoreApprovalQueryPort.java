package com.panjia.contracts.port;

import com.panjia.contracts.dto.ScoreApprovalStatusDTO;

/**
 * 积分审批状态跨域查询端口（payroll → people）。
 * <p>
 * 薪酬批次创建（进入算薪）前校验：当月积分须总监审批通过。
 * 与"考勤审批卡点"（PeopleAttendanceApprovalQueryPort）同构。
 */
public interface PeopleScoreApprovalQueryPort {

    /**
     * 查询期间积分审批状态。
     *
     * @param period 期间 YYYY-MM
     * @return 状态（含 dataExists / isApproved 判定）
     */
    ScoreApprovalStatusDTO getApprovalStatus(String period);
}
