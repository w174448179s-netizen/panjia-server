-- ============================================================
-- V120018: 考勤（ATTENDANCE）模板取消工号必填——宽容乱数据口径
--
-- 与积分（POINTS，V120017）口径统一：钉钉月度汇总存在部分员工
-- 工号为空（后台未维护），行照常导入留痕，统计时跳过——
--   工号为空 → 行照常导入，聚合器跳过（无法归属到人，不统计）；
--   考勤明细手工登记不受影响（不走模板校验）。
-- 聚合器 AttendanceSummaryAggregator 已按此口径实现（空工号行跳过），
-- 本迁移仅放宽模板 D 列（工号）required 校验 + 移除 not_blank 行级规则。
-- ============================================================

BEGIN;

UPDATE pj_import_template
SET column_mapping = (
        SELECT jsonb_agg(
                   CASE WHEN elem->>'target_field' = 'employeeCode'
                        THEN jsonb_set(elem, '{required}', 'false')
                        ELSE elem END
                   ORDER BY ord)
        FROM jsonb_array_elements(column_mapping) WITH ORDINALITY AS t(elem, ord)
        WHERE template_code = 'ATTENDANCE'
    ),
    validation_rules = '{
        "file_level": [{"rule":"max_rows:5000","message":"单次导入不超过5000行"}],
        "row_level": []
    }'::jsonb,
    description = '钉钉考勤后台导出的《月度汇总》Excel 原文件直接上传，无需下载模板改写；请保留原始两行表头，归属月选择文件统计日期所在月份。宽容空值：工号为空的行照常导入但不统计（无法归属到人，需在钉钉后台补齐工号后重新导入）。',
    updated_at = now()
WHERE template_code = 'ATTENDANCE';

COMMIT;
