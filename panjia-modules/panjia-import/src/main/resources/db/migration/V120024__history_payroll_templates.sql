-- ============================================================
-- 历史工资导入（HISTORY_PAYROLL）标准管线模板 seed
-- 段位：V120024（2026-09-24）
-- 依据：真实天街工资表（天街工资表2026.07.xlsx）逐 sheet 表头核对 + 已批准重构计划 D1
-- 设计：
--   · 同 source_type=HISTORY_PAYROLL 10 套激活模板，引擎循环全部模板逐 sheet 解析（一模板一 sheet）
--   · 「绩效和扣款」为左右双表结构（两个「姓名」列），拆 3 套模板各自解析同一 sheet：
--       左半 HIST_PERF_LEFT（姓名@1）、右半 HIST_PERF_RIGHT（姓名@2）、积分口径 HIST_SCORE（姓名@2）
--   · 「店长工资」R1 为标题行（R2 才是表头）→ header_row=2 / data_start_row=3；
--     R1 透视区残留含「门店」重名 → 全列加 @N occurrence 语法固定取第 1 次出现
--   · 「人事数据」拆 2 套：HIST_HR_ATT（考勤口径 → RawAttendance）、HIST_HR_PATCH（工资补丁 → RawPayroll(HR)）
--   · 新签/结佣业绩列结构相同，仅 sheetName 不同（RawSigned 复用，recordType 标记在 raw_json）
--   · 历史数据脏（「不考核」等文本、空行、合并行残留）：全部列 type=STRING + required=false，
--     宽容解析放消费端/归一化分支；员工匹配按姓名在归一化期批量富化（模板不卡必填）
--   · 「宿舍管理费\xa0」表头含不间断空格（U+00A0），JSON 内以 \u00a0 转义精确匹配
-- target_field 约定（消费端依赖，勿随意改名）：
--   金额族字段由 payroll 域 PayrollArchiveHandler 按 extraJson 合并进 pj_payroll_detail；
--   业绩族字段由 performance 域快照进 pj_perf_fact（amount85 为折算后金额，归一化期 ÷ 折算因子）。
-- 模板 ID 段：1761500000000000031 ~ 1761500000000000040（V120021 已占用至 ...030）
-- ============================================================

BEGIN;

-- 1. 工资表（HIST_PAYROLL → RawPayroll(WAGE)）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000031,
    'HIST_PAYROLL', 'HIST_MULTI_V1',
    '历史工资·工资表', 'HISTORY_PAYROLL', 'EXCEL',
    '工资表', 1, 2,
    '[
        {"source_column":"A","source_header":"门店","target_field":"storeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"姓名","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"职级","target_field":"positionLevel","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"职位","target_field":"position","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"当月新签业绩（0.94、0.96）","target_field":"newSignAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"当月新签业绩提成比列","target_field":"newSignRatio","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"绩效提成扣点","target_field":"perfDeductPoint","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"个人提点奖励","target_field":"personalBonus","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"当月最终提成比列","target_field":"finalRatio","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"J","source_header":"结佣业绩","target_field":"commissionAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"提成比例","target_field":"commissionRatio","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"提成金额","target_field":"commissionFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"M","source_header":"招聘奖励","target_field":"recruitReward","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"N","source_header":"底薪","target_field":"baseSalary","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"O","source_header":"绩效","target_field":"perfAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"P","source_header":"考勤扣款","target_field":"attendanceFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"Q","source_header":"积分扣款","target_field":"pointsFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"R","source_header":"应发工资","target_field":"grossSalary","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"S","source_header":"社保扣款","target_field":"socialFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"T","source_header":"公积金扣款","target_field":"housingFundFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"U","source_header":"往月负工资","target_field":"prevNegativeSalary","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"V","source_header":"商业保险","target_field":"commercialInsurance","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"W","source_header":"宿舍管理费","target_field":"dormitoryFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"X","source_header":"工资合计","target_field":"salaryTotal","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"Y","source_header":"实发工资","target_field":"actualSalary","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"Z","source_header":"个税扣除","target_field":"taxDeduction","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"AA","source_header":"最终发放","target_field":"finalPay","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「工资表」sheet（经纪人+店长主表，28列含店长级）：姓名按归一化期批量匹配工号，全字段随 raw_json 留痕并由 payroll 域合并进工资明细。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '工资族 → pj_import_raw_payroll(sheet_kind=WAGE)',
    'admin', now(), NULL, now()
);

