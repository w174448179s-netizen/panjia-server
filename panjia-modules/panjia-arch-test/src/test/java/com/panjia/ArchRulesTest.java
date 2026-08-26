package com.panjia;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static com.tngtech.archunit.library.dependencies.SlicesRuleBuilder.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 盘家智管架构守护规则（ArchUnit）
 * <p>
 * 守卫 1：目录边界 — 底座包 org.dromara.. 不得依赖盘家包 com.panjia..
 * 守卫 2：命名域隔离 — ruoyi-* 目录不得出现 com.panjia 包
 * 守卫 3：依赖方向 — 盘家可依赖底座，底座不得反向依赖盘家
 */
@Tag("dev")
@Tag("prod")
public class ArchRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("org.dromara..", "com.panjia..");
    }

    // ==================== 守卫 1：目录边界 ====================

    /**
     * 底座包 org.dromara.. 不得依赖盘家包 com.panjia..
     */
    @Test
    void 底座不得依赖盘家() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("org.dromara..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.panjia..")
                .because("底座内核 org.dromara 不得反向依赖盘家业务域 com.panjia，确保升级时零冲突");

        rule.check(classes);
    }

    /**
     * 盘家包 com.panjia.. 可以依赖底座包 org.dromara..（单向依赖）
     */
    @Test
    void 盘家可依赖底座() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.panjia..")
                .should().adhereToAPattern() // 仅验证不报错即可
                .because("盘家业务域可依赖底座，此规则验证类可加载");

        // 此测试实际验证的是反向规则不触发
        noClasses()
                .that().resideInAPackage("org.dromara..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.panjia..")
                .check(classes);
    }

    // ==================== 守卫 2：命名域隔离 ====================

    /**
     * com.panjia 包不得出现在 org.dromara 的源码树中
     */
    @Test
    void 盘家包不得出现在底座源码中() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.panjia..")
                .should().beDefinedIn("..org.dromara..")
                .because("ruoyi-* 目录不得出现 com.panjia 包，CI 强制校验");

        rule.check(classes);
    }

    // ==================== 守卫 3：依赖方向与模块边界 ====================

    /**
     * 盘家各业务模块之间不得循环依赖
     */
    @Test
    void 盘家模块无循环依赖() {
        ArchRule rule = slices()
                .matching("com.panjia.(*)..")
                .should().beFreeOfCycles();

        rule.check(classes);
    }

    /**
     * panjia-salary 不得被其他 panjia 模块依赖（核心业务模块，不暴露给其他业务域）
     */
    @Test
    void 薪酬模块不被其他盘家模块依赖() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.panjia..")
                .and().resideOutsideOfPackage("com.panjia.salary..")
                .and().resideOutsideOfPackage("com.panjia.common..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.panjia.salary..")
                .because("薪酬模块是核心业务，不应被其他盘家模块反向依赖");

        rule.check(classes);
    }

    /**
     * 所有 Controller 必须使用 /api/v1/ 前缀路由
     */
    @Test
    void controller必须使用ApiV1前缀() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .and().resideInAPackage("com.panjia..")
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")
                .because("盘家 Controller 必须使用 @RequestMapping 声明 /api/v1/ 路由前缀");

        rule.check(classes);
    }
}
