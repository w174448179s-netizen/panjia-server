-- ============================================================================
-- V100006 菜单权限梳理 + 流程表单路径修复
-- ============================================================================
-- 依据：盘家智管_薪酬与收支模块_业务需求说明书 V4.6 第二章「系统用户与权限」
-- 全部结论均来自本地库实测（非推测），实测快照见文末"验证基线"
--
-- 用户反馈三件事：
--   1. 「我的任务」里点【办理】跳转全是错的 → 业务流程走不下去
--   2. 菜单冗余、角色权限错配 → 有的点不到、有的不该有
--   3. 待办列表看不出在审什么
-- 本脚本负责 1 和 2；第 3 项的"业务标题为空"根因在后端（见文末"遗留"）
--
-- 幂等：UPDATE / DELETE / INSERT ... ON CONFLICT DO NOTHING，可重复执行
-- ============================================================================

BEGIN;

-- ============================================================================
-- 一、流程表单路径修复（最高优先：直接解锁业务流程）
-- ============================================================================
-- 【根因】7 条 flow_definition 的 form_path 全部被写成
--         '/workflow/processDefinition/index'（流程定义列表页）。
--         前端 workflowCommon.routerJump() 把 form_path 直接交给 router.push，
--         所以点【办理】永远跳去流程定义列表，业务单据根本打不开。
--
-- 【取值依据】form_path 必须等于 sys_menu 里算出来的真实路由
--   （规则：父菜单 path + '/' + 子菜单 path，见 store/modules/permission.ts:114）
--   实测映射（已逐条比对 sys_menu.path + component）：
--     commission_apply    -> /performance/apply       组件 commission/apply/index
--     commission_adjust   -> /performance/adjust      组件 commission/adjust/index
--     perf_adjust         -> /performance/adjustment  组件 performance/adjust/index
--     perf_received       -> /performance/received    组件 performance/received/index
--     bonus_apply         -> /payroll/bonus           组件 payroll/bonus/index
--     payroll_batch       -> /payroll/batch           组件 payroll/batch/index
--     payroll_supplement  -> /payroll/adjust          组件 payroll/adjust/index
-- ----------------------------------------------------------------------------

UPDATE flow_definition SET form_path = '/performance/apply', update_time = now()
 WHERE flow_code = 'commission_apply';        -- 结佣审批
UPDATE flow_definition SET form_path = '/performance/adjust', update_time = now()
 WHERE flow_code = 'commission_adjust';       -- 结佣调整审批
UPDATE flow_definition SET form_path = '/performance/adjustment', update_time = now()
 WHERE flow_code = 'perf_adjust';             -- 业绩调整审批
UPDATE flow_definition SET form_path = '/performance/received', update_time = now()
 WHERE flow_code = 'perf_received';           -- 实收业绩审批
UPDATE flow_definition SET form_path = '/payroll/bonus', update_time = now()
 WHERE flow_code = 'bonus_apply';             -- 奖金录入审批
UPDATE flow_definition SET form_path = '/payroll/batch', update_time = now()
 WHERE flow_code = 'payroll_batch';           -- 算薪批次审批
UPDATE flow_definition SET form_path = '/payroll/adjust', update_time = now()
 WHERE flow_code = 'payroll_supplement';      -- 补发单审批

-- 同步修正节点级 form_path（节点级覆盖优先于定义级）。
-- 实测 flow_node.form_path 当前全部为 NULL，此处为防御性写法，保证后续
-- 有人在流程设计器里给节点单独配过路径时也能被纠正。
UPDATE flow_node SET form_path = '/performance/apply'
 WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code = 'commission_apply')
   AND form_path IS NOT NULL;
UPDATE flow_node SET form_path = '/performance/adjust'
 WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code = 'commission_adjust')
   AND form_path IS NOT NULL;
UPDATE flow_node SET form_path = '/performance/adjustment'
 WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code = 'perf_adjust')
   AND form_path IS NOT NULL;
