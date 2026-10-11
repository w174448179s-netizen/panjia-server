-- ============================================================================
-- V150006 结佣申请单唯一锚点统一为 (period, order_no)，删除合同号唯一索引
--
-- 背景：同一合同号可挂多个订单号，合同号粒度唯一索引 uk_capp_period_contract
--       会让同合同第二笔订单的结佣申请单撞唯一索引。结佣申请粒度=订单
--       （列表业务键 biz_key=COALESCE(order_no, contract_no)），合同号仅作快照
--       与新签-实收退化匹配（双侧闸门）使用。
-- 幂等：同一 (period, order_no) 只允许一张未完结申请单（REJECTED/CANCELLED 可
--       重新发起）。PG 部分唯一索引视 NULL 互异，order_no 为 NULL 的历史单不受
--       唯一索引约束，其幂等由 findActiveApplication 按 contract_no OR order_no
--       双列业务键保障。
-- ============================================================================

DROP INDEX IF EXISTS uk_capp_period_contract;

CREATE UNIQUE INDEX IF NOT EXISTS uk_capp_period_order
    ON pj_commission_application(period, order_no)
    WHERE status IN ('DRAFT','SUBMITTED','APPROVED','LOCKED');
