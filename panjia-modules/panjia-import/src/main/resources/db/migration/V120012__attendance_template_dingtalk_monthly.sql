-- ============================================================
-- V120012: 考勤（ATTENDANCE）导入模板——钉钉《月度汇总》V200 最终态
--          + 原始考勤表补 leave_days 列
--
-- （原 V120012~V120015 四个演进迁移已合并为本文件，仅适用于全新初始化）
--
-- 标准文件：成都市花照天街房地产经纪有限公司_月度汇总_YYYYMMDD-YYYYMMDD.xlsx
--
-- 文件结构（单 sheet「月度汇总」）：
--   第 1 行：标题「月度汇总 统计日期：...」（合并单元格，跳过）
--   第 2 行：报表生成时间（合并单元格，跳过）
--   第 3 行：表头字段名（A-P 固定指标列；Q 列起合并为「考勤结果」）
--   第 4 行：考勤结果子表头（1/2/3/六/日... 每日一列，随月份天数动态展开；
--            H-I 列主表头合并为「请假」，子表头为「事假(天)」「病假(天)」）
--   第 5 行起：数据，一行一人一月
--
-- 映射策略：
--   A-P 共 16 列固定指标做字段映射。多行表头由解析器（XlsxFileParser）合并
--   （子表头非空值覆盖主表头），合并后 H=事假(天)、I=病假(天)，可分别映射。
--   Q 列起每日考勤结果文本（如「A班次:正常」「随心假...」）列数随月变化（28~31 列），
--   不做静态映射，随原始文件归档留痕。
--   月度汇总行内无日期列，归属月取导入时选择的批次期间（AttendanceDataSource 归一为当月 1 日）。
--   请假天数口径：事假 + 病假 合计参与算薪扣款，存入 pj_import_raw_attendance.leave_days，
--   并在归一化 extraJson 中带出供薪酬域消费。
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
        {"source_column":"H","source_header":"事假(天)","target_field":"personalLeaveDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"病假(天)","target_field":"sickLeaveDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"J","source_header":"休息天数","target_field":"restDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"迟到次数","target_field":"lateCount","data_type":"INT","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"迟到时长","target_field":"lateMinutes","data_type":"INT","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"M","source_header":"上班缺卡次数","target_field":"missingCardCount","data_type":"INT","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"N","source_header":"旷工天数","target_field":"absentDays","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"O","source_header":"休息日加班","target_field":"weekendOvertime","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"P","source_header":"节假日加班","target_field":"holidayOvertime","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"}
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
    '客户标准表：两行表头（第3行字段名/第4行每日日期，H-I 子表头为事假/病假天数），数据从第5行开始；A-P 固定指标映射，Q列起每日考勤结果随当月天数动态展开，不做字段映射',
    'admin', now(), NULL, now()
);

-- 原始考勤记录表：请假天数（事假+病假合计，参与算薪）
ALTER TABLE pj_import_raw_attendance
    ADD COLUMN IF NOT EXISTS leave_days NUMERIC(10,2);

COMMENT ON COLUMN pj_import_raw_attendance.leave_days IS '请假天数（事假+病假合计，参与算薪）';

COMMIT;
