package com.panjia.contracts.port;

import com.panjia.contracts.dto.NormalizedRecordDTO;
import org.dromara.common.core.domain.PageResult;

/**
 * 只读 import 域归一化记录的端口接口（跨域契约，panjia-contracts 叶子模块）。
 * <p>
 * 业绩域通过此端口读取 import 域归一化后的业务记录，用于生成业绩事实。
 * 实现由 import 域（panjia-import）提供，依赖方向：performance → contracts ← import。
 * <p>
 * 业务约束：仅 {@code status='ARCHIVED' AND superseded_by_batch_id IS NULL} 的批次归一化记录可被消费；
 * 废弃批次（SUPERSEDED）的归一化记录对下游不可见（业绩事实已被冲销）。
 * <p>
 * 分页参数说明：本端口位于 panjia-contracts 叶子模块，刻意不依赖 {@code ruoyi-common-mybatis}（PageQuery 来源），
 * 仅用基础 {@code int} 表达分页约束，保持 contracts 层的"零基础设施"特性。
 * 端口调用方（panjia-import / panjia-performance）应自行转换 PageQuery ↔ (pageNum, pageSize)。
 */
public interface ImportNormalizedRecordQueryPort {

    /**
     * 默认页大小：用于调用方未显式指定时。
     */
    int DEFAULT_PAGE_SIZE = 500;

    /**
     * 默认页号（1-based）：用于调用方传入非法值时兜底。
     */
    int DEFAULT_PAGE_NUM = 1;

    /**
     * 按批次分页查询归一化记录（仅活跃批次：{@code status='ARCHIVED' AND superseded_by_batch_id IS NULL}）。
     *
     * @param batchId  批次ID（必填；null 时返回空结果）
     * @param pageNum  页号（1-based，≤0 时按 {@link #DEFAULT_PAGE_NUM} 兜底）
     * @param pageSize 页大小（≤0 时按 {@link #DEFAULT_PAGE_SIZE} 兜底）
     * @return 归一化记录分页结果（rows 按 id 升序）
     */
    PageResult<NormalizedRecordDTO> listByBatchId(Long batchId, int pageNum, int pageSize);

    /**
     * 按批次统计活跃批次的归一化记录数。
     *
     * @param batchId 批次ID
     * @return 记录总数
     */
    long countByBatchId(Long batchId);
}
