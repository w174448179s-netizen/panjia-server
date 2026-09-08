-- ============================================================
-- 盘家智管 · 数据导入域 · pj_import_template 种子数据
-- 依据：AI 任务卡 01 Task-0-1 + Phase0 详细设计 V3.0 §1.8
-- 版本号说明：任务卡概念版本 V2，全局实际 V100003
-- ============================================================

-- 雪花 ID 前缀：17614 + 模板编号（与菜单 ID 段区分，菜单用 17614，模板用 17615）

-- ============================================================
-- 1. 贝壳业绩导入模板（SHELL_PERFORMANCE V2）
-- 对接贝壳系统导出的业绩 Excel（立房通 202607 版本）
-- 列映射：A=工号 B=经纪人姓名 C=业绩金额 D=签约日期 E=业务类型
-- 校验规则：文件级 max_rows + 行级 金额>0 + 业务类型字典校验
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000001,
    'SHELL_PERFORMANCE',
    'V2',
    '贝壳业绩导入模板（立房通 202607）',
    'SHELL',
    'EXCEL',
    '业绩明细',
    1,
    2,
    -- column_mapping: 贝壳导出 Excel 列映射
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"经纪人姓名","target_field":"agentName","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"业绩金额(万元)","target_field":"performanceAmount","data_type":"DECIMAL","required":true,"default_value":null,"transform":"multiply:10000","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"签约日期","target_field":"signDate","data_type":"DATE","required":true,"default_value":null,"transform":"date_format:yyyy-MM-dd","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"业务类型","target_field":"bizType","data_type":"STRING","required":true,"default_value":null,"transform":"lookup:panjia_biz_type","header_match_mode":"TRIM"}
    ]'::jsonb,
    -- validation_rules: 文件级 + 行级
    '{
        "file_level": [
            {"rule":"max_rows:5000","message":"单次导入不超过5000行"}
        ],
        "row_level": [
            {"field":"performanceAmount","rule":"gt:0","message":"业绩金额必须>0"},
            {"field":"bizType","rule":"dict_in:panjia_biz_type","message":"业务类型非法，允许值：{allowed}"},
            {"field":"employeeCode","rule":"not_blank","message":"工号不能为空"},
            {"field":"agentName","rule":"not_blank","message":"经纪人姓名不能为空"},
            {"field":"signDate","rule":"not_null","message":"签约日期不能为空"}
        ]
    }'::jsonb,
    '贝壳立房通系统导出的业绩明细 Excel。V2 版本对接 202607 版导出格式，业绩金额单位为万元，导入时自动乘以 10000 转为元。',
    'SHELL_LIFANGTONG_202607',
    true,
    '2026-09-01'::date,
    NULL,
    '贝壳业绩导入主模板',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 2. 贝壳业绩导入模板 V1（历史版本，已过期，用于版本并存演示）
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000002,
    'SHELL_PERFORMANCE',
    'V1',
    '贝壳业绩导入模板（立房通 202601 旧版）',
    'SHELL',
    'EXCEL',
    'Sheet1',
    1,
    2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"EXACT"},
        {"source_column":"B","source_header":"姓名","target_field":"agentName","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"EXACT"},
        {"source_column":"C","source_header":"金额","target_field":"performanceAmount","data_type":"DECIMAL","required":true,"default_value":null,"transform":"multiply:10000","header_match_mode":"EXACT"},
        {"source_column":"D","source_header":"日期","target_field":"signDate","data_type":"DATE","required":true,"default_value":null,"transform":"date_format:yyyy-MM-dd","header_match_mode":"EXACT"},
        {"source_column":"E","source_header":"类型","target_field":"bizType","data_type":"STRING","required":true,"default_value":null,"transform":"lookup:panjia_biz_type","header_match_mode":"EXACT"}
    ]'::jsonb,
    '{
        "file_level": [
            {"rule":"max_rows:5000","message":"单次导入不超过5000行"}
        ],
        "row_level": [
            {"field":"performanceAmount","rule":"gt:0","message":"业绩金额必须>0"},
            {"field":"bizType","rule":"dict_in:panjia_biz_type","message":"业务类型非法，允许值：{allowed}"}
        ]
    }'::jsonb,
    '贝壳立房通 202601 旧版导出格式，表头无括号后缀，sheet 名为 Sheet1。已被 V2 替代。',
    'SHELL_LIFANGTONG_202601',
    false,
    '2026-03-01'::date,
    '2026-08-31'::date,
    '历史版本（已过期）',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 3. 考勤数据导入模板（ATTENDANCE）
