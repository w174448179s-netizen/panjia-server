-- ============================================================
-- V100021: 统一工作流「运行中」状态叫法 + 补店长详情弹窗员工姓名解析权限
--
-- 背景：
-- 1) 状态口径统一。业务侧（结佣明细/详情弹窗等）SUBMITTED 一律显示
--    「审批中」，但工作流系统页（我发起的/我的已办）的流程状态走全局
--    字典 wf_business_status：V1 基线 label=「待审核」，运行库曾被改为
--    「待审批」，导致同一张单两处叫法不一致。系统页状态为粗粒度
--    waiting（运行中），无法按节点细分，故字典统一改为「审批中」，
--    与业务页保持一致（waiting 覆盖所有工作流单据，语义均为运行中）。
--
-- 2) 店长在「我发起的」点详情报「没有访问权限，请联系管理员授权」。
--    根因：详情弹窗 CommissionApplyDetail 会调 useEmployeeMap 解析
--    员工ID→姓名，接口 GET /people/employee/list 要求
--    people:employee:list（员工档案菜单 1761400000000002001）。
--    V100020 店长权限收窄后未包含该权限 → 403（拦截器弹出报错）。
--    修复：在员工档案菜单下新增 F 按钮（同 perms），仅给店长绑按钮
--    而不绑菜单，接口权限生效且侧边栏不出现「员工档案」入口。
-- ============================================================

-- 1) 全局字典：waiting（运行中）统一叫「审批中」，幂等
UPDATE sys_dict_data
SET dict_label = '审批中',
    update_by  = 1761100000000000001,
    update_time = now()
WHERE dict_type = 'wf_business_status'
  AND dict_value = 'waiting';

-- 2) 新增按钮级授权：员工姓名解析（挂员工档案菜单下，不出现在侧边栏）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component,
                      query_param, is_frame, is_cache, menu_type, visible, status,
                      perms, icon, create_dept, create_by, create_time, remark)
VALUES (1761400000000011840, '员工姓名解析', 1761400000000002001, 99, '', NULL,
        NULL, 'N', 'Y', 'F', '0', '0',
        'people:employee:list', '#', 1761000000000000100, 1761100000000000001, now(),
        '工作流详情弹窗员工ID→姓名解析（useEmployeeMap）专用授权，不挂侧边栏入口')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1761300000000000011, 1761400000000011840)
ON CONFLICT (role_id, menu_id) DO NOTHING;
