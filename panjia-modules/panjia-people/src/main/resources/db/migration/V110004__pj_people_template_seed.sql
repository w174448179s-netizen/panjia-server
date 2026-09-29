-- ============================================================
-- pj_people 字典 + 员工导入模板 seed 最终态（清库重建版）
-- 合并自：V110002（字典部分）, V110005, V110006
-- 说明：
--   * 字典 seed 原在 V110002（建表）文件内，按语句类型分流至本文件；
--     职级字典 A0~A5/S1/S2 + C0~C3/D，员工状态 ACTIVE/PARTTIME/LEFT/PENDING；
--   * 导入模板为 V200（dept_level1/2/3 三列），其中门店/小组 required 已按
--     V110006 的 jsonb_set 结果折叠为 false（仅大区必填），非 V110005 原始 JSON；
--   * 老 V100 模板（dept_full 单列）从未在当前迁移链中播种，最终态仅 V200 一行。
-- ============================================================

BEGIN;

-- ---------- 一、员工职级字典（A0~A5/S1/S2；旧 DIRECTOR 职级废弃——总监是岗位/角色不是职级） ----------
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000001, '员工职级', 'panjia_employee_level', 1761000000000000100, 1761100000000000001, now(), '员工职级列表（A0~A5/S1/S2）；底薪/比例归 payroll 规则侧')
ON CONFLICT (dict_type) DO UPDATE SET dict_name = EXCLUDED.dict_name, remark = EXCLUDED.remark;

DELETE FROM sys_dict_data WHERE dict_type = 'panjia_employee_level';
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761600000000010001, 1, 'A0', 'A0', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010002, 2, 'A1', 'A1', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010003, 3, 'A2', 'A2', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010004, 4, 'A3', 'A3', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010005, 5, 'A4', 'A4', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010006, 6, 'A5', 'A5', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010007, 7, 'S1', 'S1', 'panjia_employee_level', 'success', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010008, 8, 'S2', 'S2', 'panjia_employee_level', 'success', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010009, 9, 'C0', 'C0', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010010, 9, 'C1', 'C1', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010011, 10, 'C2', 'C2', 'panjia_employee_level', 'success', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010012, 11, 'C3', 'C3', 'panjia_employee_level', 'success', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010013, 12, 'D', 'D', 'panjia_employee_level', 'success', 'N', 1761000000000000100, 1761100000000000001, now());

-- ---------- 二、员工状态字典（V5.2：ACTIVE/PARTTIME/LEFT/PENDING） ----------
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000004, '员工状态', 'panjia_employee_status', 1761000000000000100, 1761100000000000001, now(), '员工状态（在职/兼职/离职/待入职）')
ON CONFLICT (dict_type) DO UPDATE SET dict_name = EXCLUDED.dict_name, remark = EXCLUDED.remark;

DELETE FROM sys_dict_data WHERE dict_type = 'panjia_employee_status';
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761600000000040001, 1, '在职', 'ACTIVE', 'panjia_employee_status', 'success', 'Y', 1761000000000000100, 1761100000000000001, now()),
(1761600000000040002, 2, '兼职', 'PARTTIME', 'panjia_employee_status', 'warning', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000040003, 3, '离职', 'LEFT', 'panjia_employee_status', 'danger', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000040004, 4, '待入职', 'PENDING', 'panjia_employee_status', 'info', 'N', 1761000000000000100, 1761100000000000001, now());

-- ---------- 三、员工导入模板 V200（部门按层级拆分三列，门店/小组非必填） ----------
-- 业务背景：员工部门为 3 级（大区-门店-小组）；未来出现第 4 级时只需在 column_json
-- 追加一列 {deptLevel:N}，代码零改动。业务层按 deptLevel 升序用 '-' 拼接部门路径，
-- tryAssembleDeptPath 已实现"跳级阻断"（低级为空高级不可填）。
INSERT INTO pj_people_import_template (id, template_code, template_version, column_json, enabled)
VALUES (
    1761600000000000002,
    'EMPLOYEE',
    'V200',
    '[
      {"colName":"大区","field":"dept_level1","type":"STRING","required":true,"deptLevel":1},
      {"colName":"门店","field":"dept_level2","type":"STRING","required":false,"deptLevel":2},
      {"colName":"小组","field":"dept_level3","type":"STRING","required":false,"deptLevel":3},
      {"colName":"工号","field":"employee_code","type":"STRING","required":true,"maxLength":32},
      {"colName":"姓名","field":"employee_name","type":"STRING","required":true,"maxLength":64},
      {"colName":"职级","field":"level","type":"STRING","required":true,"dictType":"panjia_employee_level"},
      {"colName":"职位","field":"post_names","type":"STRING","required":true},
      {"colName":"电话","field":"phone","type":"STRING","required":false,"maxLength":20},
      {"colName":"身份证","field":"id_card","type":"STRING","required":false,"maxLength":64},
      {"colName":"报道时间","field":"report_date","type":"DATE","required":false,"dateFormat":"yyyy-MM-dd"},
      {"colName":"入职时间","field":"hire_date","type":"DATE","required":true,"dateFormat":"yyyy-MM-dd"},
      {"colName":"社保","field":"social","type":"BOOL","required":true},
      {"colName":"公积金","field":"housing","type":"BOOL","required":true},
      {"colName":"商业保险","field":"commercial","type":"BOOL","required":true},
      {"colName":"宿舍","field":"dormitory","type":"BOOL","required":true},
      {"colName":"兼职","field":"parttime","type":"BOOL","required":true},
      {"colName":"师傅工号","field":"mentor_code","type":"STRING","required":false,"maxLength":32},
      {"colName":"社保金额","field":"social_fee","type":"DECIMAL","required":false,"precision":10,"scale":2},
      {"colName":"商业保险金额","field":"commercial_fee","type":"DECIMAL","required":false,"precision":10,"scale":2},
      {"colName":"公积金金额","field":"housing_fund","type":"DECIMAL","required":false,"precision":10,"scale":2},
      {"colName":"宿舍费金额","field":"dormitory_fee","type":"DECIMAL","required":false,"precision":10,"scale":2}

    ]'::jsonb,
    1
)
ON CONFLICT (template_code, template_version) DO UPDATE
SET column_json = EXCLUDED.column_json,
    enabled     = 1;

COMMIT;
