package com.panjia.contracts.event;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Outbox 事件状态枚举（消除魔法字符串）。
 * <p>
 * DB 字段 VARCHAR(16) 存枚举名；MyBatis-Plus 默认 MybatisEnumTypeHandler
 * 按枚举 name() 映射，无需 @EnumValue（panjia-contracts 为叶子模块，
 * 不依赖 mybatis-plus）。Jackson 序列化用 @JsonValue 返回枚举名。
 * <p>
 * 状态流转：PENDING → PROCESSED / FAILED。
 * <ul>
 *   <li>{@link #PENDING} 待投递</li>
 *   <li>{@link #PROCESSED} 投递成功</li>
 *   <li>{@link #FAILED} 重试达上限（retry_count &gt;= 10），不再投递</li>
 * </ul>
 */
public enum OutboxStatusEnum {

    /** 待投递 */
    PENDING,

    /** 投递成功 */
    PROCESSED,

    /** 重试达上限，标记失败 */
    FAILED;

    /**
     * JSON 序列化值（枚举名）。
     *
     * @return 枚举名
     */
    @JsonValue
    public String getCode() {
        return name();
    }
}
