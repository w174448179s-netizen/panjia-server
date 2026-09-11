-- =====================================================
-- KE_SIGNED 模板 V200：贝壳·经纪人业绩结算明细（结佣业绩·应收+实收）
-- 数据来源：经纪人业绩明细表-246555.xlsx 第 4 个 sheet「经纪人业绩结算明细表」
-- 两行表头：第 1 行分组、第 2 行列名，数据从第 3 行开始（header_row/data_start_row 为 1-based）
-- 共 30 列 A-AD
-- 引擎消费 10 字段：arriveMonth/bizType/orderNo/contractNo/roleSysNo/roleName/roleType/
-- shareRatio/currentReceivable/currentReceived；其余 20 列随 rawJson 归档
-- 幂等：重复执行不产生重复行（ON CONFLICT DO NOTHING）
-- =====================================================

BEGIN;

-- 停用旧版本 V100
UPDATE pj_import_template SET is_active = false WHERE source_type = 'KE_SIGNED' AND template_version = 'V100';

-- 插入新版本 V200
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
    -- column_mapping: 30 列 A-AD（data_type 为 ColumnMapping @JsonProperty 键名；
    -- target_field 前 10 个为引擎消费字段：arriveMonth/bizType/orderNo/contractNo/roleSysNo/
    -- roleName/roleType/shareRatio/currentReceivable/currentReceived，其余为 raw_json 归档字段）
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
      {"source_column":"M","source_header":"业绩比例","target_field":"shareRatio","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
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
