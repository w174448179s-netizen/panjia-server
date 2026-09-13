-- 清理空壳菜单「数据管理」（无任何子菜单，多余）
DELETE FROM sys_role_menu WHERE menu_id = 1761400000000002530;
DELETE FROM sys_menu WHERE menu_id = 1761400000000002530;
