package com.panjia.importdomain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
import com.panjia.contracts.event.ImportBatchRenormalizedEvent;
import com.panjia.contracts.event.ImportBatchRevokedEvent;
import com.panjia.contracts.port.BatchConsumptionQueryPort;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportBatchStatus;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportIssueStatus;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.domain.raw.RawAttendance;
import com.panjia.importdomain.domain.raw.RawManual;
import com.panjia.importdomain.domain.raw.RawPayroll;
import com.panjia.importdomain.domain.raw.RawPoints;
import com.panjia.importdomain.domain.raw.RawReceived;
import com.panjia.importdomain.domain.raw.RawSigned;
import com.panjia.importdomain.mapper.ImportBatchMapper;
import com.panjia.importdomain.mapper.ImportIssueMapper;
import com.panjia.importdomain.mapper.NormalizedRecordMapper;
import com.panjia.importdomain.mapper.RawAttendanceMapper;
import com.panjia.importdomain.mapper.RawManualMapper;
import com.panjia.importdomain.mapper.RawPayrollMapper;
import com.panjia.importdomain.mapper.RawPointsMapper;
import com.panjia.importdomain.mapper.RawReceivedMapper;
import com.panjia.importdomain.mapper.RawSignedMapper;
import com.panjia.importdomain.service.ImportBatchService;
import com.panjia.importdomain.service.ImportEngine;
import com.panjia.importutil.archive.FileArchiver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入批次服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportBatchServiceImpl implements ImportBatchService {

    private final ImportEngine importEngine;
    private final ImportBatchMapper batchMapper;
    private final ImportIssueMapper issueMapper;
    private final NormalizedRecordMapper normalizedRecordMapper;
    private final RawSignedMapper rawSignedMapper;
    private final RawReceivedMapper rawReceivedMapper;
    private final RawAttendanceMapper rawAttendanceMapper;
    private final RawPointsMapper rawPointsMapper;
    private final RawManualMapper rawManualMapper;
    private final RawPayrollMapper rawPayrollMapper;
    private final ObjectProvider<BatchConsumptionQueryPort> consumptionQueryPortProvider;
    private final FileArchiver fileArchiver;
    private final EventPort eventPort;
    private final com.panjia.importdomain.service.AttendanceSummaryAggregator attendanceSummaryAggregator;
    private final com.panjia.importdomain.service.ScoreSummaryAggregator scoreSummaryAggregator;
    private final com.panjia.importdomain.service.BatchSupersedeService batchSupersedeService;

    @Override
    public Long importFromFile(ImportSourceType sourceType, byte[] content,
                               String fileName, String period, Long operatorId, Long deptId) {
        return importEngine.importFromFile(sourceType, content, fileName, period, operatorId, deptId);
    }

    @Override
    public ImportBatch getById(Long id) {
        return batchMapper.selectById(id);
    }

    @Override
    public List<ImportBatch> list(ImportSourceType sourceType, String period) {
        LambdaQueryWrapper<ImportBatch> qw = new LambdaQueryWrapper<>();
        if (sourceType != null) {
            qw.eq(ImportBatch::getSourceType, sourceType);
        }
        if (period != null && !period.isBlank()) {
            qw.eq(ImportBatch::getPeriod, period);
        }
        qw.orderByDesc(ImportBatch::getCreateTime);
        return batchMapper.selectList(qw);
    }

    @Override
    public List<ImportBatch> listBySourceTypes(java.util.Collection<ImportSourceType> sourceTypes, String period) {
        LambdaQueryWrapper<ImportBatch> qw = new LambdaQueryWrapper<>();
        if (sourceTypes != null && !sourceTypes.isEmpty()) {
            qw.in(ImportBatch::getSourceType, sourceTypes);
        }
        if (period != null && !period.isBlank()) {
            qw.eq(ImportBatch::getPeriod, period);
        }
        qw.orderByDesc(ImportBatch::getCreateTime);
        List<ImportBatch> batches = batchMapper.selectList(qw);
        fillBatchErrorCount(batches);
        return batches;
    }

    /**
     * 批量填充批次级错误数量（BATCH_ERROR 类型 issue）。
     * <p>
     * 归一化/归档阶段整批失败时，failed_rows 保持 0 但有批次级错误，
     * 前端据此显示"问题清单"按钮。一次 IN 查询按 batchId 分组统计，避免 N+1。
     */
    private void fillBatchErrorCount(List<ImportBatch> batches) {
        if (batches == null || batches.isEmpty()) {
            return;
        }
        List<Long> batchIds = batches.stream().map(ImportBatch::getId).toList();
        // 按批次分组统计 BATCH_ERROR 数量
        List<ImportIssue> batchErrors = issueMapper.selectList(new LambdaQueryWrapper<ImportIssue>()
            .select(ImportIssue::getBatchId)
            .in(ImportIssue::getBatchId, batchIds)
            .eq(ImportIssue::getIssueType, com.panjia.importdomain.domain.ImportIssueType.BATCH_ERROR));
        java.util.Map<Long, Long> countByBatch = batchErrors.stream()
            .collect(java.util.stream.Collectors.groupingBy(ImportIssue::getBatchId,
                java.util.stream.Collectors.counting()));
        for (ImportBatch b : batches) {
            b.setBatchErrorCount(countByBatch.getOrDefault(b.getId(), 0L).intValue());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renormalize(Long batchId) {
        ImportBatch batch = requireBatch(batchId);
        batch.reNormalize();
        batchMapper.updateById(batch);
        importEngine.doNormalizePhase(batchId, batch.getSourceType(), batch.getPeriod());
        // 重归一化后批次可能因残留格式类硬错误进入 FAILED 终态——此时不发下游事件，
        // 否则业绩域会基于一个已失败批次做重算冲销
        ImportBatch latest = requireBatch(batchId);
        if (latest.getStatus() == ImportBatchStatus.FAILED) {
            log.info("重归一化后批次为 FAILED，跳过 Renormalized 事件: batchId={}", batchId);
            return;
        }
        // 重归一化完成后发事件（同一事务内 Outbox INSERT 与业务表 UPDATE 原子提交）
        emitRenormalizedEvent(latest);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive(Long batchId) {
        ImportBatch batch = requireBatch(batchId);
        // 先冲销同（类型,期间,部门）的旧生效批次，否则本批次转入 ARCHIVED 撞部分唯一索引
        batchSupersedeService.supersedeOldArchivedBatch(batch);
        batch.archive();
        batchMapper.updateById(batch);
        // 归档完成后发事件（考勤汇总随 payload 投递，supersededBatchIds 由 EventPort 内部解析）
        emitArchivedEvent(batch);
    }

    /**
     * 构造并发布 ImportBatchArchivedEvent。
     * <p>
     * supersededBatchIds 取「superseded_by_batch_id = 当前 batchId 的旧批次 ID 列表」，
     * 这些是 V2.0 §5.5 SUPERSEDED 机制下被本批废弃的旧批次，下游（业绩域）依此冲销旧事实。
     *
     * @param batch 已归档的批次
     */
    private void emitArchivedEvent(ImportBatch batch) {
        List<Long> supersededIds = batchMapper.selectSupersededBatchIds(batch.getId());
        List<String> supersededStrIds = (supersededIds == null || supersededIds.isEmpty())
            ? Collections.emptyList()
            : supersededIds.stream().map(String::valueOf).toList();

        Long operatorId = null;
        try {
            operatorId = org.dromara.common.satoken.utils.LoginHelper.getUserId();
        } catch (Exception ignored) {
        }

        ImportBatchArchivedEvent event = new ImportBatchArchivedEvent();
        event.setBatchId(batch.getId());
        event.setSourceType(batch.getSourceType() == null ? null : batch.getSourceType().getCode());
        event.setPeriod(batch.getPeriod());
        event.setOperatorId(operatorId);
        event.setSupersededBatchIds(supersededStrIds);
        // 考勤批次：聚合月度汇总随事件 payload 投递，员工域 AttendanceArchiveHandler 消费
        event.setAttendanceSummaries(attendanceSummaryAggregator.aggregateIfAttendance(
            batch.getId(), event.getSourceType(), batch.getPeriod()));
        // 积分批次：日报按工号聚合月度汇总随事件 payload 投递，员工域 ScoreArchiveHandler 消费
        event.setScoreSummaries(scoreSummaryAggregator.aggregateIfPoints(
            batch.getId(), event.getSourceType(), batch.getPeriod()));
        // 积分每日明细（一人一天一行），员工域写 pj_people_score_detail
        event.setScoreDetails(scoreSummaryAggregator.aggregateDetailsIfPoints(
            batch.getId(), event.getSourceType(), batch.getPeriod()));
        // 考勤每日明细（从 Q+ 列解析），员工域写 pj_people_attendance_detail
        event.setAttendanceDetails(attendanceSummaryAggregator.aggregateDetailsIfAttendance(
            batch.getId(), event.getSourceType(), batch.getPeriod()));
        eventPort.emit(event);

        log.info("[导入归档事件] 发布 ImportBatchArchivedEvent: batchId={}, sourceType={}, period={}, operatorId={}, supersededBatchIds={}",
            batch.getId(), event.getSourceType(), event.getPeriod(), operatorId, supersededStrIds);
    }

    /**
     * 构造并发布 ImportBatchRenormalizedEvent（CR-5）。
     * <p>
     * 重归一化不触发跨批次 supersede，supersededBatchIds 字段为空（业绩域仅冲销本批次）。
     */
    private void emitRenormalizedEvent(ImportBatch batch) {
        ImportBatchRenormalizedEvent event = new ImportBatchRenormalizedEvent();
        event.setBatchId(batch.getId());
        event.setSourceType(batch.getSourceType() == null ? null : batch.getSourceType().getCode());
        event.setPeriod(batch.getPeriod());
        eventPort.emit(event);

        log.info("[导入重归一化事件] 发布 ImportBatchRenormalizedEvent: batchId={}, sourceType={}, period={}",
            batch.getId(), event.getSourceType(), event.getPeriod());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ignoreIssue(Long issueId) {
        ImportIssue issue = issueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalStateException("问题不存在: " + issueId);
        }
        issue.setStatus(ImportIssueStatus.IGNORED);
        issueMapper.updateById(issue);
    }

    @Override
    public List<ImportIssue> listIssues(Long batchId) {
        return issueMapper.selectList(
            new LambdaQueryWrapper<ImportIssue>()
                .eq(ImportIssue::getBatchId, batchId)
                .orderByAsc(ImportIssue::getRowNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSuperseded(Long batchId, Long newBatchId) {
        ImportBatch batch = requireBatch(batchId);
        batchMapper.markSuperseded(batchId, newBatchId);
    }

    private ImportBatch requireBatch(Long batchId) {
        ImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalStateException("批次不存在: " + batchId);
        }
        return batch;
    }

    @Override
    public byte[] loadOriginalFile(Long batchId) {
        ImportBatch batch = requireBatch(batchId);
        String storagePath = batch.getStoragePath();
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalStateException("批次未归档原文件: " + batchId);
        }
        // 统一走 FileArchiver SPI，由 Local/Minio 各自解释 storagePath
        return fileArchiver.load(storagePath);
    }

    @Override
    public String getOriginalFileName(Long batchId) {
        ImportBatch batch = requireBatch(batchId);
        // 优先用原始文件名，缺失时退化到 fileName
        String name = batch.getOriginalFileName();
        if (name == null || name.isBlank()) {
            name = batch.getFileName();
        }
        if (name == null || name.isBlank()) {
            // 兜底：用 batchId + 来源
            name = "batch_" + batchId + "_" + (batch.getSourceType() == null ? "unknown" : batch.getSourceType().getCode()) + ".xlsx";
        }
        return name;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long batchId) {
        ImportBatch batch = requireBatch(batchId);
        // 前置校验①：仅已归档批次可撤销
        if (batch.getStatus() != ImportBatchStatus.ARCHIVED) {
            throw new ServiceException(
                "仅已归档批次可撤销，当前状态：" + batch.getStatus().getDesc());
        }
        // 前置校验②：通过反向端口查询下游消费状态（封账/调整/实收）
        // 降级策略：端口缺失或异常时默认不可撤销（保守拒绝，宁可不撤也不误撤）
        boolean revocable = false;
        String rejectReason = "下游消费状态校验失败，无法撤销";
        BatchConsumptionQueryPort port = consumptionQueryPortProvider.getIfAvailable();
        if (port != null) {
            try {
                BatchConsumptionQueryPort.RevokeCheckResult result =
                    port.checkRevocable(batchId, batch.getPeriod());
                revocable = result.isRevocable();
                if (!revocable && result.getReason() != null) {
                    rejectReason = result.getReason();
                }
            } catch (Exception e) {
                log.warn("[导入撤销] 下游消费校验异常，保守拒绝：batchId={}, error={}", batchId, e.getMessage());
            }
        } else {
            log.warn("[导入撤销] BatchConsumptionQueryPort 未实现，保守拒绝：batchId={}", batchId);
        }
        if (!revocable) {
            throw new ServiceException(rejectReason);
        }

        Long operatorId = null;
        try {
            operatorId = org.dromara.common.satoken.utils.LoginHelper.getUserId();
        } catch (Exception ignored) {
        }

        // 0. 保底恢复准备：查出曾被本批冲销的旧批次（撤销后需恢复生效）。
        //    贝壳新签/实收有完整的下游恢复链路（事实/明细翻回 + 事件重放），旧批次随撤销恢复；
        //    考勤/积分/历史工资等来源为 upsert 覆盖语义、旧数据无法精确回滚，仅解除悬空外键，
        //    旧批次保持失效状态，避免「批次显示生效但下游数据已失」的不一致。
        ImportSourceType sourceType = batch.getSourceType();
        boolean restoreSupported = sourceType == ImportSourceType.KE_SIGNED
            || sourceType == ImportSourceType.KE_RECEIVED;
        List<Long> supersededIds = batchMapper.selectSupersededBatchIds(batchId);
        // 合同号在删归一化记录前快照（本批+旧批），供结佣域删除自动产生的 DRAFT 草稿单
        List<String> contractNos = collectContractNos(batchId, sourceType, supersededIds);

        // 1. 硬删问题清单
        issueMapper.delete(new LambdaQueryWrapper<ImportIssue>()
            .eq(ImportIssue::getBatchId, batchId));
        // 2. 硬删归一化记录
        normalizedRecordMapper.delete(new LambdaQueryWrapper<com.panjia.importdomain.domain.NormalizedRecord>()
            .eq(com.panjia.importdomain.domain.NormalizedRecord::getBatchId, batchId));
        // 3. 硬删原始解析数据（按来源类型删对应 raw 表，解除外键约束后才能删批次）
        deleteRawData(batchId, sourceType);
        // 4. 删除本批前先把旧批次对本批的自引用 FK 改指向旧批自身（FK 不可延迟，
        //    直接删会被外键阻止；直接置 NULL 又会与本批撞部分唯一索引）
        if (!supersededIds.isEmpty()) {
            int detached = batchMapper.detachSupersededReferences(batchId);
            log.info("[导入撤销] 旧批次引用已解除：batchId={}, count={}", batchId, detached);
        }
        // 5. 硬删导入批次本身（原始上传文件保留在文件存储中，storage_path 指向的归档文件不删）
        batchMapper.deleteById(batchId);
        // 6. 贝壳新签/实收：旧批次恢复生效（本批已删，不再撞唯一索引）
        List<Long> restoredBatchIds = Collections.emptyList();
        if (restoreSupported && !supersededIds.isEmpty()) {
            int restored = batchMapper.restoreSupersededBatches(supersededIds);
            restoredBatchIds = supersededIds;
            log.info("[导入撤销] 被冲销旧批次已恢复生效：batchId={}, restoredBatchIds={}, count={}",
                batchId, supersededIds, restored);
        }

        // 7. 发布撤销事件（Outbox，与事务原子提交），下游级联删除/恢复
        emitRevokedEvent(batch, operatorId, restoredBatchIds, contractNos);

        log.info("[导入撤销] 批次已删除，事件已发布：batchId={}, sourceType={}, period={}, operatorId={}, restoredBatchIds={}",
            batchId, sourceType, batch.getPeriod(), operatorId, restoredBatchIds);
    }

    /**
     * 汇总本批次及被恢复旧批次归一化记录中的业务键（合同号 + 订单号，去重、去空白）。
     * <p>仅贝壳新签/实收批次需要：结佣域撤销处理器据此删除实收自动通过时产生的
     * DRAFT 结佣草稿单（无论是否存在旧批次，本批连锁产生的草稿都要删）；
     * 一手房/房产金融/家装荐客等无合同号的行以订单号为业务键（结佣单 contract_no
     * 落库时也兜底为订单号），故合同号与订单号都要收集；其他来源类型返回空列表。
     * 必须在本批归一化记录删除前调用。
     */
    private List<String> collectContractNos(Long batchId, ImportSourceType sourceType,
                                            List<Long> supersededIds) {
        if (sourceType != ImportSourceType.KE_SIGNED
            && sourceType != ImportSourceType.KE_RECEIVED) {
            return Collections.emptyList();
        }
        List<Long> batchIds = new ArrayList<>(supersededIds.size() + 1);
        batchIds.add(batchId);
        batchIds.addAll(supersededIds);
        List<com.panjia.importdomain.domain.NormalizedRecord> records = normalizedRecordMapper.selectList(
            new LambdaQueryWrapper<com.panjia.importdomain.domain.NormalizedRecord>()
                .select(com.panjia.importdomain.domain.NormalizedRecord::getContractNo,
                    com.panjia.importdomain.domain.NormalizedRecord::getOrderNo)
                .in(com.panjia.importdomain.domain.NormalizedRecord::getBatchId, batchIds));
        Set<String> bizNos = new LinkedHashSet<>();
        for (com.panjia.importdomain.domain.NormalizedRecord r : records) {
            // MyBatis 默认 returnInstanceForEmptyRow=false：所选两列全为 NULL 的行会映射成 null 实体
            if (r == null) {
                continue;
            }
            for (String c : new String[]{r.getContractNo(), r.getOrderNo()}) {
                if (c != null && !c.isBlank()) {
                    bizNos.add(c.trim());
                }
            }
        }
        return new ArrayList<>(bizNos);
    }

    /**
     * 构造并发布 ImportBatchRevokedEvent。
     *
     * @param restoredBatchIds 随本次撤销恢复生效的旧批次 ID（无恢复时为空列表）
     * @param contractNos      本批及旧批涉及合同号集合（贝壳新签/实收，供结佣域删 DRAFT 草稿）
     */
    private void emitRevokedEvent(ImportBatch batch, Long operatorId,
                                  List<Long> restoredBatchIds, List<String> contractNos) {
        ImportBatchRevokedEvent event = new ImportBatchRevokedEvent();
        event.setBatchId(batch.getId());
        event.setSourceType(batch.getSourceType() == null ? null : batch.getSourceType().getCode());
        event.setPeriod(batch.getPeriod());
        event.setOperatorId(operatorId);
        event.setRestoredBatchIds(restoredBatchIds.stream().map(String::valueOf).toList());
        event.setContractNos(contractNos == null ? Collections.emptyList() : contractNos);
        eventPort.emit(event);
    }

    /**
     * 按来源类型删除对应 raw 表的解析数据。
     * <p>
     * 必须在删批次之前调用，否则外键约束会报错。
     */
    private void deleteRawData(Long batchId, ImportSourceType sourceType) {
        if (sourceType == null) {
            return;
        }
        int count;
        switch (sourceType) {
            case KE_SIGNED:
                count = rawSignedMapper.delete(new LambdaQueryWrapper<RawSigned>()
                    .eq(RawSigned::getBatchId, batchId));
                log.info("[导入撤销] 原始解析数据已删除：batchId={}, type=KE_SIGNED, count={}", batchId, count);
                break;
            case KE_RECEIVED:
                count = rawReceivedMapper.delete(new LambdaQueryWrapper<RawReceived>()
                    .eq(RawReceived::getBatchId, batchId));
                log.info("[导入撤销] 原始解析数据已删除：batchId={}, type=KE_RECEIVED, count={}", batchId, count);
                break;
            case ATTENDANCE:
                count = rawAttendanceMapper.delete(new LambdaQueryWrapper<RawAttendance>()
                    .eq(RawAttendance::getBatchId, batchId));
                log.info("[导入撤销] 原始解析数据已删除：batchId={}, type=ATTENDANCE, count={}", batchId, count);
                break;
            case POINTS:
                count = rawPointsMapper.delete(new LambdaQueryWrapper<RawPoints>()
                    .eq(RawPoints::getBatchId, batchId));
                log.info("[导入撤销] 原始解析数据已删除：batchId={}, type=POINTS, count={}", batchId, count);
                break;
            case OTHERS:
                count = rawManualMapper.delete(new LambdaQueryWrapper<RawManual>()
                    .eq(RawManual::getBatchId, batchId));
                log.info("[导入撤销] 原始解析数据已删除：batchId={}, type=OTHERS, count={}", batchId, count);
                break;
            case HISTORY_PAYROLL:
                // 多模板混装：4 张 raw 表逐一清理（外键都指向批次）
                long signed = rawSignedMapper.delete(new LambdaQueryWrapper<RawSigned>()
                    .eq(RawSigned::getBatchId, batchId));
                long attendance = rawAttendanceMapper.delete(new LambdaQueryWrapper<RawAttendance>()
                    .eq(RawAttendance::getBatchId, batchId));
                long points = rawPointsMapper.delete(new LambdaQueryWrapper<RawPoints>()
                    .eq(RawPoints::getBatchId, batchId));
                count = rawPayrollMapper.delete(new LambdaQueryWrapper<RawPayroll>()
                    .eq(RawPayroll::getBatchId, batchId));
                log.info("[导入撤销] 原始解析数据已删除：batchId={}, type=HISTORY_PAYROLL, "
                    + "signed={}, attendance={}, points={}, payroll={}",
                    batchId, signed, attendance, points, count);
                break;
            default:
                log.warn("[导入撤销] 未知来源类型，跳过 raw 数据删除：batchId={}, sourceType={}", batchId, sourceType);
        }
    }
}
