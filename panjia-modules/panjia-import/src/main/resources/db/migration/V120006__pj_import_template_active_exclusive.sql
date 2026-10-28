-- ============================================================
-- V120006
-- 1) 模板启用互斥：同一 template_code 只保留 1 套启用模板
-- 2) 积分默认模板表头去掉 ✨（2026-10 之后钉钉导出为「今日总积分」，不再带 ✨）
-- 3) 积分默认模板数据起始行改为 2（新导出只有 1 行表头，数据从第 2 行开始）
--
-- 说明：只改「启用中」的那一套，未启用的历史版本（含旧的两行表头 ✨ 版）原样保留，
--       需要回导旧格式文件时可再启用对应历史版本。
-- ============================================================

-- 1. 同 template_code 收敛为 1 套启用：保留 id 最大（最新复制）的版本
UPDATE pj_import_template t
   SET is_active = false,
       updated_at = now()
 WHERE t.is_active
   AND EXISTS (
       SELECT 1
         FROM pj_import_template t2
        WHERE t2.template_code = t.template_code
          AND t2.is_active
          AND t2.id > t.id
   );

-- 2. 积分默认模板：列映射表头去掉 ✨
UPDATE pj_import_template
   SET column_mapping = replace(column_mapping::text, '✨', '')::jsonb,
       updated_at = now()
 WHERE template_code = 'POINTS'
   AND is_active = true
   AND column_mapping::text LIKE '%✨%';

-- 3. 积分默认模板：单行表头，数据从第 2 行开始
UPDATE pj_import_template
   SET data_start_row = 2,
       updated_at = now()
 WHERE template_code = 'POINTS'
   AND is_active = true
   AND header_row = 1
   AND data_start_row = 3;
