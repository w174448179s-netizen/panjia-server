-- ============================================================
-- V120013: 考勤模板替换为客户标准表——钉钉《月度汇总》（ATTENDANCE V100 → V200）
-- 标准文件：成都市花照天街房地产经纪有限公司_月度汇总_20260701-20260731.xlsx
--
-- 文件结构（单 sheet「月度汇总」）：
--   第 1 行：标题「月度汇总 统计日期：...」（合并单元格，跳过）
--   第 2 行：报表生成时间（合并单元格，跳过）
--   第 3 行：表头字段名（A-N 固定指标列；O 列起合并为「考勤结果」）
--   第 4 行：考勤结果子表头（1/2/3/六/日... 每日一列，随月份天数动态展开）
--   第 5 行起：数据，一行一人一月
--
-- 映射策略：
--   A-N 共 14 列固定指标做字段映射；O 列起每日考勤结果文本（如「A班次:正常」「随心假...」
--   「年假...」「休息」）列数随月变化（28~31 列），不做静态映射，随原始文件归档留痕。
--   月度汇总行内无日期列，归属月取导入时选择的批次期间（AttendanceDataSource 归一为当月 1 日）。
--
-- 旧 V100（工号/考勤日期/迟到次数/旷工天数 + V120012 补的扣款金额，人事整理扁平表）物理删除；
-- 历史批次只快照 template_version 字符串，不受影响；重归一化自动取激活的 V200。
-- ============================================================

BEGIN;

DELETE FROM pj_import_template WHERE template_code = 'ATTENDANCE';

INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000020,
    'ATTENDANCE',
    'V200',
    '钉钉·考勤月度汇总',
    'ATTENDANCE',
    'EXCEL',
    '月度汇总', 3, 5,
    '[
        {"source_column":"A","source_header":"姓名","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"考勤组","target_field":"attendanceGroup","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"部门","target_field":"deptName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"职位","target_field":"position","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"UserId","target_field":"userId","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"出勤天数","target_field":"attendDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"休息天数","target_field":"restDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"迟到次数","target_field":"lateCount","data_type":"INT","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"J","source_header":"迟到时长","target_field":"lateMinutes","data_type":"INT","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"上班缺卡次数","target_field":"missingCardCount","data_type":"INT","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"旷工天数","target_field":"absentDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"M","source_header":"休息日加班","target_field":"weekendOvertime","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"N","source_header":"节假日加班","target_field":"holidayOvertime","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": [
            {"field":"employeeCode","rule":"not_blank","message":"工号不能为空（钉钉后台需为员工维护工号，且与员工档案一致）"}
        ]
    }'::jsonb,
    '钉钉考勤后台导出的《月度汇总》Excel 原文件直接上传，无需下载模板改写；请保留原始两行表头，归属月选择文件统计日期所在月份。',
    'ATTENDANCE_DINGTALK_202607',
    true, '2026-09-18'::date, NULL,
    '客户标准表：两行表头（第3行字段名/第4行每日日期），数据从第5行开始；O列起每日考勤结果随当月天数动态展开，不做字段映射',
    'admin', now(), NULL, now()
);

COMMIT;
