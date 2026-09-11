package com.panjia.performance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.dto.NormalizedRecordDTO;
import com.panjia.contracts.port.ImportNormalizedRecordQueryPort;
import com.panjia.performance.config.PerformanceProperties;
import com.panjia.performance.domain.ConsumeStatus;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceConsumeLog;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.PerformanceSource;
import com.panjia.performance.dto.EmployeeSnapshotDTO;
import com.panjia.performance.engine.ConversionEngine;
import com.panjia.performance.mapper.PerformanceConsumeLogMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.port.EmployeeSnapshotQueryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 业绩消费引擎。
 * <p>
 * 消费编排主引擎，负责从导入批次生成业绩事实。
 * 核心职责：
 * <ul>
 *   <li>从 import 域拉取归一化记录</li>
 *   <li>逐条生成业绩事实（员工归属、金额折算、幂等校验）</li>
 *   <li>记录消费日志，支持幂等与失败重试</li>
 * </ul>
 * <p>
 * 注意：当前 {@code ImportQueryAdapter} 和 {@code PeopleSnapshotAdapter} 为空实现（抛异常），
 * 本类的核心流程骨架已完成，跨域调用待后续联调。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceEngine {

    private final PerformanceFactMapper factMapper;
    private final PerformanceConsumeLogMapper consumeLogMapper;
    private final ConversionEngine conversionEngine;
    private final PerformanceProperties properties;
    private final ImportNormalizedRecordQueryPort importQueryPort;
    private final EmployeeSnapshotQueryPort employeeQueryPort;
    private final ReverseService reverseService;

    /** 期间格式：YYYY-MM */
    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 从导入批次构建业绩事实。
     * <p>
     * 整体流程：
     * <ol>
     *   <li><b>幂等检查</b>：若 batchId + eventType 已存在成功记录，直接返回（防止重复消费）</li>
     *   <li><b>创建消费日志</b>：状态为 RUNNING，记录开始时间</li>
     *   <li><b>冲销旧批次（CR-1，supersede 链路）</b>：supersededBatchIds 非空时，按 SUPERSEDE
     *       reason 冲销每个旧批次下的全部 ACTIVE 事实</li>
     *   <li><b>分页拉取归一化记录</b>：通过 importQueryPort 按批次分页读取</li>
     *   <li><b>逐行生成业绩事实</b>：调用 {@link #buildSingleFact} 构建每条事实</li>
     *   <li><b>统计并更新消费日志</b>：成功数 / 失败数 / 最终状态</li>
     * </ol>
     * <p>
     * 状态流转规则：
     * <ul>
     *   <li>全部成功 → SUCCESS</li>
     *   <li>部分成功 → PARTIAL</li>
     *   <li>全部失败 → FAILED</li>
     * </ul>
     *
     * @param batchId             导入批次 ID
     * @param eventId             事件 ID（幂等锚点）
     * @param eventType           事件类型（IMPORT_BATCH_ARCHIVED / IMPORT_BATCH_RENORMALIZED）
     * @param operatorId          操作人 ID
     * @param supersededBatchIds  被本批 supersede 的旧批次 ID 列表（CR-1，可空）
     * @return 消费日志记录
     */
    @Transactional(rollbackFor = Exception.class)
    public PerformanceConsumeLog buildFromBatch(Long batchId, String eventId, String eventType,
                                                Long operatorId, List<Long> supersededBatchIds) {
        // ========== 1. 幂等检查 ==========
        // 查询是否已有相同 batchId + eventType 的成功/部分成功记录
        LambdaQueryWrapper<PerformanceConsumeLog> idempotentWrapper = new LambdaQueryWrapper<>();
        idempotentWrapper.eq(PerformanceConsumeLog::getBatchId, batchId)
                .eq(PerformanceConsumeLog::getEventType, eventType)
                .in(PerformanceConsumeLog::getStatus, ConsumeStatus.SUCCESS, ConsumeStatus.PARTIAL);
        PerformanceConsumeLog existingLog = consumeLogMapper.selectOne(idempotentWrapper);
        if (existingLog != null) {
            log.info("[业绩消费] 幂等命中，跳过处理：batchId={}, eventType={}, logId={}",
                    batchId, eventType, existingLog.getId());
            return existingLog;
        }

        // ========== 2. 创建消费日志（RUNNING） ==========
        PerformanceConsumeLog consumeLog = new PerformanceConsumeLog();
        consumeLog.setBatchId(batchId);
        consumeLog.setEventId(eventId);
        consumeLog.setEventType(eventType);
        consumeLog.setStatus(ConsumeStatus.RUNNING);
        consumeLog.setOperatorId(operatorId);
        consumeLog.setTotalRows(0);
        consumeLog.setSuccessRows(0);
        consumeLog.setFailedRows(0);
        consumeLogMapper.insert(consumeLog);

        // ========== 2.5 冲销旧批次（CR-1，supersede 链路） ==========
        // supersededBatchIds 非空时，按 SUPERSEDE reason 冲销每个旧批次下全部 ACTIVE 事实，
        // 与本批次的"重建"在同一事务内执行（all-or-nothing）。空列表（首次导入）跳过。
        if (supersededBatchIds != null && !supersededBatchIds.isEmpty()) {
            int totalReversed = 0;
            for (Long oldBatchId : supersededBatchIds) {
                try {
                    int reversed = reverseService.reverseBySupersede(oldBatchId, operatorId);
                    totalReversed += reversed;
                    log.info("[业绩supersede] 冲销旧批次事实：oldBatchId={}, 冲销数={}", oldBatchId, reversed);
                } catch (Exception e) {
                    log.error("[业绩supersede] 旧批次冲销失败：oldBatchId={}", oldBatchId, e);
                    throw e;
                }
            }
            log.info("[业绩supersede] 旧批次冲销汇总：totalBatchIds={}, totalReversedFacts={}",
                supersededBatchIds.size(), totalReversed);
        }

        // ========== 3. 分页拉取归一化记录，逐行生成业绩事实 ==========
        // 注意：importQueryPort 当前为空实现（抛 UnsupportedOperationException），
        // 跨域调用联调后此处将真正执行分页拉取逻辑。
        int successCount = 0;
        int failedCount = 0;
        int totalCount = 0;
        String period = null;

        try {
            // 统计总记录数（用于 totalRows）
            totalCount = (int) importQueryPort.countByBatchId(batchId);
            consumeLog.setTotalRows(totalCount);

            // 分页拉取并处理
            // 端口契约位于 panjia-contracts（叶子模块，不依赖 ruoyi-common-mybatis），
            // 此处用基础 int 参数调用，避免把 PageQuery 牵入跨域契约。
            int pageNum = 1;
            int pageSize = ImportNormalizedRecordQueryPort.DEFAULT_PAGE_SIZE;

            PageResult<NormalizedRecordDTO> pageResult;
            do {
                // 调用跨域端口拉取归一化记录
                pageResult = importQueryPort.listByBatchId(batchId, pageNum, pageSize);

                if (pageResult != null && pageResult.getRows() != null) {
                    for (NormalizedRecordDTO record : pageResult.getRows()) {
                        try {
                            // 从记录中推导期间（取第一条记录的期间作为消费日志的期间）
                            if (period == null && record.getBusinessDate() != null) {
                                period = derivePeriod(record.getBusinessDate());
                            } else if (period == null && record.getPeriod() != null) {
                                period = record.getPeriod();
                            }

                            // 构建单条业绩事实
                            FactType factType = FactType.fromCode(properties.getDefaultFactType());
                            buildSingleFact(record, factType, operatorId);
                            successCount++;
                        } catch (Exception e) {
                            // 单条失败不影响整体，记录失败数
                            log.error("[业绩消费] 单条事实构建失败：recordId={}, batchId={}",
                                    record.getId(), batchId, e);
                            failedCount++;
                        }
                    }
                }
                pageNum++;
            } while (pageResult != null && pageResult.getRows() != null
                    && !pageResult.getRows().isEmpty()
                    && successCount + failedCount < totalCount);

        } catch (UnsupportedOperationException e) {
            // 跨域端口未实现，记录日志并标记为失败
            // 此为开发阶段预期行为，联调后将移除该分支
            log.warn("[业绩消费] 跨域查询端口暂未实现，消费流程骨架已就绪：batchId={}", batchId);
            consumeLog.setMessage("跨域查询端口暂未实现，待联调");
            consumeLog.setStatus(ConsumeStatus.FAILED);
            consumeLogMapper.updateById(consumeLog);
            return consumeLog;

        } catch (Exception e) {
            // 整体异常，标记为失败
            log.error("[业绩消费] 批次消费异常：batchId={}", batchId, e);
            consumeLog.setMessage("消费异常：" + e.getMessage());
            consumeLog.setStatus(ConsumeStatus.FAILED);
            consumeLog.setSuccessRows(successCount);
            consumeLog.setFailedRows(failedCount);
            consumeLogMapper.updateById(consumeLog);
            return consumeLog;
        }

        // ========== 4. 统计并更新消费日志状态 ==========
        consumeLog.setSuccessRows(successCount);
        consumeLog.setFailedRows(failedCount);
        consumeLog.setPeriod(period);

        if (totalCount == 0) {
            consumeLog.setStatus(ConsumeStatus.SUCCESS);
            consumeLog.setMessage("批次无数据");
        } else if (failedCount == 0) {
            consumeLog.setStatus(ConsumeStatus.SUCCESS);
        } else if (successCount == 0) {
            consumeLog.setStatus(ConsumeStatus.FAILED);
            consumeLog.setMessage("全部失败");
        } else {
            consumeLog.setStatus(ConsumeStatus.PARTIAL);
            consumeLog.setMessage("部分成功");
        }

        consumeLogMapper.updateById(consumeLog);

        log.info("[业绩消费] 批次消费完成：batchId={}, total={}, success={}, failed={}, status={}",
                batchId, totalCount, successCount, failedCount, consumeLog.getStatus());

        return consumeLog;
    }

    /**
     * 构建单条业绩事实。
     * <p>
     * 处理流程：
     * <ol>
     *   <li><b>员工归属查询</b>：通过 employeeQueryPort 按工号查员工快照</li>
     *   <li><b>业绩金额计算</b>：调用 conversionEngine.calculate 计算</li>
     *   <li><b>幂等检查</b>：sourceKey + factType + ACTIVE 已存在则跳过</li>
     *   <li><b>保存事实记录</b>：插入新的业绩事实</li>
     * </ol>
     * <p>
     * 注意：employeeQueryPort 当前为空实现，联调后员工查询将真正生效。
     *
     * @param record     归一化记录
     * @param factType   事实口径
     * @param operatorId 操作人 ID
     * @return 构建的业绩事实（若幂等命中则返回已存在的事实）
     */
    public PerformanceFact buildSingleFact(NormalizedRecordDTO record, FactType factType, Long operatorId) {
        // ========== 1. 员工归属查询 ==========
        // 注意：employeeQueryPort 当前为空实现（抛 UnsupportedOperationException），
        // 跨域联调后将真正查询员工信息并填充 employeeId / deptId 等字段。
        EmployeeSnapshotDTO employeeSnapshot = null;
        try {
            employeeSnapshot = employeeQueryPort.getByEmployeeCode(record.getEmployeeCode());
        } catch (UnsupportedOperationException e) {
            // 开发阶段：端口未实现，员工信息暂留空，联调后补充
            log.debug("[业绩构建] 员工快照端口暂未实现，跳过员工归属查询：employeeCode={}",
                    record.getEmployeeCode());
        }

        // ========== 2. 计算业绩金额 ==========
        BigDecimal originAmount = record.getOriginAmount();
        BigDecimal shareRatio = record.getShareRatio();
        // 折算系数：当前暂用默认值，后续可根据 bizType 从配置中获取专属系数
        BigDecimal conversionRate = null;
        BigDecimal performanceAmount = conversionEngine.calculate(originAmount, shareRatio, conversionRate);

        // ========== 3. 幂等检查 ==========
        // 幂等锚点：sourceKey + factType + ACTIVE
        String sourceKey = generateSourceKey(record);
        LambdaQueryWrapper<PerformanceFact> idempotentWrapper = new LambdaQueryWrapper<>();
        idempotentWrapper.eq(PerformanceFact::getSourceKey, sourceKey)
                .eq(PerformanceFact::getFactType, factType)
                .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE);
        PerformanceFact existingFact = factMapper.selectOne(idempotentWrapper);
        if (existingFact != null) {
            log.debug("[业绩构建] 幂等命中，跳过：sourceKey={}, factType={}", sourceKey, factType);
            return existingFact;
        }

        // ========== 4. 构建并保存业绩事实 ==========
        PerformanceFact fact = new PerformanceFact();
        fact.setFactType(factType);
        fact.setPeriod(derivePeriod(record.getBusinessDate()));
        fact.setBusinessDate(record.getBusinessDate());
        fact.setBatchId(record.getBatchId());
        fact.setNormalizedRecordId(record.getId());
        fact.setSourceKey(sourceKey);
        fact.setBizType(record.getBizType());

        // 员工信息（联调后从 employeeSnapshot 填充）
        if (employeeSnapshot != null) {
            fact.setEmployeeId(employeeSnapshot.getEmployeeId());
            fact.setEmployeeExternalCode(employeeSnapshot.getEmployeeCode());
            fact.setDeptId(employeeSnapshot.getDeptId());
        } else {
            fact.setEmployeeExternalCode(record.getEmployeeCode());
        }

        fact.setShareRatio(conversionEngine.getEffectiveShareRatio(shareRatio));
        fact.setOriginAmount(originAmount);
        fact.setConversionRate(conversionEngine.getEffectiveConversionRate(conversionRate));
        fact.setPerformanceAmount(performanceAmount);

        // 生效日期默认为业务发生日
        fact.setEffectiveDate(record.getBusinessDate());
        // expireDate 为空表示长期有效（被冲销时才会设置）

        fact.setFactStatus(FactStatus.ACTIVE);
        fact.setSource(PerformanceSource.IMPORT);
        fact.setOperatorId(operatorId);

        factMapper.insert(fact);

        log.debug("[业绩构建] 业绩事实已创建：factId={}, sourceKey={}, amount={}",
                fact.getId(), sourceKey, performanceAmount);

        return fact;
    }

    /**
     * 从业务日期推导期间（YYYY-MM）。
     *
     * @param businessDate 业务日期
     * @return 期间字符串（格式：YYYY-MM）；businessDate 为 null 时返回 null
     */
    public String derivePeriod(LocalDate businessDate) {
        if (businessDate == null) {
            return null;
        }
        return businessDate.format(PERIOD_FORMATTER);
    }

    /**
     * 生成来源业务单号（幂等锚点）。
     * <p>
     * 格式：{sourceType}-{record.getSourceKey()}
     * <p>
     * 通过前缀 sourceType 区分不同来源系统的业务单号，避免不同来源间单号冲突。
     *
     * @param record 归一化记录
     * @return 来源业务单号
     */
    public String generateSourceKey(NormalizedRecordDTO record) {
        if (record == null) {
            return null;
        }
        String sourceType = record.getSourceType() == null ? "" : record.getSourceType();
        String recordSourceKey = record.getSourceKey() == null ? "" : record.getSourceKey();
        return sourceType + "-" + recordSourceKey;
    }
}
