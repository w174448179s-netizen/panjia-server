package com.panjia.importdomain.adapter;

import com.panjia.contracts.port.ImportBatchWritePort;
import com.panjia.importdomain.domain.ImportIssue;
import com.panjia.importdomain.domain.ImportIssuePhase;
import com.panjia.importdomain.domain.ImportIssueStatus;
import com.panjia.importdomain.domain.ImportIssueType;
import com.panjia.importdomain.mapper.ImportIssueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 导入批次状态写入适配器（panjia-import 模块实现 {@link ImportBatchWritePort}）。
 * <p>
 * 用途：归档消费阶段（事件消费者）发生异常时，向 import 域回写批次级失败原因，
 * 写入 {@code pj_import_issue} 表（row_no=null 表示批次级问题）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportBatchWriteAdapter implements ImportBatchWritePort {

    private final ImportIssueMapper issueMapper;

    @Override
    public void recordBatchError(Long batchId, String errorMessage) {
        if (batchId == null) {
            return;
        }
        ImportIssue issue = new ImportIssue();
        issue.setBatchId(batchId);
        issue.setRowNo(null);
        issue.setIssueType(ImportIssueType.BATCH_ERROR);
        issue.setFieldName(null);
        issue.setRawValue(null);
        issue.setMessage(truncate(errorMessage, 1000));
        issue.setStatus(ImportIssueStatus.OPEN);
        issue.setPhase(ImportIssuePhase.NORMALIZE);
        issueMapper.insert(issue);
        log.info("[导入批次] 记录批次级错误：batchId={}, issueId={}", batchId, issue.getId());
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
