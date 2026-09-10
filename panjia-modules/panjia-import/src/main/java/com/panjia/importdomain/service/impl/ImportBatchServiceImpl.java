package com.panjia.importdomain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportIssueStatus;
import com.panjia.importdomain.domain.ImportSourceType;
import com.panjia.importdomain.mapper.ImportBatchMapper;
import com.panjia.importdomain.mapper.ImportIssueMapper;
import com.panjia.importdomain.service.ImportBatchService;
import com.panjia.importdomain.service.ImportEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional(rollbackFor = Exception.class)
    public void renormalize(Long batchId) {
        ImportBatch batch = requireBatch(batchId);
        batch.reNormalize();
        batchMapper.updateById(batch);
        importEngine.doNormalizePhase(batchId, batch.getSourceType(), batch.getPeriod());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive(Long batchId) {
        ImportBatch batch = requireBatch(batchId);
        batch.archive();
        batchMapper.updateById(batch);
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
}
