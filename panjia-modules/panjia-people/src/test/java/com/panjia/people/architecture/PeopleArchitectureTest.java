package com.panjia.people.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 员工域架构守护（V5.2 六边形 Port-Adapter）。
 * <ul>
 *   <li>规则1：只有 adapter 包可以依赖 org.dromara.system.*（sys_user/sys_dept/sys_post 底座细节）；
 *       domain/service/port/dto/controller 只能面向端口与 org.dromara.common.* 通用件编程。</li>
 *   <li>规则2：people 域禁止依赖兄弟业务域（import/payroll/outbox 等），跨域协作只走 contracts 端口。</li>
 * </ul>
 */
@Tag("dev")
class PeopleArchitectureTest {

    /** 员工域全部类 */
    private final JavaClasses peopleClasses =
        new ClassFileImporter().importPackages("com.panjia.people");

    /**
     * 规则1：org.dromara.system.* 仅 adapter 包可依赖。
     * <p>
     * sys_user / sys_dept / sys_post / sys_role 的读写细节收敛在 RuoYi*Adapter，
     * service 层只依赖 people.port 端口，保证业务逻辑与底座解耦。
     */
    @Test
    void only_adapter_may_depend_on_ruoyi_system() {
        ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("com.panjia.people.adapter..")
            .should().dependOnClassesThat().resideInAnyPackage("org.dromara.system..")
            .because("sys_user/sys_dept/sys_post 等底座细节只能由 adapter 包访问，"
                + "service/domain/port 必须面向 people.port 端口编程");
        assertDoesNotThrow(() -> rule.check(peopleClasses));
    }

    /**
     * 规则2：people 禁止依赖任何兄弟业务域实现。
     * <p>
     * 跨域协作只允许走 com.panjia.contracts（PeopleQueryPort / EventPort 等），
     * 禁止 import com.panjia.import / payroll / outbox 等域实现包。
     */
    @Test
    void people_must_not_depend_on_sibling_domains() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.panjia.people..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.panjia.import..",
                "com.panjia.performance..",
                "com.panjia.commission..",
                "com.panjia.payroll..",
                "com.panjia.ledger..",
                "com.panjia.outbox..",
                "com.panjia.backup..")
            .because("people 与兄弟域协作只能走 com.panjia.contracts 端口，禁止依赖域实现包");
        assertDoesNotThrow(() -> rule.check(peopleClasses));
    }
}