-- 2. 总监工资（HIST_DIRECTOR → RawPayroll(DIRECTOR)）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000032,
    'HIST_DIRECTOR', 'HIST_MULTI_V1',
    '历史工资·总监工资', 'HISTORY_PAYROLL', 'EXCEL',
    '总监工资', 1, 2,
    '[
        {"source_column":"A","source_header":"姓名","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"组别","target_field":"storeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"新签业绩","target_field":"newSignAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"社保业绩","target_field":"socialAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"合计","target_field":"totalAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"提成比例","target_field":"ratio","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"提成金额","target_field":"bonusAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"底薪","target_field":"baseSalary","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"全勤","target_field":"fullAttendance","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"J","source_header":"绩效","target_field":"perfAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"结佣业绩","target_field":"commissionAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"业绩提成","target_field":"perfBonus","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"M","source_header":"招聘提成","target_field":"recruitBonus","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"N","source_header":"社保","target_field":"socialFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"O","source_header":"公积金","target_field":"housingFundFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"P","source_header":"商业保险","target_field":"commercialInsurance","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"Q","source_header":"应发工资","target_field":"grossSalary","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"R","source_header":"个税","target_field":"taxDeduction","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"S","source_header":"实发工资","target_field":"actualSalary","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「总监工资」sheet（19列）：总监跨店合并行（后续行仅组别无姓名）由归一化分支按「仅取有姓名行」过滤。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '工资族 → pj_import_raw_payroll(sheet_kind=DIRECTOR)',
    'admin', now(), NULL, now()
);

-- 3. 店长工资（HIST_MANAGER → RawPayroll(MANAGER)，header_row=2：R1 是标题行）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000033,
    'HIST_MANAGER', 'HIST_MULTI_V1',
    '历史工资·店长工资', 'HISTORY_PAYROLL', 'EXCEL',
    '店长工资', 2, 3,
    '[
        {"source_column":"A","source_header":"门店@1","target_field":"storeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"姓名@1","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"职级@1","target_field":"positionLevel","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"7月新签团队业绩（85、96、94）@1","target_field":"teamNewSignAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"社保业绩扣款@1","target_field":"socialDeduction","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"新签与结佣差额@1","target_field":"diffAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"团队计薪业绩@1","target_field":"teamSalaryAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"提成比例@1","target_field":"ratio","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"团队提成金额@1","target_field":"teamBonus","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"J","source_header":"当月个人新签业绩提成@1","target_field":"personalBonus","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"合计@1","target_field":"totalAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"保底@1","target_field":"guaranteedSalary","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"M","source_header":"补足8000部分@1","target_field":"makeup8000","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"N","source_header":"其他扣款@1","target_field":"otherDeduction","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"O","source_header":"店长工资@1","target_field":"managerSalary","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「店长工资」sheet（R1 标题「7月店长工资」+右侧透视区残留，R2 才是表头 → header_row=2）：R1 残留含「门店」重名列，全列加 @1 occurrence 固定取第 1 次出现。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '工资族 → pj_import_raw_payroll(sheet_kind=MANAGER)',
    'admin', now(), NULL, now()
);

-- 4. 人事数据·考勤口径（HIST_HR_ATT → RawAttendance，月度汇总行）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000034,
    'HIST_HR_ATT', 'HIST_MULTI_V1',
    '历史工资·人事数据（考勤口径）', 'HISTORY_PAYROLL', 'EXCEL',
    '人事数据', 1, 2,
    '[
        {"source_column":"A","source_header":"门店名称","target_field":"storeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"姓名","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"出勤天数","target_field":"attendDays","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"考勤扣款","target_field":"leaveAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"J","source_header":"考勤详情","target_field":"attendanceDetail","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「人事数据」sheet 考勤口径（出勤天数/考勤扣款/考勤详情）：落 pj_import_raw_attendance 月度汇总行（attendDate=归属月首日），迟到次数由考勤详情正则解析（迟到N次）。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '考勤口径 → pj_import_raw_attendance',
    'admin', now(), NULL, now()
);

