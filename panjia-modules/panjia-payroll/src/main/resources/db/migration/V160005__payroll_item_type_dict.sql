-- ============================================================
-- 薪酬手工项类型字典（奖金类型 / 其他收入类型 / 其他支出类型）
-- 依据《薪酬与收支模块业务需求说明书 V4.6》L800-810 / L833-841 / L1006-1014；
-- 字典项可在「系统管理-字典管理」随时增删，前端录入页与列表实时取字典。
-- ============================================================

-- ---------- 一、奖金类型（panjia_payroll_bonus_type） ----------
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES (1761700000000000001, '奖金类型', 'panjia_payroll_bonus_type', 1761000000000000100, 1761100000000000001, now(), '奖金录入子类型（需求 V4.6 L800-810），可在字典管理中扩充')
ON CONFLICT (dict_type) DO UPDATE SET dict_name = EXCLUDED.dict_name, remark = EXCLUDED.remark;

DELETE FROM sys_dict_data WHERE dict_type = 'panjia_payroll_bonus_type';
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761700000000010001, 1, '优秀员工奖',   'EXCELLENT_STAFF',  'panjia_payroll_bonus_type', 'primary', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000010002, 2, '大单成交奖',   'BIG_DEAL',         'panjia_payroll_bonus_type', 'success', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000010003, 3, '团队PK奖',     'TEAM_PK',          'panjia_payroll_bonus_type', 'warning', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000010004, 4, '管理奖',       'MANAGEMENT',       'panjia_payroll_bonus_type', 'primary', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000010005, 5, '人才培养奖',   'TALENT_CULTIVATION','panjia_payroll_bonus_type', 'success', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000010006, 6, '破纪录奖',     'RECORD_BREAKING',  'panjia_payroll_bonus_type', 'danger',  'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000010007, 7, '其他临时激励', 'OTHER_TEMP',       'panjia_payroll_bonus_type', 'info',    'N', 1761000000000000100, 1761100000000000001, now());

-- ---------- 二、其他收入类型（panjia_payroll_income_type） ----------
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES (1761700000000000002, '其他收入类型', 'panjia_payroll_income_type', 1761000000000000100, 1761100000000000001, now(), '其他收支录入-收入子类型（需求 V4.6 L833-841），可在字典管理中扩充')
ON CONFLICT (dict_type) DO UPDATE SET dict_name = EXCLUDED.dict_name, remark = EXCLUDED.remark;

DELETE FROM sys_dict_data WHERE dict_type = 'panjia_payroll_income_type';
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761700000000020001, 1, '补发工资', 'SALARY_SUPPLEMENT', 'panjia_payroll_income_type', 'warning', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000020002, 2, '交通补贴', 'TRANSPORT',         'panjia_payroll_income_type', 'primary', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000020003, 3, '通讯补贴', 'COMMUNICATION',     'panjia_payroll_income_type', 'primary', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000020004, 4, '餐补',     'MEAL',              'panjia_payroll_income_type', 'primary', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000020005, 5, '加班费',   'OVERTIME',          'panjia_payroll_income_type', 'success', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000020006, 6, '个税补差', 'TAX_DIFF',          'panjia_payroll_income_type', 'info',    'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000020007, 7, '其他',     'OTHER',             'panjia_payroll_income_type', 'info',    'N', 1761000000000000100, 1761100000000000001, now());

-- ---------- 三、其他支出类型（panjia_payroll_deduct_type） ----------
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES (1761700000000000003, '其他支出类型', 'panjia_payroll_deduct_type', 1761000000000000100, 1761100000000000001, now(), '其他收支录入-支出子类型（需求 V4.6 L1006-1014），可在字典管理中扩充')
ON CONFLICT (dict_type) DO UPDATE SET dict_name = EXCLUDED.dict_name, remark = EXCLUDED.remark;

DELETE FROM sys_dict_data WHERE dict_type = 'panjia_payroll_deduct_type';
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761700000000030001, 1, '新人培训费', 'TRAINING_FEE',    'panjia_payroll_deduct_type', 'warning', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000030002, 2, '资格证费',   'CERT_FEE',        'panjia_payroll_deduct_type', 'warning', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000030003, 3, '罚款',       'FINE',            'panjia_payroll_deduct_type', 'danger',  'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000030004, 4, '赔偿款',     'COMPENSATION',    'panjia_payroll_deduct_type', 'danger',  'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000030005, 5, '借款还款',   'LOAN_REPAY',      'panjia_payroll_deduct_type', 'primary', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000030006, 6, '物品赔偿',   'ITEM_COMPENSATION','panjia_payroll_deduct_type', 'danger', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761700000000030007, 7, '其他',       'OTHER',           'panjia_payroll_deduct_type', 'info',    'N', 1761000000000000100, 1761100000000000001, now());
