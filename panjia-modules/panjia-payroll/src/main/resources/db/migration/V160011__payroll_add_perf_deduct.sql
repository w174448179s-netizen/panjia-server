-- ============================================================
-- V160011: 工资明细增加绩效提成扣点（积分等级扣点）落地字段
--
-- 背景：
--   finalRate = baseRate + perfDeduct + manualAdjust + mentorAdd
--   perfDeduct 为积分等级（A/B/C）对应的提成扣点（A=0/B=-2%/C=-4%），
--   原先仅参与 finalRate 计算未单独落地，导出/列表「绩效提成扣点」列
--   误用 manualAdjust（人工+未参保调整）导致多数员工为空。
--   单独落地后导出与列表直接取列值，避免前端反推误差。
-- ============================================================

ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS perf_deduct NUMERIC(8,6) DEFAULT 0;

COMMENT ON COLUMN pj_payroll_detail.perf_deduct IS '绩效提成扣点（积分等级A/B/C对应扣点，A=0/B=-2%/C=-4%，负=扣点）';
