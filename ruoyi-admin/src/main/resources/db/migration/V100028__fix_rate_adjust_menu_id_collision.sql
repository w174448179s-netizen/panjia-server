-- ============================================================
-- V100028: 修复 V100027 与 V160003/V160004 的菜单 ID 撞号
--
-- 事故原委（2026-09-19 全新初始化库暴露）：
--   V100027（提成点调整菜单）版本号 100027 < V160003，共享同一
--   flyway_schema_history 按版本号排序执行，V100027 先跑，抢占了
--   2311/2312/2313：
--     2311 被插成 C「提成点调整」(payroll:rateadjust:list)
--     2312 被插成 F「登记调整」  (payroll:rateadjust:add)
--     2313 被插成 F「撤销调整」  (payroll:rateadjust:cancel)
--   V160003 后跑时 payroll:batch:add/submit/approve 三个按钮
--   INSERT 全部主键冲突被 ON CONFLICT DO NOTHING 静默跳过；
--   V160004 又按固定 ID 删除 2307/2313/2314（下线 batch 业务直批
--   按钮的既定决策），连带把 V100027 的 2313「撤销调整」删掉。
--   终态：payroll:batch:add / batch:submit 权限码从库里消失，
--   财务/总监/店长新建、提交算薪批次全部 403；rateadjust:cancel
--   按钮同样丢失；店长还被 V160003 的补授权误挂了提成点菜单。
--
-- 修复（对全新库 / 已升级老库均幂等）：
--   1. 提成点调整菜单迁移到全新空闲段位 2320(C)/2321(F add)/2322(F cancel)
--   2. 仅当 2311/2312 当前确为 rateadjust 菜单时（故障库状态）才清理，
--      老库上它们本就是 batch 按钮，条件不命中、不受影响
--   3. 恢复 V160003 定义的 2311=新建批次(batch:add)、
--      2312=提交审核(batch:submit) 及 总监/店长/财务 授权
--   4. 不恢复 2313/2314（batch:approve/reject 已由 V160004 有意下线，
--      审批动作收敛「我的待办」由引擎判权）
-- ============================================================

-- ---------- 1. 提成点调整菜单落到新 ID 2320/2321/2322 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002320, '提成点调整', 1761400000000002300, 7, 'rateadjust', 'payroll/rateadjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:rateadjust:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提成点调整菜单（财务登记业绩扣点调整，总监审批通过后按生效区间扣点）')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002321, '登记调整', 1761400000000002320, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:rateadjust:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提成点调整-登记/修改/删除/提交按钮'),
(1761400000000002322, '撤销调整', 1761400000000002320, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:rateadjust:cancel', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提成点调整-撤销按钮（撤回在途流程/作废生效中调整）')
ON CONFLICT (menu_id) DO NOTHING;

-- 角色授权：财务(012) 全量、总监(010) 仅查看
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000002320),
(1761300000000000012, 1761400000000002321),
(1761300000000000012, 1761400000000002322),
(1761300000000000010, 1761400000000002320)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ---------- 2. 清理被抢占的 2311/2312（仅故障库命中） ----------
-- 先删角色授权（连带清掉店长 011 被 V160003 误挂到提成点菜单的授权）
DELETE FROM sys_role_menu rm
WHERE rm.menu_id IN (1761400000000002311, 1761400000000002312)
  AND EXISTS (SELECT 1 FROM sys_menu m
              WHERE m.menu_id = rm.menu_id AND m.perms LIKE 'payroll:rateadjust:%');

DELETE FROM sys_menu
WHERE menu_id IN (1761400000000002311, 1761400000000002312)
  AND perms LIKE 'payroll:rateadjust:%';

-- ---------- 3. 恢复算薪批次按钮（V160003 原定义） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002311, '新建批次', 1761400000000002301, 4, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:add', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '新建算薪批次'),
(1761400000000002312, '提交审核', 1761400000000002301, 5, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:submit', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提交批次进入总监审核')
ON CONFLICT (menu_id) DO NOTHING;

-- 新建批次 / 提交审核 → 总监(010) + 店长(011) + 财务(012)
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002311),
(1761300000000000011, 1761400000000002311),
(1761300000000000012, 1761400000000002311),
(1761300000000000010, 1761400000000002312),
(1761300000000000011, 1761400000000002312),
(1761300000000000012, 1761400000000002312)
ON CONFLICT (role_id, menu_id) DO NOTHING;
