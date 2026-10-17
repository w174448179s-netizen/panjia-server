-- ===================================================================
-- V160012: 手工录入项（奖金/其他收入/其他支出）改为生效区间
-- 口径：对齐提成点调整 startMonth/endMonth——算薪取 startMonth ≤ 算薪月 ≤ endMonth
--       的审批通过项；原单值「归属月 period」废弃（列保留存历史，代码不再映射）
-- 迁移：存量记录 start_month = end_month = period，行为与改造前一致
-- ===================================================================

ALTER TABLE pj_payroll_manual_item
    ADD COLUMN IF NOT EXISTS start_month varchar(7),
    ADD COLUMN IF NOT EXISTS end_month   varchar(7);

COMMENT ON COLUMN pj_payroll_manual_item.start_month IS '生效起始月 YYYY-MM（含）';
COMMENT ON COLUMN pj_payroll_manual_item.end_month   IS '生效截止月 YYYY-MM（含），可空=长期生效';

UPDATE pj_payroll_manual_item
SET start_month = period,
    end_month   = period
WHERE start_month IS NULL
  AND period IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_manual_item_range ON pj_payroll_manual_item (start_month, end_month);
