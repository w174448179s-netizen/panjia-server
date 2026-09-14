-- ============================================================================
-- V170001 流程表单路径 + 菜单权限「终局修正」
-- ============================================================================
-- 段位：17（panjia 项目保留的「修正段位」，必须高于所有业务种子段位）
-- 依据：盘家智管_薪酬与收支模块_业务需求说明书 V4.6 第二章「系统用户与权限」
--
-- ----------------------------------------------------------------------------
-- 【为什么 V100006 修过还要再修一遍】
-- ----------------------------------------------------------------------------
-- V100006 的修复是有效的，但它跑在 installed_rank=7（V100001 刚建完基础数据）。
-- 之后还有 5 个高版本种子脚本执行，其中 4 个把 V100006 的成果覆盖回去了：
--
--   覆盖者(rank)                   被覆盖的内容
--   ----------------------------- ------------------------------------------
--   V120006 (17) import 模板菜单   重插「模板管理」(2510)，parent_id 写回
--                                  系统管理(1) → 财务/人事菜单树再次断裂
--   V140002 (23) perf 流程种子     重插 perf_adjust / perf_received，
--                                  form_path 写回占位错误值
--   V140003 (24) perf 菜单种子     重插空壳顶级目录「数据管理」(2530)
--                                  —— V100003、V100006 都删过它
--   V150001 (26) commission 重建   删旧建新 commission_apply，
--                                  form_path 写回占位错误值
--
-- 净结果（本脚本执行前的本地库实测）：
--   · form_path 7 条中 3 条仍指向 /workflow/processDefinition/index
--     → 用户点【办理】跳到「流程定义列表页」，业务流程走不下去
--   · 顶级空壳目录「数据管理」复活，侧边栏多一个点不开的空目录
--   · 菜单树断裂 2 条（财务、人事各 1 条，均为「模板管理」无父目录）
--
-- 【为什么必须放在版本号最大处】
--   Flyway 按版本号升序执行；out-of-order=true 只允许「补执行」低版本，
--   不会重排顺序。本脚本 170001 > 现有最大 160002，是唯一的「最终仲裁者」：
--   无论空库首建还是既有库升级，跑完所有迁移后本脚本最后生效，状态必然正确。
--
--   ⚠ 后续新增迁移若再次 INSERT/UPDATE flow_definition.form_path 或
--     sys_menu.parent_id，**必须直接写正确值**，不要再依赖本脚本兜底
--     （本脚本只在首次执行时生效，重复启动不会重跑）。
--
-- 【幂等】全部 UPDATE / DELETE / INSERT ... ON CONFLICT DO NOTHING，可重复执行
-- ============================================================================

BEGIN;

-- ============================================================================
-- 一、流程表单路径修复（最高优先：直接解锁业务流程）
-- ============================================================================
-- 【原理】前端 workflowCommon.routerJump() 把 flow_definition.form_path 直接交给
--        router.push()，所以 form_path 必须等于 sys_menu 算出来的真实路由
--        （规则：父菜单 path + '/' + 子菜单 path，见 store/modules/permission.ts:114）
--
-- 【实测映射（逐条比对 sys_menu.path + component）】
--   flow_code            form_path                     对应菜单
--   -------------------- ----------------------------- ----------------------------
--   commission_apply     /performance/apply            2202 结佣申请
--   commission_adjust    /performance/adjust           2203 结佣调整
--   perf_adjust          /performance/adjustment       2620 业绩调整
--   perf_received        /performance/received         2640 实收审批
--   bonus_apply          /payroll/bonus                2303 奖金录入
--   payroll_batch        /payroll/batch                2301 算薪批次
--   payroll_supplement   /payroll/adjust               2305 调整与补发
--
-- 【审批人路由可达性核对（已实测）】审批人必须拥有对应菜单，否则路由 404：
--   总监: 2202 2203 2620 2640 2301 2303 2305   ✓ 全覆盖 7 条流程
--   财务: 2202 2203 2620 2640 2301 2305        ✓ 覆盖其承担的 commission/perf/
--                                                payroll_supplement 审批
--   店长: 2202 2203 2620 2640 2301 2303 2305   ✓ 覆盖其发起的单据
--
-- 只更新「当前值确实是错的」的行，天然幂等。
-- ----------------------------------------------------------------------------

UPDATE flow_definition SET form_path = '/performance/apply', update_time = now()
 WHERE flow_code = 'commission_apply'  AND form_path IS DISTINCT FROM '/performance/apply';
UPDATE flow_definition SET form_path = '/performance/adjust', update_time = now()
 WHERE flow_code = 'commission_adjust' AND form_path IS DISTINCT FROM '/performance/adjust';
