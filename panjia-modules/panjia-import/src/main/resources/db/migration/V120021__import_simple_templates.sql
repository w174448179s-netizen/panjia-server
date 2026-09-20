-- ============================================================
-- 简版导入模板：积分/考勤（未接贝壳/钉钉的门店手工填写用）
-- 段位：V120021（2026-09-20）
-- 设计：
--   同一 source_type 允许多套激活模板，上传时引擎按文件表头自动匹配：
--   · 列名与钉钉/日报原始文件重叠 → 匹配列数最多者（原模板优先）
--   · 简版模板列名独立（如「今日积分」≠「✨今日总积分」）→ 仅简版文件命中简版模板
--   target_field 与原模板一致 → DataSource/聚合层零改动
-- 版本号 'V100S' 独立于原模板（重新解析按 version 精确回放，不冲突）
-- ============================================================

BEGIN;

-- 1. 积分简版（POINTS_SIMPLE，source_type=POINTS）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000017,
    'POINTS_SIMPLE', 'V100S',
    '积分导入模板（简版）',
    'POINTS', 'EXCEL',
    NULL, 1, 2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"姓名","target_field":"reporterName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"填报时间","target_field":"submitTime","data_type":"DATETIME","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"今日积分","target_field":"score","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": [
            {"field":"employeeCode","rule":"not_blank","message":"工号不能为空"}
        ]
    }'::jsonb,
    '未接积分日报系统的门店手工填写的简版模板：每员工每日一行，填报时间须在当日 19:30~23:00 之间（早于 19:30 当日积分不计，晚于 23:00 计晚提交扣款）。',
    'SIMPLE_202609',
    true, '2026-09-20'::date, NULL,
    '列名与日报原始文件不同（今日积分 vs ✨今日总积分），互不冲突；上传时按表头自动匹配',
    'admin', now(), NULL, now()
) ON CONFLICT (template_code, template_version) DO NOTHING;

-- 2. 考勤简版（ATTENDANCE_SIMPLE，source_type=ATTENDANCE）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000018,
    'ATTENDANCE_SIMPLE', 'V100S',
    '考勤导入模板（简版）',
    'ATTENDANCE', 'EXCEL',
    NULL, 1, 2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"姓名","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"出勤天数","target_field":"attendDays","data_type":"INT","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"迟到次数","target_field":"lateCount","data_type":"INT","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"迟到时长(分钟)","target_field":"lateMinutes","data_type":"DECIMAL","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"上班缺卡次数","target_field":"missingCardCount","data_type":"INT","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"旷工天数","target_field":"absentDays","data_type":"DECIMAL","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"事假(天)","target_field":"personalLeaveDays","data_type":"DECIMAL","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"病假(天)","target_field":"sickLeaveDays","data_type":"DECIMAL","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": [
            {"field":"employeeCode","rule":"not_blank","message":"工号不能为空"}
        ]
    }'::jsonb,
    '未接钉钉考勤的门店手工填写的简版模板：每员工一行月度汇总；未填列按 0 处理。',
    'SIMPLE_202609',
    true, '2026-09-20'::date, NULL,
    '列名为钉钉月度汇总的子集；钉钉原文件上传时按列数最多者优先匹配原模板',
    'admin', now(), NULL, now()
) ON CONFLICT (template_code, template_version) DO NOTHING;

COMMIT;
