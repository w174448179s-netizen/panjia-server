-- ============================================================
-- V210011 部门收支录入 · 历史空记账流水号补全
-- 规则：yyyyMMddHHmmss（优先取创建时间，缺失取当前时间）+ 5 位数字后缀
-- 说明：后缀按行号取模生成，保证同一批补全内不重复
-- ============================================================

WITH blank AS (
    SELECT id,
           to_char(COALESCE(create_time, now()), 'YYYYMMDDHH24MISS') AS ts,
           ROW_NUMBER() OVER (ORDER BY COALESCE(create_time, now()), id) AS rn
    FROM pj_ledger_entry
    WHERE source_serial_no IS NULL OR BTRIM(source_serial_no) = ''
)
UPDATE pj_ledger_entry e
SET source_serial_no = b.ts || lpad(((b.rn - 1) % 100000)::text, 5, '0')
FROM blank b
WHERE e.id = b.id;
