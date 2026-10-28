-- ============================================================
-- V120007
-- 积分默认模板不限定 sheet 名
-- 二手门店导出 sheet=「二手积分日报5.0版」，租赁门店导出 sheet=「租赁积分日报3.0版」，
-- 列结构相同；限定 sheet 名会导致其中一种文件读取失败。
-- XlsxFileParser 在 sheet_name 为空时按第一个 sheet 读取。
-- ============================================================

UPDATE pj_import_template
   SET sheet_name = NULL,
       updated_at = now()
 WHERE template_code = 'POINTS'
   AND is_active = true;
