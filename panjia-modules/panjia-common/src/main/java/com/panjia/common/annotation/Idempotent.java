package com.panjia.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等注解
 * <p>
 * 基于 Redis 实现接口幂等，防止同一操作被重复执行。
 * 幂等键有效期默认 24 小时，失败可重触发。
 *
 * <pre>
 * 用法示例：
 * &#64;Idempotent(key = "#batchId", expire = 3600)
 * public void calculateSalary(String batchId) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * 幂等键，支持 SpEL 表达式
     */
    String key();

    /**
     * 过期时间（秒），默认 86400 = 24 小时
     */
    int expire() default 86400;

    /**
     * 提示信息
     */
    String message() default "操作已处理，请勿重复提交";
}
