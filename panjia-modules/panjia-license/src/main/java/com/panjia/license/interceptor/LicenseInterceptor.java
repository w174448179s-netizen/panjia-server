package com.panjia.license.interceptor;

import com.panjia.common.exception.LicenseExpiredException;
import com.panjia.license.service.LicenseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * License 校验拦截器
 * <p>
 * 拦截链顺序：Sa-Token 认证 → License 校验 → 数据权限 → 业务逻辑
 * <ul>
 *   <li>未授权：拒绝所有请求</li>
 *   <li>到期保护期：仅允许 GET 请求 + 导出操作</li>
 *   <li>完全过期：拒绝所有请求</li>
 * </ul>
 */
@Component
public class LicenseInterceptor implements HandlerInterceptor {

    private final LicenseService licenseService;

    public LicenseInterceptor(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LicenseService.LicenseStatus status = licenseService.checkStatus();

        switch (status) {
            case VALID:
                return true;
            case GRACE_PERIOD:
                // 保护期：仅允许 GET 请求 + 导出操作
                String method = request.getMethod();
                String uri = request.getRequestURI();
                boolean isGetOrExport = "GET".equalsIgnoreCase(method)
                        || uri.contains("/export")
                        || uri.contains("/download");
                if (isGetOrExport) {
                    return true;
                }
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                throw new LicenseExpiredException("License 处于保护期，仅允许查询和导出操作");
            case EXPIRED:
            case UNAUTHORIZED:
            default:
                throw new LicenseExpiredException();
        }
    }
}
