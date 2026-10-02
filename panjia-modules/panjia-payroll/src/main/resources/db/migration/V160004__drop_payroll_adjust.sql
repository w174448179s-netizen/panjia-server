-- ============================================================
-- 下线「调整与补发」菜单与 pj_payroll_adjust 表
-- 背景：该功能仅有登记壳，从未接入算薪（无消费方、单据不会置 EXECUTED）；
--      临时补发/扣回统一走「其他收支录入」（pj_payroll_manual_item，
--      算薪已按 manualIncome/manualDeduct 消费）。
-- 需求 10.5 的正式「补发单」（业绩调整/退单红冲自动生成、关联合同、走审批）
-- 后续如需落地再单独立项，不复用本半成品。
-- ============================================================

-- 1. 菜单「调整与补发」(1761400000000002305, payroll:adjust:list) 及角色授权
DELETE FROM sys_role_menu WHERE menu_id = 1761400000000002305;
DELETE FROM sys_menu WHERE menu_id = 1761400000000002305;

-- 2. 从未接线的「补发单审批」流程定义（payroll_supplement，定义 ID 1762400000000000701）
--    含其节点/跳转；该流程零实例零任务，无运行时数据
DELETE FROM flow_skip WHERE definition_id = 1762400000000000701;
DELETE FROM flow_node WHERE definition_id = 1762400000000000701;
DELETE FROM flow_his_task WHERE definition_id = 1762400000000000701;
DELETE FROM flow_task WHERE definition_id = 1762400000000000701;
DELETE FROM flow_instance WHERE definition_id = 1762400000000000701;
DELETE FROM flow_definition WHERE id = 1762400000000000701;

-- 3. 流程配套开关「补发单审批-跳过核验」
DELETE FROM sys_config WHERE config_key = 'panjia.workflow.payroll_supplement.skip_verify';

-- 4. 下线调整/补发单表（存量仅 2 条 2026-10-02 手工测试数据，未参与任何算薪）
DROP TABLE IF EXISTS pj_payroll_adjust;
