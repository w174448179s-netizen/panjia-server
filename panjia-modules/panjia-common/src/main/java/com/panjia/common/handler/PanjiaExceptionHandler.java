package com.panjia.common.handler;

import com.panjia.common.exception.DuplicateOperationException;
import com.panjia.common.exception.LicenseExpiredException;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 盘家全局异常处理器
 * <p>
 * 统一处理 License 过期、数据权限、幂等拦截等业务异常。
 */
@RestControllerAdvice
public class PanjiaExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PanjiaExceptionHandler.class);

    /**
     * License 过期
     */
    @ExceptionHandler(LicenseExpiredException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> licenseExpired(LicenseExpiredException e) {
        log.warn("License 过期: {}", e.getMessage());
        return R.fail(HttpStatus.FORBIDDEN.value(), e.getMessage());
    }

    /**
     * 重复操作（幂等拦截）
     */
    @ExceptionHandler(DuplicateOperationException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<Void> duplicate(DuplicateOperationException e) {
        log.info("幂等拦截: {}", e.getMessage());
        return R.fail(e.getMessage());
    }

    /**
     * 数据权限异常
     */
    @ExceptionHandler(ServiceException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<Void> serviceException(ServiceException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getMessage());
    }
}
