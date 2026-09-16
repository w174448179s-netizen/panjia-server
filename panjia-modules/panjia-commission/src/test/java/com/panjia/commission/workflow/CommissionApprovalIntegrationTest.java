package com.panjia.commission.workflow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结佣审批集成 S16 用例落地（T-05）。
 * <p>
 * 依据《审批集成设计说明 V1.0》§十 S16，结佣场景：
 * <ul>
 *   <li>S16-1 我的待办审批 → completeTask → 业务状态回写（集成测试，待基座）；</li>
 *   <li>S16-2 业务明细直接审批 → currentTaskId + complete 与待办一致（集成测试，待基座）；</li>
 *   <li>S16-3 明细入口仍鉴权 → 非审批人服务端拒绝（集成测试，待基座）；</li>
 *   <li>S16-6 总监超时自动通过 → mock 时间推进（集成测试，待基座）；</li>
 *   <li>S16-8 条件跳过财务 → 当前业务层跳过逻辑静态契约校验
 *       （{@code afterDirectorPassed} 调 {@code approvalPort.completeAsSys} 自动完成财务节点，
 *       互斥网关路径依赖 T-04 未批准）。</li>
 * </ul>
 * S16-9 换引擎不影响业务由 T-02 ArchUnit 覆盖（见 {@code CommissionArchitectureTest}），不重复写。
 */
@Tag("dev")
class CommissionApprovalIntegrationTest {

    private static final Path APPLICATION_SERVICE =
        Paths.get("src/main/java/com/panjia/commission/service/CommissionApplicationService.java");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    // ==================== S16-8：条件跳过财务（当前业务层实现） ====================

    /**
     * S16-8：条件跳过财务（当前业务层实现，互斥网关路径待 T-04）。
     * <p>设计文档 §三要求：实收 == 应收 → 网关跳过财务；有差异正常走。
     * 当前实现：{@link CommissionApplicationService#afterDirectorPassed} 在总监通过后判断
     * 实收/应收差异，若 {@code panjia.flow.skip_finance=true} 或无差异 →
     * 调 {@code approvalPort.completeAsSys} 自动完成财务节点。
     * <p>静态契约校验：
     * <ul>
     *   <li>存在 {@code afterDirectorPassed} 公共方法签名（监听器可调用）；</li>
     *   <li>方法体内必须调 {@code approvalPort.completeAsSys(...)}（系统办理财务节点）；</li>
     *   <li>必须存在差异判断分支（{@code hasDiff} 或 {@code skip_finance} 配置项）。</li>
     * </ul>
     * 互斥网关 + skipCondition 改造依赖 T-04（P3 可选，待产品批准），未批准前以本静态校验护栏。
     */
    @Test
    void S16_8_afterDirectorPassedAutoCompletesFinanceWhenNoDiff() throws IOException {
        String content = read(APPLICATION_SERVICE);
        assertTrue(content.contains("afterDirectorPassed("),
            "S16-8 违规：缺少 afterDirectorPassed 方法，总监通过后无法自动完成财务节点");

        // 抽取 afterDirectorPassed 方法体（从签名到下一个 public/protected/private 方法或类尾）
        int idx = content.indexOf("afterDirectorPassed(");
        int methodStart = content.lastIndexOf("public", idx);
        assertTrue(methodStart > 0, "未找到 afterDirectorPassed 的 public 修饰符");
        // 取方法体到下一个 public 方法或类结束
        int nextPublic = content.indexOf("\n    public ", methodStart + 10);
        String body = nextPublic > 0
            ? content.substring(methodStart, nextPublic)
            : content.substring(methodStart);

        // 必须有差异判断分支
        boolean hasDiffBranch = body.contains("hasDiff") || body.contains("skip_finance");
        assertTrue(hasDiffBranch,
            "S16-8 违规：afterDirectorPassed 缺少差异/配置判断分支，无条件跳过财务逻辑");

        // 必须调用 approvalPort.completeAsSys 系统办理财务节点
        assertTrue(body.contains("approvalPort.completeAsSys("),
            "S16-8 违规：afterDirectorPassed 未调 approvalPort.completeAsSys，无法系统自动完成财务节点");

        // 错误信息应区分「全局跳过」与「无差异自动过」（业务可读）
        boolean hasSkipReason = body.contains("全局跳过财务") || body.contains("无差异");
        assertTrue(hasSkipReason,
            "S16-8 违规：afterDirectorPassed 的 completeAsSys 调用缺少区分性 reason 提示");
    }

