-- ============================================================
-- 导入域 V2.0 模板种子（五类交易业务单据：KE_SIGNED V200 + KE_NEW_SIGN/ATTENDANCE/POINTS/OTHERS V100）
-- 段位：V120005（2026-09-11 由 V100007 重命名；同日吸收 V120007（原 V100023）KE_SIGNED V200 段并删除该脚本）
-- 依据：导入域详细设计_V2.0.md
-- 说明：员工主数据模板已迁至 people 域（员工导入模板表）；
--       V120003 为旧版 STORE_COST 保留种子，其余旧模板（SHELL/ATTENDANCE_MONTHLY/SCORE/MANUAL/TEST）
--       已在 V120003 顶部 DELETE 兜底清理，其业务由本脚本替代；
--       KE_SIGNED 不再有 V100 行（历史版本段已删除），直接落 V200。
-- ============================================================

BEGIN;

-- 1. 贝壳新签（KE_NEW_SIGN）
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
        {"source_column":"F","source_header":"业绩比例","target_field":"shareRatio","data_type":"DECIMAL","required":false,"default_value":null,"transform":"percent","header_match_mode":"TRIM"},
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

-- 2. 考勤（ATTENDANCE）
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

-- 3. 积分（POINTS）
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

-- 4. 手工录入（OTHERS）
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

-- 5. 贝壳结佣（KE_SIGNED）V200：贝壳·经纪人业绩结算明细（结佣业绩·应收+实收）
-- 数据来源：经纪人业绩明细表-246555.xlsx 第 4 个 sheet「经纪人业绩结算明细表」
-- 两行表头：第 1 行分组、第 2 行列名，数据从第 3 行开始（header_row/data_start_row 为 1-based）
-- 共 30 列 A-AD；引擎消费 10 字段：arriveMonth/bizType/orderNo/contractNo/roleSysNo/roleName/roleType/
-- shareRatio/currentReceivable/currentReceived；其余 20 列随 rawJson 归档
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000016,
    'KE_SIGNED', 'V200',
    '贝壳·经纪人业绩结算明细（结佣业绩·应收+实收）',
    'KE_SIGNED', 'EXCEL',
    '经纪人业绩结算明细表', 2, 3,
    '[
      {"source_column":"A","source_header":"结算月","target_field":"arriveMonth","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"B","source_header":"业务类型","target_field":"bizType","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"C","source_header":"订单号","target_field":"orderNo","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"D","source_header":"合同号","target_field":"contractNo","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"E","source_header":"签约(成销)时间","target_field":"signDate","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"F","source_header":"过户日期（仅二手）","target_field":"transferDate","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"G","source_header":"完结日期（仅二手）","target_field":"completeDate","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"H","source_header":"物业地址","target_field":"propertyAddress","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"I","source_header":"费用项","target_field":"feeItem","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"J","source_header":"角色人系统号","target_field":"roleSysNo","data_type":"STRING","required":true,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"K","source_header":"角色人姓名","target_field":"roleName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"L","source_header":"角色类型","target_field":"roleType","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"M","source_header":"业绩比例","target_field":"shareRatio","data_type":"DECIMAL","required":false,"default_value":null,"transform":"percent","header_match_mode":"TRIM"},
      {"source_column":"N","source_header":"当月应收业绩","target_field":"currentReceivable","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
      {"source_column":"O","source_header":"总应收业绩","target_field":"totalReceivable","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
      {"source_column":"P","source_header":"当月实收业绩","target_field":"currentReceived","data_type":"DECIMAL","required":true,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
      {"source_column":"Q","source_header":"总实收业绩","target_field":"totalReceived","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
      {"source_column":"R","source_header":"合同当月到账金额","target_field":"contractMonthlyArrivalAmount","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
      {"source_column":"S","source_header":"合同总到账金额","target_field":"contractTotalArrivalAmount","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
      {"source_column":"T","source_header":"合同当月支付手续费","target_field":"monthlyHandlingFee","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
      {"source_column":"U","source_header":"合同总支付手续费","target_field":"totalHandlingFee","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
      {"source_column":"V","source_header":"备注","target_field":"remark","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"W","source_header":"店组编码","target_field":"deptCode","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"X","source_header":"店组名称","target_field":"deptName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"Y","source_header":"门店编码","target_field":"storeCode","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"Z","source_header":"门店名称","target_field":"storeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"AA","source_header":"加盟商编码","target_field":"franchiserCode","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"AB","source_header":"加盟商mdmCode","target_field":"franchiserMdmCode","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"AC","source_header":"加盟商名称","target_field":"franchiserName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
      {"source_column":"AD","source_header":"分账主体理房通商户号","target_field":"splitAccountNo","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:50000","message":"单次导入不超过50000行"}],
        "row_level": [
            {"field":"roleSysNo","rule":"not_blank","message":"角色人系统号不能为空"},
            {"field":"currentReceived","rule":"gte:0","message":"当月实收业绩必须>=0"}
        ]
    }'::jsonb,
    '贝壳·经纪人业绩明细表第 4 个 sheet「经纪人业绩结算明细表」原始导出，直接上传原文件，无需下载模板',
    'KE_SIGNED_202608',
    true, '2026-09-01'::date, NULL,
    '对应贝壳·经纪人业绩明细表（两行表头：第1行分组、第2行列名，数据从第3行开始，共30列A-AD）',
    'admin', now(), NULL, now()
) ON CONFLICT (template_code, template_version) DO NOTHING;

COMMIT;
