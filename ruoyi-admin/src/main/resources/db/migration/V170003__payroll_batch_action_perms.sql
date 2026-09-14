-- ============================================================================
-- V170003 工资批次「操作类」权限码补齐 + 按流程节点授予角色
--
-- 背景（越权缺陷修复）：
--   PayrollController 原先**没有任何 @SaCheckPermission**，任何登录用户
--   （含财务）都能调用 /payroll/batch/{id}/approve、/reject，
--   即财务可以审批本应由总监办理的算薪批次。
--
--   同时 sys_menu 只定义了 payroll:batch:list / :calculate / :lock / :release
--   四个权限码，缺少 add / submit / approve / reject，
--   因此「补注解」必须与「建权限码 + 授权」一起做，否则补了注解反而全员无权。
--
-- 授权口径对齐 flow_definition.payroll_batch 的节点办理人：
--   payroll_submit（提交算薪）→ role:…012 财务 + role:…011 店长
--   payroll_review（总监审核）→ role:…010 总监
--   payroll_lock  （总监锁定）→ role:…010 总监
--   故 approve / reject 仅授予总监，财务与店长不授予。
--
-- 注意：本脚本只做 INSERT / 授权收敛，不改动任何既有 role_menu 记录。
-- ============================================================================

BEGIN;

-- ========== 1. 补齐缺失的 4 个按钮权限码（F 型，挂在「算薪批次」2301 下）==========
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002311, '新建批次', 1761400000000002301, 4, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:add', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '新建算薪批次'),
(1761400000000002312, '提交审核', 1761400000000002301, 5, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:submit', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '提交批次进入总监审核'),
(1761400000000002313, '审批通过', 1761400000000002301, 6, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:approve', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '总监审核通过（仅总监）'),
(1761400000000002314, '驳回', 1761400000000002301, 7, '', NULL, NULL,
 'N', 'Y', 'F', '0', '0', 'payroll:batch:reject', '#', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '驳回批次退回已计算（仅总监）')
ON CONFLICT (menu_id) DO NOTHING;

-- ========== 2. 按流程节点办理人授予角色 ==========

-- 2.1 新建批次 / 提交审核 → 总监(010) + 店长(011) + 财务(012)
--     与 payroll:batch:calculate 的既有授权保持同一集合，避免出现「能算不能提」
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002311),  -- 总监 payroll:batch:add
(1761300000000000011, 1761400000000002311),  -- 店长 payroll:batch:add
(1761300000000000012, 1761400000000002311),  -- 财务 payroll:batch:add
(1761300000000000010, 1761400000000002312),  -- 总监 payroll:batch:submit
(1761300000000000011, 1761400000000002312),  -- 店长 payroll:batch:submit
(1761300000000000012, 1761400000000002312)   -- 财务 payroll:batch:submit
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 2.2 审批通过 / 驳回 → **仅总监(010)**
--     这是本次越权修复的关键：财务与店长不得持有该权限码
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002313),  -- 总监 payroll:batch:approve
(1761300000000000010, 1761400000000002314)   -- 总监 payroll:batch:reject
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ========== 3. fail-fast 守卫 ==========
-- 状态不对就让迁移事务整体回滚并中断启动，避免「迁移跑过了但没生效」的静默故障
DO $$
DECLARE
    v_cnt INTEGER;
BEGIN
    -- 3.1 四个权限码必须都已落库
    SELECT count(*) INTO v_cnt FROM sys_menu
     WHERE perms IN ('payroll:batch:add', 'payroll:batch:submit',
                     'payroll:batch:approve', 'payroll:batch:reject')
       AND status = '0';
    IF v_cnt <> 4 THEN
        RAISE EXCEPTION '[V170003] 工资批次权限码缺失：期望 4 个，实际 %', v_cnt;
    END IF;

    -- 3.2 安全不变式：财务(012) 与 店长(011) 绝不能持有 approve / reject
    SELECT count(*) INTO v_cnt FROM sys_role_menu rm
      JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE rm.role_id IN (1761300000000000011, 1761300000000000012)
       AND m.perms IN ('payroll:batch:approve', 'payroll:batch:reject');
    IF v_cnt <> 0 THEN
        RAISE EXCEPTION '[V170003] 财务/店长被误授予工资审批权限，共 % 条，已回滚', v_cnt;
    END IF;

    -- 3.3 总监必须有 approve / reject，否则审批功能不可用
    SELECT count(*) INTO v_cnt FROM sys_role_menu rm
      JOIN sys_menu m ON m.menu_id = rm.menu_id
     WHERE rm.role_id = 1761300000000000010
       AND m.perms IN ('payroll:batch:approve', 'payroll:batch:reject');
    IF v_cnt <> 2 THEN
        RAISE EXCEPTION '[V170003] 总监缺少工资审批权限：期望 2 个，实际 %', v_cnt;
    END IF;

    RAISE NOTICE '[V170003] 工资批次权限补齐并校验通过';
END $$;

COMMIT;