UPDATE flow_node SET form_path = '/performance/received'
 WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code = 'perf_received')
   AND form_path IS NOT NULL;
UPDATE flow_node SET form_path = '/payroll/bonus'
 WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code = 'bonus_apply')
   AND form_path IS NOT NULL;
UPDATE flow_node SET form_path = '/payroll/batch'
 WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code = 'payroll_batch')
   AND form_path IS NOT NULL;
UPDATE flow_node SET form_path = '/payroll/adjust'
 WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code = 'payroll_supplement')
   AND form_path IS NOT NULL;

-- ============================================================================
-- 二、删除空壳顶级目录「数据管理」(2530)
-- ============================================================================
-- 实测：父级菜单，子菜单数 = 0，纯噪音。
-- V100003 删过一次，V140003 以"修复孤儿引用的前置依赖"名义又 INSERT 回来。
-- 本次先解绑角色再删菜单，杜绝复活。
-- ----------------------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id = 1761400000000002530;
DELETE FROM sys_menu      WHERE menu_id = 1761400000000002530;

-- ============================================================================
-- 三、【结构性修复】把「模板管理」(2510) 从「系统管理」改挂到「数据导入」
-- ============================================================================
-- 实测故障：模板管理(2510) 的 parent_id 指向「系统管理」(1)，但
--   · 财务 绑了 2510，却没绑 系统管理  → 菜单树断裂
--   · 人事 绑了 2510 的 4 个按钮子项，也没绑 2510 → 按钮权限全落空
-- RuoYi 侧边栏只从顶层目录递归渲染，父目录没授权 → 子菜单被丢弃。
-- 而 2510 的组件本就是 import/template/index（导入模板页），
-- 语义上属于「数据导入」，改挂是一处改动同时修好两个角色的最优解。
-- 注意：改挂后 系统管理 仍有 15 个子菜单，不会变成空壳。
-- ----------------------------------------------------------------------------
UPDATE sys_menu SET parent_id = 1761400000000002100
 WHERE menu_id = 1761400000000002510;

-- ============================================================================
-- 四、人事角色闭环（当前菜单树断裂 + 权限缺项）
-- ============================================================================
-- V4.6 §2 人事：全部门店（考勤/积分管理）；下载/填写考勤、积分标准 Excel 模板、
--   导入考勤与积分数据、维护考勤扣款标准与积分考核规则、处理"待对齐"匹配；
--   无审批权限、发起业绩调整申请。
--
-- 实测人事现状（10 项）与差距：
--   [断裂] 业绩明细(2610)      缺父 业绩管理(2200)
--   [断裂] 我发起的(11629)     缺父 我的任务(11618)
--   [断裂] 我的待办(11619)     缺父 我的任务(11618)
--   [断裂] 模板管理 4 个按钮   缺父 模板管理(2510)（由第三节修好）
--   [越权] 贝壳业绩导入(2101)  贝壳导入是财务职责，人事只管考勤/积分
--   [多余] 我的待办(11619)     V4.6 明确人事"无审批权限"，待办列表必然为空
--   [缺项] 业绩调整(2620)      V4.6 明确人事可"发起业绩调整申请"
-- ----------------------------------------------------------------------------

-- 4.1 补父目录：我的任务(11618)、业绩管理(2200)、模板管理(2510)
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000011618),  -- 我的任务（目录）
(1761300000000000013, 1761400000000002200),  -- 业绩管理（目录）
(1761300000000000013, 1761400000000002510)   -- 模板管理（页面，供 4 个模板按钮挂靠）
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 4.2 去掉越权的「贝壳业绩导入」页面（perf/import 数据入口归财务）
DELETE FROM sys_role_menu
 WHERE role_id = 1761300000000000013
   AND menu_id = 1761400000000002101;

