-- ============================================================================
-- 取消结佣申请单草稿态（2026-11-01）
--
-- 背景：草稿单没有前端处理入口，其非冲销明细又占用新签事实（结佣发起页按
--       「事实是否已被结佣明细占用」过滤，草稿占住事实导致合同整行隐藏），
--       形成「看不到、也处理不了」。业务改为「建单即提交」，不存在已建未提交的单据。
-- 历史数据：由应用启动任务 CommissionLegacyDraftMigrationRunner 迁移
--       （有有效明细则原单转审批中；无明细或启动流程失败则作废并冲销明细释放事实）。
-- 本脚本只做结构收口 + 索引冲突前置清理。
-- ============================================================================

-- 一、默认状态改为审批中
ALTER TABLE pj_commission_application ALTER COLUMN status SET DEFAULT 'SUBMITTED';

COMMENT ON COLUMN pj_commission_application.status IS
    '状态 SUBMITTED=审批中 APPROVED=已通过 LOCKED=已锁定(终态) REJECTED=已驳回 CANCELLED=已作废';

-- 二、前置清理：同一 (period, order_no) 已存在未完结单时，历史草稿单必然与新索引冲突，
--     直接作废并冲销明细（释放新签事实，用户重新发起）
UPDATE pj_commission_item i
   SET status = 'REVERSED',
       reversed_reason = 'APPLICATION_CANCELLED'
 WHERE i.status <> 'REVERSED'
   AND i.application_id IN (
       SELECT d.id
         FROM pj_commission_application d
        WHERE d.status = 'DRAFT'
          AND d.order_no IS NOT NULL
          AND EXISTS (
              SELECT 1
                FROM pj_commission_application a
               WHERE a.period = d.period
                 AND a.order_no = d.order_no
                 AND a.id <> d.id
                 AND a.status IN ('SUBMITTED', 'APPROVED', 'LOCKED')));

UPDATE pj_commission_application d
   SET status = 'CANCELLED',
       item_count = 0,
       total_amount = 0,
       process_instance_id = NULL,
       current_node = NULL
 WHERE d.status = 'DRAFT'
   AND d.order_no IS NOT NULL
   AND EXISTS (
       SELECT 1
         FROM pj_commission_application a
        WHERE a.period = d.period
          AND a.order_no = d.order_no
          AND a.id <> d.id
          AND a.status IN ('SUBMITTED', 'APPROVED', 'LOCKED'));

-- 三、唯一索引去掉 DRAFT（草稿态不再产生，也不允许存量草稿抢占唯一键）
DROP INDEX IF EXISTS uk_capp_period_order;

CREATE UNIQUE INDEX uk_capp_period_order
    ON pj_commission_application (period, order_no)
    WHERE status IN ('SUBMITTED', 'APPROVED', 'LOCKED')
      AND apply_no NOT LIKE 'TD%';
