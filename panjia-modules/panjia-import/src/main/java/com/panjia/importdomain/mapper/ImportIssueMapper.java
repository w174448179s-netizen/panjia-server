package com.panjia.importdomain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.importdomain.domain.ImportIssue;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 导入问题 Mapper。
 */
@Mapper
public interface ImportIssueMapper extends BaseMapper<ImportIssue> {

    /**
     * 按批次删除「归一化/归档消费阶段」问题（重归一化时调用）。
     * <p>
     * 只删 NORMALIZE + ARCHIVE 阶段：PARSE 阶段的基础校验 issue（REQUIRED_MISSING 等）
     * 是模板 required / validation_rules 的校验产物，必须保留，
     * 否则模板校验会"看起来没生效"；ARCHIVE 阶段（实收转人工审批等）是归档消费的派生结论，
     * 重归一化后会重新得出，一并清理避免残留旧结论。
     *
     * @param batchId 批次 ID
     * @return 影响行数
     */
    @Delete("DELETE FROM pj_import_issue WHERE batch_id = #{batchId} AND phase IN ('NORMALIZE', 'ARCHIVE')")
    int deleteNormalizePhaseByBatchId(@Param("batchId") Long batchId);
}