-- 5. 人事数据·工资补丁（HIST_HR_PATCH → RawPayroll(HR)）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000035,
    'HIST_HR_PATCH', 'HIST_MULTI_V1',
    '历史工资·人事数据（补丁）', 'HISTORY_PAYROLL', 'EXCEL',
    '人事数据', 1, 2,
    '[
        {"source_column":"B","source_header":"姓名","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"成都社保扣款","target_field":"socialFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"公积金扣款","target_field":"housingFundFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"M","source_header":"宿舍管理费\u00a0","target_field":"dormitoryFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"N","source_header":"积分扣款","target_field":"pointsFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"O","source_header":"新人绩效","target_field":"newcomerPerf","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"P","source_header":"入职未满半年需要扣除基地训+从业资格证费用","target_field":"trainingDeduction","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"Q","source_header":"新人带教","target_field":"mentorFee","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"R","source_header":"其他扣款","target_field":"otherDeduction","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「人事数据」sheet 工资补丁口径（社保/公积金/宿舍管理费/积分扣款/新人绩效等）：落 pj_import_raw_payroll(sheet_kind=HR)，由 payroll 域合并进工资明细。「宿舍管理费」表头含 U+00A0 不间断空格。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '工资族 → pj_import_raw_payroll(sheet_kind=HR)',
    'admin', now(), NULL, now()
);

-- 6. 绩效和扣款·左半（HIST_PERF_LEFT → RawPayroll(PERF_LEFT)）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000036,
    'HIST_PERF_LEFT', 'HIST_MULTI_V1',
    '历史工资·绩效和扣款（左半）', 'HISTORY_PAYROLL', 'EXCEL',
    '绩效和扣款', 1, 2,
    '[
        {"source_column":"A","source_header":"门店","target_field":"storeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"姓名@1","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"动态考核","target_field":"dynamicDeduction","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"积分考核","target_field":"scoreDeduction","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"其他扣款","target_field":"otherDeduction","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「绩效和扣款」sheet 左半表（col1-5：动态考核/积分考核/其他扣款）：该 sheet 为左右双表且有两个「姓名」列，用 @1 occurrence 语法取第 1 个姓名列。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '工资族 → pj_import_raw_payroll(sheet_kind=PERF_LEFT)',
    'admin', now(), NULL, now()
);

-- 7. 绩效和扣款·右半（HIST_PERF_RIGHT → RawPayroll(PERF_RIGHT)）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000037,
    'HIST_PERF_RIGHT', 'HIST_MULTI_V1',
    '历史工资·绩效和扣款（右半）', 'HISTORY_PAYROLL', 'EXCEL',
    '绩效和扣款', 1, 2,
    '[
        {"source_column":"J","source_header":"姓名@2","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"N","source_header":"绩效等级","target_field":"perfGrade","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"O","source_header":"绩效提成点","target_field":"perfBonusPoint","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"P","source_header":"未买社保提成点","target_field":"noSocialBonusPoint","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"Q","source_header":"未完成电话考核提成点","target_field":"phoneBonusPoint","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"R","source_header":"合计","target_field":"totalAmount","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「绩效和扣款」sheet 右半表（col10-18：绩效等级/绩效提成点/合计）：用 @2 occurrence 语法取第 2 个姓名列。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '工资族 → pj_import_raw_payroll(sheet_kind=PERF_RIGHT)',
    'admin', now(), NULL, now()
);

