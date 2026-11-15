package com.panjia.performance;

import com.panjia.performance.domain.AdjustType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.bo.ManualOffsetItem;
import com.panjia.performance.domain.bo.PerformanceAdjustCreateBo;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.service.impl.PerformanceAdjustServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 合同级业绩调整单代表事实回写边界测试（2026-11-09）。
 * <p>
 * {@code pj_perf_adjust.fact_id} 单列无法表达合同级调整的多条事实，因此只回写一条「代表事实」：
 * <ul>
 *   <li>业绩冲正（MANUAL_OFFSET）：取明细中最小的 factId（纯新增无 factId → null）；</li>
 *   <li>合同级调整（AMOUNT/DEDUCT/INCREASE）：按原归属月优先装载 ACTIVE 事实，取首条；</li>
 *   <li>合同号与订单号均为空 → 不关联。</li>
 * </ul>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdjustFactIdResolverTest {

    @Mock
    private PerformanceFactMapper factMapper;

    @InjectMocks
    private PerformanceAdjustServiceImpl service;

    private Method resolveMethod;

    @BeforeEach
    void setUp() throws Exception {
        resolveMethod = PerformanceAdjustServiceImpl.class.getDeclaredMethod(
            "resolveRepresentativeFactId", PerformanceAdjustCreateBo.class, AdjustType.class);
        resolveMethod.setAccessible(true);
    }

    @Test
    void manualOffset_returnsMinFactId() throws Exception {
        PerformanceAdjustCreateBo bo = new PerformanceAdjustCreateBo();
        bo.setOffsetItems(List.of(offsetItem(30L), offsetItem(null), offsetItem(10L), offsetItem(20L)));

        Long result = resolve(bo, AdjustType.MANUAL_OFFSET);

        assertEquals(10L, result.longValue());
        verifyNoInteractions(factMapper);
    }

    @Test
    void contractLevel_returnsFirstActiveFactId() throws Exception {
        when(factMapper.selectActiveFactsByContractNo("2026-08", "PERF_EXPECT", "C1"))
            .thenReturn(List.of(fact(101L), fact(102L)));

        PerformanceAdjustCreateBo bo = contractBo(null, "2026-08", "C1");
        Long result = resolve(bo, AdjustType.AMOUNT);

        assertEquals(101L, result.longValue());
    }

    @Test
    void originalPeriodPreferred() throws Exception {
        when(factMapper.selectActiveFactsByContractNo("2026-07", "PERF_EXPECT", "C1"))
            .thenReturn(List.of(fact(201L)));

        PerformanceAdjustCreateBo bo = contractBo("2026-07", "2026-08", "C1");
        Long result = resolve(bo, AdjustType.AMOUNT);

        assertEquals(201L, result.longValue());
    }

    @Test
    void blankContractAndOrder_returnsNull() throws Exception {
        PerformanceAdjustCreateBo bo = contractBo(null, "2026-08", " ");
        bo.setOrderNo(" ");

        Long result = resolve(bo, AdjustType.AMOUNT);

        assertNull(result);
        verifyNoInteractions(factMapper);
    }

    private Long resolve(PerformanceAdjustCreateBo bo, AdjustType type) throws Exception {
        return (Long) resolveMethod.invoke(service, bo, type);
    }

    private static PerformanceAdjustCreateBo contractBo(String originalPeriod, String period, String contractNo) {
        PerformanceAdjustCreateBo bo = new PerformanceAdjustCreateBo();
        bo.setOriginalPeriod(originalPeriod);
        bo.setPeriod(period);
        bo.setContractNo(contractNo);
        bo.setFactType("PERF_EXPECT");
        return bo;
    }

    private static ManualOffsetItem offsetItem(Long factId) {
        ManualOffsetItem item = new ManualOffsetItem();
        item.setFactId(factId);
        return item;
    }

    private static PerformanceFact fact(Long id) {
        PerformanceFact fact = new PerformanceFact();
        fact.setId(id);
        return fact;
    }
}
