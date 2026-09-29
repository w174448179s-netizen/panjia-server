package com.panjia.contracts.port;

import java.util.List;

/**
 * 导入批次 → 实收表写入端口（panjia-received 域实现）。
 * <p>
 * 拆表后 KE_RECEIVED / HISTORY_PAYROLL 导入批次不再经过 PerformanceEngine
 * 建 PERF_REAL，而是直接写入实收域两张表（pj_received_contract + pj_received_detail）。
 *本端口定义在 contracts 层，ImportBatchArchivedHandler 调用，
 * 实现方在 panjia-received 模块。
 */
public interface ImportToReceivedPort {

    /**
     * 消费导入批次 → 写入实收合同 + 实收明细表。
     * <p>幂等：已存在的 order_no + period + source_type 合同不重复建；
     *          已存在的 source_key 明细不重复插。
     *
     * @param batchId      导入批次 ID
     * @param period       归属期间 YYYY-MM（从归档事件或批次推导）
     * @param sourceType   来源类型（KE_RECEIVED / HISTORY_PAYROLL）
     * @param operatorId   操作人 ID（归档发起人）
     * @return 新建合同数 + 新建明细数（格式："contracts=N, details=M"）
     */
    ImportToReceivedResult consumeBatch(Long batchId, String period, String sourceType, Long operatorId);

    /**
     * 重复导入冲销：将被替换旧批次的 ACTIVE 实收明细标记 SUPERSEDED。
     * <p>必须在 {@link #consumeBatch} 之前调用——否则旧明细仍占用 source_key
     * 唯一锚点（uk_received_detail_anchor），新批次会全部被幂等跳过写入 0 条。
     *
     * @param oldBatchIds 被替换的旧批次 ID 集合（可为空，空直接返回）
     */
    void supersedeBatches(List<Long> oldBatchIds);

    record ImportToReceivedResult(int newContracts, int newDetails) {}
}
