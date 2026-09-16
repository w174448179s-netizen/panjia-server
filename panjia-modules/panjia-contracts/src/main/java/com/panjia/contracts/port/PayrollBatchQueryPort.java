package com.panjia.contracts.port;

/**
 * 算薪批次跨域查询端口。
 * <p>
 * 定义在 panjia-contracts 叶子模块，实现方为 panjia-payroll。
 * 业绩域在作废/恢复事实前通过本端口校验该期间是否正在被算薪批次使用。
 */
public interface PayrollBatchQueryPort {

    /**
     * 指定期间是否存在已计算但未锁定的算薪批次。
     * <p>
     * 状态 >= CALCULATED 且 < LOCKED 的批次视为"正在算薪中"，
     * 此时作废业绩会导致算薪数据不一致。
     *
     * @param period 期间 YYYY-MM
     * @return true 表示存在正在算薪中的批次
     */
    boolean hasActiveBatch(String period);
}