-- 人事整理的月度考勤 Excel
-- 列映射：A=工号 B=出勤天数 C=迟到次数 D=旷工天数
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000003,
    'ATTENDANCE_MONTHLY',
    'V1',
    '考勤数据导入模板',
    'ATTENDANCE',
    'EXCEL',
    NULL,
    1,
    2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"出勤天数","target_field":"attendanceDays","data_type":"INT","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"迟到次数","target_field":"lateCount","data_type":"INT","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"旷工天数","target_field":"absentDays","data_type":"DECIMAL","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [
            {"rule":"max_rows:5000","message":"单次导入不超过5000行"}
        ],
        "row_level": [
            {"field":"employeeCode","rule":"not_blank","message":"工号不能为空"},
            {"field":"attendanceDays","rule":"gte:0","message":"出勤天数必须>=0"},
            {"field":"lateCount","rule":"gte:0","message":"迟到次数必须>=0"},
            {"field":"absentDays","rule":"gte:0","message":"旷工天数必须>=0"}
        ]
    }'::jsonb,
    '人事整理的月度考勤 Excel。sheet_name 为 NULL 表示取第一个 sheet。',
    NULL,
    true,
    '2026-09-01'::date,
    NULL,
    '考勤导入主模板',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 4. 积分数据导入模板（SCORE）
-- 人事整理的月度积分 Excel
-- 列映射：A=工号 B=积分值 C=等级
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000004,
    'SCORE_MONTHLY',
    'V1',
    '积分数据导入模板',
    'SCORE',
    'EXCEL',
    NULL,
    1,
    2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"积分值","target_field":"scoreValue","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"等级","target_field":"grade","data_type":"STRING","required":true,"default_value":null,"transform":"lookup:panjia_score_grade","header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [
            {"rule":"max_rows:5000","message":"单次导入不超过5000行"}
        ],
        "row_level": [
            {"field":"employeeCode","rule":"not_blank","message":"工号不能为空"},
            {"field":"scoreValue","rule":"gte:0","message":"积分值必须>=0"},
            {"field":"grade","rule":"dict_in:panjia_score_grade","message":"等级非法，允许值：{allowed}"}
        ]
    }'::jsonb,
    '人事整理的月度积分 Excel。等级 A/B/C 通过反向字典翻译为系统枚举值。',
    NULL,
    true,
    '2026-09-01'::date,
    NULL,
    '积分导入主模板',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 5. 手动录入模板（MANUAL）
-- 占位模板，供手动录入业绩或其他数据使用
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000005,
    'MANUAL_ENTRY',
    'V1',
    '手动录入模板（占位）',
    'MANUAL',
    'EXCEL',
    NULL,
    1,
    2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"经纪人姓名","target_field":"agentName","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"业绩金额","target_field":"performanceAmount","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"签约日期","target_field":"signDate","data_type":"DATE","required":true,"default_value":null,"transform":"date_format:yyyy-MM-dd","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"业务类型","target_field":"bizType","data_type":"STRING","required":true,"default_value":null,"transform":"lookup:panjia_biz_type","header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [
            {"rule":"max_rows:5000","message":"单次导入不超过5000行"}
        ],
        "row_level": [
            {"field":"performanceAmount","rule":"gt:0","message":"业绩金额必须>0"},
            {"field":"bizType","rule":"dict_in:panjia_biz_type","message":"业务类型非法，允许值：{allowed}"}
        ]
    }'::jsonb,
    '手动录入占位模板，供算薪人员手动补充非贝壳来源的业绩数据。',
    NULL,
    true,
    '2026-09-01'::date,
    NULL,
    '手动录入占位模板',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 6. 门店成本录入模板（COST）
-- 物业水电、租金、装修款等手工录入
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000006,
    'STORE_COST',
    'V1',
    '门店成本录入模板（占位）',
    'COST',
    'EXCEL',
    NULL,
    1,
    2,
    '[]'::jsonb,
    NULL,
    '门店成本录入占位模板，列映射待业务确认后补全。物业水电、租金、装修款等手工录入。',
    NULL,
    true,
    '2026-09-01'::date,
    NULL,
    '门店成本录入占位模板',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 7. 测试模板（带 effective_from/to 生效窗口）
-- 用于验证时间窗口查询逻辑
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000007,
    'SHELL_PERFORMANCE_TEST',
    'V1',
    '测试模板（生效窗口验证）',
    'SHELL',
    'EXCEL',
    NULL,
    1,
    2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"金额","target_field":"performanceAmount","data_type":"DECIMAL","required":true,"default_value":null,"transform":"multiply:10000","header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [
            {"rule":"max_rows:100","message":"测试模板单次导入不超过100行"}
        ],
        "row_level": [
            {"field":"performanceAmount","rule":"gt:0","message":"业绩金额必须>0"}
        ]
    }'::jsonb,
    '测试模板：验证 effective_from/to 时间窗口查询逻辑。此模板仅在 2026-09-01 ~ 2026-09-30 生效。',
    'TEST',
    true,
    '2026-09-01'::date,
    '2026-09-30'::date,
    '测试模板（生效窗口验证）',
    'admin',
    now(),
    NULL,
    now()
);
