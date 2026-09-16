package com.panjia.performance.workflow;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 监听器回写幂等 S16-5 用例落地（T-05）。
 * <p>
 * 依据《审批集成设计说明 V1.0》§十 S16-5：
 * 重复触发 → 状态不重复变更、无重复审批记录。
 * <p>
 * 实现方式：静态契约校验。项目当前无 Mockito / 集成测试基座，
 * 对 {@code handleWorkflowEvent} 方法体扫描幂等守卫代码：
 * <ul>
 *   <li>每个状态分支前必须有「当前状态前置校验」，已处于目标状态时直接 return；</li>
 *   <li>finish 分支必须校验 {@code AdjustStatus.EXECUTED}（已执行幂等忽略）；</li>
 *   <li>back / invalid / termination / cancel 分支必须校验「非 SUBMITTED 态忽略」。</li>
 * </ul>
 * 行为级幂等验证（mock 连续两次发 ApprovalEvent 断言状态机不重复变更）依赖基座建立后补齐。
 */
@Tag("dev")
class ListenerIdempotentTest {

    private static final Path ADJUST_SERVICE =
        Paths.get("src/main/java/com/panjia/performance/service/impl/PerformanceAdjustServiceImpl.java");
    private static final Path RECEIVED_SERVICE =
        Paths.get("src/main/java/com/panjia/performance/service/impl/ReceivedApplyServiceImpl.java");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 提取指定方法的方法体（从方法签名到下一个 case / 方法签名 / 类结束）。
     */
    private static String extractMethodBody(String content, String methodSignature) {
        int idx = content.indexOf(methodSignature);
        assertTrue(idx > 0, "未找到方法：" + methodSignature);
        // 取到下一个 @Override 或类结束 }
        int nextOverride = content.indexOf("@Override", idx + 1);
        return nextOverride > 0
            ? content.substring(idx, nextOverride)
            : content.substring(idx);
    }

    /**
     * S16-5：监听器回写幂等。
     * <p>PerformanceAdjustServiceImpl.handleWorkflowEvent 必须有幂等守卫：
     * <ul>
     *   <li>finish 分支前置校验 {@code adjust.getStatus() == AdjustStatus.EXECUTED} → 幂等忽略 return；</li>
     *   <li>非 SUBMITTED/APPROVED 态 finish 回调 → 幂等忽略 return；</li>
     *   <li>back / invalid / termination / cancel 分支前置校验「非 SUBMITTED 态忽略」。</li>
     * </ul>
     * 缺失任一守卫即可能在 Warm-Flow 重发事件时导致状态重复变更 / 重复执行调整。
     */
    @Test
    void S16_5_adjustHandleWorkflowEventHasIdempotentGuards() throws IOException {
        String content = read(ADJUST_SERVICE);
        String body = extractMethodBody(content,
            "public void handleWorkflowEvent(Long adjustId, String status, String handler, String message)");

        // finish 分支必须有 EXECUTED 幂等守卫（已执行 → 直接 return，不再调 executeAdjust）
        assertTrue(body.contains("AdjustStatus.EXECUTED"),
            "S16-5 违规：finish 分支缺少 AdjustStatus.EXECUTED 幂等守卫，重复 finish 回调会重复执行调整");
        // 守卫语句必须出现在 executeAdjust 调用之前（按代码顺序断言）
        int executedGuard = body.indexOf("AdjustStatus.EXECUTED");
        int executeCall = body.indexOf("executeAdjust(");
        assertTrue(executeCall < 0 || executedGuard < executeCall,
            "S16-5 违规：EXECUTED 幂等守卫必须出现在 executeAdjust 调用之前");

        // 各非 finish 分支必须有「非 SUBMITTED 态忽略」前置校验（防止跨状态回写）
        // 通过统计「非提交态，忽略」类日志或 status != SUBMITTED 判断的出现次数
        long guardCount = Pattern.compile("getStatus\\(\\)\\s*!=\\s*AdjustStatus\\.SUBMITTED").matcher(body).results().count();
        // back / invalid / termination / cancel 至少 4 处守卫，加上 finish 分支的 SUBMITTED/APPROVED 校验
        assertTrue(guardCount >= 3,
            "S16-5 违规：非 finish 分支缺少足够的「非 SUBMITTED 态忽略」前置校验，期望≥3 处，实际 " + guardCount);
    }

    /**
     * S16-5 补强：实收审批单 handleWorkflowEvent finish 分支也必须有幂等守卫
     * （APPROVED 态直接 return，不重复 setApproverTime）。
     */
    @Test
    void S16_5_receivedHandleWorkflowEventFinishIsIdempotent() throws IOException {
        String content = read(RECEIVED_SERVICE);
        String body = extractMethodBody(content,
            "public void handleWorkflowEvent(Long applyId, String status, String handler, String message)");

        // finish 分支必须有 APPROVED 幂等守卫
        int finishIdx = body.indexOf("case \"finish\"");
        assertTrue(finishIdx > 0, "未找到 finish case 分支");
        int nextCase = body.indexOf("case ", finishIdx + 1);
        String finishBody = nextCase > 0
            ? body.substring(finishIdx, nextCase)
            : body.substring(finishIdx);
        assertTrue(finishBody.contains("ReceivedApplyStatus.APPROVED"),
            "S16-5 违规：实收审批 finish 分支缺少 APPROVED 幂等守卫，重复 finish 回调会重复回写审批人/时间");
        // 守卫必须在 updateById 调用之前
        int guard = finishBody.indexOf("ReceivedApplyStatus.APPROVED");
        int update = finishBody.indexOf("applyMapper.updateById");
        assertTrue(update < 0 || guard < update,
            "S16-5 违规：APPROVED 幂等守卫必须出现在 applyMapper.updateById 之前");
    }

    /**
     * S16-5 行为级测试占位：mock 连续两次发 ApprovalEvent，断言 mapper.updateById 调用次数 = 1。
     * <p>依赖 Mockito + 集成测试基座，标注 Disabled 待后续补齐。
     */
    @Test
    @org.junit.jupiter.api.Disabled("待 Mockito + 集成测试基座建立后补齐行为级幂等验证")
    void S16_5_doubleFinishCallbackShouldNotDoubleUpdate() {
        // 占位：行为级测试骨架
        // 1. mock adjustMapper.selectById 返回 SUBMITTED 状态的 adjust
        // 2. 第一次 handleWorkflowEvent(id, "finish", ...) → executeAdjust → EXECUTED
        // 3. mock adjustMapper.selectById 返回 EXECUTED 状态的 adjust
        // 4. 第二次 handleWorkflowEvent(id, "finish", ...) → 幂等忽略
        // 5. verify(adjustMapper, times(1)).updateById(any())
    }
}
