package com.panjia.license.interceptor;

import com.panjia.license.config.LicenseProperties;
import com.panjia.license.enums.OperationEnum;
import com.panjia.license.security.LicenseGuard;
import com.panjia.license.service.LicenseContext;
import com.panjia.license.service.LicenseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * License 关键操作拦截器。
 *
 * 当 License 未启用时（非 prod profile 且 enabled=false），所有请求直接放行。
 * prod profile 下 LicenseGuard 强制开启，无法绕过。
 *
 * 使用方式：在 Controller 方法上标注 @LicenseCheck(operation = OperationEnum.CALCULATE)
 */
@Slf4j
@Component
public class LicenseInterceptor implements HandlerInterceptor {

    private final LicenseService licenseService;
    private final LicenseContext licenseContext;
    private final LicenseGuard licenseGuard;

    public LicenseInterceptor(LicenseService licenseService,
                              LicenseContext licenseContext,
                              LicenseGuard licenseGuard) {
        this.licenseService = licenseService;
        this.licenseContext = licenseContext;
        this.licenseGuard = licenseGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!licenseGuard.shouldEnforce()) {
            return true;
        }

        if (handler instanceof org.springframework.web.method.HandlerMethod hm) {
            var annotation = hm.getMethodAnnotation(com.panjia.license.interceptor.annotation.LicenseCheck.class);
            if (annotation == null) {
                return true;
            }
            OperationEnum operation = annotation.operation();

            boolean allowed = licenseService.isOperationAllowed(operation.name());
            if (allowed) {
                log.debug("[LicenseInterceptor] 操作允许: {}", operation);
                return true;
            } else {
                log.warn("[LicenseInterceptor] 操作拒绝: {}，当前状态={}, restricted={}",
                        operation, licenseContext.getStatus(), licenseContext.isRestricted());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                try {
                    response.getWriter().write(cn.hutool.json.JSONUtil.toJsonStr(java.util.Map.of(
                            "code", "LICENSE_DENIED",
                            "msg", getDenyMessage(operation),
                            "status", licenseContext.getStatus().name()
                    )));
                } catch (Exception e) {
                    try {
                        response.getWriter().write("{\"code\":\"LICENSE_DENIED\",\"msg\":\"" + getDenyMessage(operation) + "\"}");
                    } catch (java.io.IOException ex) {
                        log.error("[LicenseInterceptor] 写入失败响应异常: {}", ex.getMessage());
                    }
                }
                return false;
            }
        }
        return true;
    }

    private String getDenyMessage(OperationEnum operation) {
        if (licenseContext.getStatus() == com.panjia.license.enums.LicenseStatusEnum.OFFLINE_LOCK) {
            return "系统已锁定，请联系服务商恢复";
        }
        if (licenseContext.isRestricted()) {
            return "授权受限，核心功能不可用，请联系服务商";
        }
        if (!licenseContext.isNetworkReachable()) {
            return "网络不可达，离线模式仅允许查看";
        }
        return "操作不被允许：" + operation.getDescription();
    }
}
