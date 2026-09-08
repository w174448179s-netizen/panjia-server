-- ============================================================
-- 盘家智管 · 薪酬与收支模块 · 菜单与角色种子数据（生产上线版）
-- 依据：docs/盘家智管_薪酬与收支_菜单设计_生产上线版.md
-- 说明：
--   1) sys_menu：28 条（5 个业务一级目录 + 子菜单 + F 按钮权限 + 系统管理追加项）
--   2) sys_role：5 个业务角色（总监/店长/算薪人员/人事/经纪人）
--   3) sys_role_menu：依据设计文档第三章角色功能矩阵绑定
--   4) 超级管理员 userId=1 由 SysMenuServiceImpl#selectMenuTreeByUserId
--      走 selectMenuTreeAll 直接见全部菜单，无需 role_menu 绑定
--   5) sys_menu 无 data_scope 列（数据权限在 sys_role 上，
--      由 @DataPermission 注解实现门店维度拦截）
--   6) 菜单 ID 采用 19 位雪花段，与基线不冲突
--   7) F 按钮按 RuoYi 惯例挂在所属 C 菜单下（算薪批次）
-- ============================================================

BEGIN;

-- ============================================================
-- 一、sys_menu 菜单数据（28 条）
-- ============================================================

-- ---------- 一级目录：基础档案 (people 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002000, '基础档案', 0, 10, 'people', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'peoples', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '基础档案目录（people 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002001, '员工档案', 1761400000000002000, 1, 'employee', 'people/employee/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:employee:list', 'user', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '员工档案菜单（工号/入职/离职/兼职）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002002, '职级与社保模板', 1761400000000002000, 2, 'level', 'people/level/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:level:list', 'dict', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '职级与社保模板菜单（A0~A5/S1S2/总监、社保比例、底薪）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002003, '师徒关系', 1761400000000002000, 3, 'mentor', 'people/mentor/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:mentor:list', 'tree', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '师徒关系菜单（推荐人-徒弟绑定）');

-- ---------- 一级目录：数据导入 (import 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002100, '数据导入', 0, 20, 'import', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'upload', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '数据导入目录（import 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002101, '贝壳业绩导入', 1761400000000002100, 1, 'shell', 'import/shell/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:shell:list', 'upload', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '贝壳业绩导入菜单（Excel→预览→归档→归一化）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002102, '考勤数据导入', 1761400000000002100, 2, 'attendance', 'import/attendance/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:attendance:list', 'date', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤数据导入菜单（自动计算扣款）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002103, '积分数据导入', 1761400000000002100, 3, 'score', 'import/score/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:score:list', 'star', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '积分数据导入菜单（汇总判定 A/B/C）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002104, '导入批次查询', 1761400000000002100, 4, 'batch', 'import/batch/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:batch:list', 'list', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '导入批次查询菜单（历史批次/原始文件下载/差异比对）');

-- ---------- 一级目录：业绩管理 (performance / commission 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002200, '业绩管理', 0, 30, 'performance', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'chart', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩管理目录（performance / commission 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002201, '业绩明细', 1761400000000002200, 1, 'fact', 'performance/fact/index', NULL, 'N', 'Y', 'C', '0', '0', 'performance:fact:list', 'list', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '业绩明细菜单（归一化后业绩事实）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002202, '结佣申请', 1761400000000002200, 2, 'apply', 'commission/apply/index', NULL, 'N', 'Y', 'C', '0', '0', 'commission:apply:list', 'form', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '结佣申请菜单（待审批/已审批/驳回）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002203, '结佣调整', 1761400000000002200, 3, 'adjust', 'commission/adjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'commission:adjust:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '结佣调整菜单（漏算补录，新增记录不改原始）');

-- ---------- 一级目录：薪酬计算 (payroll 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002300, '薪酬计算', 0, 40, 'payroll', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'money', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '薪酬计算目录（payroll 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002301, '算薪批次', 1761400000000002300, 1, 'batch', 'payroll/batch/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:batch:list', 'documentation', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '算薪批次菜单（草稿→算薪中→已计算→审核中→已批准→已锁定→已发放）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002302, '工资明细', 1761400000000002300, 2, 'detail', 'payroll/detail/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:detail:list', 'list', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '工资明细菜单（应发/扣款/实发，钻取结佣原始）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002303, '奖金录入', 1761400000000002300, 3, 'bonus', 'payroll/bonus/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:bonus:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '奖金录入菜单（店长/总监发起→总监审批→计入当月工资）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002304, '其他收支录入', 1761400000000002300, 4, 'other', 'payroll/other/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:otherearn:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '其他收支录入菜单（手动录入其他收入/其他支出）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002305, '调整与补发', 1761400000000002300, 5, 'adjust', 'payroll/adjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:adjust:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '调整与补发菜单（锁定后修正，新增调整单/补发单）');

-- 算薪批次关键按钮权限（F 类型，挂在算薪批次 C 菜单下）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002306, '发起计算', 1761400000000002301, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:batch:calculate', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '算薪批次-发起计算按钮');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002307, '锁定批次', 1761400000000002301, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:batch:lock', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '算薪批次-锁定批次按钮');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002308, '发放工资', 1761400000000002301, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:batch:release', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '算薪批次-发放工资按钮');

-- ---------- 一级目录：部门收支 (ledger 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002400, '部门收支', 0, 50, 'ledger', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'dashboard', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '部门收支目录（ledger 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002401, '部门收支表', 1761400000000002400, 1, 'dept', 'ledger/dept/index', NULL, 'N', 'Y', 'C', '0', '0', 'ledger:dept:list', 'dashboard', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '部门收支表菜单（事件驱动自动生成）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002402, '收支科目配置', 1761400000000002400, 2, 'subject', 'ledger/subject/index', NULL, 'N', 'Y', 'C', '0', '0', 'ledger:subject:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '收支科目配置菜单（收入/支出科目维护、利润公式）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002403, '门店成本录入', 1761400000000002400, 3, 'cost', 'ledger/cost/index', NULL, 'N', 'Y', 'C', '0', '0', 'ledger:cost:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '门店成本录入菜单（物业水电/租金/装修款）');

-- ---------- 系统管理追加项（挂在原生系统管理目录 1761400000000000001 下） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002500, '授权管理', 1761400000000000001, 90, 'license', 'system/license/index', NULL, 'N', 'Y', 'C', '0', '0', 'system:license:list', 'lock', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '授权管理菜单（License 状态/激活/心跳）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002501, '备份恢复', 1761400000000000001, 95, 'backup', 'system/backup/index', NULL, 'N', 'Y', 'C', '0', '0', 'system:backup:list', 'database', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '备份恢复菜单（算薪前自动备份，手动/策略恢复）');

-- ============================================================
-- 二、sys_role 业务角色（5 个）
-- data_scope: 1=全部 2=自定义 3=本部门 4=本部门及以下 5=仅本人
-- ============================================================

-- 总监（全量数据权限）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761300000000000010, '总监', 'director', 10, '1', true, true, '0', '0', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '总监角色（全量数据，发起/审批/锁定/发放）');

-- 店长（本部门数据权限）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761300000000000011, '店长', 'manager', 11, '3', true, true, '0', '0', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '店长角色（本店数据，发起本店）');

