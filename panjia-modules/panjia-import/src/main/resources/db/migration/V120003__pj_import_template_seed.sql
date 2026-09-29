-- ============================================================
-- 盘家智管 · 五类交易单据导入模板 seed 最终态（清库重建版）
-- STORE_COST V1 + KE_SIGNED/ATTENDANCE/POINTS V200 + OTHERS V100 + 积分/考勤简版 V100S
-- 合并自 V120003, V120005, V120008, V120012, V120016, V120017, V120018, V120019, V120020, V120021, V120028
-- 说明：
--   1) KE_NEW_SIGN 已按 V120007 删除，不再 seed；
--   2) 全部 jsonb_set/UPDATE patch 链已逐模板按版本顺序推演为最终 JSONB，
--      每个模板行只写一条最终 INSERT；
--   3) 列清单与原 V120003/V120005 的 INSERT 列清单完全一致；
--   4) V120019 的 REPLACE patch 对 jsonb 键序未命中（V120020 已证实），
--      最终态以 V120020 的 jsonb 合并结果为准（填报时间 → submitTime/DATETIME）。
-- ============================================================

-- ============================================================
-- 1. 门店成本录入模板（STORE_COST V1，V120003 原样）
-- 物业水电、租金、装修款等手工录入
-- 列映射待业务确认后补全（占位模板）
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
-- 2. 贝壳结佣（KE_SIGNED V200，V120005 种子 + V120008 实收允许负数 + V120028 角色人系统号改非必填）
-- 贝壳·经纪人业绩结算明细（结佣业绩·应收+实收）
-- 两行表头：第 1 行分组、第 2 行列名，数据从第 3 行开始（header_row/data_start_row 为 1-based）
-- 共 30 列 A-AD；J 列 roleSysNo required=false（空经纪人行按店组挂 99999 虚拟人）
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000016,
    'KE_SIGNED',
    'V200',
    '贝壳·经纪人业绩结算明细（结佣业绩·应收+实收）',
    'KE_SIGNED',
    'EXCEL',
    '经纪人业绩结算明细表',
    2,
    3,
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
      {"source_column":"J","source_header":"角色人系统号","target_field":"roleSysNo","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
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
            {"field":"currentReceived","rule":"not_blank","message":"当月实收业绩不能为空（退单可为负）"}
        ]
    }'::jsonb,
    '贝壳·经纪人新签明细表第 4 个 sheet「经纪人业绩结算明细表」原始导出，直接上传原文件，无需下载模板',
    'KE_SIGNED_202608',
    true,
    '2026-09-01'::date,
    NULL,
    '贝壳新签导入模板 V1.1（角色人系统号改非必填，空经纪人行按店组挂门店 99999 虚拟人）',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 3. 考勤（ATTENDANCE V200，V120012 钉钉《月度汇总》+ V120018 工号改非必填）
-- 标准文件：成都市花照天街房地产经纪有限公司_月度汇总_YYYYMMDD-YYYYMMDD.xlsx
-- 两行表头（第3行字段名/第4行每日日期，H-I 子表头为事假/病假天数），数据从第5行开始
-- A-P 固定指标映射；D 列工号 required=false（宽容空值口径）
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000020,
    'ATTENDANCE',
    'V200',
    '钉钉·考勤月度汇总',
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
        "row_level": []
    }'::jsonb,
    '钉钉考勤后台导出的《月度汇总》Excel 原文件直接上传，无需下载模板改写；请保留原始两行表头，归属月选择文件统计日期所在月份。宽容空值：工号为空的行照常导入但不统计（无法归属到人，需在钉钉后台补齐工号后重新导入）。',
    'ATTENDANCE_DINGTALK_202607',
    true,
    '2026-09-18'::date,
    NULL,
    '客户标准表：两行表头（第3行字段名/第4行每日日期，H-I 子表头为事假/病假天数），数据从第5行开始；A-P 固定指标映射，Q列起每日考勤结果随当月天数动态展开，不做字段映射',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 4. 积分（POINTS V200，V120016 日报5.0版 + V120017 宽容空值 + V120020 填报时间改 submitTime/DATETIME）
-- 标准文件：二手积分日报5.0版.xlsx（钉钉智能填报导出）
-- 两行表头（第1行主表头/第2行子表头），数据从第3行开始，一人一天一行
-- E 列填报时间落 submitTime（DATETIME，提交窗口判定），全列 required=false（宽容空值口径）
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000030,
    'POINTS',
    'V200',
    '二手积分日报5.0版',
    'POINTS',
    'EXCEL',
    '二手积分日报5.0版',
    1,
    3,
    '[
        {"source_column":"A","source_header":"User ID","target_field":"userId","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"工号","target_field":"employeeCode","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"填报人","target_field":"reporterName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"部门","target_field":"deptName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"填报时间","target_field":"submitTime","data_type":"DATETIME","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"✨今日总积分","target_field":"score","data_type":"DECIMAL","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    '{
        "file_level": [{"rule":"max_rows:10000","message":"单次导入不超过10000行"}],
        "row_level": []
    }'::jsonb,
    '《二手积分日报5.0版》钉钉智能填报导出 Excel 原文件直接上传，无需下载模板改写；请保留原始两行表头，归属月选择填报日期所在月份。宽容空值：工号空行导入但不统计（无法归属到人），积分空行只计出勤天数，日期空行只计总积分。',
    'SCORE_DAILY_REPORT_V50',
    true,
    '2026-09-19'::date,
    NULL,
    '客户标准表：两行表头（第1行主表头/第2行子表头），数据从第3行开始，一人一天一行；B=工号（必填）、E=填报时间（中文日期时间）、H=今日总积分；其余列不映射随原始文件留痕',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 5. 手工录入（OTHERS V100，V120005 原样）
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000015,
    'OTHERS',
    'V100',
    '手工录入模板（其他费用）',
    'OTHERS',
    'EXCEL',
    NULL,
    1,
    2,
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
    true,
    '2026-09-01'::date,
    NULL,
    'V1.4 手工录入模板',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 6. 积分简版（POINTS_SIMPLE V100S，V120021 原样；未接积分日报系统的门店手工填写）
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000017,
    'POINTS_SIMPLE',
    'V100S',
    '积分导入模板（简版）',
    'POINTS',
    'EXCEL',
    NULL,
    1,
    2,
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
    true,
    '2026-09-20'::date,
    NULL,
    '列名与日报原始文件不同（今日积分 vs ✨今日总积分），互不冲突；上传时按表头自动匹配',
    'admin',
    now(),
    NULL,
    now()
);

-- ============================================================
-- 7. 考勤简版（ATTENDANCE_SIMPLE V100S，V120021 原样；未接钉钉考勤的门店手工填写）
-- ============================================================
INSERT INTO pj_import_template (id, template_code, template_version, template_name, source_type, file_type, sheet_name, header_row, data_start_row, column_mapping, validation_rules, description, source_file_version, is_active, effective_from, effective_to, remark, created_by, created_at, updated_by, updated_at)
VALUES (
    1761500000000000018,
    'ATTENDANCE_SIMPLE',
    'V100S',
    '考勤导入模板（简版）',
    'ATTENDANCE',
    'EXCEL',
    NULL,
    1,
    2,
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
    true,
    '2026-09-20'::date,
    NULL,
    '列名为钉钉月度汇总的子集；钉钉原文件上传时按列数最多者优先匹配原模板',
    'admin',
    now(),
    NULL,
    now()
);
