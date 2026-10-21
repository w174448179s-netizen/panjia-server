-- ============================================================
-- V110020: 合同甲方（用人单位）配置
--   * 合同打印时甲方三要素（单位名/注册地址/法定代表人）不再硬编码于模板，
--     按员工所属部门映射取值；dept_id=0 为默认甲方（部门未配置时兜底）
--   * 当前仅一家主体，种子为默认甲方；后续新增主体时给对应门店/部门配映射即可
-- ============================================================

CREATE TABLE IF NOT EXISTS pj_people_contract_party (
    party_id      BIGINT       PRIMARY KEY,
    dept_id       BIGINT       NOT NULL DEFAULT 0,
    company_name  VARCHAR(200) NOT NULL,
    reg_address   VARCHAR(500),
    legal_person  VARCHAR(50),
    remark        VARCHAR(500),
    create_by     BIGINT,
    create_time   TIMESTAMP    DEFAULT now(),
    update_by     BIGINT,
    update_time   TIMESTAMP
);

COMMENT ON TABLE  pj_people_contract_party IS '合同甲方（用人单位）配置：部门→单位映射，dept_id=0 为默认甲方';
COMMENT ON COLUMN pj_people_contract_party.dept_id IS '映射部门ID（0=默认甲方，部门未配置时兜底）';
COMMENT ON COLUMN pj_people_contract_party.company_name IS '单位（甲方）全称';
COMMENT ON COLUMN pj_people_contract_party.reg_address IS '注册地址';
COMMENT ON COLUMN pj_people_contract_party.legal_person IS '法定代表人';

-- 同一部门仅一条映射；默认甲方（0）也只能一条
CREATE UNIQUE INDEX IF NOT EXISTS uk_pj_people_contract_party_dept ON pj_people_contract_party (dept_id);

-- 种子：默认甲方（当前唯一主体）
INSERT INTO pj_people_contract_party (party_id, dept_id, company_name, reg_address, legal_person, remark)
SELECT 1761400000000002901, 0, '成都市人居天街房地产经纪有限公司', '成都市金牛区花照壁中横街88号1层', '廖明', '默认甲方（全部部门）'
WHERE NOT EXISTS (SELECT 1 FROM pj_people_contract_party WHERE dept_id = 0);
