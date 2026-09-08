-- ============================================================
-- 盘家智管 · 薪酬与收支模块 · 菜单种子数据（生产上线版）
-- 依据：docs/盘家智管_薪酬与收支_菜单设计_生产上线版.md
-- 说明：
--   1) 仅写 sys_menu，不写 sys_role_menu（超级管理员 userId=1 由
--      SysMenuServiceImpl#selectMenuTreeByUserId 走 selectMenuTreeAll
--      直接见全部菜单；业务角色绑定属部署期任务）。
--   2) sys_menu 无 data_scope 列（设计文档中的 data_scope 已剔除，
--      数据权限在 sys_role 上，由 @DataPermission 注解实现门店维度拦截）。
--   3) 菜单 ID 采用 19 位（17614 + 14 位），落在基线未占用的 0002xxx 段，
--      与设计文档逻辑分段一一对应，避免与基线 000001~000162、001001~001031、
--      011638~011860 冲突。
--   4) F 按钮按 RuoYi 惯例挂在所属 C 菜单下（算薪批次），而非目录。
-- ============================================================

BEGIN;

-- 公共审计字段值（与基线一致）
--   create_dept = 1761000000000000103
--   create_by   = 1761100000000000001
--   create_time = now()

-- ---------- 一级目录：基础档案 (people 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002000, '基础档案', 0, 10, 'people', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'peoples', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '基础档案目录（people 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002001, '员工档案', 1761400000000002000, 1, 'employee', 'people/employee/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:employee:list', 'user', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '员工档案菜单（工号/入职/离职/兼职）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002002, '职级与社保模板', 1761400000000002000, 2, 'level', 'people/level/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:level:list', 'dict', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '职级与社保模板菜单（A0~A5/S1S2/总监、社保比例、底薪）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002003, '师徒关系', 1761400000000002000, 3, 'mentor', 'people/mentor/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:mentor:list', 'tree', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '师徒关系菜单（推荐人-徒弟绑定）');

-- ---------- 一级目录：数据导入 (import 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002100, '数据导入', 0, 20, 'import', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'upload', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '数据导入目录（import 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002101, '贝壳业绩导入', 1761400000000002100, 1, 'shell', 'import/shell/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:shell:list', 'upload', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '贝壳业绩导入菜单（Excel→预览→归档→归一化）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002102, '考勤数据导入', 1761400000000002100, 2, 'attendance', 'import/attendance/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:attendance:list', 'date', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '考勤数据导入菜单（自动计算扣款）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002103, '积分数据导入', 1761400000000002100, 3, 'score', 'import/score/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:score:list', 'star', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '积分数据导入菜单（汇总判定 A/B/C）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002104, '导入批次查询', 1761400000000002100, 4, 'batch', 'import/batch/index', NULL, 'N', 'Y', 'C', '0', '0', 'import:batch:list', 'list', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '导入批次查询菜单（历史批次/原始文件下载/差异比对）');

-- ---------- 一级目录：业绩管理 (performance / commission 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002200, '业绩管理', 0, 30, 'performance', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'chart', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '业绩管理目录（performance / commission 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002201, '业绩明细', 1761400000000002200, 1, 'fact', 'performance/fact/index', NULL, 'N', 'Y', 'C', '0', '0', 'performance:fact:list', 'list', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '业绩明细菜单（归一化后业绩事实）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002202, '结佣申请', 1761400000000002200, 2, 'apply', 'commission/apply/index', NULL, 'N', 'Y', 'C', '0', '0', 'commission:apply:list', 'form', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '结佣申请菜单（待审批/已审批/驳回）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002203, '结佣调整', 1761400000000002200, 3, 'adjust', 'commission/adjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'commission:adjust:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '结佣调整菜单（漏算补录，新增记录不改原始）');

-- ---------- 一级目录：薪酬计算 (payroll 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002300, '薪酬计算', 0, 40, 'payroll', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'money', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '薪酬计算目录（payroll 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002301, '算薪批次', 1761400000000002300, 1, 'batch', 'payroll/batch/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:batch:list', 'documentation', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '算薪批次菜单（草稿→算薪中→已计算→审核中→已批准→已锁定→已发放）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002302, '工资明细', 1761400000000002300, 2, 'detail', 'payroll/detail/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:detail:list', 'list', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '工资明细菜单（应发/扣款/实发，钻取结佣原始）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002303, '奖金录入', 1761400000000002300, 3, 'bonus', 'payroll/bonus/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:bonus:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '奖金录入菜单（店长/总监发起→总监审批→计入当月工资）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002304, '其他收支录入', 1761400000000002300, 4, 'other', 'payroll/other/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:otherearn:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '其他收支录入菜单（手动录入其他收入/其他支出）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002305, '调整与补发', 1761400000000002300, 5, 'adjust', 'payroll/adjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:adjust:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '调整与补发菜单（锁定后修正，新增调整单/补发单）');

-- 算薪批次关键按钮权限（F 类型，挂在算薪批次 C 菜单下）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002306, '发起计算', 1761400000000002301, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:batch:calculate', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '算薪批次-发起计算按钮');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002307, '锁定批次', 1761400000000002301, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:batch:lock', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '算薪批次-锁定批次按钮');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002308, '发放工资', 1761400000000002301, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:batch:release', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '算薪批次-发放工资按钮');

-- ---------- 一级目录：部门收支 (ledger 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002400, '部门收支', 0, 50, 'ledger', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'dashboard', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '部门收支目录（ledger 域）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002401, '部门收支表', 1761400000000002400, 1, 'dept', 'ledger/dept/index', NULL, 'N', 'Y', 'C', '0', '0', 'ledger:dept:list', 'dashboard', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '部门收支表菜单（事件驱动自动生成）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002402, '收支科目配置', 1761400000000002400, 2, 'subject', 'ledger/subject/index', NULL, 'N', 'Y', 'C', '0', '0', 'ledger:subject:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '收支科目配置菜单（收入/支出科目维护、利润公式）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002403, '门店成本录入', 1761400000000002400, 3, 'cost', 'ledger/cost/index', NULL, 'N', 'Y', 'C', '0', '0', 'ledger:cost:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '门店成本录入菜单（物业水电/租金/装修款）');

-- ---------- 系统管理追加项（挂在原生系统管理目录下） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002500, '授权管理', 1761400000000000001, 90, 'license', 'system/license/index', NULL, 'N', 'Y', 'C', '0', '0', 'system:license:list', 'lock', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '授权管理菜单（License 状态/激活/心跳）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002501, '备份恢复', 1761400000000000001, 95, 'backup', 'system/backup/index', NULL, 'N', 'Y', 'C', '0', '0', 'system:backup:list', 'database', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '备份恢复菜单（算薪前自动备份，手动/策略恢复）');

COMMIT;
