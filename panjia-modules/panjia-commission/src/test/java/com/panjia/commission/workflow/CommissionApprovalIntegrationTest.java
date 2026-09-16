package com.panjia.commission.workflow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ==================== S16-8：条件跳过财务（T-04 互斥网关落地） ====================

    /**
     * S16-8：条件跳过财务（T-04 落地后，由互斥网关 skip_condition 决定）。
     * <p>设计文档 §三要求：实收 == 应收 → 网关跳过财务；有差异正常走。
     * T-04 改造后：
     * <ul>
     *   <li>流程定义新增 flow_skip {@code capp_director→capp_end, skip_condition=eq@@${realAmount}@@${expectedAmount}}
     *       （见 V150004 迁移脚本）；</li>
     *   <li>{@link CommissionApplicationService#afterDirectorPassed} 仅保留实收对齐逻辑，
     *       <b>不再调 {@code approvalPort.completeAsSys}</b> 旁路完成财务节点——
     *       无差异时网关直接跳到 capp_end，capp_finance 节点不创建，监听器不触发。</li>
     * </ul>
     * 静态契约校验：
     * <ul>
     *   <li>存在 {@code afterDirectorPassed} 公共方法签名（监听器可调用）；</li>
     *   <li>方法体内必须保留实收对齐分支（{@code hasDiff} + {@code alignReceivedToExpected}）；</li>
     *   <li>方法体内<b>不得</b>调 {@code approvalPort.completeAsSys}（旁路已移除，交由网关）。</li>
     * </ul>
     */
    @Test
    void S16_8_afterDirectorPassedNoLongerBypassesFinance() throws IOException {
        String content = read(APPLICATION_SERVICE);
        assertTrue(content.contains("afterDirectorPassed("),
            "S16-8 违规：缺少 afterDirectorPassed 方法，总监通过后无法触发实收对齐");

        // 抽取 afterDirectorPassed 方法体
        int idx = content.indexOf("afterDirectorPassed(");
        int methodStart = content.lastIndexOf("public", idx);
        assertTrue(methodStart > 0, "未找到 afterDirectorPassed 的 public 修饰符");
        int nextPublic = content.indexOf("\n    public ", methodStart + 10);
        String body = nextPublic > 0
            ? content.substring(methodStart, nextPublic)
            : content.substring(methodStart);

        // 必须保留实收对齐分支（hasDiff + alignReceivedToExpected）
        assertTrue(body.contains("hasDiff"),
            "S16-8 违规：afterDirectorPassed 缺少 hasDiff 差异判断分支");
        assertTrue(body.contains("alignReceivedToExpected"),
            "S16-8 违规：afterDirectorPassed 缺少 alignReceivedToExpected 调用，实收对齐逻辑丢失");

        // 不得再调 approvalPort.completeAsSys（旁路已移除，交由互斥网关 skip_condition）
        assertFalse(body.contains("approvalPort.completeAsSys("),
            "S16-8 违规：afterDirectorPassed 仍调 approvalPort.completeAsSys，未移交互斥网关路由");
    }

    /**
     * S16-8 补强：approve 在总监办理前更新流程变量（供网关 skip_condition 求值）。
     * <p>T-04 落地：{@link CommissionApplicationService#approve} 在 NODE_DIRECTOR 分支
     * 必须调 {@code approvalPort.setVariable} 更新 realAmount/expectedAmount 为最新值。
     */
    @Test
    void S16_8_approveUpdatesAmountVariablesBeforeDirectorComplete() throws IOException {
        String content = read(APPLICATION_SERVICE);
        // 双入口改造后：主方法签名是 approve(Long applicationId, ApprovalAction action, String comment)
        int approveIdx = content.indexOf("public void approve(Long applicationId, ApprovalAction action, String comment)");
        assertTrue(approveIdx > 0, "未找到 approve(Long applicationId, ApprovalAction action, String comment) 方法");
        // 取 approve 方法体（到下一个 public）
        int nextPublic = content.indexOf("\n    public ", approveIdx + 10);
        String body = nextPublic > 0
            ? content.substring(approveIdx, nextPublic)
            : content.substring(approveIdx);

        // 必须在总监分支前调 updateAmountVariables
        assertTrue(body.contains("NODE_DIRECTOR.equals(node)"),
            "S16-8 违规：approve 缺少 NODE_DIRECTOR 判断分支");
        assertTrue(body.contains("updateAmountVariables("),
            "S16-8 违规：approve 未在总监办理前调 updateAmountVariables 更新流程变量");

        // updateAmountVariables 必须用 approvalPort.setVariable 写入 realAmount/expectedAmount
        int updateIdx = content.indexOf("private void updateAmountVariables(");
        assertTrue(updateIdx > 0, "未找到 updateAmountVariables 方法");
        int updateNext = content.indexOf("\n    private ", updateIdx + 10);
        int updateEnd = updateNext > 0 ? updateNext : content.indexOf("\n    public ", updateIdx + 10);
        String updateBody = updateEnd > 0
            ? content.substring(updateIdx, updateEnd)
            : content.substring(updateIdx);
        assertTrue(updateBody.contains("approvalPort.setVariable("),
            "S16-8 违规：updateAmountVariables 未调 approvalPort.setVariable");
        assertTrue(updateBody.contains("realAmount"),
            "S16-8 违规：updateAmountVariables 未写入 realAmount 变量");
        assertTrue(updateBody.contains("expectedAmount"),
            "S16-8 违规：updateAmountVariables 未写入 expectedAmount 变量");
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
     * S16-6：总监超时自动通过（落地于 ruoyi-workflow 模块的 WorkflowGlobalListener.create）。
     * <p>T-03 落地：节点创建监听器注册 Spring {@code TaskScheduler} 延时任务，到点系统自动办理。
     * 行为级集成测试（mock 时间推进 + 断言 message 含「超时自动审批」）依赖 Mockito + 基座，
     * 静态契约校验见 {@code org.dromara.workflow.listener.WorkflowGlobalListenerTest}
     * （ruoyi-workflow 模块）。
     */
    @Test
    @Disabled("行为级测试待 Mockito + 集成测试基座；静态契约校验已落地于 WorkflowGlobalListenerTest")
    void S16_6_directorTimeoutAutoApprove() {
        // 占位：行为级测试骨架
        // 1. mock ConfigService.getConfigInt 返回 24（hours）
        // 2. mock TaskScheduler.schedule 捕获 Runnable
        // 3. 触发 Runnable（模拟到点）
        // 4. 断言 message 含「超时自动审批 24.0 小时，系统自动通过」
    }

    /**
     * S16-7：超时用创建监听器（T-03 已落地）。
     * <p>静态契约校验见 {@code org.dromara.workflow.listener.WorkflowGlobalListenerTest}：
     * <ul>
     *   <li>S16_7_executeAutoApprovalDispatchesBySkipType：断言 PASS/REJECT 分流；</li>
     *   <li>S16_6_directorTimeoutJobStillExistsAsFallback：断言 DirectorTimeoutJob 兜底仍存在。</li>
     * </ul>
     */
    @Test
    @Disabled("行为级测试待 Mockito + 集成测试基座；静态契约校验已落地于 WorkflowGlobalListenerTest")
    void S16_7_timeoutUsesCreationListener() {
        // 占位：T-03 已落地，行为级测试待基座补齐
    }
}
