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

    /**
     * 撤销恢复第一步：解除旧批次对被撤销批次的自引用外键（临时指向旧批次自身）。
     * <p>
     * 撤销一个曾冲销旧批的批次时存在循环依赖：
     * 旧批 superseded_by_batch_id 指向本批，直接删本批撞自引用 FK（不可延迟）；
     * 直接把旧批该列置 NULL 又会因旧批（status=ARCHIVED）与本批同时满足部分唯一索引
     * uk_import_batch_type_period_dept 而撞键。故事务内先把引用改写为旧批次自身 id
     * （仍非 NULL，不占生效唯一索引；自引用满足 FK），删除本批后再由
     * {@link #restoreSupersededBatches} 置空恢复生效；不支持下游恢复的来源类型
     * （考勤/积分/历史工资等 upsert 覆盖无法精确回滚）则保留自引用状态，
     * 语义上仍是「被替代的失效行」，与下游数据状态一致。
     *
     * @param newBatchId 被撤销的批次 ID
     * @return 被解除引用的旧批次行数
     */
    @Update("UPDATE pj_import_batch SET superseded_by_batch_id = id, update_time = NOW() " +
        "WHERE superseded_by_batch_id = #{newBatchId}")
    int detachSupersededReferences(@Param("newBatchId") Long newBatchId);

    /**
     * 撤销恢复第二步：旧批次恢复生效（superseded_by_batch_id 置空）。
     * <p>
     * 仅处理处于 {@link #detachSupersededReferences} 留下的临时自引用状态的行，
     * 与 detach 严格成对，重复执行幂等。调用时机必须在被撤销批次行删除之后，
     * 否则撞部分唯一索引 uk_import_batch_type_period_dept。
     *
     * @param batchIds 待恢复的旧批次 ID 列表
     * @return 恢复生效的旧批次行数
     */
    @Update("""
        <script>
        UPDATE pj_import_batch SET superseded_by_batch_id = NULL, update_time = NOW()
        WHERE superseded_by_batch_id = id AND id IN
        <foreach collection='batchIds' item='oldBatchId' open='(' separator=',' close=')'>#{oldBatchId}</foreach>
        </script>
        """)
    int restoreSupersededBatches(@Param("batchIds") List<Long> batchIds);
}
