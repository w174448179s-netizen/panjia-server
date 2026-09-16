package com.panjia.payroll.workflow;

import com.panjia.payroll.domain.BatchStatus;
import com.panjia.payroll.domain.PayrollBatch;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 业务单据状态为准 S16-4 用例落地（T-05）。
 * <p>
 * 依据《审批集成设计说明 V1.0》§十 S16-4：
 * 流程已过但业务未回写 → payroll 以业务单据为准并告警。
 * <p>
 * 实现方式：纯单测。PayrollBatch 聚合根的状态机断言（{@code assertCanCalculate}
 * / {@code assertCanSubmit} / {@code assertCanPay} / {@code assertNotLocked}）
 * 直接编码「业务状态为准」语义：流程实例状态（processInstanceId）不入参，
 * 所有可推进的操作只看 {@link BatchStatus}，与 Warm-Flow finish 状态无耦合。
 */
@Tag("dev")
class PayrollStatusPrecedenceTest {

    private static PayrollBatch batch(BatchStatus status) {
        PayrollBatch batch = new PayrollBatch();
        batch.setId(1L);
        batch.setStatus(status);
        // ★ 即使流程实例已 finish，但业务单据 status 未回写，聚合根仍按业务状态拒绝推进
        batch.setProcessInstanceId("99999");
        return batch;
    }

    // ==================== S16-4：业务单据状态为准 ====================

    /**
     * S16-4 主用例：流程 finish 但业务状态仍 REVIEWING（未回写 APPROVED/LOCKED）→ 不能进入发放。
     * <p>模拟场景：Warm-Flow 流程实例状态 = finish（processInstanceId 已回填），
     * 但监听器未回写业务单据（PayrollBatch.status 仍停在 REVIEWING）。
     * 断言：调 {@link PayrollBatch#assertCanPay()} 抛 ServiceException，
     * 业务层不会读流程状态绕过业务状态机。
     */
    @Test
    void S16_4_reviewingBatchCannotPayEvenIfWorkflowFinished() {
        PayrollBatch batch = batch(BatchStatus.REVIEWING);
        // 流程实例 ID 已回填（流程已发起），但业务状态仍 REVIEWING
        assertEquals("99999", batch.getProcessInstanceId());
        // 业务状态为准：REVIEWING != LOCKED，不能发放
        ServiceException ex = assertThrows(ServiceException.class, batch::assertCanPay);
        // 错误信息应包含当前状态描述（业务可读提示）
        String msg = ex.getMessage();
        assertEquals(true, msg.contains("待审核") || msg.contains("REVIEWING"),
            "业务单据状态为准：REVIEWING 态不能发放，错误信息应含当前状态描述：" + msg);
    }

    /**
     * S16-4 反向用例：业务状态回写 LOCKED 后可发放，与流程实例 ID 无关。
     * <p>断言：只要业务状态 = LOCKED，即使 processInstanceId 为空（流程实例信息缺失），
     * 仍可进入发放操作——证明「业务单据状态为准」。
     */
    @Test
    void S16_4_lockedBatchCanPayRegardlessOfProcessInstanceId() {
        PayrollBatch batch = new PayrollBatch();
        batch.setId(2L);
        batch.setStatus(BatchStatus.LOCKED);
        batch.setProcessInstanceId(null); // 流程实例信息缺失
        assertDoesNotThrow(batch::assertCanPay);
    }

    /**
     * S16-4 补强：锁定 / 已发放状态下不可重新算薪，防止流程回退导致重复计算。
     */
    @Test
    void S16_4_lockedOrPaidBatchCannotRecalculate() {
        // LOCKED 态不能重算
        PayrollBatch locked = batch(BatchStatus.LOCKED);
        ServiceException lockedEx = assertThrows(ServiceException.class, locked::assertCanCalculate);
        assertEquals(true, lockedEx.getMessage().contains("已锁定"),
            "LOCKED 态不能重新算薪，错误信息应提示锁定：" + lockedEx.getMessage());

        // PAID 态不能重算
        PayrollBatch paid = batch(BatchStatus.PAID);
        assertThrows(ServiceException.class, paid::assertCanCalculate);
    }

    /**
     * S16-4 补强：提交审核（submit）只接受 CALCULATED 态。
     * <p>模拟场景：DRAFT / REVIEWING / APPROVED 态调用 submit → 业务层必须拒绝，
     * 即使流程实例状态推进也无效。
     */
    @Test
    void S16_4_submitOnlyAcceptsCalculatedState() {
        // 非 CALCULATED 态均不能提交
        for (BatchStatus s : new BatchStatus[]{BatchStatus.DRAFT, BatchStatus.REVIEWING, BatchStatus.APPROVED, BatchStatus.LOCKED}) {
            PayrollBatch batch = batch(s);
            assertThrows(ServiceException.class, batch::assertCanSubmit,
                "状态 " + s + " 不应允许提交审核");
        }
        // 仅 CALCULATED 态可提交
        PayrollBatch calculated = batch(BatchStatus.CALCULATED);
        assertDoesNotThrow(calculated::assertCanSubmit);
    }

    /**
     * S16-4 补强：assertNotLocked 语义。
     * <p>锁定 / 已发放状态下任何修改（明细调整、规则重算）必须被业务层拒绝。
     */
    @Test
    void S16_4_notLockedGuardsModifications() {
        // REVIEWING 态可修改（审批中未锁定）
        PayrollBatch reviewing = batch(BatchStatus.REVIEWING);
        assertDoesNotThrow(reviewing::assertNotLocked);

        // LOCKED 态不可修改
        PayrollBatch locked = batch(BatchStatus.LOCKED);
        assertThrows(ServiceException.class, locked::assertNotLocked);

        // PAID 态不可修改
        PayrollBatch paid = batch(BatchStatus.PAID);
        assertThrows(ServiceException.class, paid::assertNotLocked);
    }
}
