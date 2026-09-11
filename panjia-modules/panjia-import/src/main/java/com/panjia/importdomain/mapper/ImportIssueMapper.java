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
     * 按批次删除「归一化阶段」问题（重归一化时调用）。
     * <p>
     * 只删 NORMALIZE 阶段：PARSE 阶段的基础校验 issue（REQUIRED_MISSING 等）
     * 是模板 required / validation_rules 的校验产物，必须保留，
     * 否则模板校验会"看起来没生效"。
     *
     * @param batchId 批次 ID
     * @return 影响行数
     */
    @Delete("DELETE FROM pj_import_issue WHERE batch_id = #{batchId} AND phase = 'NORMALIZE'")
    int deleteNormalizePhaseByBatchId(@Param("batchId") Long batchId);
}