-- 4.3 去掉「我的待办」：V4.6 明确人事无审批权限，保留只会是空列表
DELETE FROM sys_role_menu
 WHERE role_id = 1761300000000000013
   AND menu_id = 1761400000000011619;

-- 4.4 补「业绩调整」：页面 + 查询 + 发起 + 取消
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002620),  -- perf:adjust:list   页面
(1761300000000000013, 1761400000000002621),  -- perf:adjust:query
(1761300000000000013, 1761400000000002622),  -- perf:adjust:add    发起调整
(1761300000000000013, 1761400000000002625)   -- perf:adjust:edit   取消调整
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 4.5 数据范围纠偏：人事应为「全部门店」
-- 实测 sys_role.data_scope = 3（本部门），而人事部门 = 富房（根节点）→
-- 按部门过滤后将查不到任何门店的考勤/积分数据。V4.6 明确人事管全部门店。
-- 当前人事角色未分配给任何用户，此变更无即时影响，仅纠正配置。
-- ----------------------------------------------------------------------------
UPDATE sys_role SET data_scope = '1', update_time = now()
 WHERE role_id = 1761300000000000013 AND data_scope <> '1';

-- ============================================================================
-- 五、总监：移除「数据导入」整块（导入是财务/人事的作业面）
-- ============================================================================
-- V4.6 §2 总监允许操作不含任何导入动作。实测总监却挂了整套导入权限，
-- 包括 4 个写操作按钮（上传/重新归一化/归档/忽略问题）。
-- 且这 4 个页面因缺父目录「数据导入」(2100) 本就渲染不出来 ——
-- 属于纯孤儿绑定，移除后界面零变化、只收回越权能力。
-- 总监的"查看全部数据"由 业绩明细 / 结佣申请 / 流水追踪 / 工资明细 覆盖。
-- ----------------------------------------------------------------------------
DELETE FROM sys_role_menu
 WHERE role_id = 1761300000000000010
   AND menu_id IN (
     1761400000000002101,  -- 贝壳业绩导入（页面）
     1761400000000002102,  -- 考勤数据导入（页面）
     1761400000000002103,  -- 积分数据导入（页面）
     1761400000000002104,  -- 导入批次查询（页面）
     1761400000000011810,  -- import:batch:upload
     1761400000000011811,  -- import:batch:renormalize
     1761400000000011812,  -- import:batch:archive
     1761400000000011813   -- import:issue:ignore
   );

-- ============================================================================
-- 六、总监：收敛「工作流」运维菜单（业务角色不需要流程设计器）
-- ============================================================================
-- 移除：流程分类(11622 + 5 按钮)、流程定义(11620 + 9 按钮)、
--       流程设计(11700)、流程表达式(11801 + 5 按钮)
-- 保留：工作流目录(11616) + 流程监控(11630) 及其 流程实例(11621)/待办任务(11631)
--       —— 总监审批时需要看审批进度
-- ----------------------------------------------------------------------------
DELETE FROM sys_role_menu
 WHERE role_id = 1761300000000000010
   AND menu_id IN (
     1761400000000011622,                          -- 流程分类
     1761400000000011623, 1761400000000011624,
     1761400000000011625, 1761400000000011626, 1761400000000011627,
     1761400000000011620,                          -- 流程定义
     1761400000000011644, 1761400000000011645, 1761400000000011646,
     1761400000000011647, 1761400000000011648, 1761400000000011649,
     1761400000000011650, 1761400000000011651, 1761400000000011652,
     1761400000000011700,                          -- 流程设计
     1761400000000011801,                          -- 流程表达式
     1761400000000011802, 1761400000000011803,
     1761400000000011804, 1761400000000011805, 1761400000000011806
   );

