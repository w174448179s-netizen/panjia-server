-- ============================================================
-- V100027: 提成点调整（薪酬计算域）菜单、权限与字典
--
-- 薪酬计算(2300) 下新增「提成点调整」C 菜单（财务/总监可见）：
--   财务登记员工业绩扣点调整（电话考核未完成/个人调整，原因必填）
--   → 总监「我的待办」审批 → 通过后按生效区间在算薪时自动叠加扣点。
--   未买社保扣点为档案参保事实自动判断，不在此登记（页面类型字典中
--   保留 NO_SOCIAL 仅用于工资明细溯源标签渲染）。
--
-- 权限点：
--   payroll:rateadjust:list    查询（含审批详情弹窗）
--   payroll:rateadjust:add     登记/修改/删除/提交（财务）
--   payroll:rateadjust:cancel  撤销（财务）
--
-- 字典：rate_adjust_type 提成点调整类型
--   NO_SOCIAL   未买社保扣点（自动，不在本页登记）
--   PHONE_CHECK 电话考核扣点
--   PERSONAL    个人调整扣点
--
-- 菜单 ID：2311/2312/2313（2310 已被薪酬规则配置占用，全仓查重后取新段位）。
-- 字典 ID：1761500000000000501（类型）、0511~0513（数据），全仓查重后空闲。
-- ============================================================

-- ---------- C 菜单：薪酬计算 → 提成点调整 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002311, '提成点调整', 1761400000000002300, 7, 'rateadjust', 'payroll/rateadjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:rateadjust:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提成点调整菜单（财务登记业绩扣点调整，总监审批通过后按生效区间扣点）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- F 按钮 ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002312, '登记调整', 1761400000000002311, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:rateadjust:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提成点调整-登记/修改/删除/提交按钮'),
(1761400000000002313, '撤销调整', 1761400000000002311, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:rateadjust:cancel', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提成点调整-撤销按钮（撤回在途流程/作废生效中调整）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 角色授权：财务(012) 全量、总监(010) 查看 ----------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000002311),
(1761300000000000012, 1761400000000002312),
(1761300000000000012, 1761400000000002313),
(1761300000000000010, 1761400000000002311)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ---------- 字典类型：提成点调整类型 ----------
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761500000000000501, '提成点调整类型', 'rate_adjust_type', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '员工业绩提成点调整类型')
ON CONFLICT (dict_id) DO NOTHING;

-- ---------- 字典数据 ----------
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761500000000000511, 1, '未买社保扣点', 'NO_SOCIAL',   'rate_adjust_type', NULL, 'warning', 'N', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '档案参保事实自动判断，免审批，不在调整页登记'),
(1761500000000000512, 2, '电话考核扣点', 'PHONE_CHECK', 'rate_adjust_type', NULL, 'primary', 'N', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '电话考核未完成的业绩扣点'),
(1761500000000000513, 3, '个人调整扣点', 'PERSONAL',    'rate_adjust_type', NULL, 'danger',  'N', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '针对个人的业绩扣点调整')
ON CONFLICT (dict_code) DO NOTHING;
