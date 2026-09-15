package com.panjia.performance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.contracts.dto.NormalizedRecordDTO;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.PerformanceFactCreatedEvent;
import com.panjia.contracts.port.ImportNormalizedRecordQueryPort;
import com.panjia.performance.domain.ConsumeStatus;
import com.panjia.performance.domain.FactStatus;
import com.panjia.performance.domain.FactType;
import com.panjia.performance.domain.PerformanceConsumeLog;
import com.panjia.performance.domain.PerformanceFact;
import com.panjia.performance.domain.ReceivedApply;
import com.panjia.performance.domain.ReceivedApplyStatus;
import com.panjia.performance.mapper.ReceivedApplyMapper;
import com.panjia.performance.domain.PerformanceSource;
import com.panjia.performance.domain.ReversalType;
import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.performance.mapper.PerformanceConsumeLogMapper;
import com.panjia.performance.mapper.PerformanceFactMapper;
import com.panjia.performance.port.EmployeeSnapshotQueryPort;
import com.panjia.performance.service.PeriodCloseService;
import com.panjia.performance.util.MoneyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.system.api.ConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    private final ImportNormalizedRecordQueryPort importQueryPort;
    private final EmployeeSnapshotQueryPort employeeQueryPort;
    private final ReverseService reverseService;
    private final EventPort eventPort;
    private final ConfigService configService;
    private final PeriodCloseService periodCloseService;
    private final ReceivedApplyMapper receivedApplyMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 期间格式：YYYY-MM */
    private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 整单退判定容差：贝壳负数行与原事实当前额相差 ≤ 1 分视为全额退，精确镜像避免尾差 */
    private static final BigDecimal FULL_REFUND_TOLERANCE = new BigDecimal("0.01");

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
     * @param sourceType          数据源类型（事件携带，可空；MANUAL_BUILD 无事件上下文传 null）
     * @param period              归属月（事件携带，可空；RUNNING 日志创建时即回填，缺失时消费完成后从记录推导）
     * @return 消费日志记录
     */
    @Transactional(rollbackFor = Exception.class)
    public PerformanceConsumeLog buildFromBatch(Long batchId, String eventId, String eventType,
                                                Long operatorId, List<Long> supersededBatchIds,
                                                String sourceType, String period) {
        // ========== 0. 期间封账校验（§3.5 CLOSED 期间禁止重新消费） ==========
        // period 为空时（MANUAL_BUILD）延迟到消费完成由 derivedPeriod 兜底；此处仅校验显式传入的 period
        if (StringUtils.isNotBlank(period) && periodCloseService.isClosed(period)) {
            throw new IllegalStateException("期间已封账，禁止重新消费批次：period=" + period);
        }

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

        // 重试时清理上次 FAILED 日志：唯一约束 uk_pcl_batch_event 覆盖所有状态，
        // 遗留 FAILED 行会导致 INSERT RUNNING 撞约束，陷入「失败→重试→撞约束→再 FAILED」死循环
        consumeLogMapper.delete(new LambdaQueryWrapper<PerformanceConsumeLog>()
                .eq(PerformanceConsumeLog::getBatchId, batchId)
                .eq(PerformanceConsumeLog::getEventType, eventType)
                .eq(PerformanceConsumeLog::getStatus, ConsumeStatus.FAILED));

        // ========== 2. 创建消费日志（RUNNING） ==========
        // period/sourceType 在创建时即从事件回填：批次归属月是事件上下文，不该等到消费完才推导；
        // 事件缺失时（历史空 period 批次 / MANUAL_BUILD）留空，消费完成后兜底推导（period 列可空）
        PerformanceConsumeLog consumeLog = new PerformanceConsumeLog();
        consumeLog.setBatchId(batchId);
        consumeLog.setEventId(eventId);
        consumeLog.setEventType(eventType);
        consumeLog.setPeriod(period);
        consumeLog.setSourceType(sourceType);
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
        // ★ 事务语义：本方法 @Transactional，PostgreSQL 下任何一条 SQL 失败整个事务即 aborted，
        //   "单条失败跳过继续"是伪命题（后续 SQL 全部 25P02，根因还被吞掉）。
        //   因此这里不做逐条 catch：任何一行失败 → 异常直接传播 → 整批回滚，
        //   handler 在事务外用 markConsumeFailed 补记 FAILED 日志（Outbox 退避重试）。
        int successCount = 0;
        int totalCount = 0;
        String derivedPeriod = null;

        // 统计总记录数（用于 totalRows）
        totalCount = (int) importQueryPort.countByBatchId(batchId);
        consumeLog.setTotalRows(totalCount);

        // 分页拉取并处理
        // 端口契约位于 panjia-contracts（叶子模块，不依赖 ruoyi-common-mybatis），
        // 此处用基础 int 参数调用，避免把 PageQuery 牵入跨域契约。
        int pageNum = 1;
        int pageSize = ImportNormalizedRecordQueryPort.DEFAULT_PAGE_SIZE;

        // 本批新建事实收集器（仅幂等 miss 才新建；用于事务内发布 FactCreated 事件）
        // 一条 SIGNED 行双发 PERF_REAL + PERF_EXPECT 两条事实，收集器内两种 factType 并存，
        // 发事件前再按 factType 分组（结佣域只消费 PERF_REAL 事件，PERF_EXPECT 事件其直接忽略）
        List<PerformanceFact> createdFacts = new ArrayList<>();

        PageResult<NormalizedRecordDTO> pageResult;
        do {
            pageResult = importQueryPort.listByBatchId(batchId, pageNum, pageSize);

            if (pageResult != null && pageResult.getRows() != null) {
                for (NormalizedRecordDTO record : pageResult.getRows()) {
                    if (derivedPeriod == null && record.getBusinessDate() != null) {
                        derivedPeriod = derivePeriod(record.getBusinessDate());
                    } else if (derivedPeriod == null && record.getPeriod() != null) {
                        derivedPeriod = record.getPeriod();
                    }

                    // ★ 双口径分发（V4.2 算薪对齐 / C-12）：
                    //  SIGNED 行（贝壳业绩明细表，唯一业绩来源）→ PERF_REAL(实收) + PERF_EXPECT(应收) 双发
                    //  其余类型 → 不产生业绩事实（handler 层已过滤，此处双保险）
                    List<FactType> factTypes = factTypesForRecord(record);
                    if (factTypes.isEmpty()) {
                        log.debug("[业绩消费] 非业绩类记录跳过：recordId={}, recordType={}",
                            record.getId(), record.getRecordType());
                        continue;
                    }
                    for (FactType factType : factTypes) {
                        buildSingleFact(record, factType, operatorId, createdFacts);
                    }
                    // 计数口径＝归一化行数（一行无论发几条事实都算处理成功 1 行），
                    // 实际新建事实条数以 createdFacts / 库表为准并在完成日志中打印
                    successCount++;
                }
            }
            pageNum++;
        } while (pageResult != null && pageResult.getRows() != null
                && !pageResult.getRows().isEmpty()
                && successCount < totalCount);

        // ========== 4. 统计并更新消费日志状态 ==========
        // 走到这里即整批成功（任何失败都会在上方传播出去）
        consumeLog.setSuccessRows(successCount);
        consumeLog.setFailedRows(0);
        // 事件未携带 period 时用首条记录推导兜底；已有值则保留事件口径（批次归属月）
        if (consumeLog.getPeriod() == null) {
            consumeLog.setPeriod(derivedPeriod);
        }
        consumeLog.setStatus(ConsumeStatus.SUCCESS);
        if (totalCount == 0) {
            consumeLog.setMessage("批次无数据");
        }

        consumeLogMapper.updateById(consumeLog);

        // ========== 5. 发布事实创建事件（结佣域联动；同一事务内 emit，Outbox 原子提交） ==========
        // 双口径事实按 factType 分组各发一个事件：结佣域 handler 仅消费 PERF_REAL，
        // PERF_EXPECT 事件被其显式忽略（新签透传不走结佣明细）
        emitFactCreatedEvents(batchId, consumeLog.getPeriod(), createdFacts);

        log.info("[业绩消费] 批次消费完成：batchId={}, totalRows={}, successRows={}, createdFacts={}, status={}",
                batchId, totalCount, successCount, createdFacts.size(), consumeLog.getStatus());

        return consumeLog;
    }

    /**
     * 发布业绩事实创建事件（结佣域按 (period, deptId) 提示增量重拉；新签透传不依赖本事件）。
     * <p>
     * 只对本批<b>新建</b>的事实发布（幂等命中跳过的事实不算），且必须在业务写事务内调用。
     * <p>
     * 双口径分组：同一批可能同时产生 PERF_REAL / PERF_EXPECT 两类事实，
     * 按 {@code factType} 分组各发一个事件（事件的 factType 字段为单值，禁止混合发布），
     * 结佣域只认 PERF_REAL 事件，PERF_EXPECT 事件由其直接忽略。
     *
     * @param batchId      导入批次 ID
     * @param period       归属期间（事件缺失时由消费日志回填推导）
     * @param createdFacts 本批新建的事实列表（可含两种 factType）
     */
    private void emitFactCreatedEvents(Long batchId, String period, List<PerformanceFact> createdFacts) {
        if (createdFacts == null || createdFacts.isEmpty()) {
            return;
        }
        // 按 factType 分桶（保持插入顺序，REAL 先于 EXPECT）
        java.util.Map<FactType, List<PerformanceFact>> grouped = new java.util.LinkedHashMap<>();
        for (PerformanceFact fact : createdFacts) {
            grouped.computeIfAbsent(fact.getFactType(), k -> new ArrayList<>()).add(fact);
        }
        grouped.forEach((factType, facts) -> emitOneFactCreatedEvent(batchId, period, factType, facts));
    }

    /**
     * 发布单个口径的事实创建事件。
     *
     * @param batchId      导入批次 ID
     * @param period       归属期间
     * @param factType     事实口径（单值：PERF_REAL / PERF_EXPECT）
     * @param createdFacts 本批新建且均为该口径的事实列表
     */
    private void emitOneFactCreatedEvent(Long batchId, String period, FactType factType,
                                         List<PerformanceFact> createdFacts) {
        PerformanceFactCreatedEvent event = new PerformanceFactCreatedEvent();
        event.setBatchId(batchId == null ? 0L : batchId);
        event.setPeriod(period);
        event.setFactType(factType != null ? factType.getCode() : null);

        List<String> factIds = new ArrayList<>(createdFacts.size());
        Set<String> deptIds = new LinkedHashSet<>();
        // 与 factIds 下标对齐的红冲溯源链（非红冲位置填 null）
        List<String> refundOfFactIds = new ArrayList<>(createdFacts.size());
        boolean hasRedink = false;
        for (PerformanceFact fact : createdFacts) {
            factIds.add(String.valueOf(fact.getId()));
            if (fact.getDeptId() != null) {
                deptIds.add(String.valueOf(fact.getDeptId()));
            }
            if (fact.getReversalType() == ReversalType.REDINK_REFUND && fact.getRefundOfFactId() != null) {
                refundOfFactIds.add(String.valueOf(fact.getRefundOfFactId()));
                hasRedink = true;
            } else {
                refundOfFactIds.add(null);
            }
        }
        event.setFactIds(factIds);
        event.setDeptIds(new ArrayList<>(deptIds));
        if (hasRedink) {
            // 仅含红冲事实时才下发标记，下游据此按原事实冻结口径扣回，禁止按当期提点重算
            event.setRefundRedink(true);
            event.setRefundOfFactIds(refundOfFactIds);
        }

        eventPort.emit(event);
        log.info("[业绩消费] 已发布事实创建事件：batchId={}, period={}, factCount={}, factType={}",
                batchId, period, factIds.size(), factType != null ? factType.getCode() : null);
    }

    /**
     * 消费失败落 FAILED 日志（供 handler 在原事务回滚后调用）。
     * <p>
     * buildFromBatch 的 @Transactional 已回滚——RUNNING 行不复存在，此处以新事务
     * 插入一条 FAILED 记录作为失败审计；同一 eventId 的历史 FAILED 行先清除，
     * 只保留最新一条（Outbox 退避重试会多次触发本方法）。
     * <p>
     * REQUIRES_NEW：防止未来调用方（如 OutboxDispatcher）包事务时把本写入拖回滚。
     *
     * @param cause 消费失败根因（message 取链首原因摘要，防超长截断）
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void markConsumeFailed(Long batchId, String eventId, String eventType,
                                  String sourceType, String period, Long operatorId, Throwable cause) {
        consumeLogMapper.delete(new LambdaQueryWrapper<PerformanceConsumeLog>()
            .eq(PerformanceConsumeLog::getEventId, eventId)
            .eq(PerformanceConsumeLog::getStatus, ConsumeStatus.FAILED));

        PerformanceConsumeLog failedLog = new PerformanceConsumeLog();
        failedLog.setBatchId(batchId);
        failedLog.setEventId(eventId);
        failedLog.setEventType(eventType);
        failedLog.setPeriod(period);
        failedLog.setSourceType(sourceType);
        failedLog.setStatus(ConsumeStatus.FAILED);
        failedLog.setOperatorId(operatorId);
        failedLog.setTotalRows(0);
        failedLog.setSuccessRows(0);
        failedLog.setFailedRows(0);
        failedLog.setMessage(rootMessage(cause));
        consumeLogMapper.insert(failedLog);

        log.error("[业绩消费] 已记录消费失败日志：batchId={}, eventId={}, reason={}",
                batchId, eventId, failedLog.getMessage());
    }

    /** 取异常链最深层原因的 message 摘要（防 null / 超长，message 列 VARCHAR(500)） */
    private String rootMessage(Throwable cause) {
        Throwable t = cause;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        String full = "消费失败：" + msg;
        return full.length() > 500 ? full.substring(0, 500) : full;
    }

    /**
     * 构建单条业绩事实。
     * <p>
     * 处理流程：
     * <ol>
     *   <li><b>员工归属查询</b>：通过 employeeQueryPort 按工号查员工快照</li>
     *   <li><b>业绩金额计算</b>：直接使用贝壳导入金额（分摊比例仅展示，不参与计算）</li>
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
        return buildSingleFact(record, factType, operatorId, null);
    }

    /**
     * 构建单条业绩事实（带新建事实收集器）。
     * <p>
     * 与 {@link #buildSingleFact(NormalizedRecordDTO, FactType, Long)} 逻辑一致；
     * 幂等命中（事实已存在）时不写入收集器，仅<b>本批真正新建</b>的事实进入 {@code createdCollector}，
     * 供调用方在事务内发布 FactCreated 事件。
     *
     * @param record           归一化记录
     * @param factType         事实口径
     * @param operatorId       操作人 ID
     * @param createdCollector 新建事实收集器（可空）
     * @return 构建的业绩事实（若幂等命中则返回已存在的事实）
     */
    public PerformanceFact buildSingleFact(NormalizedRecordDTO record, FactType factType, Long operatorId,
                                           List<PerformanceFact> createdCollector) {
        // 幂等锚点与贝壳「当前金额」两条路径共用，提前解析
        String sourceKey = generateSourceKey(record);
        BigDecimal currentAmount = resolveFactCurrentAmount(record, factType);

        // ========== 0. 退单红冲路径（负数行） ==========
        // ★ 金额铁律（§2.8 修订）：退单必须镜像成交月原事实的冻结口径冲回，
        //   禁止用退单当月的员工快照（部门/职级可能已变）与当期折算配置重算。
        //   例：8月 A2/60% 计提 6,000，9月升 A3/65% 后整单退 → 必须冲回 6,000，不是 6,500。
        if (MoneyUtil.isNegative(currentAmount)) {
            PerformanceFact original = factMapper.selectOriginalPositiveFact(
                buildSourceKeyPrefix(record), factType.getCode());
            if (original != null) {
                return buildRedinkFact(record, factType, currentAmount, original,
                    sourceKey, operatorId, createdCollector);
            }
            log.warn("[业绩红冲] 负数行未匹配到成交月原正数事实，按当期口径兜底生成（无法保证快照一致）："
                + "sourceKey={}, factType={}", sourceKey, factType);
        }

        // ========== 0.5 跨月应收「只认一次」增量认定（仅 SIGNED 正数 PERF_EXPECT 行） ==========
        // 贝壳 9 月回款表会把 8 月合同行整行重复带出（「当月应收业绩」仍有值）。
        // 业务铁律：同一合同+角色人+费项的应收只认一次，重复行认 0（仍落 0 元事实留痕），
        // 合同累计应收（总应收业绩）增长时当月行视为增量全额认列。
        if (factType == FactType.PERF_EXPECT
            && RECORD_TYPE_SIGNED.equals(record.getRecordType())
            && currentAmount != null && currentAmount.signum() > 0) {
            currentAmount = recognizeCrossMonthIncrement(record, currentAmount);
        }

        // ========== 1. 员工归属查询 ==========
        // EmployeeSnapshotQueryPort 已由 PeopleSnapshotAdapter 真实实现（contracts 员工主数据端口）；
        // 员工不存在（脏数据/已删）返回 null，下方 else 分支以 employeeCode 兜底
        EmployeeSnapshot employeeSnapshot = employeeQueryPort.getByEmployeeCode(record.getEmployeeCode());

        // ========== 2. 计算业绩金额 ==========
        // ★ 金额口径由 factType 决定：
        //  PERF_REAL   → 当月实收 receivedAmount（结佣计薪业绩）
        //  PERF_EXPECT → 当月应收 receivableAmount（新签业绩，店长/总监团队提成基数）
        // 贝壳导入的金额就是折后金额（performance_amount 口径），直接使用，不再乘以分摊比例；
        // 分摊比例仅作展示用，不参与计算
        BigDecimal performanceAmount = MoneyUtil.round2(currentAmount);

        // ========== 3. 幂等检查 ==========
        // 幂等锚点：sourceKey + factType + ACTIVE
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
        // NOT NULL 防线：businessDate/period 由 adapter 以归属月初保证；缺失即脏数据，fail fast
        LocalDate businessDate = record.getBusinessDate();
        if (businessDate == null || record.getPeriod() == null) {
            throw new IllegalStateException(
                "归一化记录缺少业务日期/归属月，拒绝构建业绩事实：recordId=" + record.getId());
        }
        PerformanceFact fact = new PerformanceFact();
        fact.setFactType(factType);
        // ★ 归属月统一取 record.getPeriod()（导入数据的归属月），与 sourceKey 尾段的 period 同源；
        //   不再由 businessDate 推导——业务日期（成交日）与导入归属月可能跨月错位
        fact.setPeriod(record.getPeriod());
        fact.setBusinessDate(record.getBusinessDate());
        fact.setBatchId(record.getBatchId());
        fact.setNormalizedRecordId(record.getId());
        fact.setSourceKey(sourceKey);
        fact.setBizType(record.getBizType());
        // 所属角色（KE 角色类型，如 客源成交人/VR拍摄人）：归一化记录已携带，构建事实时原样落库
        fact.setRoleType(record.getRoleType());

        // 员工信息（联调后从 employeeSnapshot 填充）
        if (employeeSnapshot != null) {
            fact.setEmployeeId(employeeSnapshot.getEmployeeId());
            fact.setEmployeeExternalCode(employeeSnapshot.getEmployeeCode());
            fact.setDeptId(employeeSnapshot.getDeptId());
        } else {
            fact.setEmployeeExternalCode(record.getEmployeeCode());
        }

        fact.setShareRatio(record.getShareRatio()); // 分摊比例仅展示用，不参与计算
        fact.setPerformanceAmount(performanceAmount);

        // 生效日期默认为业务发生日
        fact.setEffectiveDate(record.getBusinessDate());
        // expireDate 为空表示长期有效（被冲销时才会设置）

        fact.setFactStatus(FactStatus.ACTIVE);
        fact.setSource(PerformanceSource.IMPORT);
        fact.setOperatorId(operatorId);

        factMapper.insert(fact);

        // 仅真正新建的事实进入收集器（供调用方在事务内发布 FactCreated 事件）
        if (createdCollector != null) {
            createdCollector.add(fact);
        }

        log.debug("[业绩构建] 业绩事实已创建：factId={}, sourceKey={}, amount={}",
                fact.getId(), sourceKey, performanceAmount);

        return fact;
    }

    /**
     * 构建退单红冲事实（负数行镜像成交月原事实的冻结口径）。
     * <p>
     * 口径规则：
     * <ul>
     *   <li><b>归属月</b>＝红冲负数行所在结算月（退单月），历史月事实与已发工资不回改；</li>
     *   <li><b>人员归属 / 角色 / 分摊比例 / 折算系数</b>：全部镜像原事实冻结值，不取当期快照、不读当期配置；</li>
     *   <li><b>整单退</b>（负数当前额与原当前额相差 ≤ {@value #FULL_REFUND_TOLERANCE} 元）：
     *       金额精确镜像相反数，杜绝尾差；</li>
     *   <li><b>部分退</b>：红冲比例＝本次负数当前额 ÷ 原当前额（负值），原事实金额按比例红冲；</li>
     *   <li>原事实保持 ACTIVE（红字冲销语义：原行留痕 + 退单月负行），负事实通过
     *       {@code refund_of_fact_id} 建立溯源链。</li>
     * </ul>
     *
     * @param record           退单月负数归一化记录
     * @param factType         事实口径
     * @param negCurrentAmount 本行贝壳当前金额（负数）
     * @param original         成交月原正数事实
     * @param sourceKey        红冲事实幂等锚点（含退单月 period）
     * @param operatorId       操作人 ID
     * @param createdCollector 新建事实收集器（可空）
     * @return 新建的红冲事实（幂等命中时返回已存在事实）
     */
    private PerformanceFact buildRedinkFact(NormalizedRecordDTO record, FactType factType,
                                            BigDecimal negCurrentAmount, PerformanceFact original,
                                            String sourceKey, Long operatorId,
                                            List<PerformanceFact> createdCollector) {
        // 幂等检查（同期间重导时旧负事实已随旧批次 supersede，新负事实可正常插入）
        LambdaQueryWrapper<PerformanceFact> idempotentWrapper = new LambdaQueryWrapper<>();
        idempotentWrapper.eq(PerformanceFact::getSourceKey, sourceKey)
                .eq(PerformanceFact::getFactType, factType)
                .eq(PerformanceFact::getFactStatus, FactStatus.ACTIVE);
        PerformanceFact existingFact = factMapper.selectOne(idempotentWrapper);
        if (existingFact != null) {
            log.debug("[业绩红冲] 幂等命中，跳过：sourceKey={}, factType={}", sourceKey, factType);
            return existingFact;
        }

        LocalDate businessDate = record.getBusinessDate();
        if (businessDate == null || record.getPeriod() == null) {
            throw new IllegalStateException(
                "退单红冲记录缺少业务日期/归属月，拒绝构建事实：recordId=" + record.getId());
        }

        // 原事实贝壳「当前金额」= performance_amount（分摊比例仅展示，不参与计算，无需还原）
        BigDecimal originalCurrent = original.getPerformanceAmount();

        BigDecimal redinkPerformance;
        if (MoneyUtil.isZero(originalCurrent)) {
            // 原事实当前额为 0 的异常数据：直接镜像相反数兜底，避免除零
            log.warn("[业绩红冲] 原正数事实当前额为 0，按金额全额镜像：originalFactId={}", original.getId());
            redinkPerformance = MoneyUtil.round2(original.getPerformanceAmount().negate());
        } else if (negCurrentAmount.abs().subtract(originalCurrent.abs()).abs()
                .compareTo(FULL_REFUND_TOLERANCE) <= 0) {
            // 整单退：精确镜像原事实金额相反数（保证成交月+退单月净额恰好为 0）
            redinkPerformance = MoneyUtil.round2(original.getPerformanceAmount().negate());
        } else {
            // 部分退：红冲比例（负数）= 本次负数当前额 ÷ 原当前额，按比例冲回
            BigDecimal ratio = negCurrentAmount.divide(originalCurrent, 8, RoundingMode.HALF_UP);
            if (ratio.abs().compareTo(BigDecimal.ONE) > 0) {
                // §3.5 红冲超额拦截：退金额 > 原金额时禁止生成超额红冲事实，
                // 否则合同 real_total 可能变负、下游算薪扣回异常。
                // 仅 warn 会让错误数据静默入库，此处抛异常拒绝并提示核对贝壳数据。
                throw new ServiceException(
                    "[业绩红冲] 红冲金额超过原正数事实（超额退单），拒绝生成超额红冲事实：originalFactId="
                        + original.getId() + ", ratio=" + ratio
                        + "，请核对贝壳数据后重新导入");
            }
            redinkPerformance = MoneyUtil.round2(original.getPerformanceAmount().multiply(ratio));
        }

        PerformanceFact fact = new PerformanceFact();
        fact.setFactType(factType);
        // ★ 归属月＝红冲负数行所在导入归属月（退单月），与 sourceKey 尾段同源（方案 A 统一口径）
        fact.setPeriod(record.getPeriod());
        fact.setBusinessDate(businessDate);
        fact.setBatchId(record.getBatchId());
        fact.setNormalizedRecordId(record.getId());
        fact.setSourceKey(sourceKey);
        fact.setBizType(record.getBizType());
        // ★ 人员归属/角色镜像原事实快照（不退单当月重查员工主数据）
        fact.setEmployeeId(original.getEmployeeId());
        fact.setEmployeeExternalCode(original.getEmployeeExternalCode());
        fact.setDeptId(original.getDeptId());
        fact.setRoleType(original.getRoleType());
        // ★ 分摊比例镜像原事实冻结值（仅展示用，不参与计算）
        fact.setShareRatio(original.getShareRatio());
        fact.setPerformanceAmount(redinkPerformance);
        fact.setEffectiveDate(businessDate);
        fact.setFactStatus(FactStatus.ACTIVE);
        fact.setSource(PerformanceSource.IMPORT);
        fact.setReversalType(ReversalType.REDINK_REFUND);
        fact.setRefundOfFactId(original.getId());
        fact.setOperatorId(operatorId);

        factMapper.insert(fact);
        if (createdCollector != null) {
            createdCollector.add(fact);
        }

        log.info("[业绩红冲] 退单红冲事实已生成：factId={}, originalFactId={}, 原期间={}, 红冲期间={}, performanceAmount={}",
            fact.getId(), original.getId(), original.getPeriod(), fact.getPeriod(),
            redinkPerformance);
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
     * 生成业绩事实的幂等锚点（sourceKey）。
     * <p>
     * 由 sourceType + 归一化记录 sourceKey（订单|合同|角色|费项）+ 结算月 period 组成。
     * <p>
     * ★ period 必须纳入：同一订单同一角色在不同结算月各存独立事实（成交月正行 + 退单月红冲负行）。
     * 退单负行不再独立按当期规则计算，而是经 {@link #buildRedinkFact} 按业务键前缀
     * （{@link #buildSourceKeyPrefix}）回溯到成交月原正数事实，镜像其冻结口径生成（§2.8 修订）。
     * 同期间重导由 batch supersede 冲销旧事实后重建，不受 period 纳入影响。
     *
     * @param record 归一化记录
     * @return 来源业务单号
     */
    public String generateSourceKey(NormalizedRecordDTO record) {
        if (record == null) {
            return null;
        }
        return buildSourceKeyPrefix(record) + (record.getPeriod() == null ? "" : record.getPeriod());
    }

    /**
     * 业务键前缀（不含 period）：{@code sourceType-recordSourceKey-}。
     * <p>
     * 同一笔业务（订单|合同|角色人|费用项|角色类型）在成交月与退单月的事实此前缀相同，
     * 用于退单红冲时定位成交月原正数事实。
     *
     * @param record 归一化记录
     * @return 业务键前缀
     */
    public static String buildSourceKeyPrefix(NormalizedRecordDTO record) {
        if (record == null) {
            return "";
        }
        String sourceType = record.getSourceType() == null ? "" : record.getSourceType();
        String recordSourceKey = record.getSourceKey() == null ? "" : record.getSourceKey();
        return sourceType + "-" + recordSourceKey + "-";
    }

    /** 归一化记录类型 code：贝壳业绩明细行，同携当月应收 + 当月实收两列金额 */
    public static final String RECORD_TYPE_SIGNED = "SIGNED";

    /**
     * 按归一化记录类型决定本条记录要生成的事实口径集合（V4.2 双口径契约，纯函数）。
     * <p>
     * <ul>
     *   <li>{@code SIGNED}（贝壳·经纪人业绩明细表，唯一业绩来源）：同一条业务行双发
     *       <b>PERF_REAL（实收，结佣计薪）+ PERF_EXPECT（应收，新签/团队基数）</b>；
     *       两事实 sourceKey 相同、factType 不同，由部分唯一索引
     *       {@code uk_perf_fact_source_key(fact_type, source_key, fact_status)} 保证共存不冲突；</li>
     *   <li>其余类型（考勤 / 积分 / 手工）：不产生业绩事实，返回空列表。</li>
     * </ul>
     * 顺序固定 REAL 在前 EXPECT 在后，保证事件发布顺序稳定可预期。
     *
     * @param record 归一化记录 DTO
     * @return 需生成的事实口径列表（可能为空，永不为 null）
     */
    public static List<FactType> factTypesForRecord(NormalizedRecordDTO record) {
        if (record == null || record.getRecordType() == null) {
            return List.of();
        }
        return switch (record.getRecordType()) {
            case RECORD_TYPE_SIGNED -> List.of(FactType.PERF_REAL, FactType.PERF_EXPECT);
            default -> List.of();
        };
    }

    /**
     * 解析事实口径对应的贝壳「当前金额」（折算后原值，未还原）。
     * PERF_EXPECT 取当月应收 receivableAmount，PERF_REAL 及其余口径取当月实收 receivedAmount。
     * <p>
     * ★ 空列口径（§双口径契约）：SIGNED 行应收/实收两列并存，某列为空（null）表示
     * 「当月该口径无发生额」，按 0 处理，<b>禁止回退另一口径金额</b>。
     * 典型场景：8 月导入合同有应收无实收（REAL=0），9 月回款再次导入时应收列留空、
     * 实收列有值——若回退，9 月 PERF_EXPECT 会错取实收额，导致店长团队提成/总监门店
     * 提成按同一笔钱在 8、9 两月重复计提。
     * <p>
     * 仅非 SIGNED 的历史单口径行（无应收/实收分列）才回退 DTO.originAmount 兼容。
     */
    public static BigDecimal resolveFactCurrentAmount(NormalizedRecordDTO record, FactType factType) {
        if (record == null || factType == null) {
            return null;
        }
        boolean dualCaliber = RECORD_TYPE_SIGNED.equals(record.getRecordType());
        if (factType == FactType.PERF_EXPECT) {
            if (record.getReceivableAmount() != null) {
                return record.getReceivableAmount();
            }
            return dualCaliber ? BigDecimal.ZERO : record.getOriginAmount();
        }
        // PERF_REAL 及其余口径默认实收
        if (record.getReceivedAmount() != null) {
            return record.getReceivedAmount();
        }
        return dualCaliber ? BigDecimal.ZERO : record.getOriginAmount();
    }

    /** 跨月应收重复认列尾差容忍：当月值与已认值差 ≤ 1 分视为全额重复 */
    private static final BigDecimal RECEIVABLE_DEDUP_TOLERANCE = new BigDecimal("0.01");

    /**
     * 跨月重复导入时的应收增量认定（查库版，决策逻辑见
     * {@link #resolveIncrementalReceivable} 纯函数）。
     *
     * @param record            当月归一化行
     * @param monthlyReceivable 当月行「当月应收业绩」金额（&gt; 0）
     * @return 本月实际认列的应收当前金额（0 表示重复行，仍落 0 元事实留痕）
     */
    private BigDecimal recognizeCrossMonthIncrement(NormalizedRecordDTO record, BigDecimal monthlyReceivable) {
        String prefix = buildSourceKeyPrefix(record);
        BigDecimal priorRole = factMapper.sumRecognizedCurrentByPrefix(
            prefix, FactType.PERF_EXPECT.getCode(), record.getPeriod());
        if (priorRole == null) {
            priorRole = BigDecimal.ZERO;
        }
        BigDecimal priorContractTotal = BigDecimal.ZERO;
        BigDecimal totalNow = record.getTotalReceivableAmount();
        String contractNo = extractContractNo(record.getSourceKey());
        if (totalNow != null && contractNo != null) {
            priorContractTotal = factMapper.selectMaxPriorContractTotalReceivable(
                contractNo, record.getPeriod());
            if (priorContractTotal == null) {
                priorContractTotal = BigDecimal.ZERO;
            }
        }
        BigDecimal recognized = resolveIncrementalReceivable(
            monthlyReceivable, priorRole, totalNow, priorContractTotal);
        if (recognized.compareTo(monthlyReceivable) != 0) {
            log.info("[业绩应收防重] 跨月重复行增量认定：sourceKey={}, 合同={}, 期间={}, "
                    + "当月应收={}, 该角色已认={}, 合同累计={}/历史累计={}, 本次认列={}",
                prefix, contractNo, record.getPeriod(), monthlyReceivable, priorRole,
                totalNow, priorContractTotal, recognized);
        }
        return recognized;
    }

    /**
     * 应收跨月增量认定纯规则（无 IO，便于单测）。业务铁律：应收只计算一次。
     * <ul>
     *   <li>该角色行首次出现（历史已认 ≤ 0）：当月金额全额认列；</li>
     *   <li>合同累计应收（总应收业绩）较历史增长：当月行即本期增量，全额认列；</li>
     *   <li>其余情况按「当月应收 − 该角色已认」取差，≤ {@value #RECEIVABLE_DEDUP_TOLERANCE}
     *       （1 分尾差）或为负一律认 0：全额重复行不再产生新签业绩。</li>
     * </ul>
     *
     * @param monthly             当月行应收金额
     * @param priorRoleRecognized 同合同+角色人+费项更早期间已认列当前金额合计
     * @param totalNow            当月行合同累计应收（可空）
     * @param priorContractTotal  历史合同累计应收最大值（可空）
     * @return 本月认列金额（≥ 0，两位小数）
     */
    public static BigDecimal resolveIncrementalReceivable(BigDecimal monthly, BigDecimal priorRoleRecognized,
                                                          BigDecimal totalNow, BigDecimal priorContractTotal) {
        if (monthly == null || monthly.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal prior = priorRoleRecognized == null ? BigDecimal.ZERO : priorRoleRecognized;
        if (prior.signum() <= 0) {
            return MoneyUtil.round2(monthly);
        }
        if (totalNow != null && priorContractTotal != null
            && totalNow.compareTo(priorContractTotal) > 0) {
            // 合同累计应收增长 → 当月行承载的是本期增量
            return MoneyUtil.round2(monthly);
        }
        BigDecimal delta = monthly.subtract(prior);
        if (delta.compareTo(RECEIVABLE_DEDUP_TOLERANCE) <= 0) {
            return BigDecimal.ZERO;
        }
        return MoneyUtil.round2(delta);
    }

    /**
     * 从归一化业务键 {@code 订单号|合同号|角色人系统号|费用项|角色类型} 中取合同号（第 2 段）。
     */
    public static String extractContractNo(String recordSourceKey) {
        if (recordSourceKey == null) {
            return null;
        }
        String[] parts = recordSourceKey.split("\\|", -1);
        return parts.length >= 2 && !parts[1].isEmpty() ? parts[1] : null;
    }

}
