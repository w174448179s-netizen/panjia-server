package com.panjia.performance;

import com.panjia.contracts.dto.NormalizedRecordDTO;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.port.ImportNormalizedRecordQueryPort;
import com.panjia.contracts.port.ReceivedApplyPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.mapper.PerfRefundMapper;
import com.panjia.performance.mapper.PerformanceConsumeLogMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.port.EmployeeSnapshotQueryPort;
import com.panjia.performance.service.IPeriodCloseService;
import com.panjia.performance.service.PerformanceEngine;
import com.panjia.performance.service.ReverseService;
import org.dromara.system.api.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新签增量导入事实级幂等边界测试（2026-11-09）。
 * <p>
 * 覆盖 {@link PerformanceEngine#buildSingleFact} 的五条边界：
 * <ol>
 *   <li>精确 sourceKey 命中 → 返回既有事实，不 insert；</li>
 *   <li>sourceKey 带归一化层追加的 {@code #行号}，按剥离行号的前缀命中 → 返回既有事实；</li>
 *   <li>新签 0 金额行 → 直接跳过，不查询、不写入；</li>
 *   <li>前缀未命中 → 正常新建并 insert；</li>
 *   <li>负数退单行在同原事实/同退单月/同红冲金额已存在 → 返回既有红冲，不重复冲减。</li>
 * </ol>
 */
@Tag("dev")
class PerformanceEngineDedupTest {

    private static final String SOURCE_TYPE = "KE_SIGNED";
    private static final String PERIOD = "2026-08";

    private PerformanceFactMapper factMapper;
    private PerformanceConsumeLogMapper consumeLogMapper;
    private ImportNormalizedRecordQueryPort importQueryPort;
    private EmployeeSnapshotQueryPort employeeQueryPort;
    private ReverseService reverseService;
    private EventPort eventPort;
    private ConfigService configService;
    private IPeriodCloseService periodCloseService;
    private ReceivedApplyPort receivedApplyPort;
    private PerfRefundMapper perfRefundMapper;
    private JdbcTemplate jdbcTemplate;
    private PerformanceEngine engine;

    @BeforeEach
    void setUp() {
        factMapper = mock(PerformanceFactMapper.class);
        consumeLogMapper = mock(PerformanceConsumeLogMapper.class);
        importQueryPort = mock(ImportNormalizedRecordQueryPort.class);
        employeeQueryPort = mock(EmployeeSnapshotQueryPort.class);
        reverseService = mock(ReverseService.class);
        eventPort = mock(EventPort.class);
        configService = mock(ConfigService.class);
        periodCloseService = mock(IPeriodCloseService.class);
        receivedApplyPort = mock(ReceivedApplyPort.class);
        perfRefundMapper = mock(PerfRefundMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        engine = new PerformanceEngine(factMapper, consumeLogMapper, importQueryPort, employeeQueryPort,
            reverseService, eventPort, configService, periodCloseService, receivedApplyPort,
            perfRefundMapper, jdbcTemplate);
    }

    @Test
    void exactSourceKeyHit_returnsExistingWithoutInsert() {
        PerformanceFact existing = fact(9001L, "K1-2026-08");
        when(factMapper.selectOne(any())).thenReturn(existing);

        NormalizedRecordDTO record = signedRecord("K1", new BigDecimal("100.00"), PERIOD);
        PerformanceFact result = engine.buildSingleFact(record, FactType.PERF_EXPECT, 1L,
            new java.util.ArrayList<>(), new LinkedHashSet<>());

        assertSame(existing, result);
        verify(factMapper, never()).insert(any(PerformanceFact.class));
        verify(factMapper, never()).selectList(any());
    }

    @Test
    void unstableRowNoPrefixHit_returnsExistingWithoutInsert() {
        PerformanceFact existing = fact(9002L, "K2-2026-08");
        when(factMapper.selectOne(any())).thenReturn(null);
        when(factMapper.selectList(any())).thenReturn(List.of(existing));

        // 归一化层为批内冲突行追加 #7，行号跨文件不稳定，应命中剥离 #行号 的前缀
        NormalizedRecordDTO record = signedRecord("K2#7", new BigDecimal("100.00"), PERIOD);
        PerformanceFact result = engine.buildSingleFact(record, FactType.PERF_EXPECT, 1L,
            new java.util.ArrayList<>(), new LinkedHashSet<>());

        assertSame(existing, result);
        verify(factMapper, times(1)).selectList(any());
        verify(factMapper, never()).insert(any(PerformanceFact.class));
    }

    @Test
    void zeroAmountSignedRow_returnsNullWithoutQueryOrInsert() {
        NormalizedRecordDTO record = signedRecord("K3", BigDecimal.ZERO, PERIOD);

        PerformanceFact result = engine.buildSingleFact(record, FactType.PERF_EXPECT, 1L,
            new java.util.ArrayList<>(), new LinkedHashSet<>());

        assertNull(result);
        verify(factMapper, never()).selectList(any());
        verify(factMapper, never()).insert(any(PerformanceFact.class));
    }

    @Test
    void prefixMiss_createsAndInsertsFact() {
        when(factMapper.selectOne(any())).thenReturn(null);
        when(factMapper.selectList(any())).thenReturn(Collections.emptyList());

        NormalizedRecordDTO record = signedRecord("K4#1", new BigDecimal("123.456"), PERIOD);
        record.setBusinessDate(LocalDateTime.of(2026, 8, 15, 10, 30));

        PerformanceFact result = engine.buildSingleFact(record, FactType.PERF_EXPECT, 7L,
            new java.util.ArrayList<>(), new LinkedHashSet<>());

        assertNotNull(result);
        assertEquals(engine.generateSourceKey(record), result.getSourceKey());
        assertEquals(FactStatus.ACTIVE, result.getFactStatus());
        // 123.456 四舍五入到 2 位 = 123.46
        assertEquals(0, new BigDecimal("123.46").compareTo(result.getPerformanceAmount()));
        verify(factMapper, times(1)).insert(result);
    }

    @Test
    void negativeRedinkDuplicate_returnsExistingWithoutInsert() {
        PerformanceFact original = fact(8001L, "K5-2026-07");
        original.setPerformanceAmount(new BigDecimal("100.00"));
        PerformanceFact existingRedink = fact(9005L, "K5-2026-08");
        existingRedink.setPerformanceAmount(new BigDecimal("-100.00"));

        when(perfRefundMapper.countEffectiveByBizKeys(any(), any())).thenReturn(0L);
        when(factMapper.selectOriginalPositiveFact(anyString(), eq(FactType.PERF_EXPECT.getCode()), any()))
            .thenReturn(original);
        when(factMapper.selectOne(any())).thenReturn(null);
        when(factMapper.selectList(any())).thenReturn(List.of(existingRedink));

        NormalizedRecordDTO record = signedRecord("K5#3", new BigDecimal("-100.00"), PERIOD);
        record.setBusinessDate(LocalDateTime.of(2026, 8, 20, 9, 0));

        PerformanceFact result = engine.buildSingleFact(record, FactType.PERF_EXPECT, 1L,
            new java.util.ArrayList<>(), new LinkedHashSet<>());

        assertSame(existingRedink, result);
        verify(factMapper, never()).insert(any(PerformanceFact.class));
    }

    private static NormalizedRecordDTO signedRecord(String sourceKey, BigDecimal amount, String period) {
        NormalizedRecordDTO record = new NormalizedRecordDTO();
        record.setId(1L);
        record.setBatchId(100L);
        record.setSourceType(SOURCE_TYPE);
        record.setRecordType("SIGNED");
        record.setSourceKey(sourceKey);
        record.setPeriod(period);
        record.setEmployeeCode("E001");
        record.setContractNo("C-" + sourceKey);
        record.setOrderNo("O-" + sourceKey);
        record.setReceivableAmount(amount);
        return record;
    }

    private static PerformanceFact fact(Long id, String sourceKey) {
        PerformanceFact fact = new PerformanceFact();
        fact.setId(id);
        fact.setSourceKey(sourceKey);
        fact.setFactType(FactType.PERF_EXPECT);
        fact.setFactStatus(FactStatus.ACTIVE);
        return fact;
    }
}
