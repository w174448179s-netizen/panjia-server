package com.panjia.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.contracts.event.OutboxStatusEnum;
import com.panjia.outbox.entity.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 事件 Mapper。
 * <p>
 * 自定义查询方法供 Dispatcher 使用：拉取待投递事件 + 状态流转。
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 拉取待投递事件（status 为 PENDING 且 next_retry_at<=now），按创建时间升序，限制批量大小。
     * 使用部分索引 idx_outbox_pending（WHERE status='PENDING'）。
     *
     * @param status 待拉取状态（PENDING，由调用方传枚举，禁止 SQL 魔法字符串）
     * @param now    当前时间
     * @param limit  批量上限
     * @return 待投递事件列表
     */
    @Select("SELECT * FROM pj_event_outbox " +
        "WHERE status = #{status} AND (next_retry_at IS NULL OR next_retry_at <= #{now}) " +
        "ORDER BY created_at ASC LIMIT #{limit}")
    List<OutboxEvent> selectPending(@Param("status") OutboxStatusEnum status,
                                    @Param("now") LocalDateTime now,
                                    @Param("limit") int limit);

    /**
     * 标记状态流转：PENDING → PROCESSED（投递成功，errorMessage 传 null 清空历史错误）
     * 或 PENDING → FAILED（重试达上限，errorMessage 传最后一次错误信息）。
     *
     * @param id           事件 ID
     * @param status       目标状态（PROCESSED / FAILED，由调用方传枚举，禁止 SQL 魔法字符串）
     * @param errorMessage 错误信息（成功时传 null）
     * @param now          更新时间
     * @return 影响行数
     */
    @Update("UPDATE pj_event_outbox SET status = #{status}, error_message = #{errorMessage}, " +
        "updated_at = #{now} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id,
                     @Param("status") OutboxStatusEnum status,
                     @Param("errorMessage") String errorMessage,
                     @Param("now") LocalDateTime now);

    /**
     * 标记重试失败：retry_count+1，计算 next_retry_at，记录错误信息。
     * 当 retry_count 达上限时由 Dispatcher 调 {@link #updateStatus} 置 FAILED。
     *
     * @param id            事件 ID
     * @param nextRetryAt   下次重试时间（指数退避）
     * @param errorMessage  错误信息
     * @param now           更新时间
     * @return 影响行数
     */
    @Update("UPDATE pj_event_outbox " +
        "SET retry_count = retry_count + 1, next_retry_at = #{nextRetryAt}, " +
        "error_message = #{errorMessage}, updated_at = #{now} WHERE id = #{id}")
    int incrementRetry(@Param("id") Long id,
                       @Param("nextRetryAt") LocalDateTime nextRetryAt,
                       @Param("errorMessage") String errorMessage,
                       @Param("now") LocalDateTime now);
}
