-- ============================================================================
-- 退单负实收补挂实收审批单（历史数据修复）
-- 背景：实收管理页按「审批单」维度展示（pj_perf_received_apply），退单生成的负实收明细
--       原先 received_apply_id 为空，导致页面选退单月时看不到负实收。
--       代码侧已改为退单执行时直建 APPROVED 审批单并绑定负明细；
--       本脚本为已执行退单补建单据并绑定明细（幂等，可重复执行）。
-- 单号：沿用退单号（TD 前缀），结佣发起候选查询按该前缀排除，避免重复冲减。
-- 索引：退单冲减单与当月正常实收单并存（同期间同订单两张单），故唯一索引排除 TD 单号。
-- 幂等：ID 复用退单 ID（雪花 ID 全表唯一），ON CONFLICT DO NOTHING。
-- ============================================================================

-- 一、唯一索引排除退单冲减单（同期间同订单允许并存「正常实收单 + 退单冲减单」）
DROP INDEX IF EXISTS uk_rapp_period_order;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rapp_period_order
    ON pj_perf_received_apply (period, order_no)
    WHERE status IN ('DRAFT', 'SUBMITTED', 'APPROVED')
      AND apply_no NOT LIKE 'TD%';

-- 二、为已执行退单补建审批单（同退单号已存在则跳过）
INSERT INTO pj_perf_received_apply (
    id, apply_no, period, contract_no, order_no, property_address, business_date, dept_id,
    batch_id, received_amount, expected_amount, item_count, biz_type, status, current_node,
    process_instance_id, applicant_id, approver_id, approve_time, version, create_time, update_time)
SELECT r.id,
       r.refund_no,
       r.refund_period,
       r.contract_no,
       r.order_no,
       rc.property_address,
       rc.business_date,
       rc.dept_id,
       NULL,
       COALESCE(SUM(d.performance_amount), 0),
       0,
       COUNT(*),
       rc.biz_type,
       'APPROVED',
       NULL,
       NULL,
       r.operator_id,
       r.operator_id,
       COALESCE(r.execute_time, NOW()),
       0,
       COALESCE(r.execute_time, NOW()),
       NOW()
FROM pj_perf_refund r
         JOIN pj_received_detail d
              ON d.period = r.refund_period
                  AND d.detail_status = 'ACTIVE'
                  AND d.source_key LIKE ('%MANUAL-RFND-' || r.id || '-%')
         JOIN pj_received_contract rc ON rc.id = d.contract_id
WHERE r.status = 'EXECUTED'
  AND r.real_amount <> 0
  AND NOT EXISTS (SELECT 1 FROM pj_perf_received_apply a WHERE a.apply_no = r.refund_no)
GROUP BY r.id, r.refund_no, r.refund_period, r.contract_no, r.order_no,
         rc.property_address, rc.business_date, rc.dept_id, rc.biz_type, r.operator_id, r.execute_time
ON CONFLICT (id) DO NOTHING;

-- 三、退单负明细统一挂到退单冲减单（含此前误并入正常实收单的行）
UPDATE pj_received_detail d
SET received_apply_id = a.id
FROM pj_perf_refund r
         JOIN pj_perf_received_apply a ON a.apply_no = r.refund_no
WHERE d.period = r.refund_period
  AND d.detail_status = 'ACTIVE'
  AND d.source_key LIKE ('%MANUAL-RFND-' || r.id || '-%')
  AND (d.received_apply_id IS NULL OR d.received_apply_id <> a.id);

-- 四、重算退单冲减单金额与条数（按绑定后 ACTIVE 明细实时合计）
UPDATE pj_perf_received_apply a
SET received_amount = agg.total_amount,
    item_count      = agg.item_count,
    update_time     = NOW()
FROM (SELECT received_apply_id,
             SUM(performance_amount) AS total_amount,
             COUNT(*)                AS item_count
      FROM pj_received_detail
      WHERE detail_status = 'ACTIVE'
        AND received_apply_id IS NOT NULL
      GROUP BY received_apply_id) agg
WHERE a.id = agg.received_apply_id
  AND a.apply_no LIKE 'TD%';
