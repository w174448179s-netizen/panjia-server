-- ============================================================================
-- V100001 盘家智管 sys_menu 最终态种子（清库重建合并版）
-- 全部业务菜单（基础档案/数据导入/业绩管理/薪酬计算/部门收支/综合查询）+ F 按钮，
-- 以及对 V1 基线菜单的清理（系统工具/AI/客户端）与排序调整。
-- 排序/改名/挂载修复链均已折叠为最终值：
--   · 我的任务(11618) order=1（V100004→5、V100006/V100007→1）
--   · 结佣调整(2203) order=4（V100008）
--   · 业绩查询(2650) 挂综合查询(2700) order=1（V100018）
--   · 考勤明细→考勤管理（V100029）、绩效积分→积分管理（V100030）
--   · 提成点调整最终 ID=2320/2321/2322（V100027 建 2311~2313 与 V160003 撞号，
--     V100028 修复，2311/2312 恢复为算薪批次按钮 payroll:batch:add/submit）
--   · 空壳顶级目录「数据管理」(2530) 删除链（V100003/V100006/V100007/V170001）
--     最终态=不创建，本文件无其 INSERT
-- 合并自：V100001,V100002,V100004,V100006,V100007,V100008,V100012,V100014,
--         V100017,V100018,V100021,V100025,V100026,V100027,V100028,V100029,
--         V100030,V100031,V100033（V100003/V170001 仅含 2530 删除链，净效果为零）
-- ============================================================================

-- ============================================================
-- 一、一级目录与业务菜单（折叠后的最终行）
-- ============================================================

-- ---------- 一级目录：基础档案 (people 域) ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002000, '基础档案', 0, 10, 'people', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'peoples', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '基础档案目录（people 域）');

-- 注：panjia-people V110003 会将本菜单改名为「员工管理」并增删 F 按钮（跨模块链，此处保留 V100001 原始行）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002001, '员工档案', 1761400000000002000, 1, 'employee', 'people/employee/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:employee:list', 'user', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '员工档案菜单（工号/入职/离职/兼职）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002002, '职级与社保模板', 1761400000000002000, 2, 'level', 'people/level/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:level:list', 'dict', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '职级与社保模板菜单（A0~A5/S1S2/总监、社保比例、底薪）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002003, '师徒关系', 1761400000000002000, 3, 'mentor', 'people/mentor/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:mentor:list', 'tree', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '师徒关系菜单（推荐人-徒弟绑定）');

-- ---------- 考勤明细（V100025）→ 改名「考勤管理」（V100029，已折叠） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002010, '考勤管理', 1761400000000002000, 4, 'attendance', 'people/attendance/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:attendance:list', 'date', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤明细菜单（人事/总监登记与维护员工日考勤）');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002011, '考勤新增', 1761400000000002010, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:attendance:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤明细-新增按钮');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002012, '考勤修改', 1761400000000002010, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:attendance:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤明细-修改按钮');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002013, '考勤删除', 1761400000000002010, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:attendance:remove', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤明细-删除按钮');

-- ---------- 绩效积分（V100026）→ 改名「积分管理」（V100030，已折叠） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002020, '积分管理', 1761400000000002000, 5, 'score', 'people/score/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:score:list', 'star', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '绩效积分明细（人事/总监查询积分汇总、提交月度审批）');

-- ---------- 积分新增/删除按钮（V100031） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002021, '积分新增', 1761400000000002020, 1, '', NULL, NULL, 'N', 'N', 'F', '0', '0', 'people:score:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '手工新增积分记录（补录/修正）'),
(1761400000000002022, '积分删除', 1761400000000002020, 2, '', NULL, NULL, 'N', 'N', 'F', '0', '0', 'people:score:remove', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '删除积分记录（期间锁定后不可删）')
ON CONFLICT (menu_id) DO NOTHING;

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

-- 注：唯一「新签明细」菜单为 2610（perms=perf:fact:*，由 panjia-performance V140003 提供）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002202, '结佣明细', 1761400000000002200, 3, 'apply', 'commission/apply/index', NULL, 'N', 'Y', 'C', '0', '0', 'commission:apply:list', 'form', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '结佣明细菜单（待审批/已审批/驳回）');

-- 结佣调整：order_num 折叠 V100008（3 → 4）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002203, '结佣调整', 1761400000000002200, 4, 'adjust', 'commission/adjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'commission:adjust:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '结佣调整菜单（漏算补录，新增记录不改原始）');

-- ---------- 发起调整按钮（V100033，commission:adjust:add，财务/总监专用） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002204, '发起调整', 1761400000000002203, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'commission:adjust:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '结佣调整-发起调整按钮（财务/总监）')
ON CONFLICT (menu_id) DO NOTHING;

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

-- 注：2307「锁定批次」在 V100001 建立；panjia-payroll V160004 会将其下线（审批收敛「我的待办」），跨模块链此处保留原始行
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002307, '锁定批次', 1761400000000002301, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:batch:lock', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '算薪批次-锁定批次按钮');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002308, '发放工资', 1761400000000002301, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:batch:release', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '算薪批次-发放工资按钮');

-- ---------- 薪酬规则配置（V100002） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002310, '薪酬规则配置', 1761400000000002300, 6, 'rule', 'payroll/rule/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:rule:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '薪酬规则配置菜单（职级/底薪/提点/社保/公积金/折算规则，只影响新算月份）');

