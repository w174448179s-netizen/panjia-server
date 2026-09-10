package com.panjia.importdomain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.importdomain.domain.ImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 导入批次 Mapper。
 */
@Mapper
public interface ImportBatchMapper extends BaseMapper<ImportBatch> {

    /**
     * 标记旧批次被新批次废弃（回填 superseded_by_batch_id）。
     *
     * @param oldBatchId 旧批次 ID
     * @param newBatchId 新批次 ID
     * @return 影响行数
     */
    @Update("UPDATE pj_import_batch SET superseded_by_batch_id = #{newBatchId}, update_time = NOW() " +
        "WHERE id = #{oldBatchId} AND superseded_by_batch_id IS NULL")
    int markSuperseded(@Param("oldBatchId") Long oldBatchId, @Param("newBatchId") Long newBatchId);
}