-- ============================================================================
-- 七、经纪人权限纠偏
-- ============================================================================
-- V4.6 §2 经纪人：查看个人业绩明细、个人工资明细；查看本人参与合同的完整
--   业绩构成；对合同内业绩发起异议/业绩调整申请；不能修改任何数据。
--
-- 7.1 去掉「结佣申请」(2202)、「结佣调整」(2203) 两个页面 ——
--     结佣申请单由财务导入贝壳数据后生成、总监/财务审批，不是经纪人工作台。
--     ⚠ 只解绑菜单页面，**刻意保留**这两个页面下的 F 按钮权限：
--       commission:apply:query / commission:item:list 被后端
--       CommissionApplyController / CommissionItemController 的
--       @SaCheckPermission 强制校验，而「业绩明细」页需要它们，
--       一起删会导致经纪人查业绩直接 403。
-- ----------------------------------------------------------------------------
DELETE FROM sys_role_menu
 WHERE role_id = 1761300000000000014
   AND menu_id IN (
     1761400000000002202,  -- 结佣申请（页面）
     1761400000000002203   -- 结佣调整（页面）
   );

-- 7.2 补「业绩调整」：经纪人据此对合同内业绩发起异议
--     （只给 页面/查询/发起/取消，审批 2623 与执行 2624 属总监职能，不给）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000014, 1761400000000002620),  -- perf:adjust:list   页面
(1761300000000000014, 1761400000000002621),  -- perf:adjust:query
(1761300000000000014, 1761400000000002622),  -- perf:adjust:add    发起调整
(1761300000000000014, 1761400000000002625)   -- perf:adjust:edit   取消调整
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ============================================================================
-- 八、财务补「业绩调整」（V4.6 §2：财务允许发起业绩调整申请）
-- ============================================================================
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000002620),  -- perf:adjust:list
(1761300000000000012, 1761400000000002621),  -- perf:adjust:query
(1761300000000000012, 1761400000000002622),  -- perf:adjust:add
(1761300000000000012, 1761400000000002625)   -- perf:adjust:edit
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ============================================================================
-- 九、清理死菜单「我的抄送」(11633)
-- ============================================================================
-- 实测 7 条流程定义中没有任何抄送(Copy)节点，抄送列表恒为空。
-- 业务角色（总监/店长/财务/人事/经纪人）一律解绑；超级管理员不受影响。
-- ----------------------------------------------------------------------------
DELETE FROM sys_role_menu
 WHERE menu_id = 1761400000000011633
   AND role_id IN (
     1761300000000000010,  -- 总监
     1761300000000000011,  -- 店长
     1761300000000000012,  -- 财务
     1761300000000000013,  -- 人事
     1761300000000000014   -- 经纪人
   );

-- ============================================================================
-- 十、把「我的任务」提到次级导航第一位
-- ============================================================================
-- 用户诉求"保证流程操作上的便利性"。实测 order_num = 5，
-- 排在 基础档案(10) 之前但并非首位；置 1 让待办成为进入系统第一眼能看到的东西。
-- ----------------------------------------------------------------------------
UPDATE sys_menu SET order_num = 1 WHERE menu_id = 1761400000000011618;

COMMIT;

