package com.panjia.performance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReversedReason;
import com.panjia.performance.mapper.PerformanceAdjustMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 业绩冲销服务。
 * <p>
 * 业绩冲销闭环服务，负责各种场景下的事实冲销。
 * 支持以下冲销场景：
 * <ul>
 *   <li><b>替换冲销</b>（{@link #supersede}）：新版本事实替换旧版本，旧事实 expire</li>
 *   <li><b>重归一化冲销</b>（{@link #reverseByReNormalize}）：批次重新导入时冲销旧事实（reason=RENORMALIZE，CR-5）</li>
 *   <li><b>替换冲销-批次维度</b>（{@link #reverseBySupersede}）：同维度 supersede 时批量冲销旧批次全部事实（reason=SUPERSEDE，CR-1）</li>
 *   <li><b>调整单冲销</b>（{@link #reverseByAdjust}）：调整单执行时冲销原事实</li>
 *   <li><b>期间作废冲销</b>（{@link #reverseByPeriodVoid}）：期间作废时批量冲销</li>
 * </ul>
 * <p>
 * 冲销规则：
 * <ul>
 *   <li>仅 ACTIVE 状态的事实可被冲销</li>
 *   <li>冲销后状态变为 REVERSED（终态，不可再流转）</li>
 *   <li>冲销时记录冲销原因和操作人</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReverseService {

    private final PerformanceFactMapper factMapper;
    private final PerformanceAdjustMapper adjustMapper;

    /**
     * 替换冲销。
     * <p>
     * 用新事实替换旧事实：
     * <ul>
     *   <li>旧事实状态改为 REVERSED，reason = SUPERSEDE</li>
     *   <li>旧事实 expireDate = 新事实 effectiveDate 的前一天</li>
     *   <li>保存旧事实 + 插入新事实</li>
     * </ul>
     * 典型场景：同一笔业务的归一化记录更新后，生成新版本事实替换旧版本。
     *
     * @param factId     旧事实 ID
     * @param newFact    新事实（已构建好，尚未插入）
     * @param operatorId 操作人 ID
     * @return 新事实（已持久化）
     * @throws IllegalStateException 若旧事实不存在或不可冲销
     */
    @Transactional(rollbackFor = Exception.class)
    public PerformanceFact supersede(Long factId, PerformanceFact newFact, Long operatorId) {
        // 1. 查询旧事实
        PerformanceFact oldFact = factMapper.selectById(factId);
        if (oldFact == null) {
            throw new IllegalStateException("业绩事实不存在：factId=" + factId);
        }

        // 2. 检查并冲销旧事实
        checkAndReverse(oldFact, ReversedReason.SUPERSEDE, operatorId);

        // 3. 设置旧事实的 expireDate = 新事实 effectiveDate 的前一天
        if (newFact.getEffectiveDate() != null) {
            LocalDate expireDate = newFact.getEffectiveDate().minusDays(1);
            oldFact.setExpireDate(expireDate);
        }
        factMapper.updateById(oldFact);

        // 4. 插入新事实
        newFact.setOperatorId(operatorId);
        factMapper.insert(newFact);

        log.info("[冲销-替换] 事实替换完成：oldFactId={}, newFactId={}, reason={}",
                factId, newFact.getId(), ReversedReason.SUPERSEDE.getCode());

        return newFact;
    }

    /**
     * 重归一化冲销（CR-5）。
     * <p>
     * 将指定批次的所有 ACTIVE 事实全部冲销，{@code reversed_reason = RENORMALIZE}。
     * 典型场景：批次重新导入前，先冲销该批次之前生成的所有业绩事实再重建。
     * <p>
     * 与 {@link #reverseBySupersede} 复用同一闭循环（仅 reason 不同，审计可区分）。
     *
     * @param batchId    批次 ID
     * @param operatorId 操作人 ID
     * @return 冲销的事实条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int reverseByReNormalize(Long batchId, Long operatorId) {
        return reverseByReason(batchId, ReversedReason.RENORMALIZE, operatorId);
    }

    /**
     * 替换冲销（CR-1）。
     * <p>
     * 将指定批次的所有 ACTIVE 事实全部冲销，{@code reversed_reason = SUPERSEDE}。
     * 典型场景：同维度（sourceType+period+deptId）下，旧批次被新批次废弃后，
     * 业绩域收到 ImportBatchArchivedEvent.supersededBatchIds 列表，依此冲销旧批次全部事实。
     * <p>
     * 与 {@link #reverseByReNormalize} 复用同一闭循环（仅 reason 不同）。
     *
     * @param oldBatchId 被 supersede 的旧批次 ID
     * @param operatorId 操作人 ID
     * @return 冲销的事实条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int reverseBySupersede(Long oldBatchId, Long operatorId) {
        return reverseByReason(oldBatchId, ReversedReason.SUPERSEDE, operatorId);
    }

    /**
     * 通用内部：按指定 reason 冲销批次下所有 ACTIVE 事实。
     *
     * @param batchId    批次 ID
     * @param reason     冲销原因（ReversedReason.RENORMALIZE / SUPERSEDE）
     * @param operatorId 操作人 ID
     * @return 冲销的事实条数
     */
    private int reverseByReason(Long batchId, ReversedReason reason, Long operatorId) {
        LambdaQueryWrapper<PerformanceFact> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PerformanceFact::getBatchId, batchId)
                .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE);
        List<PerformanceFact> facts = factMapper.selectList(queryWrapper);

        if (facts.isEmpty()) {
            log.info("[冲销-{}] 批次无待冲销事实：batchId={}", reason.getCode(), batchId);
            return 0;
        }

        int reversedCount = 0;
        for (PerformanceFact fact : facts) {
            try {
                checkAndReverse(fact, reason, operatorId);
                factMapper.updateById(fact);
                reversedCount++;
            } catch (Exception e) {
                log.warn("[冲销-{}] 单条事实冲销失败：factId={}, batchId={}",
                        reason.getCode(), fact.getId(), batchId, e);
            }
        }

        log.info("[冲销-{}] 批次冲销完成：batchId={}, 冲销数={}",
                reason.getCode(), batchId, reversedCount);
        return reversedCount;
    }

    /**
     * 调整单冲销。
     * <p>
     * 将指定事实冲销，并关联调整单 ID。
     * 典型场景：业绩调整单审批通过后，执行冲销原业绩事实。
     *
     * @param factId     事实 ID
     * @param adjustId   调整单 ID
     * @param reason     冲销原因
     * @param operatorId 操作人 ID
     * @throws IllegalStateException 若事实不存在或不可冲销
     */
    @Transactional(rollbackFor = Exception.class)
    public void reverseByAdjust(Long factId, Long adjustId, ReversedReason reason, Long operatorId) {
        // 1. 查询事实
        PerformanceFact fact = factMapper.selectById(factId);
        if (fact == null) {
            throw new IllegalStateException("业绩事实不存在：factId=" + factId);
        }

        // 2. 检查并冲销
        checkAndReverse(fact, reason, operatorId);

        // 3. 设置关联调整单 ID
        fact.setAdjustId(adjustId);

        factMapper.updateById(fact);

        log.info("[冲销-调整单] 事实冲销完成：factId={}, adjustId={}, reason={}",
                factId, adjustId, reason.getCode());
    }

    /**
     * 期间作废冲销。
     * <p>
     * 将指定期间内所有 ACTIVE 事实全部冲销。
     * 典型场景：期间结账时发现数据异常，执行期间作废后重新计算。
     *
     * @param period     期间（YYYY-MM）
     * @param operatorId 操作人 ID
     * @return 冲销的事实条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int reverseByPeriodVoid(String period, Long operatorId) {
        // 1. 查询该期间下所有 ACTIVE 状态的事实
        LambdaQueryWrapper<PerformanceFact> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PerformanceFact::getPeriod, period)
                .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE);
        List<PerformanceFact> facts = factMapper.selectList(queryWrapper);

        if (facts.isEmpty()) {
            log.info("[冲销-期间作废] 期间无待冲销事实：period={}", period);
            return 0;
        }

        // 2. 逐条冲销
        int reversedCount = 0;
        for (PerformanceFact fact : facts) {
            try {
                checkAndReverse(fact, ReversedReason.PERIOD_VOID, operatorId);
                factMapper.updateById(fact);
                reversedCount++;
            } catch (Exception e) {
                log.warn("[冲销-期间作废] 单条事实冲销失败：factId={}, period={}",
                        fact.getId(), period, e);
            }
        }

        log.info("[冲销-期间作废] 期间冲销完成：period={}, 冲销数={}", period, reversedCount);
        return reversedCount;
    }

    /**
     * 内部方法：检查状态是否可冲销，执行冲销。
     * <p>
     * 仅 ACTIVE 状态的事实可被冲销。
     * 冲销操作：
     * <ul>
     *   <li>设置 factStatus = REVERSED</li>
     *   <li>设置 reversedReason = 指定原因</li>
     *   <li>设置 operatorId = 操作人</li>
     * </ul>
     * <p>
     * 注意：本方法仅修改实体对象状态，不执行数据库更新，
     * 由调用方决定何时调用 mapper 持久化。
     *
     * @param fact       业绩事实
     * @param reason     冲销原因
     * @param operatorId 操作人 ID
     * @throws IllegalStateException 若事实状态不可冲销
     */
    private void checkAndReverse(PerformanceFact fact, ReversedReason reason, Long operatorId) {
        // 状态校验：仅 ACTIVE 可冲销
        if (fact.getFactStatus() != FactStatus.ACTIVE) {
            throw new IllegalStateException(
                    String.format("事实状态不可冲销：factId=%d, currentStatus=%s",
                            fact.getId(), fact.getFactStatus().getCode()));
        }

        // 执行冲销
        fact.setFactStatus(FactStatus.REVERSED);
        fact.setReversedReason(reason);
        fact.setOperatorId(operatorId);
    }
}
