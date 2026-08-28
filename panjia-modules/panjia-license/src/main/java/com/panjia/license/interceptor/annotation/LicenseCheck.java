package com.panjia.license.interceptor.annotation;

import com.panjia.license.enums.OperationEnum;

import java.lang.annotation.*;

/**
 * License 操作校验注解。
 * 标注在 Controller 方法上，指定该操作需要的 License 操作粒度。
 *
 * 示例：
 *   @LicenseCheck(operation = OperationEnum.CALCULATE)
 *   @PostMapping("/salary/calculate")
 *   public R<?> calculate(@RequestBody RequestVO req) { ... }
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LicenseCheck {

    /** 该操作需要的操作粒度 */
    OperationEnum operation();
}
