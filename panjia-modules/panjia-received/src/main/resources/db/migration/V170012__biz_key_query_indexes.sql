-- ============================================================
-- 业绩/实收/结佣高频查询缺失索引补齐（2026-10-29）
-- ============================================================
-- 背景：
--   业务键口径统一为「一手房/房产金融/家装荐客订单号优先，其余合同号优先」后，
--   实收侧大量查询改为先按 rc.contract_no（或 rc.order_no）定位合同再回查明细；
--   复核现有索引发现 4 个高频访问路径没有可用索引，会退化为顺序扫描。
-- 说明：
--   1) 统一业务键是一个 CASE 表达式，B-Tree 无法直接匹配；本脚本不为其建函数索引
--      （按期间过滤后分组行数在千级，HashAggregate 足够，函数索引维护成本大于收益）。
--   2) 只补缺失索引，不改动既有索引。
-- ============================================================

-- 1) 实收合同表缺 contract_no 索引：实收侧按合同号定位
--    （业绩查询实收侧、明细下钻、原值回溯 selectOriginalAmountsByKeys 均用 rc.contract_no）；
--    现有唯一索引 uk_received_contract_anchor 前导列是 order_no，帮不上。
CREATE INDEX IF NOT EXISTS idx_rc_contract_period
    ON pj_received_contract(contract_no, period, id);

-- 2) 结佣发起页主查询（审批通过单列表）：
--    WHERE status='APPROVED' ORDER BY business_date DESC, id DESC
--    现有 idx_rapp_status 前导列是 period，本查询不带期间条件用不上。
CREATE INDEX IF NOT EXISTS idx_rapp_status_date
    ON pj_perf_received_apply(status, business_date DESC, id DESC);

-- 3) 实收明细按期间查询（合同维度汇总 / 无门店筛选的实收列表）：
--    现有 (dept_id, period)、(employee_id, period) 前导列都不是 period，无法用于纯期间过滤。
CREATE INDEX IF NOT EXISTS idx_rd_period_status
    ON pj_received_detail(period, detail_status);

-- 4) 「待匹配新签」到账组：detail_status='ACTIVE' AND received_apply_id IS NULL 的明细
--    只占少数（多数已绑定审批单），用部分索引缩小扫描面，再按 contract_id 回关联合同。
CREATE INDEX IF NOT EXISTS idx_rd_unmatched
    ON pj_received_detail(contract_id)
    WHERE detail_status = 'ACTIVE' AND received_apply_id IS NULL;
