-- ============================================================
-- 盘家智管 · 数据导入域 · pj_import_template 建表
-- 依据：AI 任务卡 01 Task-0-1 + Phase0 详细设计 V3.0 §1
-- 数据库：PostgreSQL 16（JSONB / BIGINT / CHECK）
-- 版本号说明：任务卡概念版本 V1，全局实际 V100002（避开基线 V1 / 菜单 V100001）
-- ============================================================

CREATE TABLE pj_import_template (
    id                  BIGINT       PRIMARY KEY,               -- 雪花 ID（应用层 ASSIGN_ID 生成）
    template_code       VARCHAR(64)  NOT NULL,                  -- 业务编码，如 SHELL_PERFORMANCE
    template_version    VARCHAR(32)  NOT NULL DEFAULT 'V1',     -- 业务迭代版本（非乐观锁）
    opt_lock_version    INT          NOT NULL DEFAULT 1,        -- 仅乐观锁（MyBatis-Plus @Version）
    template_name       VARCHAR(128) NOT NULL,
    source_type         VARCHAR(32)  NOT NULL,                  -- SHELL / ATTENDANCE / SCORE / MANUAL / COST
    file_type           VARCHAR(16)  NOT NULL DEFAULT 'EXCEL',
    sheet_name          VARCHAR(64),                           -- NULL=取第一个 sheet（禁止空串 ''）
    header_row          INT          NOT NULL DEFAULT 1,
    data_start_row      INT          NOT NULL DEFAULT 2,
    column_mapping      JSONB        NOT NULL,                  -- 列映射数组，见 §1.5
    validation_rules    JSONB,                                  -- 校验规则，见 §1.6
    description         TEXT,                                   -- 长说明（源文件版本/变更历史）
    source_file_version VARCHAR(64),                           -- 适配的外部文件版本，如 SHELL_LIFANGTONG_202607
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    effective_from      DATE,
    effective_to        DATE,
    remark              VARCHAR(255),
    created_by          VARCHAR(64),                           -- 存 sys_user.user_name（如 admin），非 user_id
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(64),
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 唯一约束：同一模板编码 + 版本不允许重复
    CONSTRAINT uk_template_code_ver UNIQUE (template_code, template_version),

    -- CHECK 约束（P1）
    CONSTRAINT chk_header_row   CHECK (header_row >= 1),
    CONSTRAINT chk_data_start   CHECK (data_start_row > header_row),
    CONSTRAINT chk_sheet_name   CHECK (sheet_name IS NULL OR length(trim(sheet_name)) > 0)
);

-- 生效窗口索引：按 source_type 查询 active 模板
CREATE INDEX idx_import_template_source_active
    ON pj_import_template(source_type, is_active, effective_from, effective_to);

COMMENT ON TABLE  pj_import_template IS '导入模板配置表（Excel 列映射 + 校验规则 + 生效窗口）';
COMMENT ON COLUMN pj_import_template.id IS '主键，雪花 ID（应用层 ASSIGN_ID 生成）';
COMMENT ON COLUMN pj_import_template.template_code IS '业务编码，如 SHELL_PERFORMANCE';
COMMENT ON COLUMN pj_import_template.template_version IS '业务迭代版本（非乐观锁），同一 template_code 可多版本并存';
COMMENT ON COLUMN pj_import_template.opt_lock_version IS '乐观锁版本号（MyBatis-Plus @Version 自动递增，无数据库触发器）';
COMMENT ON COLUMN pj_import_template.template_name IS '模板名称';
COMMENT ON COLUMN pj_import_template.source_type IS '来源类型：SHELL(贝壳业绩) / ATTENDANCE(考勤) / SCORE(积分) / MANUAL(手动录入) / COST(门店成本)';
COMMENT ON COLUMN pj_import_template.file_type IS '文件类型：EXCEL（V1 仅支持 Excel）';
COMMENT ON COLUMN pj_import_template.sheet_name IS 'Excel sheet 名；NULL=取第一个 sheet（禁止空串）';
COMMENT ON COLUMN pj_import_template.header_row IS '表头所在行号（>=1）';
COMMENT ON COLUMN pj_import_template.data_start_row IS '数据起始行（必须 > header_row）';
COMMENT ON COLUMN pj_import_template.column_mapping IS '列映射 JSONB 数组：source_column/source_header/target_field/data_type/required/default_value/transform/header_match_mode';
COMMENT ON COLUMN pj_import_template.validation_rules IS '校验规则 JSONB：file_level（文件级，拒绝整批） + row_level（行级，部分成功）';
COMMENT ON COLUMN pj_import_template.description IS '长说明（源文件版本/变更历史）';
COMMENT ON COLUMN pj_import_template.source_file_version IS '适配的外部文件版本，如 SHELL_LIFANGTONG_202607';
COMMENT ON COLUMN pj_import_template.is_active IS '是否启用';
COMMENT ON COLUMN pj_import_template.effective_from IS '生效起始日期';
COMMENT ON COLUMN pj_import_template.effective_to IS '生效截止日期（NULL=无限期）';
COMMENT ON COLUMN pj_import_template.remark IS '备注';
COMMENT ON COLUMN pj_import_template.created_by IS '创建人 sys_user.user_name（非 user_id）';
COMMENT ON COLUMN pj_import_template.created_at IS '创建时间';
COMMENT ON COLUMN pj_import_template.updated_by IS '更新人 sys_user.user_name';
COMMENT ON COLUMN pj_import_template.updated_at IS '更新时间';
