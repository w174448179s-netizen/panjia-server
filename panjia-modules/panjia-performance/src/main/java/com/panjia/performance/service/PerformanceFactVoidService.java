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
import java.util.List;

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
     * 合同级作废：该合同该期间的全部有效（ACTIVE）业绩一次性作废（不区分人员/角色）。
     * <p>
     * 作废后整张合同业绩不参与算薪/结佣，可由总监按合同整体恢复。
     * 封账/算薪批次校验与单条作废一致。
     *
     * @param period     归属期间
     * @param factType   事实口径（新签明细页为 PERF_EXPECT）
     * @param contractNo 合同号（或订单号）
     * @param reason     作废原因
     * @return 作废明细条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int voidByContract(String period, String factType, String contractNo, String reason) {
        if (periodCloseService.isClosed(period)) {
            throw new ServiceException("期间已封账，禁止作废：period=" + period);
        }
        if (payrollBatchQueryPort.hasActiveBatch(period)) {
            throw new ServiceException("该期间存在正在审核的算薪批次，禁止作废：period=" + period);
        }
        List<PerformanceFact> facts = factMapper.selectActiveFactsByContractNo(period, factType, contractNo);
        if (facts.isEmpty()) {
            throw new ServiceException("合同 " + contractNo + " 在 " + period + " 无有效业绩，无需作废");
        }
        for (PerformanceFact fact : facts) {
            fact.setFactStatus(FactStatus.VOIDED);
            fact.setReversedReason(ReversedReason.DIRECTOR_VOID);
            fact.setOperatorId(LoginHelper.getUserId());
            factMapper.updateById(fact);
        }
        log.info("[业绩作废-合同级] contractNo={}, period={}, factType={}, voided={}, operator={}, reason={}",
            contractNo, period, factType, facts.size(), LoginHelper.getUserId(), reason);
        return facts.size();
    }

    /**
     * 合同级恢复：该合同该期间全部已作废（VOIDED）业绩一次性恢复为 ACTIVE，
     * period 统一改为当前月（落入当月算薪），口径与单条恢复一致。
     *
     * @param period     原归属期间（作废时保持不变）
     * @param factType   事实口径
     * @param contractNo 合同号（或订单号）
     * @param reason     恢复原因
     * @return 恢复明细条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int restoreByContract(String period, String factType, String contractNo, String reason) {
        String currentPeriod = LocalDate.now().format(PERIOD_FORMATTER);
        if (periodCloseService.isClosed(currentPeriod)) {
            throw new ServiceException("当前期间已封账，禁止恢复：period=" + currentPeriod);
        }
        if (payrollBatchQueryPort.hasActiveBatch(currentPeriod)) {
            throw new ServiceException("当前期间存在正在审核的算薪批次，禁止恢复：period=" + currentPeriod);
        }
        List<PerformanceFact> facts = factMapper.selectVoidedFactsByContractNo(period, factType, contractNo);
        if (facts.isEmpty()) {
            throw new ServiceException("合同 " + contractNo + " 在 " + period + " 无已作废业绩，无需恢复");
        }
        for (PerformanceFact fact : facts) {
            fact.setFactStatus(FactStatus.ACTIVE);
            fact.setPeriod(currentPeriod);
            fact.setReversedReason(null);
            fact.setOperatorId(LoginHelper.getUserId());
            factMapper.updateById(fact);
        }
        log.info("[业绩恢复-合同级] contractNo={}, 原period={}, 新period={}, factType={}, restored={}, operator={}, reason={}",
            contractNo, period, currentPeriod, factType, facts.size(), LoginHelper.getUserId(), reason);
        return facts.size();
    }
}
