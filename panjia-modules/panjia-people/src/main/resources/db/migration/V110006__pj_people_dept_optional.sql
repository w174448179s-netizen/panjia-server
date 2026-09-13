-- ============================================================================
-- V110006 员工导入模板：门店/小组改为非必填
-- 业务背景：总监、账务、人事等岗位可能挂在大区或门店层级，
--          门店或小组列允许为空；仅大区为必填。
--          tryAssembleDeptPath 已实现"跳级阻断"（低级为空高级不可填），
--          故只需放开 required 标记即可支持部分部门路径。
-- ============================================================================

UPDATE pj_people_import_template
SET column_json = (
  SELECT jsonb_agg(
    CASE
      WHEN elem->>'field' IN ('dept_level2', 'dept_level3')
        THEN jsonb_set(elem, '{required}', 'false'::jsonb)
      ELSE elem
    END
  )
  FROM jsonb_array_elements(column_json) AS elem
)
WHERE template_code = 'EMPLOYEE'
  AND template_version = 'V200'
  AND enabled = 1;
