package com.panjia.people.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 修改员工合同请求（员工/创建人不允许改，改错走删除重录）。
 */
@Data
public class ContractUpdateDTO {

    /** 合同类型（LABOR/PARTTIME/OTHER） */
    private String contractType;

    /** 签约门店（部门 ID） */
    private Long deptId;

    /** 合同开始日 */
    private LocalDate startDate;

    /** 合同结束日（null=无固定期限；不传=不修改） */
    private LocalDate endDate;

    /** 身份证扫描件 ossId 集合（null=不修改；空集合=清空） */
    private List<String> idCardOssIds;

    /** 备注（null=不修改） */
    private String remark;
}
