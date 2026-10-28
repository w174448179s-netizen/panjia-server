package com.panjia.contracts.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 实收明细跨域查询结果（contracts 端口 DTO）。
 * <p>
 * 数据源：pj_received_detail（ACTIVE 行）。
 * 用途：新签补录审批详情页展示「对应实收」明细，与补录新签对照。
 */
@Data
public class ReceivedFactInfoDTO {

    /** 角色人员工 ID（空经纪人场景可为空） */
    private Long employeeId;

    /** 员工外部编码（工号） */
    private String employeeExternalCode;

    /** 角色类型 */
    private String roleType;

    /** 角色姓名 */
    private String roleName;

    /** 分佣比例 */
    private BigDecimal shareRatio;

    /** 实收金额（角色人到账金额，可为负） */
    private BigDecimal amount;

    /** 明细级归属门店 ID（空时调用方可取合同级门店） */
    private Long deptId;
}
