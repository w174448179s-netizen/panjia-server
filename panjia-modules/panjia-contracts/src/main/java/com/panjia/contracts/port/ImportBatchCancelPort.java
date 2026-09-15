package com.panjia.contracts.port;

/**
 * 导入批次撤销端口（跨域调用：业绩域 → 导入域）。
 * <p>
 * 业绩域撤销导入时，通过此端口将导入批次状态更新为 CANCELLED。
 * 实现位于导入域（panjia-import），不反向依赖。
 */
public interface ImportBatchCancelPort {

    /**
     * 撤销导入批次（ARCHIVED → CANCELLED）。
     *
     * @param batchId    批次 ID
     * @param operatorId 操作人 ID
     */
    void cancelBatch(Long batchId, Long operatorId);

    /**
     * 查询批次的归属期间。
     *
     * @param batchId 批次 ID
     * @return 归属期间（YYYY-MM），批次不存在时返回 null
     */
    String getPeriod(Long batchId);
}
