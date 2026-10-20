-- ============================================================
-- V110016: 合同自定义模板存储
--   * 客户可上传自己的合同模板（HTML 片段，{{占位符}}），按合同类型存储
--   * 未上传则使用系统内置默认模板
-- ============================================================

CREATE TABLE IF NOT EXISTS pj_people_contract_template (
    contract_type   VARCHAR(20)  NOT NULL,
    oss_id          BIGINT,
    url             VARCHAR(500),
    original_name   VARCHAR(200),
    update_by       BIGINT,
    update_time     TIMESTAMP,
    PRIMARY KEY (contract_type)
);

COMMENT ON TABLE  pj_people_contract_template IS '合同自定义模板';
COMMENT ON COLUMN pj_people_contract_template.contract_type IS '合同类型 LABOR/PARTTIME/OTHER';
COMMENT ON COLUMN pj_people_contract_template.oss_id IS 'OSS 文件ID';
COMMENT ON COLUMN pj_people_contract_template.url IS 'OSS 访问URL';
COMMENT ON COLUMN pj_people_contract_template.original_name IS '原始文件名';
COMMENT ON COLUMN pj_people_contract_template.update_by IS '更新人';
COMMENT ON COLUMN pj_people_contract_template.update_time IS '更新时间';
