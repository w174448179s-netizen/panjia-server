-- =====================================================================
-- 结佣申请单批量审批/批量发起按订单号查找补索引
--
-- 批量操作支持「合同号 OR 订单号」匹配（一手房/房产金融/家装荐客以订单号为准）。
-- 原 uk_capp_period_contract(period, contract_no) 仅覆盖合同号分支，
-- order_no 分支无索引会退化为 period+status 全量扫描。
-- 补 (period, order_no) 索引，使 OR 两侧均走索引，PG 用 BitmapOr 合并。
-- =====================================================================

CREATE INDEX IF NOT EXISTS idx_capp_period_order
    ON pj_commission_application(period, order_no);
