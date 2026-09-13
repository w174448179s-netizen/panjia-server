package com.panjia.payroll.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 工资批次状态机。
 * <p>
 * DRAFT ──calculate──▶ CALCULATING ──success──▶ CALCULATED
 *                             └──failure──▶ FAILED
 * CALCULATED ──submit──▶ REVIEWING ──approve──▶ APPROVED ──lock──▶ LOCKED ──pay──▶ PAID
 *                       └──reject──▶ CALCULATED
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

    public boolean canApprove() {
        return this == REVIEWING;
    }

    public boolean canReject() {
        return this == REVIEWING;
    }

    public boolean canLock() {
        return this == APPROVED;
    }

    public boolean canPay() {
        return this == LOCKED;
    }

    public boolean isLocked() {
        return this == LOCKED || this == PAID;
    }
}
