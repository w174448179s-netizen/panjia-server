package com.panjia.importutil.exception;

/**
 * 导入工具层统一异常（文件解析/模板解析/归档失败）。
 */
public class ImportUtilException extends RuntimeException {

    public ImportUtilException(String message) {
        super(message);
    }

    public ImportUtilException(String message, Throwable cause) {
        super(message, cause);
    }
}
