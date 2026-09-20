-- ============================================================
-- V120020: 修复 POINTS 模板「填报时间」映射（V120019 REPLACE 未生效）
--
-- 背景：V120019 用字符串 REPLACE 更新 column_mapping，但模板 JSON 实际
--       为格式化键序（required/data_type/transform/target_field/...），
--       REPLACE 模式未命中，静默未更新。导致 submitTime 从未解析，
--       聚合器视 submitTime 为空 → 全部积分不计入 → 总积分清零。
--
-- 修复：按 source_header 定位「填报时间」元素，用 jsonb 合并更新
--       target_field=submitTime、data_type=DATETIME（与键序无关）。
-- ============================================================

UPDATE pj_import_template
SET column_mapping = (
    SELECT jsonb_agg(
        CASE
            WHEN elem ->> 'source_header' = '填报时间'
                THEN elem || '{"target_field": "submitTime", "data_type": "DATETIME"}'::jsonb
            ELSE elem
        END
        ORDER BY ord)
    FROM jsonb_array_elements(column_mapping) WITH ORDINALITY AS t(elem, ord)
)
WHERE template_code = 'POINTS'
  AND column_mapping @> '[{"source_header": "填报时间"}]'::jsonb;
