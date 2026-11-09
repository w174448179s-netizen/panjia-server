package com.panjia.performance;

import com.panjia.contracts.port.PayrollBatchQueryPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.bo.ContractVoidBo;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.service.BizKeyMatchGuard;
import com.panjia.performance.service.IPeriodCloseService;
import com.panjia.performance.service.PerformanceFactVoidService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 合同级恢复「可指定目标月」边界测试。
 * <p>
 * 覆盖：恢复落到指定月并同步改写 source_key；目标月已有有效业绩拒绝；改月前原业务键已被
 * 重新导入拒绝；未指定目标月时默认当前月。
 */
@Tag("dev")
class PerformanceFactVoidServiceRestoreTest {

    private static final String ORIGINAL_PERIOD = "2026-08";
    private static final String TARGET_PERIOD = "2026-09";
    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private PerformanceFactMapper factMapper;
    private BizKeyMatchGuard bizKeyMatchGuard;
    private IPeriodCloseService periodCloseService;
    private PayrollBatchQueryPort payrollBatchQueryPort;
    private PerformanceFactVoidService service;

    @BeforeEach
    void setUp() {
        factMapper = mock(PerformanceFactMapper.class);
        bizKeyMatchGuard = mock(BizKeyMatchGuard.class);
        periodCloseService = mock(IPeriodCloseService.class);
        payrollBatchQueryPort = mock(PayrollBatchQueryPort.class);
        service = new PerformanceFactVoidService(factMapper, bizKeyMatchGuard, periodCloseService, payrollBatchQueryPort);
        when(periodCloseService.isClosed(anyString())).thenReturn(false);
        when(payrollBatchQueryPort.hasActiveBatch(anyString())).thenReturn(false);
    }

    @Test
    void restoreToTargetPeriod_rewritesPeriodAndSourceKey() {
        PerformanceFact fact = voidedFact();
        when(factMapper.selectVoidedFactsByContractNo(ORIGINAL_PERIOD, "PERF_EXPECT", "C1"))
            .thenReturn(List.of(fact));
        when(factMapper.selectCount(any())).thenReturn(0L, 0L);

        try (MockedStatic<LoginHelper> loginHelper = mockStatic(LoginHelper.class)) {
            loginHelper.when(LoginHelper::getUserId).thenReturn(1L);
            int restored = service.restoreByContract(query(TARGET_PERIOD));
            assertEquals(1, restored);
        }

        assertEquals(FactStatus.ACTIVE, fact.getFactStatus());
        assertEquals(TARGET_PERIOD, fact.getPeriod());
        assertEquals("KE_SIGNED-K1-2026-09", fact.getSourceKey());
        verify(factMapper).updateById(fact);
    }

    @Test
    void targetPeriodAlreadyActive_rejectsWithoutUpdate() {
        PerformanceFact fact = voidedFact();
        when(factMapper.selectVoidedFactsByContractNo(ORIGINAL_PERIOD, "PERF_EXPECT", "C1"))
            .thenReturn(List.of(fact));
        // 原业务键无 ACTIVE；目标业务键已有 ACTIVE（作废后已重新导入到 9 月）
        when(factMapper.selectCount(any())).thenReturn(0L, 1L);

        try (MockedStatic<LoginHelper> loginHelper = mockStatic(LoginHelper.class)) {
            loginHelper.when(LoginHelper::getUserId).thenReturn(1L);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> service.restoreByContract(query(TARGET_PERIOD)));
            assertTrue(ex.getMessage().contains("目标期间"));
        }

        verify(factMapper, never()).updateById(any(PerformanceFact.class));
    }

    @Test
    void originalKeyReimportedBeforeRetarget_rejects() {
        PerformanceFact fact = voidedFact();
        when(factMapper.selectVoidedFactsByContractNo(ORIGINAL_PERIOD, "PERF_EXPECT", "C1"))
            .thenReturn(List.of(fact));
        // 改月恢复前，原业务键已存在 ACTIVE（作废后又在 8 月重新导入），继续恢复会跨月双计
        when(factMapper.selectCount(any())).thenReturn(1L);

        try (MockedStatic<LoginHelper> loginHelper = mockStatic(LoginHelper.class)) {
            loginHelper.when(LoginHelper::getUserId).thenReturn(1L);
            ServiceException ex = assertThrows(ServiceException.class,
                () -> service.restoreByContract(query(TARGET_PERIOD)));
            assertTrue(ex.getMessage().contains("跨月重复计业绩"));
        }

        verify(factMapper, never()).updateById(any(PerformanceFact.class));
    }

    @Test
    void blankTargetPeriod_defaultsToCurrentMonth() {
        PerformanceFact fact = voidedFact();
        when(factMapper.selectVoidedFactsByContractNo(ORIGINAL_PERIOD, "PERF_EXPECT", "C1"))
            .thenReturn(List.of(fact));
        when(factMapper.selectCount(any())).thenReturn(0L, 0L);
        String currentPeriod = LocalDate.now().format(PERIOD_FORMATTER);

        try (MockedStatic<LoginHelper> loginHelper = mockStatic(LoginHelper.class)) {
            loginHelper.when(LoginHelper::getUserId).thenReturn(1L);
            service.restoreByContract(query(null));
        }

        assertEquals(currentPeriod, fact.getPeriod());
        assertTrue(fact.getSourceKey().endsWith("-" + currentPeriod));
    }

    private static ContractVoidBo query(String targetPeriod) {
        ContractVoidBo bo = new ContractVoidBo();
        bo.setPeriod(ORIGINAL_PERIOD);
        bo.setFactType("PERF_EXPECT");
        bo.setContractNo("C1");
        bo.setReason("误操作作废");
        bo.setTargetPeriod(targetPeriod);
        return bo;
    }

    private static PerformanceFact voidedFact() {
        PerformanceFact fact = new PerformanceFact();
        fact.setId(1L);
        fact.setFactType(FactType.PERF_EXPECT);
        fact.setFactStatus(FactStatus.VOIDED);
        fact.setPeriod(ORIGINAL_PERIOD);
        fact.setSourceKey("KE_SIGNED-K1-" + ORIGINAL_PERIOD);
        fact.setContractNo("C1");
        return fact;
    }
}