UPDATE flow_definition SET form_path = '/performance/adjustment', update_time = now()
 WHERE flow_code = 'perf_adjust'       AND form_path IS DISTINCT FROM '/performance/adjustment';
UPDATE flow_definition SET form_path = '/performance/received', update_time = now()
 WHERE flow_code = 'perf_received'     AND form_path IS DISTINCT FROM '/performance/received';
UPDATE flow_definition SET form_path = '/payroll/bonus', update_time = now()
 WHERE flow_code = 'bonus_apply'       AND form_path IS DISTINCT FROM '/payroll/bonus';
UPDATE flow_definition SET form_path = '/payroll/batch', update_time = now()
 WHERE flow_code = 'payroll_batch'     AND form_path IS DISTINCT FROM '/payroll/batch';
UPDATE flow_definition SET form_path = '/payroll/adjust', update_time = now()
 WHERE flow_code = 'payroll_supplement' AND form_path IS DISTINCT FROM '/payroll/adjust';

-- 节点级 form_path 覆盖优先于定义级；实测当前全部为 NULL，此处为防御性写法：
-- 若有人在流程设计器里给节点单独配过路径，一并纠正到同一业务页。
UPDATE flow_node SET form_path = '/performance/apply'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'commission_apply')
   AND form_path IS DISTINCT FROM '/performance/apply';
UPDATE flow_node SET form_path = '/performance/adjust'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'commission_adjust')
   AND form_path IS DISTINCT FROM '/performance/adjust';
UPDATE flow_node SET form_path = '/performance/adjustment'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'perf_adjust')
   AND form_path IS DISTINCT FROM '/performance/adjustment';
UPDATE flow_node SET form_path = '/performance/received'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'perf_received')
   AND form_path IS DISTINCT FROM '/performance/received';
UPDATE flow_node SET form_path = '/payroll/bonus'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'bonus_apply')
   AND form_path IS DISTINCT FROM '/payroll/bonus';
UPDATE flow_node SET form_path = '/payroll/batch'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'payroll_batch')
   AND form_path IS DISTINCT FROM '/payroll/batch';
UPDATE flow_node SET form_path = '/payroll/adjust'
 WHERE form_path IS NOT NULL
   AND definition_id IN (SELECT id FROM flow_definition WHERE flow_code = 'payroll_supplement')
   AND form_path IS DISTINCT FROM '/payroll/adjust';

-- ============================================================================
-- 二、彻底删除空壳顶级目录「数据管理」(2530)
-- ============================================================================
-- 实测：子菜单数 = 0、角色绑定数 = 0，纯噪音（点不开也看不到内容）。
-- 历史：V100003 删过 → V140003 以「修复孤儿引用」名义 INSERT 回来 → V100006 再删
--       → V140003 之后重跑又回来。本脚本先解绑角色再删菜单，并放在版本号最大处，
--       使其成为最终状态。
-- 说明：V140003 声称 2530 是「import/outbox/people 等运维菜单的挂载点」，
--       但实际上「数据导入」已有独立顶级目录 2100，2530 从未挂载任何菜单。
-- ----------------------------------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id = 1761400000000002530;
DELETE FROM sys_menu      WHERE menu_id = 1761400000000002530;

-- ============================================================================
-- 三、【结构性修复】「模板管理」(2510) 从「系统管理」改挂到「数据导入」
-- ============================================================================
-- 【实测故障】2510 的 parent_id 指向「系统管理」(1)，但
--   · 财务 绑了 2510，却没绑 系统管理(1)        → 菜单树断裂，模板管理不可见
--   · 人事 绑了 2510 及其 4 个员工模板按钮，没绑 1 → 父页面渲染不出，按钮全落空
-- RuoYi 侧边栏只从顶层目录递归渲染，父目录未授权 → 子菜单整枝被丢弃。
--
-- 【为什么改挂而不是给财务/人事补绑「系统管理」】
--   补绑 1 会把用户管理/角色管理/菜单管理/部门/岗位/字典/参数/日志/文件/授权管理/
--   备份恢复 共 11 个子菜单全部暴露给财务和人事 —— 严重越权，不可接受。
--
-- 【为什么改挂到 2100「数据导入」是正解】
--   2510 的组件本就是 import/template/index（导入模板管理：单据导入 + 员工导入），
--   语义上天然属于「数据导入」；且财务与人事均已绑定 2100，一处改动同时修好两个角色。
--   实测 2100 下原有 4 个页面 + 4 个按钮，加上 2510 不会造成任何越权。
--   副作用核对：总监已被 V100006 移除整块「数据导入」(2100)，故总监看不到模板管理
--   —— 符合 V4.6（总监无导入职责）；店长/经纪人未绑 2100，也看不到 —— 符合预期。
-- ----------------------------------------------------------------------------
UPDATE sys_menu SET parent_id = 1761400000000002100, update_time = now()
 WHERE menu_id = 1761400000000002510
   AND parent_id IS DISTINCT FROM 1761400000000002100;