-- 算薪人员（全量数据权限）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761300000000000012, '算薪人员', 'payroll_clerk', 12, '1', true, true, '0', '0', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '算薪人员角色（全量数据，全部操作）');

-- 人事（本部门数据权限）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761300000000000013, '人事', 'hr', 13, '3', true, true, '0', '0', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '人事角色（本店数据，考勤/积分导入与档案编辑）');

-- 经纪人（仅本人数据权限）
INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761300000000000014, '经纪人', 'agent', 14, '5', true, true, '0', '0', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '经纪人角色（仅本人数据，查看本人业绩/工资/发起调整申请）');

-- ============================================================
-- 三、sys_role_menu 角色菜单绑定
-- 依据设计文档第三章「按角色的功能矩阵」
-- ============================================================

-- ---------- 总监（role_id=1761300000000000010）----------
-- 基础档案（查看）、业绩管理（全量/发起/审批）、薪酬计算（发起/审批/锁定/发放/调整）、
-- 部门收支（全量/科目/成本）、授权管理（运维）、备份恢复（运维）
-- 注：不含其他收支录入（矩阵为"—"）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002000),  -- 基础档案目录
(1761300000000000010, 1761400000000002001),  -- 员工档案
(1761300000000000010, 1761400000000002002),  -- 职级与社保模板
(1761300000000000010, 1761400000000002003),  -- 师徒关系
(1761300000000000010, 1761400000000002200),  -- 业绩管理目录
(1761300000000000010, 1761400000000002201),  -- 业绩明细（全量）
(1761300000000000010, 1761400000000002202),  -- 结佣申请（发起/审批）
(1761300000000000010, 1761400000000002203),  -- 结佣调整（审批）
(1761300000000000010, 1761400000000002300),  -- 薪酬计算目录
(1761300000000000010, 1761400000000002301),  -- 算薪批次（发起/审批/锁定）
(1761300000000000010, 1761400000000002302),  -- 工资明细（全量）
(1761300000000000010, 1761400000000002303),  -- 奖金录入（审批）
(1761300000000000010, 1761400000000002305),  -- 调整与补发（审批）
(1761300000000000010, 1761400000000002306),  -- 发起计算（F）
(1761300000000000010, 1761400000000002307),  -- 锁定批次（F）
(1761300000000000010, 1761400000000002308),  -- 发放工资（F）
(1761300000000000010, 1761400000000002400),  -- 部门收支目录
(1761300000000000010, 1761400000000002401),  -- 部门收支表（全量）
(1761300000000000010, 1761400000000002402),  -- 收支科目配置
(1761300000000000010, 1761400000000002403),  -- 门店成本录入
(1761300000000000010, 1761400000000002500),  -- 授权管理（运维）
(1761300000000000010, 1761400000000002501),  -- 备份恢复（运维）
-- 系统管理基础功能（总监需管理用户/角色/部门/岗位）
(1761300000000000010, 1761400000000000001),  -- 系统管理目录
(1761300000000000010, 1761400000000000100),  -- 用户管理
(1761300000000000010, 1761400000000001001),  -- 用户查询（F）
(1761300000000000010, 1761400000000001002),  -- 用户新增（F）
(1761300000000000010, 1761400000000001003),  -- 用户修改（F）
(1761300000000000010, 1761400000000001004),  -- 用户删除（F）
(1761300000000000010, 1761400000000001005),  -- 用户导出（F）
(1761300000000000010, 1761400000000001006),  -- 用户导入（F）
(1761300000000000010, 1761400000000001007),  -- 重置密码（F）
(1761300000000000010, 1761400000000000131),  -- 分配角色
(1761300000000000010, 1761400000000000101),  -- 角色管理
(1761300000000000010, 1761400000000001008),  -- 角色查询（F）
(1761300000000000010, 1761400000000001009),  -- 角色新增（F）
(1761300000000000010, 1761400000000001010),  -- 角色修改（F）
(1761300000000000010, 1761400000000001011),  -- 角色删除（F）
(1761300000000000010, 1761400000000001012),  -- 角色导出（F）
(1761300000000000010, 1761400000000000130),  -- 分配用户
(1761300000000000010, 1761400000000000103),  -- 部门管理（门店=部门）
(1761300000000000010, 1761400000000000104),  -- 岗位管理
(1761300000000000010, 1761400000000000106),  -- 参数设置（社保基数/迟到单价等）
(1761300000000000010, 1761400000000000105),  -- 字典管理
(1761300000000000010, 1761400000000000108),  -- 日志管理
(1761300000000000010, 1761400000000000118);  -- 文件管理

