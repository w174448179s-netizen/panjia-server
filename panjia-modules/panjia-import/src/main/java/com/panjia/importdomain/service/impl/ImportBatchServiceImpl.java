package com.panjia.importdomain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.contracts.event.EventPort;
import com.panjia.contracts.event.ImportBatchArchivedEvent;
import com.panjia.contracts.event.ImportBatchRenormalizedEvent;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportBatchStatus;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportIssueStatus;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.mapper.ImportBatchMapper;
import com.panjia.importdomain.mapper.ImportIssueMapper;
import com.panjia.importdomain.service.ImportBatchService;
import com.panjia.importdomain.service.ImportEngine;
import com.panjia.importutil.archive.FileArchiver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

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
    private final FileArchiver fileArchiver;
    private final EventPort eventPort;

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
        return batchMapper.selectList(qw);
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
        batch.archive();
        batchMapper.updateById(batch);
        // 归档完成后发事件，supersededBatchIds 由 EventPort 内部解析（CR-1）
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

        ImportBatchArchivedEvent event = new ImportBatchArchivedEvent();
        event.setBatchId(batch.getId());
        event.setSourceType(batch.getSourceType() == null ? null : batch.getSourceType().getCode());
        event.setPeriod(batch.getPeriod());
        event.setSupersededBatchIds(supersededStrIds);
        eventPort.emit(event);

        log.info("[导入归档事件] 发布 ImportBatchArchivedEvent: batchId={}, sourceType={}, period={}, supersededBatchIds={}",
            batch.getId(), event.getSourceType(), event.getPeriod(), supersededStrIds);
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
}