-- 8. 绩效和扣款·积分口径（HIST_SCORE → RawPoints，月度总量行）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000038,
    'HIST_SCORE', 'HIST_MULTI_V1',
    '历史工资·绩效和扣款（积分口径）', 'HISTORY_PAYROLL', 'EXCEL',
    '绩效和扣款', 1, 2,
    '[
        {"source_column":"J","source_header":"姓名@2","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"总积分","target_field":"score","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"出勤天数","target_field":"attendDays","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「绩效和扣款」sheet 积分口径（右半总积分/出勤天数）：落 pj_import_raw_points 月度总量行（pointDate=归属月首日），与日报聚合逻辑在 HISTORY_PAYROLL 分支区分。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '积分口径 → pj_import_raw_points',
    'admin', now(), NULL, now()
);

-- 9. 新签业绩（HIST_NEW_SIGN → RawSigned，recordType=HIST_EXPECT）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000039,
    'HIST_NEW_SIGN', 'HIST_MULTI_V1',
    '历史工资·新签业绩', 'HISTORY_PAYROLL', 'EXCEL',
    '新签业绩', 1, 2,
    '[
        {"source_column":"A","source_header":"签约/认购日期","target_field":"signDate","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"合同号","target_field":"contractNo","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"类型","target_field":"bizType","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"房源地址","target_field":"propertyAddress","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"签约人","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"店组","target_field":"storeGroup","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"门店","target_field":"storeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"所属角色","target_field":"roleType","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"角色占比","target_field":"shareRatio","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"J","source_header":"85后","target_field":"amount85","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"是否结算","target_field":"settledFlag","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"结算日期","target_field":"settleDate","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「新签业绩」sheet（12列）：落 pj_import_raw_signed（raw_json.recordType=HIST_EXPECT 单发 PERF_EXPECT）。「85后」为折算后金额列，归一化期 ÷ bizType 折算因子还原原始金额。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '业绩口径 → pj_import_raw_signed(HIST_EXPECT)',
    'admin', now(), NULL, now()
);

-- 10. 结佣业绩（HIST_COMMISSION → RawSigned，recordType=HIST_REAL）
INSERT INTO pj_import_template (
    id, template_code, template_version, template_name, source_type, file_type,
    sheet_name, header_row, data_start_row,
    column_mapping, validation_rules, description, source_file_version,
    is_active, effective_from, effective_to, remark,
    created_by, created_at, updated_by, updated_at
) VALUES (
    1761500000000000040,
    'HIST_COMMISSION', 'HIST_MULTI_V1',
    '历史工资·结佣业绩', 'HISTORY_PAYROLL', 'EXCEL',
    '结佣业绩', 1, 2,
    '[
        {"source_column":"A","source_header":"签约/认购日期","target_field":"signDate","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"B","source_header":"合同号","target_field":"contractNo","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"C","source_header":"类型","target_field":"bizType","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"D","source_header":"房源地址","target_field":"propertyAddress","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"E","source_header":"签约人","target_field":"employeeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"F","source_header":"店组","target_field":"storeGroup","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"G","source_header":"门店","target_field":"storeName","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"H","source_header":"所属角色","target_field":"roleType","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"I","source_header":"角色占比","target_field":"shareRatio","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"J","source_header":"85后","target_field":"amount85","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"},
        {"source_column":"K","source_header":"是否结算","target_field":"settledFlag","data_type":"STRING","required":false,"default_value":null,"transform":"trim","header_match_mode":"TRIM"},
        {"source_column":"L","source_header":"结算日期","target_field":"settleDate","data_type":"STRING","required":false,"default_value":null,"transform":null,"header_match_mode":"TRIM"}
    ]'::jsonb,
    NULL,
    '天街工资表「结佣业绩」sheet（12列，列结构与新签业绩相同）：落 pj_import_raw_signed（raw_json.recordType=HIST_REAL 单发 PERF_REAL）。「85后」为折算后金额列，归一化期 ÷ bizType 折算因子还原原始金额。',
    'TIANJIE_202607',
    true, '2026-09-24'::date, NULL,
    '业绩口径 → pj_import_raw_signed(HIST_REAL)',
    'admin', now(), NULL, now()
);

COMMIT;
