package com.panjia.performance.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 业绩冲正/补录单条明细。
 * <p>
 * 保存时携带冲正弹窗表格行的全部字段快照（工号/门店/角色占比/当前业绩等），
 * 详情页直接按快照展示；执行时优先按 {@code factId} 复制对应源事实，保证
 * 生成的事实与弹窗所见行完全一致。
 */
@Data
public class ManualOffsetItem {
    @NotNull(message = "员工不能为空")
    private Long employeeId;
    @NotBlank(message = "角色类型不能为空")
    private String roleType;
    private String roleName;
    @NotNull(message = "金额不能为空")
    private BigDecimal amount;

    /** 源事实 ID（既有行带出；新增角色人行无） */
    private Long factId;
    /** 员工工号快照 */
    private String employeeCode;
    /** 门店/组别路径快照 */
    private String deptName;
    /** 角色占比快照 */
    private BigDecimal shareRatio;
    /** 该行冲正前当前业绩快照（新增行 0） */
    private BigDecimal originalAmount;
}
