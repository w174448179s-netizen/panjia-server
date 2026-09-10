package com.panjia.importdomain.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 导入域架构守护（V1.4 六边形 Port-Adapter）。
 * <ul>
 *   <li>规则1：只有 adapter 包可以依赖 org.dromara.system.*（底座 sys_* 细节）；
 *       domain/service/port/dto/controller 只能面向端口与 org.dromara.common.* 通用件编程。</li>
 *   <li>规则2：import 域对 people 域只允许依赖 port（EmployeeImportSink）与 dto（ValidatedEmployeeRow），
 *       禁止触碰 people.domain/service/mapper。</li>
 *   <li>规则3：import 禁止依赖其他兄弟业务域实现。</li>
 * </ul>
 */
@Tag("dev")
class ImportArchitectureTest {

    private final JavaClasses importClasses =
        new ClassFileImporter().importPackages("com.panjia.importdomain");

    /**
     * 规则1：org.dromara.system.* 仅 adapter 包可依赖。
     */
    @Test
    void only_adapter_may_depend_on_ruoyi_system() {
        ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("com.panjia.importdomain.adapter..")
            .should().dependOnClassesThat().resideInAnyPackage("org.dromara.system..")
            .because("sys_user/sys_dept/sys_post 等底座细节只能由 adapter 包访问");
        assertDoesNotThrow(() -> rule.check(importClasses));
    }

    /**
     * 规则2：import 对 people 仅可依赖 port 与 dto。
     */
    @Test
    void import_only_depends_on_people_port_and_dto() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.panjia.importdomain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.panjia.people.domain..",
                "com.panjia.people.service..",
                "com.panjia.people.mapper..",
                "com.panjia.people.controller..")
            .because("import 与 people 协作只能走 com.panjia.people.port / com.panjia.people.dto");
        assertDoesNotThrow(() -> rule.check(importClasses));
    }

    /**
     * 规则3：import 禁止依赖其他兄弟业务域。
     */
    @Test
    void import_must_not_depend_on_sibling_domains() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.panjia.importdomain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.panjia.salary..",
                "com.panjia.ledger..",
                "com.panjia.backup..")
            .because("import 与兄弟域协作只能走 com.panjia.contracts 端口");
        assertDoesNotThrow(() -> rule.check(importClasses));
    }
}
