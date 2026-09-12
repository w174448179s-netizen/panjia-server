-- ============================================================
-- 业绩域菜单修正（V140004）
-- 段位：V140004（performance 模块）
-- 背景：V140003 种子里「业绩明细」(2610) 指向旧页面 fact/performance/fact/index，
--       2026-09 页面重写后唯一入口为 performance/manage/index（人→合同→明细树表）。
--       该修正此前仅在开发库手动执行，clean-local-db.sh 清库后被 V140003 还原，
--       现固化为迁移，保证空库初始化即为正确菜单。
-- 幂等：可重复执行。
-- ============================================================

BEGIN;

-- 1. 「业绩明细」菜单指向新树表页面：path=manage, component=performance/manage/index
UPDATE sys_menu
SET path = 'manage',
    component = 'performance/manage/index',
    remark = '业绩明细（人→合同→明细 树表，新签/结佣双口径）',
    update_time = now()
WHERE menu_id = 1761400000000002610;

-- 2. 兜底：若历史库缺失 2610（理论上 V140003 已插入），补齐
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 1761400000000002610, '业绩明细', 1761400000000002200, 1, 'manage', 'performance/manage/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:fact:list', 'List', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩明细（人→合同→明细 树表，新签/结佣双口径）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1761400000000002610);

COMMIT;
