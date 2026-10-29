-- ============================================================
-- 业绩调整单增加实收月列：新签补录判重键修正
-- 背景：同一订单号在不同实收月会各有一张实收单（uk_rapp_period_order
-- 仅保证「期间 + 订单号」唯一，不是订单号全局唯一），仅按订单号判重
-- 会把同一订单跨月的第二张实收单误判为重复补录。补录判重改为
-- 「订单号 + 实收月」（订单号为空时回退「合同号 + 实收月」）。
-- 历史补录单从 payload_json 的 receivedPeriod 回填，非补录类型保持 NULL。
-- ============================================================

ALTER TABLE pj_perf_adjust
    ADD COLUMN received_period varchar(7) NULL DEFAULT NULL;

COMMENT ON COLUMN pj_perf_adjust.received_period IS '实收月 YYYY-MM（新签补录关联实收单的期间，与订单号共同构成判重键）';

UPDATE pj_perf_adjust
   SET received_period = payload_json::jsonb ->> 'receivedPeriod'
 WHERE adjust_type = 'SUPPLEMENT'
   AND received_period IS NULL
   AND payload_json IS NOT NULL
   AND payload_json LIKE '%receivedPeriod%';

CREATE INDEX idx_padj_order_received ON pj_perf_adjust(order_no, received_period);
