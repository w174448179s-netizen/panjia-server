-- ============================================================
-- V120016: 积分（POINTS）导入模板——《二手积分日报5.0版》客户日报格式
--
-- （替换原自建积分模板，仅适用于全新初始化）
--
-- 标准文件：二手积分日报5.0版.xlsx（钉钉智能填报导出）
--
-- 文件结构（单 sheet「二手积分日报5.0版」）：
--   第 1 行：主表头（A=User ID、B=工号、C=填报人、D=部门、E=填报时间、
--            H=今日总积分 …共 43 列，多数列带 emoji 与跨列合并）
--   第 2 行：子表头（J~X 为工作项分类子列；B/C/E/H 等垂直合并主表头无子值）
--   第 3 行起：数据，一人一天一行（当日积分日报）
--
-- 映射策略：
--   只映射积分结算所需 4+2 列：B 工号（必填唯一键）、E 填报时间（DATE，
--   格式「2026年07月01日 19:44」）、H 今日总积分（DECIMAL），
--   另 A User ID / C 填报人 / D 部门留痕；其余 36 列不映射，随原始文件归档留痕。
--   月度汇总口径由聚合器产出：总积分 = SUM(今日总积分)，
--   出勤天数 = COUNT(DISTINCT 填报日期)，平均积分 = 总积分 / 出勤天数。
--   工号为必填：空工号行会导致整批格式校验失败（防止同一员工部分天数
--   缺失造成总积分漏算），需先在钉钉后台补齐工号再导入。
-- ============================================================

BEGIN;

DELETE FROM pj_import_template WHERE template_code = 'POINTS';

INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000030,
    'POINTS',
    'V200',
    '二手积分日报5.0版',
    'POINTS',
    'EXCEL',
    '二手积分日报5.0版', 1, 3,
    '[
        {"source_column":"A","source_header":"User ID","target_field":"userId","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"填报人","target_field":"reporterName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"部门","target_field":"deptName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"填报时间","target_field":"pointDate","data_type":"DATE","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"✨今日总积分","target_field":"score","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:10000","message":"单次导入不超过10000行"}],
        "row_level": [
            {"field":"employeeCode","rule":"not_blank","message":"工号不能为空（钉钉后台需为员工维护工号，且与员工档案一致）"}
        ]
    }'::jsonb,
    '《二手积分日报5.0版》钉钉智能填报导出 Excel 原文件直接上传，无需下载模板改写；请保留原始两行表头，归属月选择填报日期所在月份。',
    'SCORE_DAILY_REPORT_V50',
    true, '2026-09-19'::date, NULL,
    '客户标准表：两行表头（第1行主表头/第2行子表头），数据从第3行开始，一人一天一行；B=工号（必填）、E=填报时间（中文日期时间）、H=今日总积分；其余列不映射随原始文件留痕',
    'admin', now(), NULL, now()
);

COMMIT;
