-- ============================================================================
-- V150003 结佣明细新增 DRAFT（待提交）状态
--
-- 背景：明细创建时即为 PENDING（待审批），但申请单此时还是 DRAFT（草稿），
-- 状态语义冲突。明细状态机细化为四态：
--   DRAFT(待提交) → PENDING(待审批) → APPROVED(已审批)
--                                      ↘ REVERSED(已冲销，终态)
-- 申请单提交时明细随单 DRAFT → PENDING。
--
-- 存量数据：草稿申请单下的 PENDING 明细回填为 DRAFT；
-- 已提交/已锁定等申请单下的明细保持 PENDING/APPROVED 不变。
-- ============================================================================

BEGIN;

-- 1. 存量回填：草稿申请单下的未冲销明细 PENDING → DRAFT
UPDATE pj_commission_item ci
SET status = 'DRAFT'
FROM pj_commission_application ca
WHERE ci.application_id = ca.id
  AND ca.status = 'DRAFT'
  AND ci.status = 'PENDING';

-- 2. 列默认值改为 DRAFT
ALTER TABLE pj_commission_item ALTER COLUMN status SET DEFAULT 'DRAFT';

-- 3. 更新字段注释
COMMENT ON COLUMN pj_commission_item.status IS '状态 DRAFT=待提交 PENDING=待审批 APPROVED=已审批(金额冻结) REVERSED=已冲销(终态，永久保留)';

COMMIT;
