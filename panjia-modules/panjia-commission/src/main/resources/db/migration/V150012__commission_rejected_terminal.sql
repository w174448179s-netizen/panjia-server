-- ============================================================================
-- 结佣驳回单终态化（2026-11-01）
--
-- 背景：驳回单此前把明细留在 PENDING（既不冲销、也不允许重新发起），
--       明细占住新签事实，导致合同/订单在结佣发起页整行隐藏；
--       业务人员必须先手动「作废」旧单才能重新发起。
-- 定稿：驳回（REJECTED）与作废（CANCELLED）同为终态——未审批明细随单冲销、
--       业绩事实立即释放，重新发起直接新建单（不重提旧单、无需先作废）。
-- 本脚本迁移历史驳回单：冲销未审批明细 + 聚合清零（与新逻辑产生的驳回单口径一致）。
-- ============================================================================

-- 一、历史驳回单：未审批明细冲销，释放新签事实
UPDATE pj_commission_item i
   SET status = 'REVERSED',
       reversed_reason = 'APPLICATION_REJECTED'
  FROM pj_commission_application a
 WHERE i.application_id = a.id
   AND a.status = 'REJECTED'
   AND i.status <> 'REVERSED';

-- 二、历史驳回单：聚合清零、清空当前节点（与作废单展示口径一致）
UPDATE pj_commission_application a
   SET item_count = 0,
       total_amount = 0,
       current_node = NULL
 WHERE a.status = 'REJECTED'
   AND (a.item_count <> 0 OR a.total_amount <> 0 OR a.current_node IS NOT NULL);
