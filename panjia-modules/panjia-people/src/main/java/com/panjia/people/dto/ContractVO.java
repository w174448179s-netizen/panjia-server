package com.panjia.people.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 员工合同视图（列表/详情共用，员工与门店信息批量 enrich）。
 */
@Data
public class ContractVO {

    /** 合同 ID */
    private Long contractId;

    /** 员工 ID */
    private Long employeeId;

    /** 工号 */
    private String employeeCode;

    /** 姓名 */
    private String employeeName;

    /** 签约门店（部门 ID） */
    private Long deptId;

    /** 门店全路径名 */
    private String deptName;

    /** 合同类型（LABOR/PARTTIME/OTHER） */
    private String contractType;

    /** 合同开始日 */
    private LocalDate startDate;

    /** 合同结束日（null=无固定期限） */
    private LocalDate endDate;

    /** 身份证扫描件 ossId 集合 */
    private List<String> idCardOssIds;

    /** 身份证扫描件访问 URL 集合（与 idCardOssIds 一一对应，预览用） */
    private List<String> idCardUrls;

    /** 合同签署扫描件 ossId 集合（签订后上传留底） */
    private List<String> contractOssIds;

    /** 合同签署扫描件访问 URL 集合（与 contractOssIds 一一对应，预览用） */
    private List<String> contractUrls;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;
}
