-- ============================================================
-- 导入域：收敛业绩来源为单一「贝壳·经纪人业绩明细表」（KE_SIGNED）
-- 段位：V120007
-- 背景：业务实际只有一种业绩来源文件（经纪人业绩明细表），同一张表同时携
--       当月应收（新签业绩 PERF_EXPECT）与当月实收（结佣业绩 PERF_REAL）两列金额，
--       由业绩引擎对同一 SIGNED 行双发两条事实。
--       早期设计预留的第二导入来源 KE_NEW_SIGN（贝壳新签）从未启用（0 数据），
--       现前后端统一移除：删除其模板种子与原始归档表。
-- ============================================================

BEGIN;

-- 1. 删除 KE_NEW_SIGN 导入模板种子（模板行不挂菜单权限，直接按编码删除）
DELETE FROM pj_import_template WHERE template_code = 'KE_NEW_SIGN';

-- 2. 删除空的原始归档表（索引随表一并删除）
DROP TABLE IF EXISTS pj_import_raw_new_sign;

-- 3. 同步更新列注释（VARCHAR 无 CHECK 约束，历史值可继续读取，仅注释对齐）
COMMENT ON COLUMN pj_import_batch.source_type IS 'KE_SIGNED/ATTENDANCE/POINTS/OTHERS（业绩来源唯一：贝壳业绩明细表 KE_SIGNED，一行双口径）';
COMMENT ON COLUMN pj_normalized_record.record_type IS 'SIGNED/ATTENDANCE/POINTS/MANUAL';
COMMENT ON TABLE pj_normalized_record IS '归一化记录（交易单据归一化产物：SIGNED/ATTENDANCE/POINTS/MANUAL）';

COMMIT;
