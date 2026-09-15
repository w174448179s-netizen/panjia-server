package com.panjia.importdomain.port;

import com.panjia.contracts.port.ImportBatchCancelPort;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.mapper.ImportBatchMapper;
import com.panjia.importdomain.service.ImportBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 导入批次撤销端口实现（供业绩域跨域调用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportBatchCancelPortImpl implements ImportBatchCancelPort {

    private final ImportBatchService importBatchService;
    private final ImportBatchMapper batchMapper;

    @Override
    public void cancelBatch(Long batchId, Long operatorId) {
        importBatchService.cancel(batchId, operatorId);
    }

    @Override
    public String getPeriod(Long batchId) {
        ImportBatch batch = batchMapper.selectById(batchId);
        return batch != null ? batch.getPeriod() : null;
    }
}
