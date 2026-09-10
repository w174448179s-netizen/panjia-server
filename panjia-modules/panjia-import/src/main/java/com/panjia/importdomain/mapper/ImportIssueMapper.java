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
     * 按批次删除旧问题（重归一化时调用）。
     *
     * @param batchId 批次 ID
     * @return 影响行数
     */
    @Delete("DELETE FROM pj_import_issue WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") Long batchId);
}
