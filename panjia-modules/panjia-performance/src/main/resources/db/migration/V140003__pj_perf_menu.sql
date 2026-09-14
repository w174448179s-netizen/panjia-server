-- =====================================================
-- 业绩域菜单与权限种子（V140003）
-- 段位：V140003（2026-09-11 由 V100021 重命名）
--
-- 整改说明（2026-09-11 段位重整）：
--   原 V100021 引用 menu_id=1761400000000002530（"数据管理"顶级目录），
--   但 2530 在所有 SQL 中从未被 INSERT，属于孤儿引用。本次整改在文件顶部
--   自包含 INSERT menu_id=2530「数据管理」作为前置依赖，修复孤儿引用。
--
-- 设计意图（2026-09-11 菜单收敛）：
--   * 删除原 2600「业绩管理（运维侧）」中间层（与 V100001 创建的 2200「业绩管理」
--     顶级菜单重复，构成"数据管理-业绩管理" 与 顶级"业绩管理" 双菜单树）
--   * 2610/2620/2630 直接挂到顶级 2200 下，与业务侧 2201/2202/2203 并列
--   * 2201「业绩明细」业务侧残留已从 V100001 删除（其 perms=performance:fact:* 后端不存在），
--     2610 为唯一「业绩明细」菜单；业务角色（店长/财务/经纪人）在 V100001 中直接绑 2610/2611
--   * 数据管理 (2530) 顶级保留，供未来 import/outbox/people 等运维菜单挂载
--   * sys_role_menu 中绑定 2600 的行整行删除（2600 不再存在）
-- =====================================================

BEGIN;

-- 前置依赖：数据管理顶级目录（修复孤儿引用）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002530, '数据管理', 0, 80, 'data', NULL, NULL, 'N', 'Y', 'M', '0', '0', '', 'DataBoard', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '数据管理顶级目录（运维聚合，import/outbox/people 等挂载点）');

-- 业绩明细（合同维度：合同→人→明细 树表，新签/结佣双口径）
-- 统一以合同为视角：总监据此调整/结佣，经纪人可看到同一合同下其他人的分成
-- perms=perf:fact:list 与 PerformanceFactController 对齐
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002610, '业绩明细', 1761400000000002200, 1, 'manage', 'performance/contract/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:fact:list', 'list', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩明细（合同→人→明细 树表）');

-- 业绩明细按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002611, '业绩查询', 1761400000000002610, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:fact:query', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002612, '重新消费', 1761400000000002610, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:fact:build', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

-- 业绩调整（reparent 2600 → 2200）
-- 注：path=adjustment——V100001 的 2203「结佣调整」(commission/adjust/index) 已占用
--     /performance/adjust，同 path 会让两个菜单点谁都路由到同一个页面
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002620, '业绩调整', 1761400000000002200, 5, 'adjustment', 'performance/adjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:adjust:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩调整单管理');

-- 调整单按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002621, '调整单查询', 1761400000000002620, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:query', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002622, '新增调整单', 1761400000000002620, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002623, '调整单审批', 1761400000000002620, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:approve', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002624, '执行调整', 1761400000000002620, 4, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:execute', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002625, '取消调整', 1761400000000002620, 5, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:adjust:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

-- 期间封账（reparent 2600 → 2200）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002630, '期间封账', 1761400000000002200, 6, 'period', 'performance/period/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:period:list', 'date', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩期间封账管理');

-- 期间封账按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002631, '期间查询', 1761400000000002630, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:period:query', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002632, '封账', 1761400000000002630, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:period:close', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002633, '反结账', 1761400000000002630, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:period:reopen', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

-- 实收业绩审批（合同维度，需求文档 §2：导入自动提交 财务→总监；支持 Excel 批量审批）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002640, '实收明细', 1761400000000002200, 2, 'received', 'performance/received/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:received:list', 'validCode', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '实收业绩明细（合同维度）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002641, '实收单查询', 1761400000000002640, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:received:query', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002642, '手工提交实收', 1761400000000002640, 2, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:received:submit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '店长/财务/总监可手工发起（§2.2）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002643, '实收审批办理', 1761400000000002640, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:received:approve', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '财务/总监审批（§2.1）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002644, '作废实收单', 1761400000000002640, 4, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:received:cancel', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002645, '实收批量审批', 1761400000000002640, 5, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:received:batch', '#', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, 'Excel 导入按合同号+金额批量审批（§2.3）');

-- 角色权限关联：超级管理员 + 总监拥有全部业绩权限
-- 注：2600「业绩管理」中间层已删除；运维角色绑顶级 2200+ 子菜单
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1, 1761400000000002610),
(1, 1761400000000002611),
(1, 1761400000000002612),
(1, 1761400000000002620),
(1, 1761400000000002621),
(1, 1761400000000002622),
(1, 1761400000000002623),
(1, 1761400000000002624),
(1, 1761400000000002625),
(1, 1761400000000002630),
(1, 1761400000000002631),
(1, 1761400000000002632),
(1, 1761400000000002633),
(1, 1761400000000002640),
(1, 1761400000000002641),
(1, 1761400000000002642),
(1, 1761400000000002643),
(1, 1761400000000002644),
(1, 1761400000000002645),
(1, 1761400000000002200),
(1761100000000000100, 1761400000000002610),
(1761100000000000100, 1761400000000002611),
(1761100000000000100, 1761400000000002612),
(1761100000000000100, 1761400000000002620),
(1761100000000000100, 1761400000000002621),
(1761100000000000100, 1761400000000002622),
(1761100000000000100, 1761400000000002623),
(1761100000000000100, 1761400000000002624),
(1761100000000000100, 1761400000000002625),
(1761100000000000100, 1761400000000002630),
(1761100000000000100, 1761400000000002631),
(1761100000000000100, 1761400000000002632),
(1761100000000000100, 1761400000000002633),
(1761100000000000100, 1761400000000002640),
(1761100000000000100, 1761400000000002641),
(1761100000000000100, 1761400000000002642),
(1761100000000000100, 1761400000000002643),
(1761100000000000100, 1761400000000002644),
(1761100000000000100, 1761400000000002645),
(1761100000000000100, 1761400000000002200),
-- 总监（1761300000000000010）：实收全部权限（手工提交+审批+批量+作废）
(1761300000000000010, 1761400000000002640),
(1761300000000000010, 1761400000000002641),
(1761300000000000010, 1761400000000002642),
(1761300000000000010, 1761400000000002643),
(1761300000000000010, 1761400000000002644),
(1761300000000000010, 1761400000000002645),
-- 财务（1761300000000000012）：实收全部权限（手工提交+审批+批量+作废）
(1761300000000000012, 1761400000000002640),
(1761300000000000012, 1761400000000002641),
(1761300000000000012, 1761400000000002642),
(1761300000000000012, 1761400000000002643),
(1761300000000000012, 1761400000000002644),
(1761300000000000012, 1761400000000002645),
-- 店长（1761300000000000011）：手工提交/查询/作废（不承担审批）
(1761300000000000011, 1761400000000002640),
(1761300000000000011, 1761400000000002641),
(1761300000000000011, 1761400000000002642),
(1761300000000000011, 1761400000000002644)
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
