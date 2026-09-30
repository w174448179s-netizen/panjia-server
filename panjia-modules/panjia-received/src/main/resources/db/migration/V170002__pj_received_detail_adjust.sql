-- ============================================================================
-- V170002 盘家智管 · 实收明细支持结佣调整 / 手工对齐的 supersede 状态机
--
-- 背景：PERF_REAL 已物理拆分到 pj_received_detail（见 V170001）。结佣调整
--       （AMOUNT 金额调整 / VOID 作废 / TRANSFER 部门划转）与财务「手工对齐实收」
--       原先对 pj_perf_fact 的 PERF_REAL 事实做「旧行置 REVERSED + 插新 ACTIVE
--       （同 source_key）」的 supersede 操作，现需在实收明细上复刻同一状态机。
--
-- 既有结构已具备：detail_status（ACTIVE/REVERSED）、部分唯一索引
--       uk_received_detail_anchor(source_key) WHERE detail_status='ACTIVE'
--       —— 旧行转 REVERSED 后即可插入同 source_key 的新 ACTIVE 行，天然支持。
-- 本迁移仅补齐「关联调整单 / 冲销原因 / 退款指向」三个溯源字段与索引。
-- ============================================================================

ALTER TABLE pj_received_detail ADD COLUMN IF NOT EXISTS adjust_id            BIGINT;
ALTER TABLE pj_received_detail ADD COLUMN IF NOT EXISTS reversal_type        VARCHAR(32);
ALTER TABLE pj_received_detail ADD COLUMN IF NOT EXISTS refund_of_detail_id  BIGINT;
-- 明细级部门：默认 NULL 取合同级 pj_received_contract.dept_id；
-- 结佣调整「单行部门划转(TRANSFER)」时写目标部门，覆盖合同级口径。
ALTER TABLE pj_received_detail ADD COLUMN IF NOT EXISTS dept_id              BIGINT;

CREATE INDEX IF NOT EXISTS idx_rd_adjust ON pj_received_detail(adjust_id);
CREATE INDEX IF NOT EXISTS idx_rd_dept_period ON pj_received_detail(dept_id, period);

COMMENT ON COLUMN pj_received_detail.dept_id IS '明细级归属门店；为空取合同级 rc.dept_id，结佣单行划转时写目标部门';
COMMENT ON COLUMN pj_received_detail.adjust_id IS '关联调整单 ID（结佣调整 pj_commission_adjust.id / 新签调整单；仅 supersede 新行有值）';
COMMENT ON COLUMN pj_received_detail.reversal_type IS '本行被冲销原因（ReversedReason code，如 MANUAL_ADJUST/REFUND；仅 REVERSED 行有值）';
COMMENT ON COLUMN pj_received_detail.refund_of_detail_id IS '退款/红冲指向的原始实收明细行 ID（对应原 PERF_REAL.refund_of_fact_id）';
