package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 导入问题状态（V1.4 §3.4）。
 */
public enum ImportIssueStatus {

    /** 未处理，批次停留 PENDING_CONFIRM */
    OPEN(0),

    /** 已人工修复数据，重归一化通过 */
    RESOLVED(1),

    /** 业务确认可忽略（仅限算薪人员/总监） */
    IGNORED(2);

    @EnumValue
    private final int code;

    ImportIssueStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ImportIssueStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ImportIssueStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}
