-- ============================================================
-- V120019: 积分日报提交时间规则
--
-- 背景：积分日报「填报时间」需判定有效提交窗口（每日 19:30~23:00），
--       早提交视为无效（积分不计），晚提交（>23:00）处罚 5 元/次。
--       原模板将填报时间映射为 DATE 丢失时分，无法判定窗口。
--
-- 改动：
--   1. pj_import_raw_points 增加 submit_time 列（TIMESTAMP），存完整填报时间
--   2. POINTS 模板「填报时间」映射从 pointDate(DATE) 改为 submitTime(DATETIME)
-- ============================================================

ALTER TABLE pj_import_raw_points ADD COLUMN IF NOT EXISTS submit_time TIMESTAMP;

-- 更新 POINTS 模板：填报时间改为 DATETIME，落 submit_time 字段
UPDATE pj_import_template
SET column_mapping = REPLACE(
    column_mapping::text,
    '{"source_column":"E","source_header":"填报时间","target_field":"pointDate","data_type":"DATE"',
    '{"source_column":"E","source_header":"填报时间","target_field":"submitTime","data_type":"DATETIME"'
)::jsonb
WHERE template_code = 'POINTS';
