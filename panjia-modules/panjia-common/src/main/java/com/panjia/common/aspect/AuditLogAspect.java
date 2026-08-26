package com.panjia.common.aspect;

import com.panjia.common.annotation.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.dromara.common.log.event.OperLogEvent;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 审计日志切面
 * <p>
 * 强制留痕，将关键业务操作记录到 sys_oper_log。
 * 适用于：授权变更、工资锁定、备份恢复、业绩调整、员工变更。
 */
@Aspect
@Component
public class AuditLogAspect {

    private final ApplicationEventPublisher eventPublisher;

    public AuditLogAspect(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        OperLogEvent event = new OperLogEvent();
        event.setTitle(auditLog.description());
        event.setMethod(joinPoint.getTarget().getClass().getName() + "." + signature.getName());
        event.setBusinessType(0);
        event.setOperatorType(1);
        event.setOperTime(LocalDateTime.now());
        event.setOperName(LoginHelper.getUsername());
        event.setUserId(LoginHelper.getUserId());
        event.setDeptId(LoginHelper.getDeptId());

        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            event.setRequestMethod(request.getMethod());
            event.setOperUrl(request.getRequestURI());
            event.setOperIp(request.getRemoteHost());
        }

        if (auditLog.recordParam()) {
            event.setOperParam(java.util.Arrays.toString(joinPoint.getArgs()));
        }

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            event.setStatus(0);
            if (auditLog.recordResult()) {
                event.setJsonResult(result != null ? result.toString() : null);
            }
            event.setCostTime(System.currentTimeMillis() - start);
            eventPublisher.publishEvent(event);
            return result;
        } catch (Throwable e) {
            event.setStatus(1);
            event.setErrorMsg(e.getMessage());
            event.setCostTime(System.currentTimeMillis() - start);
            eventPublisher.publishEvent(event);
            throw e;
        }
    }
}