-- ---------- 店长（role_id=1761300000000000011）----------
-- 业绩管理（本店/发起）、薪酬计算（发起本店/奖金/调整）
-- 注：无基础档案、数据导入、其他收支、部门收支、授权/备份
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000011, 1761400000000002200),  -- 业绩管理目录
(1761300000000000011, 1761400000000002201),  -- 业绩明细（本店）
(1761300000000000011, 1761400000000002202),  -- 结佣申请（发起）
(1761300000000000011, 1761400000000002203),  -- 结佣调整（发起）
(1761300000000000011, 1761400000000002300),  -- 薪酬计算目录
(1761300000000000011, 1761400000000002301),  -- 算薪批次（发起本店）
(1761300000000000011, 1761400000000002302),  -- 工资明细（本店）
(1761300000000000011, 1761400000000002303),  -- 奖金录入（发起）
(1761300000000000011, 1761400000000002305),  -- 调整与补发（发起）
(1761300000000000011, 1761400000000002306);  -- 发起计算（F）

-- ---------- 算薪人员（role_id=1761300000000000012）----------
-- 基础档案（查看）、数据导入（全部）、业绩管理（全量/处理）、
-- 薪酬计算（全部操作含其他收支/调整）、部门收支-门店成本
-- 注：无奖金录入（矩阵为"—"）、无部门收支表/科目配置、无授权/备份
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000002000),  -- 基础档案目录
(1761300000000000012, 1761400000000002001),  -- 员工档案（查看）
(1761300000000000012, 1761400000000002002),  -- 职级与社保模板（查看）
(1761300000000000012, 1761400000000002003),  -- 师徒关系（查看）
(1761300000000000012, 1761400000000002100),  -- 数据导入目录
(1761300000000000012, 1761400000000002101),  -- 贝壳业绩导入
(1761300000000000012, 1761400000000002102),  -- 考勤数据导入
(1761300000000000012, 1761400000000002103),  -- 积分数据导入
(1761300000000000012, 1761400000000002104),  -- 导入批次查询
(1761300000000000012, 1761400000000002200),  -- 业绩管理目录
(1761300000000000012, 1761400000000002201),  -- 业绩明细（全量）
(1761300000000000012, 1761400000000002202),  -- 结佣申请（处理）
(1761300000000000012, 1761400000000002203),  -- 结佣调整（处理）
(1761300000000000012, 1761400000000002300),  -- 薪酬计算目录
(1761300000000000012, 1761400000000002301),  -- 算薪批次（全部操作）
(1761300000000000012, 1761400000000002302),  -- 工资明细（全量）
(1761300000000000012, 1761400000000002304),  -- 其他收支录入
(1761300000000000012, 1761400000000002305),  -- 调整与补发（处理）
(1761300000000000012, 1761400000000002306),  -- 发起计算（F）
(1761300000000000012, 1761400000000002307),  -- 锁定批次（F）
(1761300000000000012, 1761400000000002308),  -- 发放工资（F）
(1761300000000000012, 1761400000000002400),  -- 部门收支目录（父目录，为门店成本录入导航）
(1761300000000000012, 1761400000000002403);  -- 门店成本录入

