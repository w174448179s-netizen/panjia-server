package com.panjia.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 盘家业务接口统一前缀装配。
 * <p>
 * 前端约定所有盘家业务接口走 {@code /api/panjia} 前缀（网关/代理层完成
 * {@code /dev-api} 剥离后到达后端），此处为 com.panjia 业务包下的 Controller
 * 统一添加该前缀，业务 Controller 只需声明模块内路径（如 {@code /people/employee}）。
 * <p>
 * 已显式声明 {@code /api} 前缀的基础设施接口（如 {@code /api/v1/backup}）保持原样。
 */
@Configuration
public class PanjiaWebMvcConfig implements WebMvcConfigurer {

    /**
     * 盘家业务接口统一前缀。
     */
    public static final String API_PREFIX = "/api/panjia";

    /**
     * 基础设施接口自带的前缀前缀，命中则不再追加统一前缀。
     */
    private static final String INFRA_PREFIX = "/api";

    private static final String PANJIA_PACKAGE = "com.panjia.";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX, this::isPanjiaBusinessController);
    }

    /**
     * 判断是否为需要追加统一前缀的盘家业务 Controller。
     *
     * @param handlerType 处理器类型
     * @return true 表示追加 {@link #API_PREFIX}
     */
    boolean isPanjiaBusinessController(Class<?> handlerType) {
        if (!handlerType.getPackageName().startsWith(PANJIA_PACKAGE)) {
            return false;
        }
        RequestMapping requestMapping = handlerType.getAnnotation(RequestMapping.class);
        if (requestMapping != null) {
            for (String path : requestMapping.value()) {
                if (path.startsWith(INFRA_PREFIX)) {
                    return false;
                }
            }
        }
        return true;
    }
}
