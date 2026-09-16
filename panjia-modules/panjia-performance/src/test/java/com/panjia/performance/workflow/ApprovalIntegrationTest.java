package com.panjia.performance.workflow;

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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 审批集成 S16 用例落地（T-05）。
 * <p>
 * 依据《审批集成设计说明 V1.0》§十 S16：
 * <ul>
 *   <li>S16-1 我的待办审批 → completeTask → 业务状态回写（集成测试，待基座）；</li>
 *   <li>S16-2 业务明细直接审批 → currentTaskId + complete 与待办路径一致（集成测试，待基座）；</li>
 *   <li>S16-3 明细入口仍鉴权 → 非审批人服务端拒绝（静态契约校验：approve 方法体存在超管/非超管分支）。</li>
 * </ul>
 * S16-9 换引擎不影响业务由 T-02 ArchUnit 覆盖（见 {@code PerformanceArchitectureTest}），不重复写。
 */
@Tag("dev")
class ApprovalIntegrationTest {

    private static final Path RECEIVED_APPLY_SERVICE =
        Paths.get("src/main/java/com/panjia/performance/service/impl/ReceivedApplyServiceImpl.java");

    // ==================== S16-3：明细入口服务端鉴权（静态契约校验） ====================

    /**
     * S16-3：业务明细入口仍鉴权。
     * <p>设计铁律：前端隐藏 ≠ 安全，服务端必须按 flow_user 判权。
     * 静态契约校验：{@code approve()} → {@code completeTaskAsLoginUser()} 方法体内必须同时存在
     * <ul>
     *   <li>{@code LoginHelper.isSuperAdmin()} 超管分支</li>
     *   <li>非超管走 {@code approvalPort.complete(...)}（带鉴权，非 ignore）</li>
     *   <li>超管走 {@code approvalPort.completeAsSys(...)}（运维解卡，原生 TaskOpPrepareComponent 同语义）</li>
     * </ul>
     * 任一分支缺失即视为「业务层无脑用 completeAsSys 旁路鉴权」违规。
     */
    @Test
    void S16_3_detailEntryStillAuthorizesNonAssignee() throws IOException {
        assertTrue(Files.exists(RECEIVED_APPLY_SERVICE),
            "未找到 ReceivedApplyServiceImpl 源码，路径配置错误：" + RECEIVED_APPLY_SERVICE);
        String content = Files.readString(RECEIVED_APPLY_SERVICE, StandardCharsets.UTF_8);

        // 必须有 isSuperAdmin 分支判断（保留运维解卡能力，但非超管不得走 ignore）
        assertTrue(content.contains("LoginHelper.isSuperAdmin()"),
            "S16-3 违规：approve 链路缺少 isSuperAdmin() 超管判断，无法区分超管与普通角色鉴权路径");

        // 非超管分支必须调 approvalPort.complete（带 flow_user 鉴权，非 ignore）
        assertTrue(content.contains("approvalPort.complete("),
            "S16-3 违规：approve 链路缺少 approvalPort.complete(...) 调用，非超管无法走引擎原生判权");

        // 超管分支必须调 approvalPort.completeAsSys（ignore=true，运维解卡专用）
        assertTrue(content.contains("approvalPort.completeAsSys("),
            "S16-3 违规：approve 链路缺少 approvalPort.completeAsSys(...) 调用，超管运维解卡路径缺失");

        // 鉴权异常必须透传而非吞掉：原 approve 方法体不得在 completeTaskAsLoginUser 调用外层 try-catch ServiceException
        // （由完整业务状态前置校验 + 异常透传保证：非审批人调用 → 引擎拒绝 → ServiceException 抛回 Controller）
        // 此处仅校验调用模式存在，完整鉴权行为落地依赖 S16-1 集成测试（见下方 Disabled 用例）
    }

