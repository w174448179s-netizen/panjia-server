-- ============================================================
-- 实收调整三类型重构（对齐新签调整，2026-10-14）：
--   原实收调整只能录入调整后合同/订单实收合计（等比分摊），
--   现拆分为三类：AMOUNT=业绩调整（总额不变，可增加角色人重新分配）、
--                DEDUCT=业绩扣减（总额必须减少）、
--                INCREASE=业绩增加（总额必须增加）；
--   三类均支持明细指定值与增加角色人（payload 复用 AddMemberPayload）。
--   adjust_type 可空：历史单回填为 AMOUNT（原等比金额调整语义一致）。
-- ============================================================

ALTER TABLE pj_perf_received_adjust ADD COLUMN IF NOT EXISTS adjust_type VARCHAR(20);

UPDATE pj_perf_received_adjust SET adjust_type = 'AMOUNT' WHERE adjust_type IS NULL;

CREATE INDEX IF NOT EXISTS idx_radj_type ON pj_perf_received_adjust(adjust_type);

COMMENT ON COLUMN pj_perf_received_adjust.adjust_type IS '调整类型：AMOUNT=业绩调整(总额不变) / DEDUCT=业绩扣减 / INCREASE=业绩增加；历史单回填 AMOUNT';
