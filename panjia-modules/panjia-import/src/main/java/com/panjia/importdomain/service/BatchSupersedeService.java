package com.panjia.importdomain.service;

import com.panjia.importdomain.domain.ImportBatch;
import com.panjia.importdomain.mapper.ImportBatchMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 重复批次冲销服务：同（来源类型, 归属月, 部门）仅允许一份生效（ARCHIVED）批次。
 * <p>
 * 设计依据：V2.0 §5.5 / §6.1 / ADR-IMP-004（单据逻辑失效）。
 * 部分唯一索引 uk_import_batch_type_period_dept 仅约束
 * status=ARCHIVED 且 superseded_by_batch_id IS NULL 的行；
 * 因此新批次转入 ARCHIVED 前，必须先把同组合的旧生效批次标记 SUPERSEDED，
 * 否则 UPDATE 撞唯一索引。
 * <p>
 * 两条归档路径共用本服务：
 * <ul>
 *   <li>自动归档（ImportEngine 归一化成功 → ARCHIVED）</li>
 *   <li>手动归档（ImportBatchService.archive，如待确认批次人工确认）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchSupersedeService {

    private final ImportBatchMapper batchMapper;

    /**
     * 若存在同（类型, 期间, 部门）的旧生效批次（ARCHIVED 且未被冲销），
     * 将其标记为被 newBatch 冲销（SUPERSEDED）。
     *
     * @return 被冲销的旧批次 ID；无旧批次时返回 null
     */
    public Long supersedeOldArchivedBatch(ImportBatch newBatch) {
        ImportBatch old = batchMapper.selectOne(new LambdaQueryWrapper<ImportBatch>()
            .eq(ImportBatch::getSourceType, newBatch.getSourceType())
            .eq(ImportBatch::getPeriod, newBatch.getPeriod())
            .eq(ImportBatch::getDeptId, newBatch.getDeptId())
            .eq(ImportBatch::getStatus, com.panjia.importdomain.domain.ImportBatchStatus.ARCHIVED)
            .isNull(ImportBatch::getSupersededByBatchId)
            .ne(ImportBatch::getId, newBatch.getId())
            .last("LIMIT 1"));
        if (old == null) {
            return null;
        }
        log.info("重复导入归档标记 SUPERSEDED: oldBatchId={} -> newBatchId={} (sourceType={}, period={}, deptId={})",
            old.getId(), newBatch.getId(), newBatch.getSourceType(), newBatch.getPeriod(), newBatch.getDeptId());
        batchMapper.markSuperseded(old.getId(), newBatch.getId());
        return old.getId();
    }
}
