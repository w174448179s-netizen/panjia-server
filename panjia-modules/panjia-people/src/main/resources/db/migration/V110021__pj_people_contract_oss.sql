-- ============================================================
-- V110021: 合同签署扫描件留底
--   * pj_people_contract 增加 contract_oss_ids：签署完成的合同扫描件
--     （多个 ossId 逗号串，文件本体走 OSS），签订后上传留底备查
-- ============================================================

ALTER TABLE pj_people_contract ADD COLUMN IF NOT EXISTS contract_oss_ids VARCHAR(2000);

COMMENT ON COLUMN pj_people_contract.contract_oss_ids IS '合同签署扫描件 ossId 串（逗号分隔），签订后上传留底';
