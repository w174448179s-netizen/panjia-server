-- 理房通流水异步导入：保存归档文件与批次结果快照
ALTER TABLE pj_lf_import_batch
    ADD COLUMN IF NOT EXISTS storage_path VARCHAR(500),
    ADD COLUMN IF NOT EXISTS result_detail TEXT,
    ADD COLUMN IF NOT EXISTS finish_time TIMESTAMP;

COMMENT ON COLUMN pj_lf_import_batch.storage_path IS '原始 Excel 归档路径，供异步任务读取';
COMMENT ON COLUMN pj_lf_import_batch.result_detail IS '导入统计与行级错误 JSON 快照';
COMMENT ON COLUMN pj_lf_import_batch.finish_time IS '导入完成或失败时间';
