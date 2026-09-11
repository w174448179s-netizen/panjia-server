package com.panjia.importdomain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.importdomain.domain.ImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    /**
     * 查询被指定新批次 supersede 的旧批次 ID 列表（pj_import_batch.superseded_by_batch_id = newBatchId）。
     * <p>
     * 用于归档事件 ImportBatchArchivedEvent.supersededBatchIds 字段填充（CR-1）：
     * 下游（业绩域）据此冲销旧批次的事实（reversed_reason = SUPERSEDE），避免新旧业绩并存。
     *
     * @param newBatchId 新批次 ID
     * @return 被本批 supersede 的旧批次 ID 集合（一般 0~1 个，理论上同维度唯一索引约束下不超过 1）
     */
    @Select("SELECT id FROM pj_import_batch WHERE superseded_by_batch_id = #{newBatchId}")
    List<Long> selectSupersededBatchIds(@Param("newBatchId") Long newBatchId);
}
