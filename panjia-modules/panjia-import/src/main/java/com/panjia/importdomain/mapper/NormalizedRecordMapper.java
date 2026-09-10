package com.panjia.importdomain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.importdomain.domain.NormalizedRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 归一化记录 Mapper。
 */
@Mapper
public interface NormalizedRecordMapper extends BaseMapper<NormalizedRecord> {

    /**
     * 按批次删除旧归一化记录（重归一化时调用）。
     *
     * @param batchId 批次 ID
     * @return 影响行数
     */
    @Delete("DELETE FROM pj_normalized_record WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") Long batchId);

    /**
     * 查询活跃批次的归一化记录（强制 SUPERSEDED 过滤）。
     * <p>
     * 内置：archive_status=ARCHIVED AND superseded_by_batch_id IS NULL。
     *
     * @param period 归属月
     * @return 归一化记录列表
     */
    @Select("SELECT n.* FROM pj_normalized_record n " +
        "JOIN pj_import_batch b ON n.batch_id = b.id " +
        "WHERE n.period = #{period} " +
        "AND b.status = 3 AND b.superseded_by_batch_id IS NULL")
    List<NormalizedRecord> selectActiveByPeriod(@Param("period") String period);
}
