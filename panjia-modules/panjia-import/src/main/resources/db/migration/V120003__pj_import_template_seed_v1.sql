-- ============================================================
-- 盘家智管 · 数据导入域 · pj_import_template 种子数据（重整版）
-- 段位：V120003（2026-09-11 由 V100003 重命名）
-- 依据：AI 任务卡 01 Task-0-1 + Phase0 详细设计 V3.0 §1.8
--
-- 整改内容（2026-09-11）：
--   1) 删除旧版 6 个模板种子（SHELL_PERFORMANCE V2/V1、ATTENDANCE_MONTHLY、
--      SCORE_MONTHLY、MANUAL_ENTRY、SHELL_PERFORMANCE_TEST），其业务概念已被
--      V120005（KE_SIGNED V200 + 其余四类 V100 模板）的更新版本替代；
--   2) 仅保留 STORE_COST 模板（门店成本录入占位，与其他模板无业务重叠）；
--   3) 顶部 DELETE 用于幂等兜底（重整期间可能多次执行）。
-- ============================================================

BEGIN;

-- 兜底清理：删除旧模板（幂等无害；新版本模板由 V120005 提供）
DELETE FROM pj_import_template WHERE template_code IN (
    'SHELL_PERFORMANCE',       -- V120005 提供 V200 KE_SIGNED 替代
    'ATTENDANCE_MONTHLY',      -- V120005 提供 V100 ATTENDANCE 替代
    'SCORE_MONTHLY',           -- V120005 提供 V100 POINTS 替代
    'MANUAL_ENTRY',            -- V120005 提供 V100 OTHERS 替代
    'SHELL_PERFORMANCE_TEST'   -- 测试模板已过期（effective_to=2026-09-30）
);

-- ============================================================
-- 1. 门店成本录入模板（STORE_COST V1）
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

COMMIT;