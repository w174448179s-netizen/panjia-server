-- ============================================================
-- 实收补录标记：新签触发补行/压缩的来源新签批次（2026-10-29）
-- ============================================================
-- 背景：
--   先导实收、后导新签时，实收侧会按新签应收补行（缺失人补应收、已有实收人压缩到
--   「实收总额 - 其他人应收合计」）。这类补行/压缩不属于任何实收导入批次，
--   若新签批次撤销后仍留在库里，再次导入实收会与新文件叠加虚增实收。
-- 用途：
--   1) 新签批次撤销时按 supplement_origin_batch_id 删除补录行、还原被压缩的原行；
--   2) 实收增量导入替换时，把带该标记的行一并视为可替换（派生数据，随文件重建）。
-- ============================================================

ALTER TABLE pj_received_detail
    ADD COLUMN IF NOT EXISTS supplement_origin_batch_id BIGINT;

COMMENT ON COLUMN pj_received_detail.supplement_origin_batch_id
    IS '触发补录的新签导入批次 ID（新签到达补行/压缩写入；实收导入批次补行走 source_batch_id）';

CREATE INDEX IF NOT EXISTS idx_rd_supplement_origin
    ON pj_received_detail(supplement_origin_batch_id);
