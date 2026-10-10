-- ============================================================
-- 历史调整单回填订单号（配套 V140009 加列 + 订单号+合同号双键匹配）
-- 规则：
--   1) 有关联事实（fact_id 非空）的：直接取该事实的 order_no，最精确；
--   2) 无 fact_id 的合同级单：同合同号+同期间下事实仅归属唯一订单时回填，
--      跨订单（同合同号挂多订单）无法判定，保持 NULL 走合同号旧口径（天然兼容）。
-- 仅回填 PERF_EXPECT（新签/增加角色人/冲正）；RECEIVED_AMOUNT 的 contract_no
-- 是实收合同号，不关联 pj_perf_fact，保持 NULL。
-- ============================================================

-- 1) 有 fact_id 的调整单：按事实精确回填
UPDATE pj_perf_adjust a
SET order_no = f.order_no
FROM pj_perf_fact f
WHERE a.fact_id = f.id
  AND a.order_no IS NULL
  AND a.fact_type = 'PERF_EXPECT'
  AND f.order_no IS NOT NULL;

-- 2) 无 fact_id 的合同级调整单：同合同号+同期间下订单唯一才回填（多订单不猜配）
UPDATE pj_perf_adjust a
SET order_no = s.order_no
FROM (
    SELECT f.contract_no, f.period, min(f.order_no) AS order_no
    FROM pj_perf_fact f
    WHERE f.contract_no IS NOT NULL
      AND f.order_no IS NOT NULL
    GROUP BY f.contract_no, f.period
    HAVING count(DISTINCT f.order_no) = 1
) s
WHERE a.contract_no = s.contract_no
  AND a.period = s.period
  AND a.fact_id IS NULL
  AND a.order_no IS NULL
  AND a.fact_type = 'PERF_EXPECT';