-- ---------- 算薪批次 新建/提交按钮（V100028 恢复 V160003 定义，修复与提成点调整的 ID 撞号） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002311, '新建批次', 1761400000000002301, 4, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:add', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '新建算薪批次'),
(1761400000000002312, '提交审核', 1761400000000002301, 5, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:submit', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提交批次进入总监审核')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 提成点调整（V100027 建 2311~2313 撞号，V100028 迁移到最终 ID 2320/2321/2322） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002320, '提成点调整', 1761400000000002300, 7, 'rateadjust', 'payroll/rateadjust/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:rateadjust:list', 'edit', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提成点调整菜单（财务登记业绩扣点调整，总监审批通过后按生效区间扣点）')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002321, '登记调整', 1761400000000002320, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:rateadjust:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提成点调整-登记/修改/删除/提交按钮'),
(1761400000000002322, '撤销调整', 1761400000000002320, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'payroll:rateadjust:cancel', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提成点调整-撤销按钮（撤回在途流程/作废生效中调整）')
ON CONFLICT (menu_id) DO NOTHING;

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

-- ---------- 业绩作废/恢复按钮（V100012；V100017 幂等补齐已折叠） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002613, '业绩作废/恢复', 1761400000000002610, 3, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'perf:fact:void', '#', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, '总监作废/恢复业绩事实（不参与算薪/落入当月）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 一级目录：综合查询（V100018，order 35 介于业绩管理30与薪酬计算40之间） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002700, '综合查询', 0, 35, 'comprehensive', NULL, NULL, 'N', 'Y', 'M', '0', '0', NULL, 'search', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, '综合查询目录（业绩查询 + 工资查询）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 工资查询（V100018） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002701, '工资查询', 1761400000000002700, 2, 'my-salary', 'payroll/my/index', NULL, 'N', 'Y', 'C', '0', '0', 'payroll:my:query', 'money', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, '工资查询（本人视角：经纪人/店长/总监/财务仅查自己工资，可追溯结佣）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 业绩查询（V100014 建，V100018 迁入综合查询：parent/order 已折叠为最终值） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002650, '业绩查询', 1761400000000002700, 1, 'search', 'performance/search/index', NULL, 'N', 'Y', 'C', '0', '0', 'perf:fact:list', 'search', '', '', 176100000000000100, 1761100000000000001, now(), NULL, NULL, '完整业绩查询（合同维度：新签/实收/调整/实收审批/结佣状态）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 考勤查询（V100025，全员仅查本人） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002702, '考勤查询', 1761400000000002700, 3, 'my-attendance', 'people/attendance/my', NULL, 'N', 'Y', 'C', '0', '0', 'people:attendance:my:query', 'date', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '考勤查询（全员仅查本人日考勤记录）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 员工姓名解析按钮（V100021，工作流详情弹窗专用，不挂侧边栏） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component,
                      query_param, is_frame, is_cache, menu_type, visible, status,
                      perms, icon, create_dept, create_by, create_time, remark)
VALUES (1761400000000011840, '员工姓名解析', 1761400000000002001, 99, '', NULL,
        NULL, 'N', 'Y', 'F', '0', '0',
        'people:employee:list', '#', 1761000000000000100, 1761100000000000001, now(),
        '工作流详情弹窗员工ID→姓名解析（useEmployeeMap）专用授权，不挂侧边栏入口')
ON CONFLICT (menu_id) DO NOTHING;

-- ============================================================
-- 二、V1 基线菜单清理（V100001：删除非生产所需菜单）
-- （基线建这些菜单在先，故删除语句保留；对应 sys_role_menu 清理见 V100002，
--   基线未插入任何角色绑定，删除绑定语句为空操作已省略）
-- ============================================================

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

-- ============================================================
-- 三、V1 基线菜单排序（V100001 建立业务菜单在前；V100004/V100006/V100007 的
--     「我的任务」调整链已折叠为最终 order_num=1）
-- ============================================================

-- 系统管理目录：1 → 90
UPDATE sys_menu SET order_num = 90 WHERE menu_id = 1761400000000000001;

-- 系统监控目录：3 → 95
UPDATE sys_menu SET order_num = 95 WHERE menu_id = 1761400000000000002;

-- 工作流：6 → 60
UPDATE sys_menu SET order_num = 60 WHERE menu_id = 1761400000000011616;

-- 我的任务：7 → 70（V100001）→ 5（V100004）→ 1（V100006/V100007），最终值 1
UPDATE sys_menu SET order_num = 1 WHERE menu_id = 1761400000000011618;

-- ============================================================
-- 四、跨模块菜单修复（V100006/V100007/V100008，目标菜单由其他模块迁移创建）
-- ============================================================

-- 「模板管理」(2510，panjia-import 创建) 从「系统管理」改挂到「数据导入」：
-- 修复财务/人事菜单树断裂。注意：panjia-import V120006 会重插 2510（parent=1），
-- 其合并文件需保留 parent_id=2100 的最终值，否则本语句（V100001 段位）先执行无法兜底。
UPDATE sys_menu SET parent_id = 1761400000000002100
 WHERE menu_id = 1761400000000002510;

-- 「实收明细」(2640，panjia-performance V140003 创建) 菜单名与业务流程对齐（V100008）
UPDATE sys_menu SET menu_name = '实收明细' WHERE menu_id = 1761400000000002640 AND menu_name != '实收明细';
