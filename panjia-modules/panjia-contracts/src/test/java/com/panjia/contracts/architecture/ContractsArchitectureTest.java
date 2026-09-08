package com.panjia.contracts.architecture;

import com.panjia.contracts.event.DomainEvent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 契约层架构校验（CI P1 门禁）。
 * <p>
 * 对应任务卡 05 §三：
 * <ul>
 *   <li>规则1：事件 payload 无 @Entity（DomainEvent 实现类不是 JPA Entity）</li>
 *   <li>规则2：contracts 不依赖业务域（叶子模块约束）</li>
 *   <li>规则3：禁止 @Value("${panjia.*}")（动态配置走 IConfigService）</li>
 * </ul>
 * 失败则阻断 PR 合并。
 * <p>
 * 实现说明：用 {@link ClassFileImporter} 手动导入契约层类，
 * 用标准 JUnit5 @Test 调用 ArchRule.check，确保 surefire 能发现测试。
 */
@Tag("dev")
class ContractsArchitectureTest {

    /** 契约层类集合（导入 contracts 包所有类） */
    private final JavaClasses contractsClasses =
        new ClassFileImporter().importPackages("com.panjia.contracts");

    /**
     * 规则1：DomainEvent 实现类禁止标注 @Entity。
     * <p>
     * 事件 payload 只允许基础类型 / Long / POJO / Snapshot，禁止持有其他域 @Entity。
     * 事件类本身被 @Entity 注解会污染 payload，CI 拦截。
     */
    @Test
    void event_payload_no_entity() {
        ArchRule rule = classes().that().implement(DomainEvent.class)
            .should().notBeAnnotatedWith("jakarta.persistence.Entity")
            .because("事件 payload 禁止 @Entity，payload 只允许基础类型/Long/POJO/Snapshot");
        assertDoesNotThrow(() -> rule.check(contractsClasses));
    }

    /**
     * 规则2：contracts 禁止依赖任何 panjia 业务域。
     * <p>
     * contracts 是叶子模块，只允许依赖 ruoyi-common-core，不依赖 people/import/payroll 等业务域。
     */
    @Test
    void contracts_no_business_dependency() {
        ArchRule rule = noClasses().that().resideInAPackage("com.panjia.contracts..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.panjia.people..",
                "com.panjia.import..",
                "com.panjia.performance..",
                "com.panjia.commission..",
                "com.panjia.payroll..",
                "com.panjia.ledger..",
                "com.panjia.outbox..")
            .because("contracts 是叶子模块，禁止依赖任何 panjia 业务域");
        assertDoesNotThrow(() -> rule.check(contractsClasses));
    }

    /**
     * 规则3：contracts 禁止用 @Value 读 panjia. 前缀配置。
     * <p>
     * 动态配置走 IConfigService#selectConfigByKey，@Value 仅用于启动期静态常量。
     * contracts 作为叶子模块更不应出现 @Value。
     */
    @Test
    void no_value_annotation() {
        ArchRule rule = noClasses().that().resideInAPackage("com.panjia.contracts..")
            .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Value")
            .because("禁止 @Value(\"${panjia.*}\") 读动态配置，contracts 叶子模块禁用 @Value");
        assertDoesNotThrow(() -> rule.check(contractsClasses));
    }
}