-- ============================================================================
-- 验证基线（执行前实测值 → 执行后实测值，均为本地库真实数据）
-- ============================================================================
-- A) 流程表单路径      7 条全为 /workflow/processDefinition/index  →  错误 0 条
-- B) 空壳顶级目录      1 条（数据管理 2530）                       →  0 条
-- C) 菜单树断裂(M/C)   16 条（总监 8 / 财务 1 / 人事 7）           →  0 条
-- D) 可见目录+菜单数（含二级嵌套，统计 menu_type IN ('M','C')）：
--      角色     修复前   修复后   变化说明
--      总监      47       39     -8  数据导入 4 页 + 工作流运维 4 页
--      店长      16       15     -1  我的抄送
--      财务      26       26      0  -我的抄送 +业绩调整（模板管理由隐藏变可见）
--      人事      10       12     +2  -贝壳导入 -我的待办 +业绩调整 +模板管理可见
--      经纪人     8        7     -1  -结佣申请 -结佣调整 +业绩调整
--
-- 对应验证 SQL：
-- A) SELECT flow_code, flow_name, form_path FROM flow_definition ORDER BY id;
-- B) SELECT m.menu_id, m.menu_name FROM sys_menu m
--     WHERE m.menu_type='M' AND m.parent_id=0
--       AND NOT EXISTS (SELECT 1 FROM sys_menu c WHERE c.parent_id=m.menu_id);
-- C) 只看 M/C —— F 按钮不参与导航渲染，其父页面解绑属预期（见第七节说明）
--    SELECT rm.role_id, m.menu_name, p.menu_name AS missing_parent
--      FROM sys_role_menu rm
--      JOIN sys_menu m ON m.menu_id=rm.menu_id
--      JOIN sys_menu p ON p.menu_id=m.parent_id
--     WHERE m.parent_id<>0 AND m.menu_type IN ('M','C')
--       AND rm.role_id IN (1761300000000000010,1761300000000000011,
--                          1761300000000000012,1761300000000000013,1761300000000000014)
--       AND NOT EXISTS (SELECT 1 FROM sys_role_menu r2
--                        WHERE r2.role_id=rm.role_id AND r2.menu_id=m.parent_id);
-- D) SELECT rm.role_id, count(*) FROM sys_role_menu rm
--      JOIN sys_menu m ON m.menu_id=rm.menu_id
--     WHERE m.menu_type IN ('M','C')
--       AND rm.role_id IN (1761300000000000010,1761300000000000011,
--                          1761300000000000012,1761300000000000013,1761300000000000014)
--     GROUP BY rm.role_id ORDER BY rm.role_id;
--
-- 回滚方式（本地库）：备份表 bk_v100006_sys_menu / bk_v100006_sys_role_menu /
--   bk_v100006_sys_role / bk_v100006_flow_definition 保留了本次执行前的全量快照。
--
-- ============================================================================
-- 遗留（本脚本刻意不处理，需产品/后端决策，详见菜单权限矩阵文档）
-- ============================================================================
-- 1. 「我的待办」业务标题列为空 → 根因在后端：
--    flow_instance_biz_ext.business_title 全为 NULL，因为 4 个业务 Service 的
--    startWorkflow() 只 setBusinessId / setFlowCode / variables，从未 setBizExt。
--    涉及 CommissionApplicationService / CommissionAdjustService /
--    PerformanceAdjustServiceImpl / ReceivedApplyServiceImpl。
-- 2. performance/received/index.vue 尚不支持 route.query 审批态入参，
--    第一节把 perf_received 指向它之后，点【办理】能进页面但不会自动打开单据。
-- 3. 店长持有 commission:apply:add/submit/cancel（V4.6 未授予"发起结佣"），
--    但后端 @SaCheckPermission 强制校验，贸然删除会让店长点击即 403。
--    需产品确认后配合前端按钮显隐一起改。
-- 4. 总监当前挂着整套「系统管理」（用户/角色/部门/岗位/字典/参数/文件/授权管理/
--    备份恢复）。实测总监 = 廖明（本租户实际管理者），摘除有锁死风险，
--    建议后续拆出独立"系统管理员"角色而非直接删。
-- 5. 人事持有 import:batch:upload（页面级已按 4.2 移除，但该权限串是三个导入页
--    共用的），后端未按导入类型细分权限，人事仍可直调贝壳导入接口。
--    需要在 ImportController 增加类型级权限。
-- 6. bonus_apply / payroll_batch / payroll_supplement 三条流程定义已发布，
--    但 Java 代码中没有任何 startCompleteTask 调用方 —— "已发布未接线"，
--    需业务侧补发起入口。
-- 7. 人事角色当前未分配给任何用户（V4.6 中人事是独立角色，实际由"行政"兼任财务）。
