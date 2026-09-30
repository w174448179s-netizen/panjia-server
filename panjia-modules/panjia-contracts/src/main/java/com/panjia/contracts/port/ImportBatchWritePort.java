package com.panjia.contracts.port;

/**
 * 导入批次状态写入端口（import 域实现，performance/commission/received 等消费域调用）。
 * <p>
 * 用途：归档消费阶段（事件消费者）发生异常时，向 import 域回写批次级失败原因，
 * 供前端问题清单展示。
 */
public interface ImportBatchWritePort {

    /**
     * 记录批次级错误（如归一化/归档阶段整批失败，如红冲超额、数据约束冲突）。
     *
     * @param batchId      批次 ID
     * @param errorMessage 错误描述（取异常 message，截断 1000 字符）
     */
    void recordBatchError(Long batchId, String errorMessage);
}
