-- ============================================================
-- 导入域 V2.0 模板种子（四类交易业务单据 V100 版本：KE_NEW_SIGN/ATTENDANCE/POINTS/OTHERS）
-- 段位：V120005（2026-09-11 由 V100007 重命名）
-- 依据：导入域详细设计_V2.0.md
-- 说明：员工主数据模板已迁至 people 域（员工导入模板表）；
--       V120003 为旧版 STORE_COST 保留种子，其余旧模板（SHELL/ATTENDANCE_MONTHLY/SCORE/MANUAL/TEST）
--       已在 V120003 顶部 DELETE 兜底清理，其业务由本脚本 V100 版本与 V120007 V200 版本替代。
-- 注：本脚本仅插入 KE_NEW_SIGN/ATTENDANCE/POINTS/OTHERS 四类 V100 模板；
--     KE_SIGNED V100 已由 V120007 用 V200 替代（V120007 顶部 UPDATE is_active=false）。
-- ============================================================

BEGIN;

-- 1. 贝壳结佣（KE_SIGNED）
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000011,
    'KE_SIGNED',
    'V100',
    '贝壳·理房通到账明细（结佣）',
    'KE_SIGNED',
    'EXCEL',
    NULL, 1, 2,
    '[
        {"source_column":"A","source_header":"到账月","target_field":"arriveMonth","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"业务类型","target_field":"bizType","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"订单号","target_field":"orderNo","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"合同号","target_field":"contractNo","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"角色人系统号","target_field":"roleSysNo","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"角色类型","target_field":"roleType","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"业绩比例","target_field":"shareRatio","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"当月应收业绩","target_field":"currentReceivable","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"当月实收业绩","target_field":"currentReceived","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": [
            {"field":"employeeExternalCode","rule":"not_blank","message":"角色人系统号不能为空"},
            {"field":"receivedAmount","rule":"gte:0","message":"实收业绩必须>=0"}
        ]
    }'::jsonb,
    '贝壳理房通到账明细，用于个人结佣提成。source_key 由 biz_type 决定（一手房用订单号，二手用合同号）。',
    'KE_SIGNED_202609',
    true, '2026-09-01'::date, NULL,
    'V1.4 结佣模板', 'admin', now(), NULL, now()
);

-- 2. 贝壳新签（KE_NEW_SIGN）
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000012,
    'KE_NEW_SIGN',
    'V100',
    '贝壳·新签业绩明细（应收）',
    'KE_NEW_SIGN',
    'EXCEL',
    NULL, 1, 2,
    '[
        {"source_column":"A","source_header":"到账月","target_field":"arriveMonth","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"业务类型","target_field":"bizType","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"合同号","target_field":"contractNo","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"角色人系统号","target_field":"roleSysNo","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"角色类型","target_field":"roleType","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"业绩比例","target_field":"shareRatio","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"当月应收业绩","target_field":"currentReceivable","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": [
            {"field":"employeeExternalCode","rule":"not_blank","message":"角色人系统号不能为空"},
            {"field":"receivableAmount","rule":"gte:0","message":"应收业绩必须>=0"}
        ]
    }'::jsonb,
    '贝壳新签业绩明细（应收），用于店长团队提成/保底、总监门店提成。',
    'KE_NEW_SIGN_202609',
    true, '2026-09-01'::date, NULL,
    'V1.4 新签模板', 'admin', now(), NULL, now()
);

-- 3. 考勤（ATTENDANCE）
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000013,
    'ATTENDANCE',
    'V100',
    '考勤数据导入模板',
    'ATTENDANCE',
    'EXCEL',
    NULL, 1, 2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"考勤日期","target_field":"attendDate","data_type":"DATE","required":true,"default_value":null,"transform":"date_format:yyyy-MM-dd","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"迟到次数","target_field":"lateCount","data_type":"INT","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"旷工天数","target_field":"absentDays","data_type":"DECIMAL","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": [
            {"field":"employeeExternalCode","rule":"not_blank","message":"工号不能为空"}
        ]
    }'::jsonb,
    '人事整理的月度考勤 Excel。',
    NULL,
    true, '2026-09-01'::date, NULL,
    'V1.4 考勤模板', 'admin', now(), NULL, now()
);

-- 4. 积分（POINTS）
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000014,
    'POINTS',
    'V100',
    '积分数据导入模板',
    'POINTS',
    'EXCEL',
    NULL, 1, 2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"积分日期","target_field":"pointDate","data_type":"DATE","required":true,"default_value":null,"transform":"date_format:yyyy-MM-dd","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"积分值","target_field":"score","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": [
            {"field":"employeeExternalCode","rule":"not_blank","message":"工号不能为空"},
            {"field":"receivedAmount","rule":"gte:0","message":"积分值必须>=0"}
        ]
    }'::jsonb,
    '人事整理的月度积分 Excel。',
    NULL,
    true, '2026-09-01'::date, NULL,
    'V1.4 积分模板', 'admin', now(), NULL, now()
);

-- 5. 手工录入（OTHERS）
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000015,
    'OTHERS',
    'V100',
    '手工录入模板（其他费用）',
    'OTHERS',
    'EXCEL',
    NULL, 1, 2,
    '[
        {"source_column":"A","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"费用类型","target_field":"itemType","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"金额","target_field":"amount","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": [
            {"field":"employeeExternalCode","rule":"not_blank","message":"工号不能为空"},
            {"field":"receivedAmount","rule":"gte:0","message":"金额必须>=0"}
        ]
    }'::jsonb,
    '手工录入兜底（商业保险、宿舍管理、其他支出等）。',
    NULL,
    true, '2026-09-01'::date, NULL,
    'V1.4 手工录入模板', 'admin', now(), NULL, now()
);

COMMIT;
