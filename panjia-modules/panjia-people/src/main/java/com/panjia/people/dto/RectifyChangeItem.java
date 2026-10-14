package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 考勤修改明细项（月汇总指标单项调整）。
 * <p>
 * 请求时仅填 field + newValue；服务端快照补全 fieldName/oldValue 后落 changes_json，
 * VO 原样回显四项。field 限定白名单：attendDays/restDays/lateCount/lateMinutes/
 * missingCardCount/absentDays/leaveDays/remark。
 */
@Data
public class RectifyChangeItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 字段名（月汇总指标白名单） */
    private String field;

    /** 字段中文名（展示） */
    private String fieldName;

    /** 原值（提交时快照） */
    private String oldValue;

    /** 新值（数值字段按字符串传输，服务端按字段类型解析校验） */
    private String newValue;
}