    /**
     * S16-8 补强：approve 链路同样有 isSuperAdmin 分支（与实收审批一致）。
     * <p>避免结佣业务层用 completeAsSys 旁路所有角色鉴权。
     */
    @Test
    void S16_8_commissionApproveHasSuperAdminBranch() throws IOException {
        String content = read(APPLICATION_SERVICE);
        assertTrue(content.contains("LoginHelper.isSuperAdmin()"),
            "S16-3/S16-8 违规：结佣 approve 链路缺少 isSuperAdmin() 超管判断，所有角色被 ignore");
        assertTrue(content.contains("approvalPort.complete("),
            "S16-3 违规：结佣 approve 链路缺少 approvalPort.complete(...) 调用，非超管无法走引擎原生判权");
    }

    // ==================== S16-1 / S16-2 / S16-3 / S16-6：集成测试（待基座） ====================

    /**
     * S16-1：结佣场景我的待办审批（集成测试）。
     * <p>项目当前无 Testcontainers / H2 / @SpringBootTest 集成测试基座，
     * 标注 {@link Disabled} 待基座建立后补齐（见任务书 T-05 §4.1）。
     */
    @Test
    @Disabled("待集成测试基座（Testcontainers / H2 / @SpringBootTest）建立后补齐")
    void S16_1_myPendingTasksThenCompleteThenWriteBack() {
        // 占位：集成测试用例骨架
        // 1. 发起 commission_apply 流程（approvalPort.startAndCompleteFirst）
        // 2. pageWaitingTasks 取 taskId（财务或总监节点）
        // 3. approvalPort.complete(taskId)
        // 4. 断言 CommissionApplication.status 回写为 APPROVED
    }

    /**
     * S16-2：结佣业务明细直接审批（集成测试）。
     */
    @Test
    @Disabled("待集成测试基座（Testcontainers / H2 / @SpringBootTest）建立后补齐")
    void S16_2_detailEntryApproveUsesSameComplete() {
        // 占位：集成测试用例骨架
        // 1. approvalPort.currentTaskId(BizType.COMMISSION, applyId) 取 taskId
        // 2. approvalPort.complete(BizType.COMMISSION, applyId, PASS, msg)
        // 3. 断言：taskId 与 S16-1 pageWaitingTasks 返回一致
        // 4. 断言：业务单据状态回写与 S16-1 路径完全一致
    }

    /**
     * S16-3：结佣明细入口服务端鉴权（集成测试）。
     * <p>模拟非审批人调用 {@code approvalPort.complete}，断言抛 ServiceException
     * （行为级验证依赖 Mockito mock 引擎回错，当前缺基座）。
     * 静态契约校验见 {@link #S16_8_commissionApproveHasSuperAdminBranch}。
     */
    @Test
    @Disabled("待 Mockito + 集成测试基座建立后补齐行为级鉴权验证")
    void S16_3_nonAssigneeShouldBeRejected() {
        // 占位：行为级测试骨架
        // 1. mock approvalPort.complete → 抛 ServiceException（模拟引擎 flow_user 判权失败）
        // 2. 调 commissionApplicationService.approve(applyId, "审批通过")
        // 3. 断言抛 ServiceException，错误信息含「无权审批」
    }

    /**
     * S16-6：总监超时自动通过（集成测试）。
     * <p>{@link org.dromara.workflow.job.DirectorTimeoutJob} 每轮扫描总监节点待办，
     * 超时阈值 {@code panjia.flow.director_timeout_hours} 由 sys_config 控制。
     * 行为级验证需 mock {@code ConfigService} + 时间推进 + 断言 message 含「超时自动审批」，
     * 当前缺 Mockito + 集成基座，标注 {@link Disabled}。
     * <p>设计文档要求改为「创建监听器」（T-03，P2 可选，待产品批准），未批准前定时任务路径等价。
     */
    @Test
    @Disabled("待 Mockito + 集成测试基座建立后补齐超时自动审批验证；创建监听器路径依赖 T-03")
    void S16_6_directorTimeoutAutoApprove() {
        // 占位：行为级测试骨架
        // 1. mock configService.getConfigInt 返回 24（小时）
        // 2. mock workflowService.autoCompleteTimeoutTasks 返回 done=1
        // 3. 调 directorTimeoutJob.autoApproveTimeoutDirectorTasks()
        // 4. 断言 message 含「总监审批超时 24 小时，系统自动通过」
    }

    /**
     * S16-7：超时用创建监听器（依赖 T-03）。
     * <p>设计文档要求总监超时改用「流程实例创建监听器」启动定时任务，按 {@code autoApproval_skipType} 执行。
     * T-03（P2 可选）未批准前标注 {@link Disabled}。
     */
    @Test
    @Disabled("待 T-03 总监超时审批改用创建监听器实现")
    void S16_7_timeoutUsesCreationListener() {
        // 占位：T-03 落地后补齐
    }
}
