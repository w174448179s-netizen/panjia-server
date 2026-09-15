package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 工资批次状态机。
 * <p>
 * DRAFT ──calculate──▶ CALCULATING ──success──▶ CALCULATED
 *                             └──failure──▶ FAILED
 * CALCULATED ──submit(发起 payroll_batch 流程)──▶ REVIEWING ──总监审核通过──▶ APPROVED
 *                       └──总监驳回(back 事件)──▶ CALCULATED
 * APPROVED ──总监锁定节点通过(finish 事件)──▶ LOCKED ──pay──▶ PAID
 * <p>
 * REVIEWING → APPROVED → LOCKED 由工作流事件回调推进（PayrollBatchWorkflowListener），
 * 不存在业务直批路径。
 */
@Getter
public enum BatchStatus {

    DRAFT("草稿"),
    CALCULATING("计算中"),
    CALCULATED("已计算"),
    FAILED("计算失败"),
    REVIEWING("待审核"),
    APPROVED("已确认"),
    LOCKED("已锁定"),
    PAID("已发放");

    @EnumValue
    private final String code;

    BatchStatus(String code) {
        this.code = code;
    }

    public static BatchStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (BatchStatus s : values()) {
            if (s.name().equals(code)) {
                return s;
            }
        }
        return null;
    }

    public boolean canCalculate() {
        return this == DRAFT || this == CALCULATED || this == FAILED || this == REVIEWING;
    }

    public boolean canSubmit() {
        return this == CALCULATED;
    }

    public boolean canPay() {
        return this == LOCKED;
    }

    public boolean isLocked() {
        return this == LOCKED || this == PAID;
    }
}
