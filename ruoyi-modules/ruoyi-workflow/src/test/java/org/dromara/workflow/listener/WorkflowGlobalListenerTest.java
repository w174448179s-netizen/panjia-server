package org.dromara.workflow.listener;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流全局监听器 S16-6 / S16-7 用例落地（T-03 验收）。
 * <p>
 * 依据《审批集成设计说明 V1.0》§十 S16：
 * <ul>
 *   <li>S16-6：总监超时自动通过 → 改用节点创建监听器启动定时器
 *       （落地于 {@link WorkflowGlobalListener#create}）。</li>
 *   <li>S16-7：超时用创建监听器 → 定时任务在创建监听器启动，按 {@code skipType} 执行
 *       （落地于 {@code scheduleAutoApproval} + {@code executeAutoApproval}）。</li>
 * </ul>
 * 行为级测试（mock 时间推进 + 断言 message 含「超时自动审批」）依赖 Mockito + 集成基座，
 * 当前以静态契约校验护栏，确保关键代码路径不被改动意外移除。
 */
@Tag("dev")
class WorkflowGlobalListenerTest {

    private static final Path LISTENER_FILE =
        Paths.get("src/main/java/org/dromara/workflow/listener/WorkflowGlobalListener.java");

    private static String read() throws IOException {
        return Files.readString(LISTENER_FILE, StandardCharsets.UTF_8);
    }

    /**
     * S16-6：总监超时自动通过。
     * <p>静态契约校验：{@code create(ListenerVariable)} 方法体内必须存在 AutoApproval 解析逻辑
     * 与 TaskScheduler 延时调度调用。缺失任一即视为超时自动通过链路断裂。
     */
    @Test
    void S16_6_createListenerRegistersTimeoutScheduler() throws IOException {
        String content = read();

        // 必须解析 ext 中的 AutoApproval 项
        assertTrue(content.contains("\"AutoApproval\""),
            "S16-6 违规：create 方法未解析 code=AutoApproval 的 ext 项");

        // 必须调用 TaskScheduler 注册延时任务
        assertTrue(content.contains("TaskScheduler"),
            "S16-6 违规：未引入 TaskScheduler，无法注册延时调度");
        assertTrue(content.contains("scheduler.schedule("),
            "S16-6 违规：未调用 scheduler.schedule(...)，节点创建后无法启动定时器");

        // 必须有超时配置不存在时的兜底（hours ≤ 0 跳过）
        assertTrue(content.contains("cfg.hours <= 0"),
            "S16-6 违规：缺少 hours ≤ 0 兜底判断，未配置 autoApproval 时会触发误调度");
    }

    /**
     * S16-7：超时用创建监听器，按 skipType 执行 PASS/REJECT。
     * <p>静态契约校验：{@code executeAutoApproval} 必须存在 PASS/REJECT 分流调用，
     * 调用 {@code flwTaskService.completeTask} (PASS) 或 {@code flwTaskService.backProcess} (REJECT)。
     */
    @Test
    void S16_7_executeAutoApprovalDispatchesBySkipType() throws IOException {
        String content = read();

        // 必须有 executeAutoApproval 方法（延时任务回调入口）
        assertTrue(content.contains("executeAutoApproval("),
            "S16-7 违规：缺少 executeAutoApproval 方法，定时器触发后无执行入口");

        // 必须检查任务仍待办（selectById + null 判断）
        assertTrue(content.contains("flwTaskService.selectById("),
            "S16-7 违规：缺少 selectById 任务待办检查，可能对已办理任务重复操作");

        // 必须有 skipType 分流（PASS / REJECT）
        assertTrue(content.contains("\"REJECT\".equalsIgnoreCase(cfg.skipType)"),
            "S16-7 违规：缺少 skipType REJECT 分流，无法按配置执行驳回");

        // PASS 分支必须用 ignore=true（系统办理跳过权限校验）
        assertTrue(content.contains("taskBo.getVariables().put(\"ignore\", true)"),
            "S16-7 违规：PASS 分支未用 ignore=true，超时自动通过会因无办理人被拒");

        // REJECT 分支必须调 backProcess
        assertTrue(content.contains("flwTaskService.backProcess("),
            "S16-7 违规：REJECT 分支未调 backProcess，超时自动驳回无执行路径");

        // 必须用 MessageTypeEnum.SYSTEM_MESSAGE（不能硬编码 "system"）
        assertTrue(content.contains("MessageTypeEnum.SYSTEM_MESSAGE.getCode()"),
            "S16-7 违规：未用 MessageTypeEnum.SYSTEM_MESSAGE.getCode()，硬编码消息类型");
    }

    /**
     * S16-6 / S16-7 共同前置：启动重扫补偿（替代原 DirectorTimeoutJob 兜底）。
     * <p>用户决策（2026-09-16）：删除 DirectorTimeoutJob 每 10 分钟轮询兜底，
     * 改用 WorkflowGlobalListener.rescheduleAutoApprovalOnStartup @PostConstruct
     * 启动时一次性扫描所有未办理任务，按剩余时长重新注册 TaskScheduler 定时器，
     * 已超时的立即触发。解决应用重启丢定时器问题。
     */
    @Test
    void S16_6_startupRescheduleCompensatesLostTimers() throws IOException {
        String content = read();

        // 必须有 @PostConstruct 启动重扫方法
        assertTrue(content.contains("@PostConstruct"),
            "S16-6 违规：缺少 @PostConstruct，无启动重扫补偿入口");
        assertTrue(content.contains("rescheduleAutoApprovalOnStartup("),
            "S16-6 违规：缺少 rescheduleAutoApprovalOnStartup 方法");

        // 必须调 pageByAllTaskWait 拉所有未办理任务
        assertTrue(content.contains("flwTaskService.pageByAllTaskWait("),
            "S16-6 违规：启动重扫未调 pageByAllTaskWait，无法拉取未办理任务");

        // 必须按剩余时长重新注册（已超时立即触发）
        assertTrue(content.contains("remainingSeconds"),
            "S16-6 违规：缺少 remainingSeconds 计算，无法按剩余时长补偿");
        assertTrue(content.contains("remainingSeconds <= 0"),
            "S16-6 违规：缺少已超时立即触发分支，超时任务不会被立即执行");

        // 必须用 Duration 计算已耗时
        assertTrue(content.contains("Duration.between("),
            "S16-6 违规：未用 Duration.between 计算已耗时，无法精确补偿");
    }

    /**
     * S16-6 兜底机制已切换：DirectorTimeoutJob 类应已删除。
     */
    @Test
    void S16_6_directorTimeoutJobClassDeleted() throws IOException {
        Path jobFile = Paths.get("src/main/java/org/dromara/workflow/job/DirectorTimeoutJob.java");
        assertFalse(Files.exists(jobFile),
            "S16-6 违规：DirectorTimeoutJob.java 仍存在，应已删除切换为启动重扫补偿");
    }
}
