package com.panjia.importdomain.service;

import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportSourceType;

import java.util.List;

/**
 * 导入批次服务。
 */
public interface ImportBatchService {

    /**
     * 执行文件导入。
     *
     * @param content 文件字节内容（归档 + 解析均消费内存字节）
     */
    Long importFromFile(ImportSourceType sourceType, byte[] content,
                        String fileName, String period, Long operatorId, Long deptId);

    /**
     * 批次详情。
     */
    ImportBatch getById(Long id);

    /**
     * 批次列表。
     */
    List<ImportBatch> list(ImportSourceType sourceType, String period);

    /**
     * 重归一化（PENDING_CONFIRM → NORMALIZING）。
     */
    void renormalize(Long batchId);

    /**
     * 归档（PENDING_CONFIRM/NORMALIZING → ARCHIVED）。
     */
    void archive(Long batchId);

    /**
     * 忽略指定问题。
     */
    void ignoreIssue(Long issueId);

    /**
     * 取批次的问题列表。
     */
    List<ImportIssue> listIssues(Long batchId);

    /**
     * 删除批次（标记被新批次废弃；若未指定新批次则直接标记为已废弃）。
     */
    void markSuperseded(Long batchId, Long newBatchId);
}
