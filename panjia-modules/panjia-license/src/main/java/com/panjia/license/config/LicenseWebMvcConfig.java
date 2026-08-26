package com.panjia.license.config;

import com.panjia.license.interceptor.LicenseInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * License 拦截器注册
 * <p>
 * 排除登录、验证码等公共接口，其余所有 /api/** 路径均需 License 校验。
 */
@Configuration
public class LicenseWebMvcConfig implements WebMvcConfigurer {

    private final LicenseInterceptor licenseInterceptor;

    public LicenseWebMvcConfig(LicenseInterceptor licenseInterceptor) {
        this.licenseInterceptor = licenseInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(licenseInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/captcha/**",
                        "/api/v1/license/activate"
                );
    }
}
