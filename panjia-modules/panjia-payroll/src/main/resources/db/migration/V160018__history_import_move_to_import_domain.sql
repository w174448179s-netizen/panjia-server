-- 历史工资导入迁入导入域：
-- 1) 页面菜单 1761400000000002323 从「薪酬计算」(2300) 移到「数据导入」目录 (1761400000000002100)，
--    组件路径改为 import/payroll/index，路由标识 payroll；权限码改为导入域批次查询
-- 2) 操作按钮 1761400000000002324 权限码改为导入域撤销导入
-- 3) 历史 CLI 时代的旧权限码 payroll:history:import 随菜单原位更新，旧 sys_role_menu 授权（菜单ID不变）继续生效
-- 4) 旧前端页面 views/payroll/history-import/index.vue 与后端 /payroll/batch/history-import 端点已删除

UPDATE sys_menu
SET parent_id = 1761400000000002100,
    path = 'payroll',
    component = 'import/payroll/index',
    perms = 'import:batch:list',
    menu_name = '历史工资导入',
    order_num = 5,
    update_by = 1,
    update_time = NOW()
WHERE menu_id = 1761400000000002323;

UPDATE sys_menu
SET perms = 'import:batch:revoke',
    menu_name = '撤销导入',
    update_by = 1,
    update_time = NOW()
WHERE menu_id = 1761400000000002324;
