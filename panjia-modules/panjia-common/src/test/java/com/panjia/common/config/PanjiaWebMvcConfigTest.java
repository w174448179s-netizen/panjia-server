package com.panjia.common.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘家业务接口统一前缀规则验证。
 */
@Tag("dev")
class PanjiaWebMvcConfigTest {

    private final PanjiaWebMvcConfig webMvcConfig = new PanjiaWebMvcConfig();

    @Test
    void businessControllerShouldCarryPanjiaPrefix() {
        assertTrue(webMvcConfig.isPanjiaBusinessController(BusinessController.class));
    }

    @Test
    void infraControllerWithApiPrefixShouldStayUntouched() {
        assertFalse(webMvcConfig.isPanjiaBusinessController(InfraController.class));
    }

    @Test
    void nonPanjiaControllerShouldStayUntouched() {
        assertFalse(webMvcConfig.isPanjiaBusinessController(PathMatchConfigurer.class));
    }

    /**
     * 模拟盘家业务 Controller（如 EmployeeController，映射 /people/employee）。
     */
    @RestController
    @RequestMapping("/people/employee")
    static class BusinessController {
    }

    /**
     * 模拟基础设施 Controller（如 BackupController，映射 /api/v1/backup）。
     */
    @RestController
    @RequestMapping("/api/v1/backup")
    static class InfraController {
    }
}
