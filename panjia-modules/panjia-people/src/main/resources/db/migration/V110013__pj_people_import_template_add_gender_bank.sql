-- ============================================================
-- V110013: 员工导入模板新增 4 列（性别/开户银行/开户支行/工资卡号）
--   * V110004 已应用不可改，本迁移用 UPDATE 覆盖 EMPLOYEE 模板的 column_json；
--   * 性别列不加 dictType（避免用户必须输 MALE/FEMALE），由导入服务映射层兼容
--     "男/女" 与 "MALE/FEMALE"；配 enumValues 仅给 Excel 下拉提示；
--   * 银行/支行/卡号为普通文本列。
-- ============================================================

BEGIN;

UPDATE pj_people_import_template
SET column_json = '[
      {"colName":"大区","field":"dept_level1","type":"STRING","required":true,"deptLevel":1},
      {"colName":"门店","field":"dept_level2","type":"STRING","required":false,"deptLevel":2},
      {"colName":"小组","field":"dept_level3","type":"STRING","required":false,"deptLevel":3},
      {"colName":"工号","field":"employee_code","type":"STRING","required":true,"maxLength":32},
      {"colName":"姓名","field":"employee_name","type":"STRING","required":true,"maxLength":64},
      {"colName":"职级","field":"level","type":"STRING","required":true,"dictType":"panjia_employee_level"},
      {"colName":"职位","field":"post_names","type":"STRING","required":true},
      {"colName":"电话","field":"phone","type":"STRING","required":false,"maxLength":20},
      {"colName":"身份证","field":"id_card","type":"STRING","required":false,"maxLength":64},
      {"colName":"性别","field":"gender","type":"STRING","required":false,"maxLength":10,"enumValues":["男","女","MALE","FEMALE"]},
      {"colName":"开户银行","field":"bank_name","type":"STRING","required":false,"maxLength":100},
      {"colName":"开户支行","field":"bank_branch","type":"STRING","required":false,"maxLength":200},
      {"colName":"工资卡号","field":"bank_card_no","type":"STRING","required":false,"maxLength":64},
      {"colName":"报道时间","field":"report_date","type":"DATE","required":false,"dateFormat":"yyyy-MM-dd"},
      {"colName":"入职时间","field":"hire_date","type":"DATE","required":true,"dateFormat":"yyyy-MM-dd"},
      {"colName":"社保","field":"social","type":"BOOL","required":true},
      {"colName":"公积金","field":"housing","type":"BOOL","required":true},
      {"colName":"商业保险","field":"commercial","type":"BOOL","required":true},
      {"colName":"宿舍","field":"dormitory","type":"BOOL","required":true},
      {"colName":"兼职","field":"parttime","type":"BOOL","required":true},
      {"colName":"伯乐工号","field":"mentor_code","type":"STRING","required":false,"maxLength":32},
      {"colName":"社保金额","field":"social_fee","type":"DECIMAL","required":false,"precision":10,"scale":2},
      {"colName":"商业保险金额","field":"commercial_fee","type":"DECIMAL","required":false,"precision":10,"scale":2},
      {"colName":"公积金金额","field":"housing_fund","type":"DECIMAL","required":false,"precision":10,"scale":2},
      {"colName":"宿舍费金额","field":"dormitory_fee","type":"DECIMAL","required":false,"precision":10,"scale":2}
    ]'::jsonb
WHERE template_code = 'EMPLOYEE' AND enabled = 1;

COMMIT;
