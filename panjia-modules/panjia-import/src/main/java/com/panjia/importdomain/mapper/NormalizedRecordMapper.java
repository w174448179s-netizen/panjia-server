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

    /**
     * 按批次分页查询归一化记录（仅 ARCHIVED 且未被 supersede 的批次）。
     * <p>
     * 用于业绩域按 ImportNormalizedRecordQueryPort.listByBatchId 拉取（V2.0 §4.1 ⑤）。
     * 过滤条件：批次 status='ARCHIVED' AND superseded_by_batch_id IS NULL，
     * 避免把已废弃旧批次的归一化记录算成业绩。
     *
     * @param batchId 批次 ID
     * @param offset  偏移
     * @param limit   每页条数
     * @return 归一化记录列表
     */
    @Select("SELECT n.* FROM pj_normalized_record n " +
        "JOIN pj_import_batch b ON n.batch_id = b.id " +
        "WHERE n.batch_id = #{batchId} " +
        "AND b.status = 3 AND b.superseded_by_batch_id IS NULL " +
        "ORDER BY n.id ASC " +
        // 标准 SQL 分页语法（LIMIT n OFFSET m），PG/MySQL 双兼容；
        // MySQL 方言的 "LIMIT offset, limit" 在 PG 直接报语法错
        "LIMIT #{limit} OFFSET #{offset}")
    List<NormalizedRecord> selectPageByBatchId(@Param("batchId") Long batchId,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit);

    /**
     * 按批次统计归一化记录数（仅 ARCHIVED 且未被 supersede 的批次）。
     *
     * @param batchId 批次 ID
     * @return 归一化记录总数
     */
    @Select("SELECT COUNT(*) FROM pj_normalized_record n " +
        "JOIN pj_import_batch b ON n.batch_id = b.id " +
        "WHERE n.batch_id = #{batchId} " +
        "AND b.status = 3 AND b.superseded_by_batch_id IS NULL")
    long countByBatchIdActive(@Param("batchId") Long batchId);
}
