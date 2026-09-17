-- V100016: 新增"结佣-实收应收差异容忍阈值"配置项
-- config_value = '1'（默认，单位：元）：|实收 - 应收| <= 该阈值视为无差异，不触发实收对齐应收、跳过财务节点
-- 调整该值可灵活控制小额尾差的处理口径，无需改代码
-- 幂等：ON CONFLICT 保证重复执行不报错

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000016, '结佣-实收应收差异容忍阈值', 'panjia.commission.diff_tolerance', '1', 'Y', 1761000000000000100, 1761100000000000001, now(),
        '实收与应收金额差异容忍阈值（元），|实收-应收|<=该值视为无差异，不对齐、跳过财务节点；默认1')
ON CONFLICT (config_id) DO NOTHING;
