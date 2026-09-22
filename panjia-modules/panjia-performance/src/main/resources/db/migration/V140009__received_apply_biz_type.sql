-- =====================================================================
-- 实收审批单落库业务类型（biz_type）
--
-- 背景：实收明细列表的「类型」列与「类型」筛选此前依赖按 (period, contract_no)
-- 实时回查 ACTIVE PERF_REAL 事实的 biz_type（非入库翻译字段），列表每页都要
-- 多打一次事实聚合查询，且无法在 SQL 层按类型筛选。
-- 现改为建单时从实收事实快照落库，列表直接读列、筛选下推到 SQL。
--
-- 回填：存量单据按已绑定事实（received_apply_id）聚合补值；无绑定事实
-- （item_count=0 的空单）保持 NULL，前端显示占位符。
-- =====================================================================

ALTER TABLE pj_perf_received_apply ADD COLUMN IF NOT EXISTS biz_type VARCHAR(32);

COMMENT ON COLUMN pj_perf_received_apply.biz_type IS '业务类型(一手房/二手买卖/租赁/租赁轻托管/写字楼租赁/轻托管推房等，建单时从实收事实快照)';
