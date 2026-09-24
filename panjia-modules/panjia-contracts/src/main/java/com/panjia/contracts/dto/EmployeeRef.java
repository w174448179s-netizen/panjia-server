package com.panjia.contracts.dto;

/**
 * 员工引用（姓名精确匹配结果：ID + 工号）。
 * <p>
 * 历史工资导入归一化用：天街历史表无工号列，按姓名匹配员工主数据后
 * 同时取得 employee_id（事实关联键）与 employee_code（外部编码留痕）。
 *
 * @param employeeId   员工 ID（pj_people_employee.employee_id）
 * @param employeeCode 工号（= sys_user.user_name）
 */
public record EmployeeRef(Long employeeId, String employeeCode) {
}
