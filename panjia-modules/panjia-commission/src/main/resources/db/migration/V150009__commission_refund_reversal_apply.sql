-- ============================================================================
-- 退单冲减结佣单独立化（历史数据修复 + 唯一索引调整）
--
-- 背景：退单执行时生成的负结佣明细（source_key = RFND-{退单ID}-{原明细ID}）原先被并入
--       同期间同订单的已有结佣单；若原单已 LOCKED（已进工资），会篡改历史单据合计
--       （如 +986.81 被净额化为 0），且在结佣申请列表里看不到独立的退单冲减记录。
--
-- 调整：退单冲减一律独立建单（单号 TD + 退单ID，状态 APPROVED），唯一索引排除 TD 单号，
--       允许「同期间同订单的正常结佣单 + 退单冲减单」并存。
-- 幂等：ID 复用退单 ID（雪花 ID 全表唯一），ON CONFLICT DO NOTHING；明细仅更新 RFND 行。
-- ============================================================================

-- 一、唯一索引排除退单冲减单
DROP INDEX IF EXISTS uk_capp_period_order;

CREATE UNIQUE INDEX IF NOT EXISTS uk_capp_period_order
    ON pj_commission_application (period, order_no)
    WHERE status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'LOCKED')
      AND apply_no NOT LIKE 'TD%';

-- 二、为已执行退单补建冲减结佣单（同退单号已存在则跳过）
INSERT INTO pj_commission_application (
    id, apply_no, period, contract_no, order_no, item_count, total_amount, expected_amount,
    aligned, status, approved_month, applicant_id, approver_id, approve_time,
    version, create_time, update_time)
SELECT r.id,
       'TD' || r.id,
       r.refund_period,
       r.contract_no,
       r.order_no,
       0, 0, 0,
       false,
       'APPROVED',
       r.refund_period,
       r.operator_id,
       r.operator_id,
       COALESCE(r.execute_time, NOW()),
       0,
       COALESCE(r.execute_time, NOW()),
       NOW()
FROM pj_perf_refund r
WHERE r.status = 'EXECUTED'
  AND EXISTS (SELECT 1
              FROM pj_commission_item i
              WHERE i.source_key LIKE ('RFND-' || r.id || '-%'))
ON CONFLICT (id) DO NOTHING;

-- 三、记录当前被退单负数污染的承载单（需要在搬移后恢复合计）
CREATE TEMP TABLE tmp_refund_polluted_apply ON COMMIT DROP AS
SELECT DISTINCT i.application_id AS id
FROM pj_commission_item i
WHERE i.source_key LIKE 'RFND-%'
  AND i.application_id IS NOT NULL;

-- 四、退单负结佣明细统一挂到退单冲减单，落账月统一为退单月
UPDATE pj_commission_item i
SET application_id = r.id,
    period         = r.refund_period,
    approved_month = r.refund_period
FROM pj_perf_refund r
WHERE r.status = 'EXECUTED'
  AND i.source_key LIKE ('RFND-' || r.id || '-%')
  AND (i.application_id IS DISTINCT FROM r.id
       OR i.period IS DISTINCT FROM r.refund_period
       OR i.approved_month IS DISTINCT FROM r.refund_period);

-- 五、重算受影响单据（原被污染单 + 退单冲减单）的明细条数与合计
UPDATE pj_commission_application a
SET item_count   = agg.item_count,
    total_amount = agg.total_amount,
    update_time  = NOW()
FROM (SELECT i.application_id,
             COUNT(*)      AS item_count,
             SUM(i.amount) AS total_amount
      FROM pj_commission_item i
      WHERE i.status <> 'REVERSED'
        AND i.application_id IN (
            SELECT id FROM tmp_refund_polluted_apply
            UNION
            SELECT r.id FROM pj_perf_refund r WHERE r.status = 'EXECUTED'
        )
      GROUP BY i.application_id) agg
WHERE a.id = agg.application_id;
