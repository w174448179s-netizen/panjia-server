package com.panjia.people.service;

import com.panjia.contracts.port.PeopleAttendanceApprovalQueryPort;
import com.panjia.people.dto.AttendanceApprovalVO;

/**
 * 考勤审批服务：人事提交当月考勤 → 总监审批 → 通过后方可创建薪酬批次进入算薪。
 * <p>
 * 同时实现 {@link PeopleAttendanceApprovalQueryPort} 供薪酬域卡点查询。
 */
public interface AttendanceApprovalService extends PeopleAttendanceApprovalQueryPort {

    /** 查询期间审批单（无则返回未提交状态 VO） */
    AttendanceApprovalVO getByPeriod(String period);

    /** 人事提交当月考勤审批 */
    void submit(String period, Long operatorId);

    /** 总监审批通过 */
    void approve(Long id, Long operatorId);

    /** 总监驳回 */
    void reject(Long id, String reason, Long operatorId);

    /**
     * 考勤数据变更后失效审批单：SUBMITTED/APPROVED → DRAFT。
     * 由考勤导入同步（syncAttendanceSummaries）调用；REJECTED 保持不变。
     */
    void invalidateOnDataChange(String period);
}
