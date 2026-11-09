package com.panjia.performance;

import com.panjia.contracts.port.ApprovalPort;
import com.panjia.contracts.port.BatchConsumptionQueryPort;
import com.panjia.contracts.port.CommissionConsumptionQueryPort;
import com.panjia.contracts.port.ReceivedConsumptionQueryPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.panjia.performance.adapter.BatchConsumptionQueryAdapter;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceAdjust;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.performance.service.IPeriodCloseService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次撤销校验边界测试（2026-11-09）。
 * <p>
 * 覆盖 {@link BatchConsumptionQueryAdapter#checkRevocable} 的业绩调整单拦截：
 * <ol>
 *   <li>调整单 fact_id 精确命中 → 立即拒绝，只查一次；</li>
 *   <li>fact_id 未命中（合同级调整只回写代表事实）→ 合同+期间兜底命中，查两次；</li>
 *   <li>仅 REJECTED/CANCELLED 调整单不锁死撤销，继续走到实收端口校验；</li>
 *   <li>无调整单且实收端口缺失 → 保守拒绝，且不执行调整单查询。</li>
 * </ol>
 */
@Tag("dev")
class BatchConsumptionRevokeTest {

    /**
     * 纯单测不启动 MyBatis-Plus 容器，{@code in(PerformanceAdjust::getFactId, ...)}
     * 需要提前注册实体的 lambda 列缓存，否则抛 “can not find lambda cache”。
     */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), PerformanceAdjust.class);
    }

    private static final String PERIOD = "2026-08";
    private static final long BATCH_ID = 100L;

    private IPeriodCloseService periodCloseService;
    private PerformanceFactMapper factMapper;
    private PerformanceAdjustMapper adjustMapper;
    private ReceivedApplyMapper receivedApplyMapper;
    private ApprovalPort approvalPort;
    private ObjectProvider<CommissionConsumptionQueryPort> commissionProvider;
    private ObjectProvider<ReceivedConsumptionQueryPort> receivedProvider;
    private BatchConsumptionQueryAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        periodCloseService = mock(IPeriodCloseService.class);
        factMapper = mock(PerformanceFactMapper.class);
        adjustMapper = mock(PerformanceAdjustMapper.class);
        receivedApplyMapper = mock(ReceivedApplyMapper.class);
        approvalPort = mock(ApprovalPort.class);
        commissionProvider = mock(ObjectProvider.class);
        receivedProvider = mock(ObjectProvider.class);
        adapter = new BatchConsumptionQueryAdapter(periodCloseService, factMapper, adjustMapper,
            receivedApplyMapper, approvalPort, commissionProvider, receivedProvider);
        when(periodCloseService.isClosed(anyString())).thenReturn(false);
    }

    @Test
    void factIdHit_rejectsImmediately() {
        when(factMapper.selectList(any())).thenReturn(List.of(expectFact(11L)));
        when(adjustMapper.selectCount(any())).thenReturn(1L);

        BatchConsumptionQueryPort.RevokeCheckResult result = adapter.checkRevocable(BATCH_ID, PERIOD);

        assertFalse(result.isRevocable());
        assertTrue(result.getReason().contains("业绩调整单（1 条）"));
        verify(adjustMapper, times(1)).selectCount(any());
    }

    @Test
    void contractPeriodFallbackHit_rejects() {
        when(factMapper.selectList(any())).thenReturn(List.of(expectFact(11L)));
        // 第一次按 fact_id 查不到（合同级调整只回写代表事实，本批事实不是代表事实）；
        // 第二次按 合同号+期间+口径 兜底命中
        when(adjustMapper.selectCount(any())).thenReturn(0L, 1L);

        BatchConsumptionQueryPort.RevokeCheckResult result = adapter.checkRevocable(BATCH_ID, PERIOD);

        assertFalse(result.isRevocable());
        assertTrue(result.getReason().contains("业绩调整单（1 条）"));
        verify(adjustMapper, times(2)).selectCount(any());
    }

    @Test
    void onlyRejectedOrCancelled_notLockedButPortMissing() {
        when(factMapper.selectList(any())).thenReturn(List.of(expectFact(11L)));
        // 已拒绝/已取消的调整单未改写事实，不阻断撤销
        when(adjustMapper.selectCount(any())).thenReturn(0L, 0L);
        when(receivedProvider.getIfAvailable()).thenReturn(null);

        BatchConsumptionQueryPort.RevokeCheckResult result = adapter.checkRevocable(BATCH_ID, PERIOD);

        assertFalse(result.isRevocable());
        assertEquals("实收消费状态校验不可用，无法撤销", result.getReason());
        verify(adjustMapper, times(2)).selectCount(any());
    }

    @Test
    void noAdjustAndMissingPort_rejectsWithPortMessage() {
        when(factMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(receivedProvider.getIfAvailable()).thenReturn(null);

        BatchConsumptionQueryPort.RevokeCheckResult result = adapter.checkRevocable(BATCH_ID, PERIOD);

        assertFalse(result.isRevocable());
        assertEquals("实收消费状态校验不可用，无法撤销", result.getReason());
        verify(adjustMapper, never()).selectCount(any());
    }

    private static PerformanceFact expectFact(Long id) {
        PerformanceFact fact = new PerformanceFact();
        fact.setId(id);
        fact.setFactType(FactType.PERF_EXPECT);
        fact.setContractNo("C1");
        fact.setOrderNo("O1");
        fact.setPeriod(PERIOD);
        return fact;
    }
}