-- ---------- 人事（role_id=1761300000000000013）----------
-- 基础档案（编辑考勤/积分档案）、数据导入（考勤/积分导入）
-- 注：无贝壳业绩导入、导入批次查询、业绩管理、薪酬计算、部门收支、授权/备份
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002000),  -- 基础档案目录
(1761300000000000013, 1761400000000002001),  -- 员工档案（编辑）
(1761300000000000013, 1761400000000002002),  -- 职级与社保模板（编辑）
(1761300000000000013, 1761400000000002003),  -- 师徒关系（编辑）
(1761300000000000013, 1761400000000002100),  -- 数据导入目录
(1761300000000000013, 1761400000000002102),  -- 考勤数据导入
(1761300000000000013, 1761400000000002103);  -- 积分数据导入

-- ---------- 经纪人（role_id=1761300000000000014）----------
-- 业绩管理（本人业绩明细/结佣申请/结佣调整-申请）、薪酬计算（本人工资明细）
-- 注：无算薪批次、奖金录入、其他收支、调整与补发、部门收支、授权/备份、无 F 按钮
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000014, 1761400000000002200),  -- 业绩管理目录
(1761300000000000014, 1761400000000002201),  -- 业绩明细（本人）
(1761300000000000014, 1761400000000002202),  -- 结佣申请（申请调整）
(1761300000000000014, 1761400000000002203),  -- 结佣调整（申请）
(1761300000000000014, 1761400000000002300),  -- 薪酬计算目录
(1761300000000000014, 1761400000000002302);  -- 工资明细（本人）

-- ============================================================
-- 三·补、工作流菜单角色绑定
-- 依据：设计文档第五章 5.1 节，结佣申请走 Warm-Flow 审批（通过/驳回）；
--       奖金录入：店长/总监发起→总监审批→计入当月工资
-- 分配策略：
--   总监     → 工作流管理（流程定义/实例/监控/分类）+ 我的任务（全量）
--   店长     → 我的任务（发起结佣申请/奖金录入、审批结果）
--   算薪人员 → 我的任务（处理结佣、发起记录）
--   经纪人   → 我的任务（发起结佣调整申请）
--   人事     → 无工作流需求（不绑定）
-- ============================================================