-- 防御性补绑：确保财务、人事都持有 2510 的父目录 2100（实测两者已绑定，
-- 此处在库被重建或角色权限被手工调整后仍能自愈）。
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000012, 1761400000000002100),  -- 财务 → 数据导入（目录）
(1761300000000000013, 1761400000000002100)   -- 人事 → 数据导入（目录）
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ============================================================================
-- 四、人事角色闭环兜底（V4.6 §2：人事管全部门店；无审批权；可发起业绩调整）
-- ============================================================================
-- 本节内容 V100006 第四节已做过，且实测未被后续脚本覆盖；此处仅作幂等兜底，
-- 防止库被重建或权限被人工调整后再次失效。
-- ----------------------------------------------------------------------------

-- 4.1 数据范围：人事应为「全部门店」（data_scope=1），而非「本部门」
--     人事部门 = 富房（根节点），按本部门过滤会查不到任何门店的考勤/积分数据。
UPDATE sys_role SET data_scope = '1', update_time = now()
 WHERE role_id = 1761300000000000013 AND data_scope IS DISTINCT FROM '1';

-- 4.2 补「我的任务」目录做父（人事需要「我发起的」看自己发起的调整单；
--     V4.6 明确人事无审批权，故**不**补 11619「我的待办」）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000011618),  -- 我的任务（目录）
(1761300000000000013, 1761400000000002200),  -- 业绩管理（目录，父 2610/2620）
(1761300000000000013, 1761400000000002510)   -- 模板管理（页面，供 4 个模板按钮挂靠）
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 4.3 回收越权项：人事不承担贝壳导入（那是财务作业面）
DELETE FROM sys_role_menu
 WHERE role_id = 1761300000000000013
   AND menu_id = 1761400000000002101;   -- 贝壳业绩导入

-- 4.4 回收「我的待办」：人事无审批权，保留只会是恒空列表（徒增困惑）
DELETE FROM sys_role_menu
 WHERE role_id = 1761300000000000013
   AND menu_id = 1761400000000011619;

-- 4.5 补「业绩调整」：页面 + 查询 + 发起 + 取消（审批 2623 / 执行 2624 不给）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000013, 1761400000000002620),  -- perf:adjust:list   页面
(1761300000000000013, 1761400000000002621),  -- perf:adjust:query
(1761300000000000013, 1761400000000002622),  -- perf:adjust:add    发起调整申请
(1761300000000000013, 1761400000000002625)   -- perf:adjust:edit   取消调整
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ============================================================================
-- 五、把「我的任务」固定在次级导航首位（用户诉求：流程操作要顺手）
-- ============================================================================
UPDATE sys_menu SET order_num = 1, update_time = now()
 WHERE menu_id = 1761400000000011618
   AND order_num IS DISTINCT FROM 1;

-- ============================================================================
-- 六、终局守卫：状态不对就让迁移失败（fail fast）
-- ============================================================================
-- 本脚本在所有迁移之后执行 —— 若此刻仍有 form_path 指向流程定义列表页、
-- 或「数据管理」空壳目录仍存在、或菜单树仍有断裂，说明有脚本把数据改错了。
-- 直接 RAISE EXCEPTION 让整个迁移事务回滚并中断启动，强制暴露问题，
-- 而不是让用户在使用中才发现「点办理跳错页」。
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    v_bad_path   int;
    v_shell_menu int;
    v_broken     int;
BEGIN
    SELECT count(*) INTO v_bad_path
      FROM flow_definition
     WHERE form_path = '/workflow/processDefinition/index';

    SELECT count(*) INTO v_shell_menu
      FROM sys_menu m
     WHERE m.menu_id = 1761400000000002530;

    SELECT count(*) INTO v_broken
      FROM sys_role_menu rm
      JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE m.menu_type IN ('M', 'C')
       AND m.parent_id <> 0
       AND rm.role_id IN (1761300000000000010, 1761300000000000011,
                          1761300000000000012, 1761300000000000013,
                          1761300000000000014)
       AND NOT EXISTS (SELECT 1 FROM sys_role_menu r2
                        WHERE r2.role_id = rm.role_id AND r2.menu_id = m.parent_id);

    IF v_bad_path > 0 THEN
        RAISE EXCEPTION '[V170001] 仍有 % 条流程 form_path 指向 /workflow/processDefinition/index，请检查种子脚本是否覆盖了正确值', v_bad_path;
    END IF;
    IF v_shell_menu > 0 THEN
        RAISE EXCEPTION '[V170001] 空壳顶级目录「数据管理」(2530) 仍然存在';
    END IF;
    IF v_broken > 0 THEN
        RAISE EXCEPTION '[V170001] 仍有 % 条菜单树断裂（子菜单已授权但父目录未授权）', v_broken;
    END IF;

    RAISE NOTICE '[V170001] 守卫通过：form_path 全部正确、无空壳目录、无菜单树断裂';
