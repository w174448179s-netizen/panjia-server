package com.panjia.contracts.event;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Outbox 事件投递状态枚举（状态机，消除魔法字符串）。
 * <p>
 * 状态流转：{@link #PENDING} → {@link #PROCESSED} / {@link #FAILED}。
 * <ul>
 *   <li>{@link #PENDING} 待投递（含投递中 / 退避等待重试），唯一非终态</li>
 *   <li>{@link #PROCESSED} 投递成功（终态）</li>
 *   <li>{@link #FAILED} 重试达上限（retry_count &gt;= 10），不再投递（终态）</li>
 * </ul>
 * <p>
 * 存储约定：DB 字段 status VARCHAR(16) 存 {@link #getCode()}（code 固定取枚举名，
 * 如 "PENDING"，与 V100004 DDL 默认值 / 部分索引谓词一致）。panjia-contracts 为
 * 叶子模块不依赖 mybatis-plus，MyBatis-Plus 默认 MybatisEnumTypeHandler 在无
 * {@code @EnumValue} 时按枚举 name() 映射——code 与 name() 同源，杜绝两套存储语义。
 * Jackson 序列化经 {@link JsonValue @JsonValue} 输出 code。
 */
public enum OutboxStatusEnum {

    /** 待投递（投递中 / 退避等待重试） */
    PENDING("待投递"),

    /** 投递成功（终态） */
    PROCESSED("投递成功"),

    /** 重试达上限，标记失败（终态） */
    FAILED("重试达上限");

    /** 状态码（DB / JSON 存储值，与枚举名一致） */
    private final String code;

    /** 状态描述 */
    private final String desc;

    OutboxStatusEnum(String desc) {
        // code 固定取枚举名：保证 DB 存值与 MyBatis-Plus 默认 name() 映射始终一致
        this.code = name();
        this.desc = desc;
    }

    /**
     * 获取状态码（DB / JSON 存储值）。
     *
     * @return 状态码
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 获取状态描述。
     *
     * @return 描述文本
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 判断是否可流转到目标状态。
     * <p>
     * 合法流转：PENDING → PROCESSED（投递成功）、PENDING → FAILED（重试达上限）；
     * 终态不可再流转，不允许自流转。
     *
     * @param target 目标状态
     * @return true 表示允许流转
     */
    public boolean canTransitTo(OutboxStatusEnum target) {
        if (this == target) {
            return false;
        }
        if (this == PENDING) {
            return target == PROCESSED || target == FAILED;
        }
        return false;
    }

    /**
     * 是否终态（PROCESSED / FAILED 不可再流转）。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == PROCESSED || this == FAILED;
    }

    /**
     * 是否进行中（PENDING：待投递 / 投递中 / 退避等待重试，尚未终结）。
     *
     * @return true 表示进行中
     */
    public boolean isRunning() {
        return this == PENDING;
    }
}