-- ---------- 总监：工作流管理 + 我的任务（全量）----------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
-- 工作流目录
(1761300000000000010, 1761400000000011616),
-- 流程分类 + F 按钮
(1761300000000000010, 1761400000000011622),
(1761300000000000010, 1761400000000011623),  -- 流程分类查询
(1761300000000000010, 1761400000000011624),  -- 流程分类新增
(1761300000000000010, 1761400000000011625),  -- 流程分类修改
(1761300000000000010, 1761400000000011626),  -- 流程分类删除
(1761300000000000010, 1761400000000011627),  -- 流程分类导出
-- 流程定义 + F 按钮
(1761300000000000010, 1761400000000011620),
(1761300000000000010, 1761400000000011644),  -- 流程定义查询
(1761300000000000010, 1761400000000011645),  -- 流程定义新增
(1761300000000000010, 1761400000000011646),  -- 流程定义修改
(1761300000000000010, 1761400000000011647),  -- 流程定义删除
(1761300000000000010, 1761400000000011648),  -- 流程定义导出
-- 流程设计
(1761300000000000010, 1761400000000011700),
-- 流程监控 + 子菜单
(1761300000000000010, 1761400000000011630),
(1761300000000000010, 1761400000000011621),  -- 流程实例
(1761300000000000010, 1761400000000011653),  -- 流程实例查询
(1761300000000000010, 1761400000000011654),  -- 流程变量查询
(1761300000000000010, 1761400000000011655),  -- 流程变量修改
(1761300000000000010, 1761400000000011656),  -- 流程实例激活/挂起
(1761300000000000010, 1761400000000011657),  -- 流程实例删除
(1761300000000000010, 1761400000000011658),  -- 流程实例作废
(1761300000000000010, 1761400000000011659),  -- 流程实例撤销
(1761300000000000010, 1761400000000011631),  -- 待办任务
(1761300000000000010, 1761400000000011660),  -- 待办任务修改
-- 我的任务目录 + 子菜单
(1761300000000000010, 1761400000000011618),
(1761300000000000010, 1761400000000011629),  -- 我发起的
(1761300000000000010, 1761400000000011619),  -- 我的待办
(1761300000000000010, 1761400000000011632),  -- 我的已办
(1761300000000000010, 1761400000000011633);  -- 我的抄送

-- ---------- 店长：我的任务（发起结佣/奖金、审批结果）----------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000011, 1761400000000011618),
(1761300000000000011, 1761400000000011629),  -- 我发起的
(1761300000000000011, 1761400000000011619),  -- 我的待办（总监审批后收到通知）
(1761300000000000011, 1761400000000011632),  -- 我的已办
(1761300000000000011, 1761400000000011633);  -- 我的抄送

-- ---------- 算薪人员：我的任务（处理结佣、发起记录）----------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000011618),
(1761300000000000012, 1761400000000011629),  -- 我发起的
(1761300000000000012, 1761400000000011619),  -- 我的待办（结佣审批通过后处理）
(1761300000000000012, 1761400000000011632),  -- 我的已办
(1761300000000000012, 1761400000000011633);  -- 我的抄送

-- ---------- 经纪人：我的任务（发起结佣调整申请）----------
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000014, 1761400000000011618),
(1761300000000000014, 1761400000000011629),  -- 我发起的
(1761300000000000014, 1761400000000011619),  -- 我的待办（调整审批结果）
(1761300000000000014, 1761400000000011632),  -- 我的已办
(1761300000000000014, 1761400000000011633);  -- 我的抄送

-- ============================================================
-- 四、菜单清理与排序调整
-- ============================================================

-- ---------- 删除非生产所需菜单 ----------

-- 系统工具目录及其子菜单
DELETE FROM sys_menu WHERE menu_id IN (1761400000000000003, 1761400000000000115, 1761400000000000116,
  1761400000000001055, 1761400000000001056, 1761400000000001057, 1761400000000001058, 1761400000000001059, 1761400000000001060);

-- AI会话
DELETE FROM sys_menu WHERE menu_id = 1761400000000000006;

-- 客户端管理及其 F 按钮
DELETE FROM sys_menu WHERE menu_id IN (1761400000000000123,
  1761400000000001061, 1761400000000001062, 1761400000000001063, 1761400000000001064, 1761400000000001065);

