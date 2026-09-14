package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 实收业绩审批单状态（pj_perf_received_apply.status）。
 */
public enum ReceivedApplyStatus {

    /** 草稿（手工建单未提交） */
    DRAFT("待提交"),

    /** 已提交（审批中） */
    SUBMITTED("审批中"),

    /** 审批通过 */
    APPROVED("已通过"),

    /** 已驳回 */
    REJECTED("已驳回"),

    /** 已作废 */
    CANCELLED("已作废");

    private final String code;
    private final String desc;

    ReceivedApplyStatus(String desc) {
        this.code = name();
        this.desc = desc;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ReceivedApplyStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ReceivedApplyStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
