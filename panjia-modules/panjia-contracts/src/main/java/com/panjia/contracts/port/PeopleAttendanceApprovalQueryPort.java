package com.panjia.contracts.port;

import com.panjia.contracts.dto.AttendanceApprovalStatusDTO;

/**
 * 考勤审批状态跨域查询端口（payroll → people）。
 * <p>
 * 薪酬批次创建（进入算薪）前校验：当月考勤须总监审批通过。
 * 与"业绩封账 → 结佣卡点"（PeriodCloseQueryPort）同构。
 */
public interface PeopleAttendanceApprovalQueryPort {

    /**
     * 查询期间考勤审批状态。
     *
     * @param period 期间 YYYY-MM
     * @return 状态（含 dataExists / isApproved 判定）
     */
    AttendanceApprovalStatusDTO getApprovalStatus(String period);
}
