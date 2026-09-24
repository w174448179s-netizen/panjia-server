package com.panjia.importdomain.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 导入问题类型（V1.4 §3.4）。
 * <p>
 * blocking 语义（V2.0 §归一化归档）：
 * <ul>
 *   <li><b>blocking=true</b>：格式/结构错误，归一化产物无效，批次必须停在
 *       {@code PENDING_CONFIRM} 人工处理（改模板 / 修原始文件），禁止自动归档；</li>
 *   <li><b>blocking=false</b>：数据行自身有效，但富化失败（如员工未匹配），
 *       归一化记录照常生成、下游按缺失字段跳过该行；批次照常自动归档、
 *       问题清单可见但不阻塞消费。历史工资导入必然出现 EMPLOYEE_NOT_MATCH
 *       （离职员工 / 重名歧义），若设为 blocking 会导致批次永远卡在待确认。</li>
 * </ul>
 */
public enum ImportIssueType {

    /** 员工未匹配（行有效但富化失败，下游跳过该行；非阻塞） */
    EMPLOYEE_NOT_MATCH("员工未匹配", false),
    /** 列类型错误（格式/模板契约破坏，阻塞） */
    COLUMN_TYPE_ERR("列类型错误", true),
    /** 必填缺失（模板 required=true 但值为空，阻塞） */
    REQUIRED_MISSING("必填缺失", true),
    /** 重复键（同一业务键多行冲突，阻塞） */
    DUPLICATE_KEY("重复键", true),
    /** 跨月不一致（归属月与行内日期偏离过大，阻塞） */
    PERIOD_MISMATCH("跨月不一致", true);

    @EnumValue
    private final String code;
    private final String desc;
    /** 归一化归档时是否阻塞批次自动归档 */
    private final boolean blocking;

    ImportIssueType(String desc, boolean blocking) {
        this.code = name();
        this.desc = desc;
        this.blocking = blocking;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public static ImportIssueType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ImportIssueType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return null;
    }
}