-- AI控制台（系统监控下）
DELETE FROM sys_menu WHERE menu_id = 1761400000000000121;

-- 清理角色菜单绑定（删除菜单后对应 role_menu 也要清理）
DELETE FROM sys_role_menu WHERE menu_id IN (
  1761400000000000003, 1761400000000000115, 1761400000000000116,
  1761400000000001055, 1761400000000001056, 1761400000000001057,
  1761400000000001058, 1761400000000001059, 1761400000000001060,
  1761400000000000006, 1761400000000000123,
  1761400000000001061, 1761400000000001062, 1761400000000001063,
  1761400000000001064, 1761400000000001065, 1761400000000000121
);

-- ---------- 菜单排序：业务菜单在前，系统管理在后 ----------

-- 业务菜单（V100001 新增的 order_num 已是 10~50，保持不变）

-- 系统管理目录：1 → 90
UPDATE sys_menu SET order_num = 90 WHERE menu_id = 1761400000000000001;

-- 系统监控目录：3 → 95
UPDATE sys_menu SET order_num = 95 WHERE menu_id = 1761400000000000002;

-- 工作流：6 → 60
UPDATE sys_menu SET order_num = 60 WHERE menu_id = 1761400000000011616;

-- 我的任务：7 → 70
UPDATE sys_menu SET order_num = 70 WHERE menu_id = 1761400000000011618;

-- ============================================================
-- 五、工作流定义数据（Warm-Flow）
-- 依据：业务需求说明书 V4.2 + 菜单设计生产上线版
--   1. 结佣申请审批（9.3节）：开始→申请人→核验(算薪人员)→总监审批→结束
--      驳回：总监可驳回回申请人；支持"跳过核验直接总监审批"开关
--   2. 结佣调整审批（9.4节）：漏算补录/金额差异/85折调整，开始→申请人→总监审批→结束
--   3. 奖金录入审批（7.5节）：店长/总监发起→总监审批→计入当月工资
--   4. 算薪批次审批（10.4节）：草稿→待审核→已确认→已锁定，发起人提交→总监审核→锁定
--   5. 补发单审批（10.5节）：工资锁定后少发修正，开始→申请人→总监审批→结束
-- ============================================================

-- ---------- 流程分类 ----------
INSERT INTO flow_category (category_id, parent_id, ancestors, category_name, order_num, del_flag, create_dept, create_by, create_time)
VALUES (1762300000000000200, 0, '', '薪酬审批', 1, '0', 1761000000000000100, 1761100000000000001, now());

-- ============================================================
-- 流程一：结佣申请审批（commission_apply）
-- 依据：业务需求 9.3 节
-- 链路：开始 → 申请人(${initiator}) → 核验(算薪人员) → 总监审批 → 结束
-- 驳回：总监驳回回申请人；核验驳回回申请人
-- 注：核验节点可跳过（通过 skip_condition 配置开关）
-- ============================================================

INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000301, 'commission_apply', '结佣申请审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/workflow/processDefinition/index', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000310, 0, 1762400000000000301, 'commission_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000311, 1, 1762400000000000301, 'commission_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000312, 1, 1762400000000000301, 'commission_verify', '核验(算薪人员)', 'role:1761300000000000012', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000313, 1, 1762400000000000301, 'commission_director', '总监审批', 'role:1761300000000000010', '0.000', '720,200|720,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000314, 2, 1762400000000000301, 'commission_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000320, 1762400000000000301, 'commission_start', 0, 'commission_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000321, 1762400000000000301, 'commission_applicant', 1, 'commission_verify', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

-- 跳过核验直接总监审批（通过 skip_condition 控制开关）
INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000322, 1762400000000000301, 'commission_applicant', 1, 'commission_director', 1, '跳过核验', 'PASS', '#{skip_verify == true}', '410,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000323, 1762400000000000301, 'commission_verify', 1, 'commission_director', 1, NULL, 'PASS', NULL, '590,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000324, 1762400000000000301, 'commission_director', 1, 'commission_end', 2, NULL, 'PASS', NULL, '770,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000325, 1762400000000000301, 'commission_director', 1, 'commission_applicant', 1, '驳回', 'REJECT', NULL, '720,200;360,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000326, 1762400000000000301, 'commission_verify', 1, 'commission_applicant', 1, '核验驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ============================================================
-- 流程二：结佣调整审批（commission_adjust）
-- 依据：业务需求 9.4 节
-- 场景：漏算补录、金额差异、85折调整
-- 链路：开始 → 申请人(${initiator}) → 总监审批 → 结束
-- ============================================================

INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000501, 'commission_adjust', '结佣调整审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/workflow/processDefinition/index', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000510, 0, 1762400000000000501, 'adjust_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000511, 1, 1762400000000000501, 'adjust_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000512, 1, 1762400000000000501, 'adjust_director', '总监审批', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000513, 2, 1762400000000000501, 'adjust_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000520, 1762400000000000501, 'adjust_start', 0, 'adjust_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000521, 1762400000000000501, 'adjust_applicant', 1, 'adjust_director', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000522, 1762400000000000501, 'adjust_director', 1, 'adjust_end', 2, NULL, 'PASS', NULL, '590,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000523, 1762400000000000501, 'adjust_director', 1, 'adjust_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ============================================================
-- 流程三：奖金录入审批（bonus_apply）
-- 依据：业务需求 7.5 节
-- 链路：开始 → 申请人(店长/总监) → 总监审批 → 结束
-- ============================================================

INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000401, 'bonus_apply', '奖金录入审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/workflow/processDefinition/index', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000410, 0, 1762400000000000401, 'bonus_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000411, 1, 1762400000000000401, 'bonus_applicant', '申请人', 'role:1761300000000000011@@role:1761300000000000010', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000412, 1, 1762400000000000401, 'bonus_director', '总监审批', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000413, 2, 1762400000000000401, 'bonus_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000420, 1762400000000000401, 'bonus_start', 0, 'bonus_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000421, 1762400000000000401, 'bonus_applicant', 1, 'bonus_director', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000422, 1762400000000000401, 'bonus_director', 1, 'bonus_end', 2, NULL, 'PASS', NULL, '590,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000423, 1762400000000000401, 'bonus_director', 1, 'bonus_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ============================================================
-- 流程四：算薪批次审批（payroll_batch）
-- 依据：业务需求 10.4 节
-- 链路：开始 → 提交人(算薪人员/店长) → 总监审核 → 总监锁定 → 结束
-- 对应状态机：草稿→待审核→已确认→已锁定
-- ============================================================

INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000601, 'payroll_batch', '算薪批次审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/workflow/processDefinition/index', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000610, 0, 1762400000000000601, 'payroll_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000611, 1, 1762400000000000601, 'payroll_submit', '提交算薪', 'role:1761300000000000012@@role:1761300000000000011', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000612, 1, 1762400000000000601, 'payroll_review', '总监审核', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000613, 1, 1762400000000000601, 'payroll_lock', '总监锁定', 'role:1761300000000000010', '0.000', '720,200|720,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"termination,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000614, 2, 1762400000000000601, 'payroll_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000620, 1762400000000000601, 'payroll_start', 0, 'payroll_submit', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000621, 1762400000000000601, 'payroll_submit', 1, 'payroll_review', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000622, 1762400000000000601, 'payroll_review', 1, 'payroll_lock', 1, '审核通过', 'PASS', NULL, '590,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000623, 1762400000000000601, 'payroll_lock', 1, 'payroll_end', 2, NULL, 'PASS', NULL, '770,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000624, 1762400000000000601, 'payroll_review', 1, 'payroll_submit', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ============================================================
-- 流程五：补发单审批（payroll_supplement）
-- 依据：业务需求 10.5 节
-- 场景：工资已锁定后发现少发，新增补发单并入指定月份
-- 链路：开始 → 申请人(${initiator}) → 总监审批 → 结束
-- ============================================================

INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000701, 'payroll_supplement', '补发单审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/workflow/processDefinition/index', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000710, 0, 1762400000000000701, 'supplement_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000711, 1, 1762400000000000701, 'supplement_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000712, 1, 1762400000000000701, 'supplement_director', '总监审批', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000713, 2, 1762400000000000701, 'supplement_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000720, 1762400000000000701, 'supplement_start', 0, 'supplement_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000721, 1762400000000000701, 'supplement_applicant', 1, 'supplement_director', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000722, 1762400000000000701, 'supplement_director', 1, 'supplement_end', 2, NULL, 'PASS', NULL, '590,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000723, 1762400000000000701, 'supplement_director', 1, 'supplement_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

COMMIT;
