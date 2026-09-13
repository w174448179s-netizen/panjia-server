-- ============================================================================
-- V150004 结佣申请单粒度：门店+月 → 合同+月
--
-- 背景：原申请单按 (period, dept_id) 发起，提交一个门店单会把该店当月所有合同
-- 一起提交/审批。业务要求按合同独立发起、独立提交、独立审批；数据可分多次导入，
-- 未发起的合同在列表中以「未发起」展示。
--
-- 结构变更：
--   pj_commission_application 增 contract_no / order_no / property_address / business_date，
--   dept_id 改为可空（跨门店合作单不属于单一门店）；
--   pj_commission_item 增 contract_no（冻结快照）；
--   唯一索引 uk_capp_period_dept → uk_capp_period_contract (period, contract_no)。
--
-- 存量拆分：同一合同在多个门店申请单中的明细全局合并为一张合同申请单；
--   合并后单状态按明细推导：存在 DRAFT 明细 → DRAFT，否则 SUBMITTED
--   （跨店合并单即使为 SUBMITTED，用户再点一次提交即可把残留 DRAFT 明细推进）。
--
-- 同时删除「增量重拉」按钮权限（按合同发起后，数据变化走 驳回/作废 → 重新发起）。
-- ============================================================================

BEGIN;

-- 1. 表结构 ----------------------------------------------------------------
ALTER TABLE pj_commission_application
    ADD COLUMN IF NOT EXISTS contract_no      VARCHAR(64),
    ADD COLUMN IF NOT EXISTS order_no         VARCHAR(64),
    ADD COLUMN IF NOT EXISTS property_address VARCHAR(255),
    ADD COLUMN IF NOT EXISTS business_date    TIMESTAMP;
ALTER TABLE pj_commission_application ALTER COLUMN dept_id DROP NOT NULL;

ALTER TABLE pj_commission_item
    ADD COLUMN IF NOT EXISTS contract_no VARCHAR(64);

COMMENT ON COLUMN pj_commission_application.contract_no IS '合同号（申请单业务键，粒度=合同+月）';
COMMENT ON COLUMN pj_commission_application.order_no IS '订单号（一手房展示快照）';
COMMENT ON COLUMN pj_commission_application.property_address IS '房源地址（快照）';
COMMENT ON COLUMN pj_commission_application.business_date IS '签约/认购时间（快照）';
COMMENT ON COLUMN pj_commission_application.dept_id IS '门店 ID（冗余快照；跨门店合作单为空）';
COMMENT ON COLUMN pj_commission_item.contract_no IS '合同号（冻结快照；DIFF 差额行沿用源明细合同号）';

-- 2. 存量拆分：按 (period, contract_no) 全局聚合 ------------------------------
CREATE TEMP TABLE tmp_app_split ON COMMIT DROP AS
WITH grouped AS (
    SELECT a.period,
           rs.contract_no,
           MAX(rs.order_no) AS order_no,
           MAX(rs.raw_json ->> 'propertyAddress') AS property_address,
           MAX(COALESCE((rs.raw_json ->> 'signDate')::timestamp, f.business_date::timestamp)) AS business_date,
           COUNT(*) FILTER (WHERE ci.status <> 'REVERSED') AS item_count,
           COALESCE(SUM(ci.amount) FILTER (WHERE ci.status <> 'REVERSED'), 0) AS total_amount,
           COUNT(*) FILTER (WHERE ci.status = 'DRAFT') AS draft_cnt,
           MAX(a.id) AS latest_old_id,
           ROW_NUMBER() OVER (ORDER BY a.period, rs.contract_no) AS rn
    FROM pj_commission_item ci
    JOIN pj_commission_application a ON a.id = ci.application_id
    JOIN pj_perf_fact f ON f.id = ci.performance_fact_id
    JOIN pj_normalized_record nr ON nr.id = f.normalized_record_id
    JOIN pj_import_raw_signed rs ON rs.id = nr.raw_data_id
    WHERE rs.contract_no IS NOT NULL
    GROUP BY a.period, rs.contract_no
)
SELECT g.*,
       la.apply_no || '-' || LPAD(
           ROW_NUMBER() OVER (PARTITION BY g.latest_old_id ORDER BY g.contract_no)::text, 3, '0') AS new_apply_no,
       la.approved_month, la.process_instance_id, la.applicant_id, la.approver_id,
       la.lock_time, la.version, la.create_time, la.update_time
FROM grouped g
JOIN pj_commission_application la ON la.id = g.latest_old_id;

-- 新申请单 ID 段（2099900000000000000 起，避开雪花 ID）
INSERT INTO pj_commission_application
    (id, apply_no, period, contract_no, order_no, property_address, business_date, dept_id,
     item_count, total_amount, status, approved_month, process_instance_id,
     applicant_id, approver_id, lock_time, version, create_time, update_time)
SELECT 2099900000000000000 + rn,
       new_apply_no, period, contract_no, order_no, property_address, business_date, NULL,
       item_count, total_amount,
       CASE WHEN draft_cnt > 0 THEN 'DRAFT' ELSE 'SUBMITTED' END,
       approved_month, process_instance_id, applicant_id, approver_id, lock_time,
       version, create_time, update_time
FROM tmp_app_split;

-- 3. 明细迁移到新申请单 + 回填合同号 ----------------------------------------
UPDATE pj_commission_item ci
SET application_id = 2099900000000000000 + s.rn,
    contract_no    = s.contract_no
FROM tmp_app_split s,
     pj_perf_fact f,
     pj_normalized_record nr,
     pj_import_raw_signed rs,
     pj_commission_application olda
WHERE ci.performance_fact_id IS NOT NULL
  AND f.id = ci.performance_fact_id
  AND nr.id = f.normalized_record_id
  AND rs.id = nr.raw_data_id
  AND rs.contract_no = s.contract_no
  AND olda.id = ci.application_id
  AND olda.period = s.period;

-- 4. 删除旧门店级申请单 -----------------------------------------------------
DELETE FROM pj_commission_application
WHERE id IN (SELECT DISTINCT latest_old_id FROM tmp_app_split);

-- 5. 唯一索引切换 -----------------------------------------------------------
DROP INDEX IF EXISTS uk_capp_period_dept;
CREATE UNIQUE INDEX uk_capp_period_contract
    ON pj_commission_application (period, contract_no)
    WHERE status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'LOCKED');

-- 6. 删除「增量重拉」按钮权限 ------------------------------------------------
DELETE FROM sys_role_menu WHERE menu_id = 1761400000000011823;
DELETE FROM sys_menu WHERE menu_id = 1761400000000011823;

COMMIT;