    /**
     * S16-3 补强：approve 必须经 completeTaskAsLoginUser 委托走 isSuperAdmin 分支，
     * 不能直接调 {@code approvalPort.completeAsSys} 旁路所有角色的鉴权。
     * <p>反向场景：若 {@code approve()} 直接调 {@code approvalPort.completeAsSys(...)}（不经
     * isSuperAdmin 判断），任何持有接口权限的角色都能批掉他人节点的单据（历史回归原因）。
     * <p>精确提取 approve 方法体：从方法签名后第一个 {@code \{} 到紧邻的 {@code \n    }} 结束，
     * 不包含 {@code completeTaskAsLoginUser} 私有方法体。
     */
    @Test
    void S16_3_detailEntryMustNotBypassAuthForAllRoles() throws IOException {
        String content = Files.readString(RECEIVED_APPLY_SERVICE, StandardCharsets.UTF_8);
        // 双入口改造后：主方法签名是 approve(Long id, ApprovalAction action, String comment)
        String signature = "public void approve(Long id, ApprovalAction action, String comment)";
        int sigIdx = content.indexOf(signature);
        assertTrue(sigIdx > 0, "未找到 approve(Long id, ApprovalAction action, String comment) 方法");
        // 找方法体起始 { 和结束 }（紧邻的 \n    }）
        int braceStart = content.indexOf('{', sigIdx);
        int braceEnd = content.indexOf("\n    }", braceStart);
        assertTrue(braceEnd > braceStart, "未能定位 approve 方法体边界");
        String approveBody = content.substring(braceStart, braceEnd);

        // approve 方法体必须调 completeTaskAsLoginUser（委托给 isSuperAdmin 判断）
        assertTrue(approveBody.contains("completeTaskAsLoginUser("),
            "S16-3 违规：approve 方法体未调用 completeTaskAsLoginUser，无法保证经 isSuperAdmin 超管判断分流");

        // approve 方法体内不得直接调 approvalPort.completeAsSys 或 approvalPort.complete
        // （必须经 completeTaskAsLoginUser 间接调用，否则无超管判断 = 所有角色都被 ignore 或都走鉴权）
        assertFalse(approveBody.contains("approvalPort.completeAsSys("),
            "S16-3 违规：approve 方法体直接调 completeAsSys，绕过 isSuperAdmin 超管判断，所有角色都被 ignore");
        assertFalse(approveBody.contains("approvalPort.complete("),
            "S16-3 违规：approve 方法体直接调 approvalPort.complete，未经 completeTaskAsLoginUser 分流");
    }

    // ==================== S16-1 / S16-2：集成测试（待基座） ====================

    /**
     * S16-1：我的待办审批。
     * <p>预期：{@code pageWaitingTasks} 取 taskId → {@code completeTask} → 业务状态同步回写。
     * 落地方式：集成测试（发起流程→取待办→办理→断言业务单据状态回写）。
     * <p>项目当前无 Testcontainers / H2 / {@code @SpringBootTest} 集成测试基座，
     * 标注 {@link Disabled} 待基座建立后补齐（见任务书 T-05 §4.1）。
     */
    @Test
    @Disabled("待集成测试基座（Testcontainers / H2 / @SpringBootTest）建立后补齐")
    void S16_1_myPendingTasksThenCompleteThenWriteBack() {
        // 占位：集成测试用例骨架
        // 1. 发起 perf_received 流程（approvalPort.startAndCompleteFirst）
        // 2. pageWaitingTasks 取 taskId
        // 3. approvalPort.complete(taskId)
        // 4. 断言 ReceivedApply.status 回写为 APPROVED
    }

    /**
     * S16-2：业务明细直接审批。
     * <p>预期：{@code currentTaskId} 取 taskId → 调用同一 {@code complete}，结果与留痕一致。
     * 落地方式：集成测试（经 {@link com.panjia.contracts.port.ApprovalPort#currentTaskId}
     * + {@link com.panjia.contracts.port.ApprovalPort#complete}，断言与 S16-1 路径一致）。
     * <p>同 S16-1，标注 {@link Disabled} 待基座建立后补齐。
     */
    @Test
    @Disabled("待集成测试基座（Testcontainers / H2 / @SpringBootTest）建立后补齐")
    void S16_2_detailEntryApproveUsesSameComplete() {
        // 占位：集成测试用例骨架
        // 1. approvalPort.currentTaskId(BizType.REAL_CONFIRM, applyId) 取 taskId
        // 2. approvalPort.complete(BizType.REAL_CONFIRM, applyId, PASS, msg)
        // 3. 断言：taskId 与 S16-1 pageWaitingTasks 返回的一致
        // 4. 断言：业务单据状态回写与 S16-1 路径完全一致
    }
}
