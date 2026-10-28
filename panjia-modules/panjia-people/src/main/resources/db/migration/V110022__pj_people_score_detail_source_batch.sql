-- ============================================================
-- V110022
-- 积分每日明细增加来源批次标记
--
-- 背景：明细改为按 (员工, 日期) 覆盖写入，同月多个批次（二手 + 租赁）按日期合并。
--       为使「批次撤销」与「重导整批替换」能精确清理本批写入的行，
--       明细需记录写入它的导入批次 ID。
-- 一员工一天唯一索引 uk_score_detail_emp_date 已存在，无需重复创建。
-- ============================================================

ALTER TABLE pj_people_score_detail
    ADD COLUMN source_batch_id BIGINT;

COMMENT ON COLUMN pj_people_score_detail.source_batch_id IS
    '写入该行的导入批次 ID（批次撤销/重导整批替换时按此精确清理，历史行为空）';

-- 按批次清理/统计辅助索引
CREATE INDEX idx_score_detail_source_batch ON pj_people_score_detail(source_batch_id);
