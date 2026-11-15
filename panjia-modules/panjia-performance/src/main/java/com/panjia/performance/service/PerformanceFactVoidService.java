package com.panjia.performance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.port.PayrollBatchQueryPort;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReversedReason;
import com.panjia.performance.domain.bo.ContractVoidBo;
import com.panjia.performance.mapper.PerformanceFactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
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
 * 恢复：VOIDED → ACTIVE，可指定目标月（默认当前月），period 与 source_key 同步改写到目标月，
 * 保证目标月后续导入仍按业务键幂等，避免同一业务行跨月重复计业绩。
 * <p>
 * 封账后禁止作废和恢复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceFactVoidService {

    private final PerformanceFactMapper factMapper;
    private final BizKeyMatchGuard bizKeyMatchGuard;
    private final IPeriodCloseService periodCloseService;
    private final PayrollBatchQueryPort payrollBatchQueryPort;

    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 统一装载 ACTIVE 事实：订单号+合同号双键精确优先；双键未命中经 {@link BizKeyMatchGuard}
     * 闸门判定（该合同 ACTIVE 事实订单维度唯一才退化，兼容新签 order_no 误填成合同号的脏数据），
     * 同合同多订单禁止退化（返回空，调用方报「无有效业绩」）；orderNo 为空直接按合同号旧口径。
     */
    private List<PerformanceFact> loadActiveFactsForVoid(String period, String factType,
                                                          String orderNo, String contractNo) {
        if (StringUtils.isNotBlank(orderNo)) {
            List<PerformanceFact> byOrder = factMapper.selectActiveFactsByOrderAndContract(period, factType, orderNo, contractNo);
            if (byOrder != null && !byOrder.isEmpty()) {
                return byOrder;
            }
            if (bizKeyMatchGuard.canDegradeToContract(period, factType,
                List.of(FactStatus.ACTIVE.getCode()), contractNo)) {
                log.warn("[业绩作废] 双键未命中且合同事实订单唯一，退化合同号匹配：orderNo={}, contractNo={}, period={}, factType={}",
                    orderNo, contractNo, period, factType);
                return factMapper.selectActiveFactsByContractNo(period, factType, contractNo);
            }
            log.warn("[业绩作废] 双键未命中且该合同存在多个订单，禁止退化：orderNo={}, contractNo={}, period={}, factType={}",
                orderNo, contractNo, period, factType);
            return List.of();
        }
        return factMapper.selectActiveFactsByContractNo(period, factType, contractNo);
    }

    /**
     * 统一装载 VOIDED 事实：口径同 {@link #loadActiveFactsForVoid}（闸门统计 VOIDED 订单基数，
     * 保证作废后恢复在脏数据场景同样可退化，多订单场景不误恢复另一订单）。
     */
    private List<PerformanceFact> loadVoidedFactsForRestore(String period, String factType,
                                                             String orderNo, String contractNo) {
        if (StringUtils.isNotBlank(orderNo)) {
            List<PerformanceFact> byOrder = factMapper.selectVoidedFactsByOrderAndContract(period, factType, orderNo, contractNo);
            if (byOrder != null && !byOrder.isEmpty()) {
                return byOrder;
            }
            if (bizKeyMatchGuard.canDegradeToContract(period, factType,
                List.of(FactStatus.VOIDED.getCode()), contractNo)) {
                log.warn("[业绩恢复] 双键未命中且合同作废事实订单唯一，退化合同号匹配：orderNo={}, contractNo={}, period={}, factType={}",
                    orderNo, contractNo, period, factType);
                return factMapper.selectVoidedFactsByContractNo(period, factType, contractNo);
            }
            log.warn("[业绩恢复] 双键未命中且该合同存在多个订单的作废事实，禁止退化：orderNo={}, contractNo={}, period={}, factType={}",
                orderNo, contractNo, period, factType);
            return List.of();
        }
        return factMapper.selectVoidedFactsByContractNo(period, factType, contractNo);
    }

    /**
     * 合同级作废：该合同该期间的全部有效（ACTIVE）业绩一次性作废（不区分人员/角色）。
     * <p>
     * 作废后整张合同业绩不参与算薪/结佣，可由总监按合同整体恢复。
     * 封账/算薪批次校验与单条作废一致。
     * 同合同号挂多订单时按订单号+合同号双键精确限定，只作废对应订单的业绩。
     *
     * @param query 作废条件（期间/事实口径/合同号/订单号/原因）
     * @return 作废明细条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int voidByContract(ContractVoidBo query) {
        String period = query.getPeriod();
        String factType = query.getFactType();
        String contractNo = query.getContractNo();
        String orderNo = query.getOrderNo();
        String reason = query.getReason();
        if (periodCloseService.isClosed(period)) {
            throw new ServiceException("期间已封账，禁止作废：period=" + period);
        }
        if (payrollBatchQueryPort.hasActiveBatch(period)) {
            throw new ServiceException("该期间存在正在审核的算薪批次，禁止作废：period=" + period);
        }
        List<PerformanceFact> facts = loadActiveFactsForVoid(period, factType, orderNo, contractNo);
        if (facts.isEmpty()) {
            throw new ServiceException("合同 " + contractNo + " 在 " + period + " 无有效业绩，无需作废");
        }
        for (PerformanceFact fact : facts) {
            fact.setFactStatus(FactStatus.VOIDED);
            fact.setReversedReason(ReversedReason.DIRECTOR_VOID);
            fact.setOperatorId(LoginHelper.getUserId());
            factMapper.updateById(fact);
        }
        log.info("[业绩作废-合同级] contractNo={}, orderNo={}, period={}, factType={}, voided={}, operator={}, reason={}",
            contractNo, orderNo, period, factType, facts.size(), LoginHelper.getUserId(), reason);
        return facts.size();
    }

    /**
     * 合同级恢复：该合同该期间全部已作废（VOIDED）业绩一次性恢复为 ACTIVE。
     * <p>
     * 目标月：优先取 {@link ContractVoidBo#getTargetPeriod()}，为空时默认当前月。
     * 恢复时 period 与 source_key 尾部期间同步改写到目标月，保证目标月后续导入同一业务行
     * 仍能按 source_key 幂等命中，不会重复计业绩。
     * <p>
     * 冲突保护：目标业务键若已存在 ACTIVE 事实（例如作废后已重新导入），或改月前原业务键
     * 已被重新导入为 ACTIVE，均直接拒绝，避免跨月双计或撞唯一索引。
     *
     * @param query 恢复条件（原期间/事实口径/合同号/订单号/目标月/原因）
     * @return 恢复明细条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int restoreByContract(ContractVoidBo query) {
        String period = query.getPeriod();
        String factType = query.getFactType();
        String contractNo = query.getContractNo();
        String orderNo = query.getOrderNo();
        String reason = query.getReason();
        String targetPeriod = StringUtils.isNotBlank(query.getTargetPeriod())
            ? query.getTargetPeriod()
            : LocalDate.now().format(PERIOD_FORMATTER);
        if (periodCloseService.isClosed(targetPeriod)) {
            throw new ServiceException("目标期间已封账，禁止恢复：period=" + targetPeriod);
        }
        if (payrollBatchQueryPort.hasActiveBatch(targetPeriod)) {
            throw new ServiceException("目标期间存在正在审核的算薪批次，禁止恢复：period=" + targetPeriod);
        }
        List<PerformanceFact> facts = loadVoidedFactsForRestore(period, factType, orderNo, contractNo);
        if (facts.isEmpty()) {
            throw new ServiceException("合同 " + contractNo + " 在 " + period + " 无已作废业绩，无需恢复");
        }
        for (PerformanceFact fact : facts) {
            String targetSourceKey = retargetSourceKey(fact.getSourceKey(), fact.getPeriod(), targetPeriod);
            assertRestorable(fact, targetSourceKey, targetPeriod);
            fact.setFactStatus(FactStatus.ACTIVE);
            fact.setPeriod(targetPeriod);
            fact.setSourceKey(targetSourceKey);
            fact.setReversedReason(null);
            fact.setOperatorId(LoginHelper.getUserId());
            factMapper.updateById(fact);
        }
        log.info("[业绩恢复-合同级] contractNo={}, orderNo={}, 原period={}, 新period={}, factType={}, restored={}, operator={}, reason={}",
            contractNo, orderNo, period, targetPeriod, factType, facts.size(), LoginHelper.getUserId(), reason);
        return facts.size();
    }

    /**
     * 将 source_key 尾部的原期间替换为目标期间（形如 {@code KE_SIGNED-业务键-2026-08}）。
     * 无法识别尾部期间（历史/手工键）时保持原样。
     */
    private String retargetSourceKey(String sourceKey, String oldPeriod, String targetPeriod) {
        if (StringUtils.isBlank(sourceKey) || StringUtils.isBlank(oldPeriod)
            || oldPeriod.equals(targetPeriod)) {
            return sourceKey;
        }
        String suffix = "-" + oldPeriod;
        if (sourceKey.endsWith(suffix)) {
            return sourceKey.substring(0, sourceKey.length() - suffix.length()) + "-" + targetPeriod;
        }
        return sourceKey;
    }

    /**
     * 恢复前的冲突保护：
     * <ol>
     *   <li>改月恢复时，原业务键若已有 ACTIVE 事实（作废后重新导入过），恢复会造成跨月双计，拒绝；</li>
     *   <li>目标业务键若已有 ACTIVE 事实，恢复会撞 ACTIVE 部分唯一索引，拒绝。</li>
     * </ol>
     */
    private void assertRestorable(PerformanceFact fact, String targetSourceKey, String targetPeriod) {
        if (!targetSourceKey.equals(fact.getSourceKey())) {
            Long originalActive = countActiveBySourceKey(fact.getFactType(), fact.getSourceKey());
            if (originalActive != null && originalActive > 0) {
                throw new ServiceException("原期间 " + fact.getPeriod()
                    + " 已存在同业务行有效业绩（可能已重新导入），请先作废后再恢复，避免跨月重复计业绩");
            }
        }
        Long targetActive = countActiveBySourceKey(fact.getFactType(), targetSourceKey);
        if (targetActive != null && targetActive > 0) {
            throw new ServiceException("目标期间 " + targetPeriod
                + " 已存在同业务行有效业绩，无法恢复，请先处理目标月数据");
        }
    }

    private Long countActiveBySourceKey(FactType factType, String sourceKey) {
        return factMapper.selectCount(new LambdaQueryWrapper<PerformanceFact>()
            .eq(PerformanceFact::getFactType, factType)
            .eq(PerformanceFact::getSourceKey, sourceKey)
            .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE));
    }
}
