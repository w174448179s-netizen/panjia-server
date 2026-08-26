package com.panjia.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解
 * <p>
 * 强制留痕，记录关键业务操作到 sys_oper_log。
 * 适用于：授权变更、工资锁定、备份恢复、业绩调整、员工变更等。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /**
     * 操作类型
     */
    OperateType value() default OperateType.OTHER;

    /**
     * 操作描述
     */
    String description() default "";

    /**
     * 是否记录请求参数
     */
    boolean recordParam() default true;

    /**
     * 是否记录返回结果
     */
    boolean recordResult() default false;

    /**
     * 操作类型枚举
     */
    enum OperateType {
        /** License 签发/吊销/更新 */
        LICENSE_GRANT,
        LICENSE_REVOKE,
        LICENSE_UPDATE,
        /** 工资锁定/解锁 */
        SALARY_LOCK,
        SALARY_UNLOCK,
        /** 备份恢复 */
        BACKUP_RESTORE,
        /** 业绩调整 */
        COMMISSION_ADJUST,
        /** 员工变更（升职/降级/转店/离职） */
        EMPLOYEE_CHANGE,
        /** 其他 */
        OTHER
    }
}
