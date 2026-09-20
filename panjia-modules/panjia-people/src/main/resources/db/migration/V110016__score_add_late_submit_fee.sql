-- ============================================================
-- V110016: 积分表增加晚提交次数与积分扣款
--
-- 背景：积分日报提交时间规则（19:30~23:00 有效，晚提交 5 元/次），
--       需在积分月度汇总表记录晚提交次数与对应扣款金额，供算薪消费。
-- ============================================================

ALTER TABLE pj_people_performance_score
    ADD COLUMN IF NOT EXISTS late_submit_count INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS points_fee NUMERIC(10,2) DEFAULT 0;

COMMENT ON COLUMN pj_people_performance_score.late_submit_count IS '当月晚提交次数（填报时间晚于23:00的天数，每天最多1次）';
COMMENT ON COLUMN pj_people_performance_score.points_fee IS '积分扣款=晚提交次数×5元/次';
