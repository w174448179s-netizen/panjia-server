package com.panjia.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.outbox.entity.OutboxIdempotent;
import org.apache.ibatis.annotations.Mapper;

/**
 * Outbox 幂等记录 Mapper（pj_outbox_idempotent）。
 * <p>
 * 消费前 INSERT，主键冲突 = 已消费 = 幂等跳过（不依赖 Redis，V1 用 DB）。
 */
@Mapper
public interface OutboxIdempotentMapper extends BaseMapper<OutboxIdempotent> {
}
