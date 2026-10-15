-- ============================================================
-- 考勤模板 V210：钉钉《月度汇总》病假拆分为两列
--   I 病假（诊所类证明）(天) / J 病假（二甲医院以上）(天)
--   后续列整体右移：K 休息天数 / L 迟到次数 / M 迟到时长 / N 上班缺卡次数 /
--   O 旷工天数 / P 休息日加班 / Q 节假日加班 / R 列起每日考勤结果
-- 旧 V200（单列「病假(天)」）保持激活：导入域按文件表头自动匹配，新旧文件均可导入。
-- 标准文件：成都市花照天街房地产经纪有限公司_月度汇总_YYYYMMDD-YYYYMMDD.xlsx
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000021,
    'ATTENDANCE',
    'V210',
    '钉钉·考勤月度汇总（病假拆分版）',
    'ATTENDANCE',
    'EXCEL',
    '月度汇总',
    3,
    5,
    '[
        {"source_column":"A","source_header":"姓名","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"考勤组","target_field":"attendanceGroup","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"部门","target_field":"deptName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"职位","target_field":"position","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"UserId","target_field":"userId","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"出勤天数","target_field":"attendDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"事假(天)","target_field":"personalLeaveDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"病假（诊所类证明）(天)","target_field":"sickClinicLeaveDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"J","source_header":"病假（二甲医院以上）(天)","target_field":"sickHospitalLeaveDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"休息天数","target_field":"restDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"迟到次数","target_field":"lateCount","data_type":"INT","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"M","source_header":"迟到时长","target_field":"lateMinutes","data_type":"INT","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"N","source_header":"上班缺卡次数","target_field":"missingCardCount","data_type":"INT","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"O","source_header":"旷工天数","target_field":"absentDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"P","source_header":"休息日加班","target_field":"weekendOvertime","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"Q","source_header":"节假日加班","target_field":"holidayOvertime","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": []
    }'::jsonb,
    '钉钉考勤后台导出的《月度汇总》Excel 原文件直接上传，无需下载模板改写；请保留原始两行表头，归属月选择文件统计日期所在月份。宽容空值：工号为空的行照常导入但不统计（无法归属到人，需在钉钉后台补齐工号后重新导入）。本版病假按证明类型拆分为「诊所类证明」「二甲医院以上」两列，分别适用不同扣款规则。',
    'ATTENDANCE_DINGTALK_202610',
    true,
    '2026-10-15'::date,
    NULL,
    '客户标准表：两行表头（第3行字段名/第4行每日日期，H-J 子表头为事假/病假诊所/病假二甲），数据从第5行开始；A-Q 固定指标映射，R列起每日考勤结果随当月天数动态展开，不做字段映射',
    'admin',
    now(),
    NULL,
    now()
);
