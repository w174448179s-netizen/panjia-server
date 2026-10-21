package com.panjia.people.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 新增员工合同请求。
 */
@Data
public class ContractCreateDTO {

    /** 员工 ID（必填） */
    @NotNull(message = "员工不能为空")
    private Long employeeId;

    /** 合同类型（LABOR/PARTTIME/OTHER，必填） */
    @NotBlank(message = "合同类型不能为空")
    private String contractType;

    /** 签约门店（部门 ID，必填） */
    @NotNull(message = "签约门店不能为空")
    private Long deptId;

    /** 合同开始日（必填） */
    @NotNull(message = "合同开始日不能为空")
    private LocalDate startDate;

    /** 合同结束日（null=无固定期限） */
    private LocalDate endDate;

    /** 身份证扫描件 ossId 集合 */
    private List<String> idCardOssIds;

    /** 合同签署扫描件 ossId 集合（签订后上传留底） */
    private List<String> contractOssIds;

    /** 备注 */
    private String remark;
}
