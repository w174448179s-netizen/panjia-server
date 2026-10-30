-- 结佣申请单「自动发起」标记。
-- 背景：实收审批通过事件会对非二手买卖自动发起结佣并直提审批（DRAFT → SUBMITTED 同事务）。
-- 这类结佣单是导入的连锁产物，导入批次撤销时需随批硬删；
-- 人工发起的结佣单一律不随批删除（由批次撤销拦截提前拦下）。
ALTER TABLE pj_commission_application
    ADD COLUMN IF NOT EXISTS auto_initiated BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN pj_commission_application.auto_initiated
    IS '是否由实收审批通过事件自动发起（true=系统自动发起，导入批次撤销时在无审批人办理的前提下可随批硬删；false=人工发起）';
