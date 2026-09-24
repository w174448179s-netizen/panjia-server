-- pj_perf_fact.role_type 放大：历史工资/贝壳业绩原始角色值可能超过 30 字符
-- （如模板列映射错误把长文本塞进 roleType），放大到 VARCHAR(50) 与 biz_type 对齐，
-- 归一化层 + PerformanceEngine 已加双保险截断，本迁移为 DB 层兜底。
ALTER TABLE pj_perf_fact ALTER COLUMN role_type TYPE VARCHAR(50);

COMMENT ON COLUMN pj_perf_fact.role_type IS
    '角色类型(主筹/跟筹/协办人等)，VARCHAR(50)，归一化层已截断到 30，DB 放大兜底原始脏值';
