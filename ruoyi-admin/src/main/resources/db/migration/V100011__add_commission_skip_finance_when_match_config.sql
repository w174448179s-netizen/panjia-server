-- V100011: 新增"结佣-实收应收无差异时跳过财务"配置开关
-- config_value = 'true'（默认）：总监审批后实收=应收时互斥网关自动跳过财务节点
-- config_value = 'false'：即使金额无差异也强制走财务人工审批
-- 幂等：ON CONFLICT 保证重复执行不报错

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000011, '结佣-实收应收无差异跳过财务', 'panjia.commission.skip_finance_when_match', 'true', 'Y', 1761000000000000100, 1761100000000000001, now(),
        'true=总监审批后实收应收无差异时自动跳过财务节点；false=强制走财务人工审批')
ON CONFLICT (config_id) DO NOTHING;
