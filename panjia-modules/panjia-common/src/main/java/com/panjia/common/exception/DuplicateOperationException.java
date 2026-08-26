package com.panjia.common.exception;

import java.io.Serial;

/**
 * 重复操作异常（幂等拦截）
 * <p>
 * 当同一幂等键在有效期内被重复提交时抛出。
 */
public class DuplicateOperationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DuplicateOperationException(String message) {
        super(message);
    }

    public DuplicateOperationException() {
        super("操作已处理，请勿重复提交");
    }
}
