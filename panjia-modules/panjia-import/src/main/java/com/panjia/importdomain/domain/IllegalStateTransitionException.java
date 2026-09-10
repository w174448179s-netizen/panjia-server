package com.panjia.importdomain.domain;

/**
 * 非法状态转换异常（状态机硬约束）。
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(ImportBatchStatus from, ImportBatchStatus to) {
        super("非法状态转换: " + from.name() + " → " + to.name());
    }
}
