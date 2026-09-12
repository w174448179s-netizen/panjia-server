-- ============================================================
-- V140005 业绩域参数：经纪人折算比例
-- ------------------------------------------------------------
-- 贝壳业绩明细表中的当月应收/实收为折算后「当前金额」，
-- 业绩事实的原始金额 origin_amount = 当前金额 / 经纪人折算比例。
-- 参数落 sys_config（RuoYi 参数配置表），默认 0.85（85%），
-- 目前只供后端计算落库，不提供界面维护；如需调整直接改参数值。
-- 幂等：按 config_key 判重，重复执行不报错。
-- ============================================================

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type,
                        create_dept, create_by, create_time, remark)
SELECT 1761500000000000004,
       '业绩-经纪人折算比例',
       'panjia.performance.broker_conversion_rate',
       '0.85',
       'Y',
       1761000000000000100,
       1761100000000000001,
       now(),
       '经纪人业绩折算比例，原始金额=贝壳当前金额÷该比例（默认0.85，即85%）'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_config WHERE config_key = 'panjia.performance.broker_conversion_rate'
);
