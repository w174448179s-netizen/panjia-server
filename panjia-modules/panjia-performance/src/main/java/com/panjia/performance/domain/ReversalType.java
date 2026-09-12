package com.panjia.performance.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 业绩事实红冲类型枚举。
 * <p>
 * 存储约定：DB 字段 reversal_type VARCHAR(20) 存 code（code 固定取枚举名），
 * MyBatis-Plus 默认按枚举 name() 映射；Jackson 经 {@link JsonValue @JsonValue} 输出 code。
 * <p>
 * 与 {@link ReversedReason} 的区别：
 * <ul>
 *   <li>{@code reversed_reason}：旧事实被作废（ACTIVE→REVERSED）时的原因，描述的是<b>旧事实的终态</b>；</li>
 *   <li>{@code reversal_type}：红冲负事实本身的类型标记，负事实仍为 ACTIVE，
 *       通过 {@code refund_of_fact_id} 指向成交月原正数事实，描述的是<b>新负事实的来源</b>。</li>
 * </ul>
 */
public enum ReversalType {

    /** 退单红冲：退单月负数行按原事实冻结口径镜像生成（金额/系数/人员取原快照，不按当期重算） */
    REDINK_REFUND("退单红冲");

    /** 红冲类型码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 红冲类型描述 */
    private final String desc;

    ReversalType(String desc) {
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取红冲类型码。
     *
     * @return 类型码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取红冲类型描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按 code 解析红冲类型。
     *
     * @param code 类型码
     * @return 红冲类型；code 为空或无法识别时返回 null
     */
    public static ReversalType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (ReversalType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
