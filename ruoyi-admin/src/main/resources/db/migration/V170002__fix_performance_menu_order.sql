-- =====================================================
-- 业绩管理菜单顺序与名称修复 + 结佣数据清理（V170002）
--
-- 问题：
--   1. 2202「结佣明细」与 2203「结佣调整」order_num 均为 3，菜单顺序错乱
--   2. 数据库中可能残留旧菜单名（实收审批/结佣申请），需确保与业务流程一致
--   3. Bug：无实收审批通过的合同也存在结佣申请记录（草稿/提交中），需清理
--
-- 业务流程正确顺序：
--   1. 业绩明细 (2610) — 查看业绩事实
--   2. 实收明细 (2640) — 实收审批
--   3. 结佣明细 (2202) — 实收审批通过后发起结佣
--   4. 结佣调整 (2203) — 结佣锁定后的调整
--   5. 业绩调整 (2620) — 业绩事实调整
--   6. 期间封账 (2630) — 期间封账管理
-- =====================================================

BEGIN;

-- 修复结佣调整 order_num：3 → 4（与结佣明细 order_num=3 区分）
UPDATE sys_menu
   SET order_num = 4
 WHERE menu_id = 1761400000000002203
   AND order_num = 3;

-- 确保菜单名称与业务流程一致（幂等更新）
UPDATE sys_menu SET menu_name = '实收明细' WHERE menu_id = 1761400000000002640 AND menu_name != '实收明细';
UPDATE sys_menu SET menu_name = '结佣明细' WHERE menu_id = 1761400000000002202 AND menu_name != '结佣明细';

-- =====================================================
-- 数据清理：取消「实收未审批通过」但存在结佣申请的记录
--
-- 规则：结佣申请单（pj_commission_application）中 status 为 DRAFT/SUBMITTED 的记录，
--   如果该合同当月实收事实（pj_perf_fact fact_type=PERF_REAL, fact_status=ACTIVE）
--   关联的实收审批单（pj_perf_received_apply）不存在或状态不是 APPROVED，
--   则将该结佣申请单置为 CANCELLED，同时冲销其明细。
--   已锁定(LOCKED)的申请单不在此处理范围（需走调整单流程）。
-- =====================================================

-- 创建临时表存储需要取消的申请单 ID
CREATE TEMP TABLE _tmp_cancel_app_ids AS
SELECT a.id
  FROM pj_commission_application a
 WHERE a.status IN ('DRAFT', 'SUBMITTED')
   AND EXISTS (
       SELECT 1
       FROM pj_perf_fact f
       JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
       JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
       LEFT JOIN pj_perf_received_apply ra ON ra.id = f.received_apply_id
       WHERE f.fact_status = 'ACTIVE'
         AND f.fact_type = 'PERF_REAL'
         AND f.period = a.period
         AND rs.contract_no = a.contract_no
         AND (ra.status IS NULL OR ra.status != 'APPROVED')
   );

-- 取消申请单
UPDATE pj_commission_application
   SET status = 'CANCELLED',
       item_count = 0,
       total_amount = 0,
       current_node = NULL,
       update_time = now()
 WHERE id IN (SELECT id FROM _tmp_cancel_app_ids);

-- 冲销被取消申请单的明细（DRAFT/PENDING → REVERSED）
UPDATE pj_commission_item
   SET status = 'REVERSED',
       reversed_reason = 'APPLICATION_CANCELLED',
       update_time = now()
 WHERE application_id IN (SELECT id FROM _tmp_cancel_app_ids)
   AND status IN ('DRAFT', 'PENDING');

DROP TABLE _tmp_cancel_app_ids;

COMMIT;

