package com.panjia.performance.service;

import com.panjia.contracts.port.PayrollBatchQueryPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReversedReason;
import com.panjia.performance.mapper.PerformanceFactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 业绩事实作废/恢复服务（总监可逆操作）。
 * <p>
 * 作废：ACTIVE → VOIDED，不参与算薪/结佣（所有下游查询过滤 fact_status = ACTIVE）。
 * 恢复：VOIDED → ACTIVE，period 改为当前月，落入当月算薪。
 * <p>
 * 封账后禁止作废和恢复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceFactVoidService {

    private final PerformanceFactMapper factMapper;
    private final PeriodCloseService periodCloseService;
    private final PayrollBatchQueryPort payrollBatchQueryPort;

    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 作废业绩事实（ACTIVE → VOIDED）。
     *
     * @param factId  事实 ID
     * @param reason  作废原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void voidFact(Long factId, String reason) {
        PerformanceFact fact = factMapper.selectById(factId);
        if (fact == null) {
            throw new ServiceException("业绩事实不存在：id=" + factId);
        }
        if (fact.getFactStatus() != FactStatus.ACTIVE) {
            throw new ServiceException("仅有效状态的业绩可作废，当前状态：" + fact.getFactStatus().getDesc());
        }
        if (periodCloseService.isClosed(fact.getPeriod())) {
            throw new ServiceException("期间已封账，禁止作废：period=" + fact.getPeriod());
        }
        if (payrollBatchQueryPort.hasActiveBatch(fact.getPeriod())) {
            throw new ServiceException("该期间存在正在审核的算薪批次，禁止作废：period=" + fact.getPeriod());
        }
        fact.setFactStatus(FactStatus.VOIDED);
        fact.setReversedReason(ReversedReason.DIRECTOR_VOID);
        fact.setOperatorId(LoginHelper.getUserId());
        factMapper.updateById(fact);
        log.info("[业绩作废] factId={}, period={}, contractNo(sourceKey)={}, operator={}, reason={}",
            factId, fact.getPeriod(), fact.getSourceKey(), LoginHelper.getUserId(), reason);
    }

    /**
     * 恢复业绩事实（VOIDED → ACTIVE），period 改为当前月。
     *
     * @param factId  事实 ID
     * @param reason  恢复原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void restoreFact(Long factId, String reason) {
        PerformanceFact fact = factMapper.selectById(factId);
        if (fact == null) {
            throw new ServiceException("业绩事实不存在：id=" + factId);
        }
        if (fact.getFactStatus() != FactStatus.VOIDED) {
            throw new ServiceException("仅已作废状态的业绩可恢复，当前状态：" + fact.getFactStatus().getDesc());
        }
        String currentPeriod = LocalDate.now().format(PERIOD_FORMATTER);
        if (periodCloseService.isClosed(currentPeriod)) {
            throw new ServiceException("当前期间已封账，禁止恢复：period=" + currentPeriod);
        }
        if (payrollBatchQueryPort.hasActiveBatch(currentPeriod)) {
            throw new ServiceException("当前期间存在正在审核的算薪批次，禁止恢复：period=" + currentPeriod);
        }
        fact.setFactStatus(FactStatus.ACTIVE);
        fact.setPeriod(currentPeriod);
        fact.setReversedReason(null);
        fact.setOperatorId(LoginHelper.getUserId());
        factMapper.updateById(fact);
        log.info("[业绩恢复] factId={}, 原period={}, 新period={}, operator={}, reason={}",
            factId, fact.getPeriod(), currentPeriod, LoginHelper.getUserId(), reason);
    }
}
