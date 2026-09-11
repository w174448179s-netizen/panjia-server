-- ============================================================================
-- V110005 员工导入模板按部门层级拆分（替代 V100 单列 dept_full）
-- 段位：V110005（2026-09-11 由 V100019 重命名）
-- 业务背景：员工部门从 2 级（门店-组别）扩展为 3 级（大区-门店-小组）；
--          未来出现第 4 级时只需在 column_json 追加一列 {deptLevel:N}，
--          代码零改动。
-- ----------------------------------------------------------------------------
-- 设计要点：
--   * ColumnDef 新增 deptLevel（Integer，可空）字段，标识该列是部门路径第几级
--   * 业务层扫描 deptLevel != null 的列，按 level 升序用 '-' 拼接后喂
--     RuoYiDeptAdapter.ensureDept（底层本就是通用循环，已支持任意层级）
--   * 老模板 V100（dept_full 单列）禁用，新模板 V200 启用
--   * sys_dept 表为任意层级树，未变更
-- ============================================================================

BEGIN;

-- 1. 停用老模板（保留历史，便于问题排查）
UPDATE pj_people_import_template
SET enabled = 0
WHERE template_code = 'EMPLOYEE' AND template_version = 'V100';

-- 2. 启用新模板 V200：把 dept_full 单列替换为 dept_level1/2/3 三列
INSERT INTO pj_people_import_template (id, template_code, template_version, column_json, enabled)
VALUES (
    1761600000000000002,
    'EMPLOYEE',
    'V200',
    '[
      {"colName":"大区","field":"dept_level1","type":"STRING","required":true,"deptLevel":1},
      {"colName":"门店","field":"dept_level2","type":"STRING","required":true,"deptLevel":2},
      {"colName":"小组","field":"dept_level3","type":"STRING","required":true,"deptLevel":3},
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
      {"colName":"师傅工号","field":"mentor_code","type":"STRING","required":false,"maxLength":32}
    ]'::jsonb,
    1
)
ON CONFLICT (template_code, template_version) DO UPDATE
SET column_json = EXCLUDED.column_json,
    enabled     = 1;

-- 3. 确认 PeopleImportTemplateBridge 解析时按 template_version DESC 选最新启用版本
--    （bridge 代码已使用 orderByDesc(templateVersion).last("LIMIT 1")，无需改动）

COMMIT;
