-- V120012: ATTENDANCE 模板补 leaveAmount（考勤扣款金额）列映射
-- 归一化（ImportEngine.fillPerformanceFields）按 leaveAmount 取考勤扣款，
-- 但 V100 模板未映射该列 → 导入后 receivable_amount 恒空（考勤扣款恒 0）。
-- 使用月度汇总数据时：一行一人，考勤日期传当月任意一天，扣款金额填当月合计。

UPDATE pj_import_template
SET column_mapping = column_mapping || '[
    {"source_column":"E","source_header":"扣款金额","target_field":"leaveAmount","data_type":"DECIMAL","required":false,"default_value":"0","transform":null,"header_match_mode":"TRIM"}
]'::jsonb,
    updated_at = now()
WHERE template_code = 'ATTENDANCE'
  AND template_version = 'V100'
  AND NOT column_mapping::text LIKE '%leaveAmount%';
