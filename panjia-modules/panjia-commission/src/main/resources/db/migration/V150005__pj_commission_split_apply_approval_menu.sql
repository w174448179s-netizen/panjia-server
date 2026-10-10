-- ============================================================================
-- 结佣发起 / 结佣审批 菜单拆分
-- 背景：结佣发起与结佣审批原本合并在「结佣明细」(2202, commission/apply/index) 一个页面，
--   现拆为两个独立页面：
--   - 结佣发起（2202，原菜单改名）：查可发起合同（实收审批通过 + 未被结佣），选择期间发起
--   - 结佣审批（新增 2205）：按结佣期间查询已有申请单，办理审批 / 作废 / 调整 / 批量审批
-- 内容：
--   1. 菜单 2202 改名「结佣明细」→「结佣发起」
--   2. 新增菜单 2205「结佣审批」(commission/approval/index)
--   3. 审批类按钮（审批/批量审批/作废）从 2202 迁挂到 2205
--   4. 新菜单 2205 绑定总监(10)/店长(11)/财务(12)（镜像 2202 的角色绑定）
-- ============================================================================

BEGIN;

-- 1. 原菜单改名：2202 结佣明细 → 结佣发起（组件不变，仍为 commission/apply/index）
UPDATE sys_menu
SET menu_name = '结佣发起',
    remark = '结佣发起菜单（实收审批通过且未结佣的合同，选择结佣期间发起）',
    update_by = 1761100000000000001,
    update_time = now()
WHERE menu_id = 1761400000000002202;

-- 2. 新增「结佣审批」菜单（order_num 4，原「结佣调整」顺延为 5）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002205, '结佣审批', 1761400000000002200, 4, 'approval', 'commission/approval/index', NULL, 'N', 'Y', 'C', '0', '0', 'commission:apply:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '结佣审批菜单（按结佣期间查询申请单，办理审批/作废/调整）')
ON CONFLICT (menu_id) DO NOTHING;

UPDATE sys_menu SET order_num = 5 WHERE menu_id = 1761400000000002203;

-- 3. 审批类按钮迁挂到结佣审批菜单（F 型按钮不在菜单树展示，仅调整归属便于管理）
UPDATE sys_menu SET parent_id = 1761400000000002205, order_num = 1, update_time = now() WHERE menu_id = 1761400000000011824; -- 审批申请
UPDATE sys_menu SET parent_id = 1761400000000002205, order_num = 2, update_time = now() WHERE menu_id = 1761400000000011833; -- 批量审批
UPDATE sys_menu SET parent_id = 1761400000000002205, order_num = 3, update_time = now() WHERE menu_id = 1761400000000011825; -- 作废申请

-- 4. 新菜单角色绑定：仅总监(10)和财务(12)可见结佣审批页
--    店长(11)只有发起权限，不绑定审批菜单
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002205),
(1761300000000000012, 1761400000000002205)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 4a. 撤销店长(11)对审批类按钮的权限：作废(1825)原 V150002 已授权给店长，
--     但作废属于审批流程，店长不应保留此权限
DELETE FROM sys_role_menu WHERE role_id = 1761300000000000011 AND menu_id = 1761400000000011825;

COMMIT;
