package com.panjia.people.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 合同到期提醒视图（员工列表顶部提醒条用）。
 * <p>
 * 口径：每员工每合同类型取最新一条（start_date 倒序），
 * 其 end_date 落在 [今天, 今天+30天] 为即将到期，早于今天为已到期未重签。
 */
@Data
public class ContractExpiringVO {

    /** 员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 门店全路径名 */
    private String deptName;

    /** 合同类型（LABOR/PARTTIME/OTHER） */
    private String contractType;

    /** 合同到期日 */
    private LocalDate endDate;

    /** true=已到期未重签（早于今天）；false=即将到期（30 天内） */
    private boolean expired;
}
