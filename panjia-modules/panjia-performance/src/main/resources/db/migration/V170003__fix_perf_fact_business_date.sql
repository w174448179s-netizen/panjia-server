-- ─────────────────────────────────────────────────────────────────────────────
-- 修正业绩事实业务日期：导入解析签约时间失败时兜底写成了归属月初。
-- 背景：贝壳签约时间原始串含时分秒（yyyy-MM-dd HH:mm:ss），
--       parseSignDate 旧逻辑按 LocalDate 整串解析失败后回退 period 月初，
--       导致 pj_perf_fact.business_date / effective_date 均非真实签约日期。
-- 修复：以归一化记录 sign_date 的日期部分为准回填
--       （business_date 为 timestamp，date 隐式补 00:00:00；effective_date 为 date）。
--       sign_date 无法解析的记录不回填，保留原值。
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE pj_perf_fact f
SET business_date = d.sign_day,
    effective_date = d.sign_day
FROM (
    SELECT nr.id,
           LEFT(REGEXP_REPLACE(nr.sign_date, '[./]', '-'), 10)::date AS sign_day
    FROM pj_normalized_record nr
    WHERE nr.sign_date ~ '^\s*\d{4}[-/.]\d{1,2}[-/.]\d{1,2}'
) d
WHERE f.normalized_record_id = d.id
  AND (f.business_date IS DISTINCT FROM d.sign_day
       OR f.effective_date IS DISTINCT FROM d.sign_day);
