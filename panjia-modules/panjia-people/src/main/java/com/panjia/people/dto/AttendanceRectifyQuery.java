package com.panjia.people.dto;

import lombok.Data;

/**
 * 考勤修改申请列表筛选条件。
 * <p>
 * 本人查询（my）仅使用 monthStart/monthEnd/status 条件，
 * 员工身份条件由服务端按登录用户强制覆盖，不信任前端传参。
 */
@Data
public class AttendanceRectifyQuery {

    /** 考勤月份起（yyyy-MM-dd，含，建议传当月 1 日） */
    private String monthStart;

    /** 考勤月份止（yyyy-MM-dd，含，建议传当月 1 日） */
    private String monthEnd;

    /** 审批状态（SUBMITTED/APPROVED/REJECTED/CANCELED，可空） */
    private String status;
}
