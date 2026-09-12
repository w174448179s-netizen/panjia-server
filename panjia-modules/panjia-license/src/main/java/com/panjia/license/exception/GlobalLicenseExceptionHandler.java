package com.panjia.license.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.exception.base.BaseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * License 全局异常处理器。
 * 统一转换 License 异常为 R 对象，前端收到后提示对应信息。
 * 同时处理被本 Advice 拦截的 Sa-Token 异常，返回与 SaTokenExceptionHandler 一致的友好提示。
 */
@Slf4j
@RestControllerAdvice
public class GlobalLicenseExceptionHandler {

    @ExceptionHandler(LicenseException.class)
    public R<Void> handleLicenseException(LicenseException e) {
        log.warn("[GlobalLicenseExceptionHandler] License 异常: {}", e.getMessage());
        if (e instanceof MonotonicException) {
            return R.fail("系统已锁定，请联系服务商恢复");
        } else if (e instanceof IntegrityException) {
            return R.fail("安全校验未通过，系统已进入受限模式");
        } else if (e instanceof FingerprintException) {
            return R.fail(e.getMessage());
        }
        return R.fail(e.getMessage());
    }

    @ExceptionHandler(RestrictedModeException.class)
    public R<Void> handleRestrictedModeException(RestrictedModeException e) {
        log.warn("[GlobalLicenseExceptionHandler] 受限模式: trigger={}, {}", e.getTriggerCode(), e.getMessage());
        return R.fail("系统已进入受限模式，请联系服务商");
    }

    /**
     * 处理 Sa-Token 权限校验失败异常。
     * 与 SaTokenExceptionHandler 返回一致，避免前端看到"系统内部错误"。
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public R<Void> handleNotAccessException(RuntimeException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        String reason = e instanceof NotRoleException ? "角色权限校验失败" : "权限码校验失败";
        log.error("请求地址'{}',{}'{}'", requestURI, reason, e.getMessage());
        return R.fail(HttpStatus.HTTP_FORBIDDEN, "没有访问权限，请联系管理员授权");
    }

    /**
     * 处理 Sa-Token 未登录异常。
     * 与 SaTokenExceptionHandler 返回一致，避免前端看到"系统内部错误"。
     */
    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLoginException(NotLoginException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',认证失败'{}',无法访问系统资源", requestURI, e.getMessage());
        String msg = switch (e.getType()) {
            case NotLoginException.TOKEN_TIMEOUT,
                 NotLoginException.TOKEN_FREEZE -> "登录已过期，请重新登录";
            case NotLoginException.BE_REPLACED -> "当前账号已在其他设备登录，您已被强制下线";
            case NotLoginException.KICK_OUT -> "账号已被管理员强制下线";
            default -> "登录状态异常，请重新登录";
        };
        return R.fail(HttpStatus.HTTP_UNAUTHORIZED, msg);
    }

    /**
     * 处理 SSE 异步响应不可用异常。
     * SSE 长连接断开（客户端断网、页面关闭等）时，SseEmitterSessionManager 清理连接调用
     * emitter.complete() 会抛出此异常。此时响应体已是 text/event-stream 且不可写，
     * 只打日志即可，不要尝试返回 R JSON 对象（否则会因 Content-Type 不匹配产生二次报错）。
     *
     * 这是正常现象（客户端随时可能断开 SSE 连接），日志级别为 warn 不打堆栈。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException e) {
        log.warn("[GlobalLicenseExceptionHandler] SSE 连接已断开: {}", e.getMessage());
    }

    /**
     * RuoYi 基础业务异常（验证码错误/过期、用户不存在或被停用等 BaseException 体系）。
     * <p>
     * 本 Advice 与框架原生 GlobalExceptionHandler 均无 @Order，若不声明此处理器，
     * 这类业务异常会被下面的 Exception 兜底抢先匹配，伪装成"系统内部错误"。
     * 必须在 Exception 兜底之前按具体类型透传其国际化提示。
     */
    @ExceptionHandler(BaseException.class)
    public R<Void> handleBaseException(BaseException e) {
        log.warn("[GlobalLicenseExceptionHandler] 业务异常: {}", e.getMessage());
        return R.fail(e.getMessage());
    }

    /**
     * RuoYi 通用业务异常（携带可选错误码），原因同 {@link #handleBaseException}。
     */
    @ExceptionHandler(ServiceException.class)
    public R<Void> handleServiceException(ServiceException e) {
        log.warn("[GlobalLicenseExceptionHandler] 业务异常: {}", e.getMessage());
        Integer code = e.getCode();
        return ObjectUtil.isNotNull(code) ? R.fail(code, e.getMessage()) : R.fail(e.getMessage());
    }

    /**
     * 兜底异常处理：仅处理未被其他异常处理器捕获的通用异常。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleGenericException(Exception e) {
        log.error("[GlobalLicenseExceptionHandler] 未预期异常: {}", e.getMessage(), e);
        return R.fail("系统内部错误，请联系服务商");
    }
}
