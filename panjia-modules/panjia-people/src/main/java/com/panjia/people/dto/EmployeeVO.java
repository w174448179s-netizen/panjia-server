package com.panjia.people.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 员工列表行 / 详情视图。
 * <p>
 * 列表与详情共用：岗位名集合由 sys_user_post 实时聚合，
 * 算薪字段来自 salary_record 当前态。
 */
@Data
public class EmployeeVO {

    /** 员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 归属部门 ID */
    private Long deptId;

    /** 部门全路径名（门店/组别） */
    private String deptName;

    /** 岗位名集合（sys_user_post 实时聚合，多岗位 / 分隔展示） */
    private List<String> postNames;

    /** 状态（ACTIVE/PARTTIME/LEFT/PENDING） */
    private String status;

    /** 职级编码 */
    private String levelCode;

    /** 是否缴社保 */
    private Boolean socialInsured;

    /** 是否缴公积金 */
    private Boolean housingInsured;

    /** 是否买商业保险 */
    private Boolean commercialInsured;

    /** 是否住宿舍 */
    private Boolean dormitory;

    /** 是否兼职 */
    private Boolean isPartTime;

    /** 师傅员工 ID */
    private Long mentorEmployeeId;

    /** 师傅姓名 */
    private String mentorName;

    /** 师傅工号 */
    private String mentorCode;

    /** 电话 */
    private String phone;

    /** 身份证号（详情展示，列表不返回） */
    private String idCard;

    /** 报道时间 */
    private LocalDate reportDate;

    /** 入职时间 */
    private LocalDate hireDate;

    /** 离职时间 */
    private LocalDate leaveDate;

    /** 关联系统账户 ID */
    private Long userId;

    /** 备注 */
    private String remark;
}
