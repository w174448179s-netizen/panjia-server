-- ============================================================
-- V140006 业绩域：查看留痕表 pj_perf_view_log
-- ------------------------------------------------------------
-- 设计文档 §3.6 / §5.5：经纪人每次打开含他人业绩的合同必须留痕，
-- 用于防批量爬业绩 + 争议时有据可查。
-- 仅经纪人记、仅记含他人业绩的打开；店长/总监/算薪属职权查看不记。
-- ============================================================

BEGIN;

CREATE TABLE IF NOT EXISTS pj_perf_view_log (
    id                  BIGINT       PRIMARY KEY,
    contract_id         BIGINT,
    contract_no         VARCHAR(64),
    viewer_employee_id  BIGINT       NOT NULL,
    viewed_employee_ids VARCHAR(512),
    view_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source              VARCHAR(32)
);

COMMENT ON TABLE  pj_perf_view_log IS '业绩查看留痕（经纪人打开含他人业绩的合同）';
COMMENT ON COLUMN pj_perf_view_log.id IS '雪花 ID';
COMMENT ON COLUMN pj_perf_view_log.contract_id IS '被查看合同 ID（若有合同域实体）';
COMMENT ON COLUMN pj_perf_view_log.contract_no IS '被查看合同号（冗余，便于检索）';
COMMENT ON COLUMN pj_perf_view_log.viewer_employee_id IS '查看人员工 ID（仅经纪人记）';
COMMENT ON COLUMN pj_perf_view_log.viewed_employee_ids IS '本次被查看的角色人集合（他人）';
COMMENT ON COLUMN pj_perf_view_log.view_time IS '查看时间';
COMMENT ON COLUMN pj_perf_view_log.source IS '进入来源（如 MY_PERF_DRILLDOWN 我的业绩下钻）';

CREATE INDEX IF NOT EXISTS idx_pview_contract ON pj_perf_view_log(contract_id, view_time);
CREATE INDEX IF NOT EXISTS idx_pview_viewer  ON pj_perf_view_log(viewer_employee_id, view_time);

COMMIT;
