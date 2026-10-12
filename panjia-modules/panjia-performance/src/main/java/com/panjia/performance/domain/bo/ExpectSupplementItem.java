package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新签补录单条明细（角色人级）。
 * <p>
 * 金额与签约日期手工录入，其余字段从实收明细继承。
 */
@Data
public class ExpectSupplementItem {
    /** 角色人员工 ID */
    @NotNull(message = "员工不能为空")
    private Long employeeId;

    /** 员工工号（从实收明细快照） */
    private String employeeExternalCode;

    /** 角色类型 */
    @NotNull(message = "角色类型不能为空")
    private String roleType;

    /** 角色人姓名 */
    private String roleName;

    /** 门店 ID（从实收明细继承） */
    private Long deptId;

    /** 分摊比例（仅展示用） */
    private BigDecimal shareRatio;

    /** 新签金额（手工录入） */
    @NotNull(message = "新签金额不能为空")
    private BigDecimal amount;
}
