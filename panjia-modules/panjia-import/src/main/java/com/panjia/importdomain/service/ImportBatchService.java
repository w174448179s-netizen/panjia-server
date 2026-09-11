package com.panjia.importdomain.service;

import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportSourceType;

import java.util.Collection;
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
     * 批次列表（按单 sourceType 过滤）。保留向后兼容，新代码请用 {@link #listBySourceTypes}。
     */
    List<ImportBatch> list(ImportSourceType sourceType, String period);

    /**
     * 批次列表（按多个 sourceType IN 过滤）。
     *
     * @param sourceTypes 允许 null/empty 表示不过滤；非空时按 in(...) 查询
     * @param period      归属月（YYYY-MM），可空
     */
    List<ImportBatch> listBySourceTypes(Collection<ImportSourceType> sourceTypes, String period);

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

    /**
     * 加载批次归档的原文件（审计下载入口，统一经 FileArchiver SPI：local/minio 都走这层）。
     *
     * @return 归档文件字节内容
     * @throws IllegalStateException 批次不存在、归档路径为空或归档文件丢失
     */
    byte[] loadOriginalFile(Long batchId);

    /**
     * 取批次上传时的原始文件名（用于下载响应头）。
     */
    String getOriginalFileName(Long batchId);
}
