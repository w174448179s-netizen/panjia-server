package com.panjia.contracts.dto;

/**
 * 员工算薪事实同步项（历史工资导入 → people 域）。
 *
 * @param employeeId 员工 ID
 * @param factType   事实类型（{@link com.panjia.contracts.constant.PeopleFactFields} 冻结 8 类）
 * @param value      事实值（布尔类 "true"/"false"，LEVEL 为职级编码）
 */
public record SalaryFactSyncDTO(Long employeeId, String factType, String value) {
}
