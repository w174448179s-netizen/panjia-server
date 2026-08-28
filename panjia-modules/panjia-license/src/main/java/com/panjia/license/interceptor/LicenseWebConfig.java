package com.panjia.license.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * License 拦截器注册。
 * 注册 LicenseInterceptor，排除鉴权接口自身（避免递归拦截）。
 *
 * 注：LicenseInterceptor 实际只拦截标注了 @LicenseCheck 的方法，
 * 此处的 excludePathPatterns 主要用于减少不必要的拦截器调用开销。
 */
@Slf4j
@Configuration
public class LicenseWebConfig implements WebMvcConfigurer {

    private final LicenseInterceptor licenseInterceptor;

    public LicenseWebConfig(LicenseInterceptor licenseInterceptor) {
        this.licenseInterceptor = licenseInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(licenseInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/**",              // 登录/认证接口
                        "/api/v1/license/**",    // License 模块内部接口
                        "/actuator/**",           // 监控端点
                        "/error",                 // 错误页
                        "/static/**",             // 静态资源
                        "/*.html",
                        "/**/*.html",
                        "/**/*.css",
                        "/**/*.js",
                        "/favicon.ico"
                );
        log.info("[LicenseWebConfig] License 拦截器已注册");
    }
}
