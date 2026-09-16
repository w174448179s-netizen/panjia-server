package com.panjia.commission.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 结佣域架构守护（审批集成改造 T-02）。
 * <p>
 * 设计依据：《审批集成设计说明 V1.0》§五铁律——业务域经 ApprovalPort 间接依赖工作流，
 * 禁止直连 org.dromara.workflow（换引擎时业务代码零改动的前提）。
 * <ul>
 *   <li>规则1：commission 域禁止依赖工作流框架包路径 org.dromara.workflow..</li>
 *   <li>规则2：commission 域禁止依赖工作流 API 包 org.dromara.workflow.api..（审批回调经 ApprovalEvent 中立事件）</li>
 * </ul>
 */
@Tag("dev")
class CommissionArchitectureTest {

    private final JavaClasses classes =
        new ClassFileImporter().importPackages("com.panjia.commission");

    /**
     * 规则1：commission 域禁止依赖工作流框架（设计文档 §五铁律 / S16-9）。
     */
    @Test
    void commission_must_not_depend_on_workflow_framework() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.panjia.commission..")
            .should().dependOnClassesThat().resideInAPackage("org.dromara.workflow..")
            .because("业务域经 ApprovalPort 间接依赖，禁止直连 Warm-Flow（设计文档 §五铁律）");
        assertDoesNotThrow(() -> rule.check(classes));
    }

    /**
     * 规则2：commission 域禁止依赖工作流 API 包（审批回调经 ApprovalEvent 中立事件转译）。
     */
    @Test
    void commission_must_not_depend_on_workflow_api() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("com.panjia.commission..")
            .should().dependOnClassesThat().resideInAPackage("org.dromara.workflow.api..")
            .because("审批回调经 ApprovalEvent 中立事件，禁止直连 Warm-Flow API（ProcessEvent/ProcessTaskEvent）");
        assertDoesNotThrow(() -> rule.check(classes));
    }
}
