package com.panjia.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.outbox.entity.OutboxIdempotent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * Outbox 幂等记录 Mapper（pj_outbox_idempotent）。
 * <p>
 * 消费后 INSERT（dispatch 成功路径），主键冲突 = 已消费 = 幂等跳过。
 * V1 用 DB 幂等，减少 Redis 依赖。
 * <p>
 * <b>为何用 {@code ON CONFLICT DO NOTHING} 而非 {@code INSERT + catch DuplicateKeyException}</b>：
 * <ul>
 *   <li>无异常路径 → 无 DuplicateKeyException 日志噪音</li>
 *   <li>避免 PostgreSQL aborted transaction 状态：catch 异常后事务被 PG 标记为失败，
 *       后续 COMMIT 会退化为 ROLLBACK，导致 {@code @Transactional} 边界语义不可靠</li>
 *   <li>单条 SQL 完成 check-and-insert，原子、无 TOCTOU 竞态</li>
 * </ul>
 */
@Mapper
public interface OutboxIdempotentMapper extends BaseMapper<OutboxIdempotent> {

    /**
     * 原子 check-and-insert：INSERT ... ON CONFLICT (event_id) DO NOTHING（PostgreSQL 9.5+）。
     * <p>
     * 调用方根据返回值判定首次/重复消费：
     * <ul>
     *   <li>affected = 1 → 本次首次消费（可继续后续流程）</li>
     *   <li>affected = 0 → 主键冲突（已消费过，跳过）</li>
     * </ul>
     *
     * @param eventId    事件 ID（与 pj_event_outbox.event_id 一致）
     * @param consumedAt 消费时间
     * @return 影响行数：1 = 首次；0 = 已存在
     */
    @Insert("INSERT INTO pj_outbox_idempotent (event_id, consumed_at) " +
        "VALUES (#{eventId}, #{consumedAt}) " +
        "ON CONFLICT (event_id) DO NOTHING")
    int insertIgnore(@Param("eventId") String eventId,
                     @Param("consumedAt") LocalDateTime consumedAt);
}