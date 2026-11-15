-- 驳回后调整：调整单挂靠原实收审批单，待原单审批通过时自动审批并执行
ALTER TABLE pj_perf_received_adjust ADD COLUMN IF NOT EXISTS received_apply_id BIGINT NULL;

COMMENT ON COLUMN pj_perf_received_adjust.received_apply_id IS '来源实收审批单 ID；非空表示随原单审批，不单独发起流程';

CREATE INDEX IF NOT EXISTS idx_radj_received_apply ON pj_perf_received_adjust (received_apply_id);
