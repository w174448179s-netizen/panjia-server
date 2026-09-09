package com.panjia.people.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 员工域（people）架构守护测试（CI P1 门禁）。
 * <p>
 * 对应详细设计铁律：
 * <ul>
 *   <li>规则1：Employee 聚合根禁止离开 people 域 —— 外部域只通过
 *       EmployeeQueryPort / EmployeeSnapshot / Long 访问，禁止直接引用领域聚合根</li>
 *   <li>规则2：people 域不反向依赖 payroll / commission 等其他业务域（依赖方向单向向内）</li>
 * </ul>
 * <p>
 * 实现说明：用 {@link ClassFileImporter} 手动导入 classpath 上的 com.panjia 类，
 * 用标准 JUnit5 @Test 调用 ArchRule.check，确保 surefire 能发现测试。
 */
@Tag("dev")
class PeopleArchitectureTest {

    /** com.panjia 全部类（test classpath 含 people / contracts / common） */
    private final JavaClasses panjiaClasses =
        new ClassFileImporter().importPackages("com.panjia");

    /**
     * 规则1：Employee 聚合根（com.panjia.people.domain.Employee）
     * 禁止被 people 域以外的类引用。
     * <p>
     * payroll / commission 等外部域只能依赖 contracts 的
     * EmployeeQueryPort / EmployeeSnapshot，不能持有 Employee 聚合根。
     */
    @Test
    void employee_aggregate_must_not_leave_people_domain() {
        ArchRule rule = noClasses().that().resideOutsideOfPackage("com.panjia.people..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("com.panjia.people.domain.Employee")
            .because("Employee 聚合根禁止离开 people 域，外部域只通过 EmployeeQueryPort/EmployeeSnapshot/Long 访问");
        assertDoesNotThrow(() -> rule.check(panjiaClasses));
    }

    /**
     * 规则2：people 域禁止依赖 payroll / commission 等其他业务域。
     * <p>
     * 依赖方向单向向内：其他域 → contracts/people，people 不反向依赖任何兄弟业务域。
     */
    @Test
    void people_must_not_depend_on_other_business_domains() {
        ArchRule rule = noClasses().that().resideInAPackage("com.panjia.people..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.panjia.payroll..",
                "com.panjia.commission..",
                "com.panjia.performance..",
                "com.panjia.ledger..",
                "com.panjia.outbox..")
            .because("people 域不反向依赖兄弟业务域，依赖方向单向向内（外部域 → contracts/people）");
        assertDoesNotThrow(() -> rule.check(panjiaClasses));
    }
}
