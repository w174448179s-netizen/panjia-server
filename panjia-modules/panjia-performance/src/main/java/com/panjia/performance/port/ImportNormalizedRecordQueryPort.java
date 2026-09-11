package com.panjia.performance.port;

import com.panjia.performance.dto.NormalizedRecordDTO;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

/**
 * 只读 import 域归一化记录的端口接口。
 * <p>
 * 业绩域通过此端口读取 import 域归一化后的业务记录，用于生成业绩事实。
 * 实现见 adapter 层 {@code ImportQueryAdapter}。
 */
public interface ImportNormalizedRecordQueryPort {

    /**
     * 按批次分页查询归一化记录。
     *
     * @param batchId   批次ID
     * @param pageQuery 分页参数
     * @return 归一化记录分页结果
     */
    PageResult<NormalizedRecordDTO> listByBatchId(Long batchId, PageQuery pageQuery);

    /**
     * 按批次统计记录数。
     *
     * @param batchId 批次ID
     * @return 记录总数
     */
    long countByBatchId(Long batchId);
}
