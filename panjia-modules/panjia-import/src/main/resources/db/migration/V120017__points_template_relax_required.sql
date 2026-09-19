-- ============================================================
-- V120017: 积分（POINTS）模板放宽必填约束——宽容乱数据口径
--
-- 业务口径（客户确认）：日报数据较乱，空值行也允许导入留痕，
-- 最终只统计到人——
--   工号为空  → 行照常导入，聚合时跳过（无法归属到人，不统计）；
--   积分为空  → 计入出勤天数（该天报了日报），不计总积分；
--   日期为空  → 计入总积分（积分有值），不计出勤天数。
-- 聚合器 ScoreSummaryAggregator 已按此口径实现（空值跳过），
-- 本迁移仅放宽模板 required 校验 + 移除工号 not_blank 行级规则。
-- ============================================================

BEGIN;

UPDATE pj_import_template
SET column_mapping = '[
        {"source_column":"A","source_header":"User ID","target_field":"userId","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"填报人","target_field":"reporterName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"部门","target_field":"deptName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"填报时间","target_field":"pointDate","data_type":"DATE","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"✨今日总积分","target_field":"score","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    validation_rules = '{
        "file_level": [{"rule":"max_rows:10000","message":"单次导入不超过10000行"}],
        "row_level": []
    }'::jsonb,
    description = '《二手积分日报5.0版》钉钉智能填报导出 Excel 原文件直接上传，无需下载模板改写；请保留原始两行表头，归属月选择填报日期所在月份。宽容空值：工号空行导入但不统计（无法归属到人），积分空行只计出勤天数，日期空行只计总积分。',
    updated_at = now()
WHERE template_code = 'POINTS';

COMMIT;
