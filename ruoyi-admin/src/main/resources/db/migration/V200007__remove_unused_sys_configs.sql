-- ============================================================
-- 清理参数管理中的无用参数（2026-10-21）
-- 以下 5 个 sys_config 参数在前后端代码中均无任何引用（全库检索 config_key 零命中），
-- 属历史方案遗留，删除以免误导配置人员：
--   1. panjia.workflow.commission_apply.skip_verify   结佣申请审批-跳过核验
--      —— 流程变量 skip_verify 从未被 Java 侧赋值，网关条件 #{skip_verify == true} 恒不命中
--   2. panjia.workflow.commission_adjust.skip_verify  结佣调整审批-跳过核验
--      —— 同上
--   3. panjia.performance.broker_conversion_rate      业绩-经纪人折算比例
--      —— 折算比例已由 pj_payroll_conversion_rule 规则表接管（ConversionFactorPort 统一取数）
--   4. panjia.flow.director_timeout_hours             总监审批自动通过超时(小时)
--      —— 超时自动审批已改为流程节点 ext 的 AutoApproval 配置（WorkflowGlobalListener），
--         不再读取该全局参数
--   5. panjia.commission.diff_tolerance               结佣-实收应收差异容忍阈值
--      —— 跳过财务节点由 panjia.commission.skip_finance_when_match 开关 + 网关等值判断实现，
--         无容忍阈值逻辑
-- 保留的在用参数：panjia.flow.skip_finance、panjia.commission.skip_finance_when_match、
--   sys.user.initPassword、sys.account.registerUser、sys.oss.previewListResource
-- 幂等：已清理的环境重复执行影响 0 行。
-- ============================================================

DELETE FROM sys_config WHERE config_key IN (
    'panjia.workflow.commission_apply.skip_verify',
    'panjia.workflow.commission_adjust.skip_verify',
    'panjia.performance.broker_conversion_rate',
    'panjia.flow.director_timeout_hours',
    'panjia.commission.diff_tolerance'
);
