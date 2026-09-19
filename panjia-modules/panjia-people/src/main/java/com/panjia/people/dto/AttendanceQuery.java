package com.panjia.people.dto;

import lombok.Data;

/**
 * 考勤汇总列表筛选条件（人月维度）。
 * <p>
 * 本人查询（my）仅使用 monthStart/monthEnd 条件，
 * 员工身份条件由服务端按登录用户强制覆盖，不信任前端传参。
 */
@Data
public class AttendanceQuery {

    /** 员工 ID */
    private Long employeeId;

    /** 姓名（模糊） */
    private String employeeName;

    /** 工号（模糊） */
    private String employeeCode;

    /** 部门 ID（含下级部门） */
    private Long deptId;

    /** 考勤月份起（yyyy-MM-dd，含，建议传当月 1 日） */
    private String monthStart;

    /** 考勤月份止（yyyy-MM-dd，含，建议传当月 1 日） */
    private String monthEnd;
}
