package com.panjia.importutil.validate;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 基础格式校验错误（纯技术，无业务语义）。
 */
@Data
public class FieldError implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 数据行号（1-based） */
    private int rowNo;

    /** 目标字段名 */
    private String field;

    /** 原始值 */
    private String rawValue;

    /** 错误原因（TYPE_ERR / REQUIRED_MISSING / ENUM_INVALID / PATTERN_MISMATCH / MAX_LENGTH） */
    private String reason;

    /** 说明文案 */
    private String message;

    public FieldError() {
    }

    public FieldError(int rowNo, String field, String rawValue, String reason, String message) {
        this.rowNo = rowNo;
        this.field = field;
        this.rawValue = rawValue;
        this.reason = reason;
        this.message = message;
    }
}