END $$;

COMMIT;

-- ============================================================================
-- 验证 SQL（执行后手工核对）
-- ============================================================================
-- A) 流程表单路径 —— 期望 7 条全部指向业务页，指向 processDefinition 的为 0 条
--    SELECT flow_code, flow_name, form_path FROM flow_definition ORDER BY flow_code;
--    SELECT count(*) FROM flow_definition
--     WHERE form_path = '/workflow/processDefinition/index';   -- 期望 0
--
-- B) 空壳顶级目录 —— 期望 0 条
--    SELECT m.menu_id, m.menu_name FROM sys_menu m
--     WHERE m.menu_type = 'M' AND m.parent_id = 0
--       AND NOT EXISTS (SELECT 1 FROM sys_menu c WHERE c.parent_id = m.menu_id);
--
-- C) 菜单树断裂（只看 M/C；F 按钮不参与导航渲染，其父页面解绑属预期）
--    SELECT r.role_name, m.menu_name, p.menu_name AS missing_parent
--      FROM sys_role_menu rm
--      JOIN sys_menu m ON m.menu_id = rm.menu_id
--      JOIN sys_menu p ON p.menu_id = m.parent_id
--      JOIN sys_role r ON r.role_id = rm.role_id
--     WHERE m.parent_id <> 0 AND m.menu_type IN ('M','C')
--       AND rm.role_id IN (1761300000000000010, 1761300000000000011,
--                          1761300000000000012, 1761300000000000013,
--                          1761300000000000014)
--       AND NOT EXISTS (SELECT 1 FROM sys_role_menu r2
--                        WHERE r2.role_id = rm.role_id AND r2.menu_id = m.parent_id);
--    -- 期望 0 行
--
-- D) 「模板管理」挂载点 —— 期望 parent_id = 1761400000000002100（数据导入）
--    SELECT menu_id, menu_name, parent_id FROM sys_menu WHERE menu_id = 1761400000000002510;
--
-- E) 各角色可见「目录+菜单」数（menu_type IN ('M','C')），本脚本执行前后应一致：
--      总监 39 / 店长 15 / 财务 26 / 人事 12 / 经纪人 7
--    （人事 data_scope 由 3 改为 1 属 V100006 已完成项，本脚本仅兜底）
--    SELECT r.role_name, count(*)
--      FROM sys_role_menu rm
--      JOIN sys_menu m ON m.menu_id = rm.menu_id
--      JOIN sys_role r ON r.role_id = rm.role_id
--     WHERE m.menu_type IN ('M','C')
--       AND rm.role_id IN (1761300000000000010, 1761300000000000011,
--                          1761300000000000012, 1761300000000000013,
--                          1761300000000000014)
--     GROUP BY r.role_name ORDER BY r.role_name;
--
-- ============================================================================
-- 遗留（前端/后端改造项，非本脚本职责）
-- ============================================================================
-- 1. 菜单树断裂检测的 M/C 之外的 F 按钮：业务角色的 F 按钮其父页面可能未授权
--    （如经纪人保留 commission:apply:query 但未授权 2202 页面），这是**刻意设计**——
--    后端 @SaCheckPermission 需要该权限串，前端导航不渲染 F，二者互不干扰。
-- 2. 店长持有 commission:apply:add/submit/cancel（V4.6 未明确授予"发起结佣"），
--    但后端强制校验该权限，贸然删除会让店长点击即 403，需产品确认后连同前端按钮显隐一起改。
-- 3. 总监当前挂着整套「系统管理」。实测总监 = 廖明（本租户实际管理者），
--    摘除有锁死风险，建议后续拆出独立"系统管理员"角色而非直接删。
-- 4. bonus_apply / payroll_batch / payroll_supplement 三条流程「已发布未接线」：
--    Java 侧无 startCompleteTask 调用方，需业务侧补发起入口。
--    form_path 已修好，一旦接线即可正常跳转。
-- 5. 人事角色当前未分配给任何用户（V4.6 中人事是独立角色，实际由"行政"兼任财务）。
