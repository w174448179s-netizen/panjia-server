-- =====================================================================
-- 结佣申请单补审批时间列
--
-- 与实收审批单（pj_perf_received_apply.approve_time）口径对齐：
-- 最近节点办理（总监通过进财务 / 财务终审锁定 / 驳回）均留痕审批人+审批时间，
-- 结佣详情弹窗按实收详情样式展示「审批人 / 审批时间」。
-- =====================================================================

ALTER TABLE pj_commission_application ADD COLUMN IF NOT EXISTS approve_time TIMESTAMP;

COMMENT ON COLUMN pj_commission_application.approve_time IS '审批时间（最近节点办理留痕：总监通过/终审锁定/驳回）';
