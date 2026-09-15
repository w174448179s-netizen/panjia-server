package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import org.dromara.common.core.exception.ServiceException;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工资批次（薪酬结算域聚合根）。
 * period = 工资归属月 = 业绩归属月。
 */
@Data
@TableName("pj_payroll_batch")
public class PayrollBatch implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 工资归属月 YYYY-MM */
    private String period;

    /** 批次范围 ALL / 单门店 */
    private String deptScope;

    private BatchStatus status;

    private Long ruleSnapshotId;

    private Integer employeeCount;

    private BigDecimal grossTotal;
    private BigDecimal deductTotal;
    private BigDecimal taxTotal;
    private BigDecimal netTotal;

    private Integer attempt;
    private String inputHash;

    /** Warm-Flow 流程实例 ID（payroll_batch：提交算薪 → 总监审核 → 总监锁定） */
    private String processInstanceId;

    private LocalDateTime lockedAt;
    private Long lockedBy;
    private Long operatorId;

    @Version
    private Integer version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ==================== 状态机断言（只能通过这些方法推进） ====================
    // 审批态（REVIEWING→APPROVED→LOCKED）由工作流事件回调推进，
    // 不再提供 approve/reject/lock 的业务断言。

    public void assertCanCalculate() {
        if (status == null || !status.canCalculate()) {
            throw new ServiceException("当前状态「" + statusName() + "」不允许重新算薪");
        }
    }

    public void assertCanSubmit() {
        if (status == null || !status.canSubmit()) {
            throw new ServiceException("当前状态「" + statusName() + "」不允许提交审核");
        }
    }

    public void assertCanPay() {
        if (status == null || !status.canPay()) {
            throw new ServiceException("当前状态「" + statusName() + "」不允许标记发放");
        }
    }

    public void assertNotLocked() {
        if (status != null && status.isLocked()) {
            throw new ServiceException("批次已锁定，不可修改");
        }
    }

    private String statusName() {
        return status == null ? "未知" : status.getCode();
    }
}
