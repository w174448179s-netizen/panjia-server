-- ============================================================
-- V160019 薪酬域参数：结佣提成比例按结算月取规则的 bizType 集合
-- ------------------------------------------------------------
-- 算薪时结佣提成比例（finalRate）的规则期间按业务类型分流：
--   bizType ∈ 本参数集合 → 按结算月（approvedMonth）的规则快照计算；
--   其余 bizType        → 按签约月（businessDate 所在月）的规则快照计算。
-- bizType 值来自业绩导入原样存储（pj_perf_fact.biz_type），
-- 默认「房产金融,家装荐客」为逗号分隔的业务类型名，
-- 若库里实际值不同（如英文编码），直接在 系统管理-参数设置 修改参数值即可，无需改代码。
-- 留空 = 所有业务类型均按签约月取规则（历史口径）。
-- 幂等：按 config_key 判重，重复执行不报错。
-- ============================================================

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type,
                        create_dept, create_by, create_time, remark)
SELECT 1761600000000000019,
       '薪酬-结佣提成按结算月取规则的业务类型',
       'panjia.payroll.commission.settlement-rate-biz-types',
       '房产金融,家装荐客',
       'Y',
       1761000000000000100,
       1761100000000000001,
       now(),
       '结佣提成比例按结算月(approvedMonth)取规则的bizType集合，逗号分隔；集合外业务类型按签约月(businessDate所在月)取规则；留空=全部按签约月（默认：房产金融,家装荐客）'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_config WHERE config_key = 'panjia.payroll.commission.settlement-rate-biz-types'
);
