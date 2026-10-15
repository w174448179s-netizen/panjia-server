-- ============================================================
-- V160011: 个税来源留痕 + 外部个税系统回补支持
-- 业务口径：
--   系统内个税为简化试算（缺专项附加扣除、真实累计口径），不再作为发放依据；
--   个税由专门个税系统（自然人电子税务局扣缴端）计算，批次 CALCULATED 后
--   通过「导入个税」回补，覆盖 tax 并重算 net。
--   tax_source 留痕个税来源，引擎按政策 tax.mode 判定（缺省 EXTERNAL）：
--     EXTERNAL = 外部回补（默认，算薪时 tax 置 0，待导入）
--     ENGINE   = 内部引擎试算（需显式配置，仅供对照）
-- ============================================================

ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS tax_source varchar(20) NOT NULL DEFAULT 'ENGINE';

COMMENT ON COLUMN pj_payroll_detail.tax_source IS '个税来源：ENGINE=内部引擎试算，EXTERNAL=外部个税系统导入回补';
