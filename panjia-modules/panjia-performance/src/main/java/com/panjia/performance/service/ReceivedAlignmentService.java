package com.panjia.performance.service;

import com.panjia.contracts.dto.ReceivedAlignmentResultDTO;
import com.panjia.contracts.port.ReceivedRealFactPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实收对齐应收服务（§3.5）。
 * <p>
 * 结佣总监审批发现合同「实收 ≠ 应收」时，由结佣域经
 * {@code CommissionPerformanceQueryPort.alignReceivedToExpected} 触发本服务。
 * PERF_REAL 拆表到实收域（rd + rc）后，对齐的全部读写逻辑下沉到
 * {@link ReceivedRealFactPort}（received 域实现），本服务仅保留跨域委托入口：
 * panjia-performance 不反向依赖 panjia-received，经 ObjectProvider 惰性取端口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceivedAlignmentService {

    private final ObjectProvider<ReceivedRealFactPort> receivedRealFactPortProvider;

    /**
     * 执行实收对齐应收（委托实收域：同合同 ACTIVE rd 按员工工号+角色配对 PERF_EXPECT，
     * 金额/分摊超容差时 supersede 为应收口径）。
     *
     * @param period     归属期间
     * @param contractNo 合同号
     * @param operatorId 操作人
     * @return 对齐结果（新旧明细映射 + 对齐前后合计）
     */
    @Transactional(rollbackFor = Exception.class)
    public ReceivedAlignmentResultDTO align(String period, String contractNo, Long operatorId) {
        ReceivedRealFactPort port = receivedRealFactPortProvider.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException("实收域端口 ReceivedRealFactPort 未装配，无法执行实收对齐应收");
        }
        ReceivedAlignmentResultDTO result = port.alignReceivedToExpected(period, contractNo, operatorId);
        log.info("[实收对齐] 委托实收域完成对齐：period={}, contractNo={}, 对齐明细数={}, before={}, after={}, expect={}",
            period, contractNo,
            result.getMappings() == null ? 0 : result.getMappings().size(),
            result.getReceivedTotalBefore(), result.getReceivedTotalAfter(), result.getExpectedTotal());
        return result;
    }
}
