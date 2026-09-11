package com.panjia.performance.adapter;

import com.panjia.performance.dto.NormalizedRecordDTO;
import com.panjia.performance.port.ImportNormalizedRecordQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;

/**
 * 导入域归一化记录查询适配器。
 * <p>
 * 由于 panjia-performance 不能直接依赖 panjia-import，本适配器当前为空实现，
 * 所有方法均抛出 {@link UnsupportedOperationException}。
 * <p>
 * 后续将通过 Event 事件驱动或跨域调用方式（如 panjia-contracts 端口）正式接入 import 域，
 * 届时在此类中完成实现，业务层代码无需改动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportQueryAdapter implements ImportNormalizedRecordQueryPort {

    @Override
    public PageResult<NormalizedRecordDTO> listByBatchId(Long batchId, PageQuery pageQuery) {
        throw new UnsupportedOperationException("跨域查询暂未实现");
    }

    @Override
    public long countByBatchId(Long batchId) {
        throw new UnsupportedOperationException("跨域查询暂未实现");
    }
}
