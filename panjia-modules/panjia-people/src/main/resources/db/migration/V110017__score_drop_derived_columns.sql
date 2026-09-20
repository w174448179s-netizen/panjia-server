-- ============================================================
-- V110017: 积分表删除派生字段，改为查询时实时计算
--
-- 平均积分/绩效等级/提成扣点/积分扣款 均可由原始事实
-- （total_points、attend_days、late_submit_count）推导，
-- 落库会因规则/数据变动产生陈旧值（如调整晚提交次数后扣款不同步）。
-- 派生口径统一收敛到 ScoreGradePolicy（与 V160001 policy.points 一致）：
--   平均分 ≥8 → A（不扣）；6~8 → B（-2%）；<6 → C（-4%）
--   积分扣款 = 晚提交次数 × 5 元/次
-- ============================================================

ALTER TABLE pj_people_performance_score
    DROP COLUMN IF EXISTS avg_points,
    DROP COLUMN IF EXISTS grade,
    DROP COLUMN IF EXISTS deduct_rate,
    DROP COLUMN IF EXISTS points_fee;
