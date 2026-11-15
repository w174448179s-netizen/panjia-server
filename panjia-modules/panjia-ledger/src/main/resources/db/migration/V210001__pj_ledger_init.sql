-- ============================================================
-- V210001 部门收支域（ledger）
-- 1) 收支科目树（收入/支出两棵树，最多 4 级，仅叶子可录入）
-- 2) 部门收支录入（生效/失效日期精确到天）
-- 3) 预置科目（对齐《部门收支分析表》2026.04）
-- ============================================================

CREATE TABLE IF NOT EXISTS pj_ledger_subject (
    id            BIGINT       NOT NULL,
    direction     VARCHAR(16)  NOT NULL,
    parent_id     BIGINT       NOT NULL DEFAULT 0,
    subject_code  VARCHAR(50),
    subject_name  VARCHAR(100) NOT NULL,
    level         INT          NOT NULL DEFAULT 1,
    sort_order    INT          NOT NULL DEFAULT 0,
    data_source   VARCHAR(30)  NOT NULL DEFAULT 'MANUAL',
    status        CHAR(1)      NOT NULL DEFAULT '0',
    remark        VARCHAR(500),
    create_time   TIMESTAMP,
    update_time   TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE pj_ledger_subject IS '收支科目（树形，最多4级；叶子节点才可录入）';
COMMENT ON COLUMN pj_ledger_subject.direction IS 'INCOME=收入 / EXPENSE=支出';
COMMENT ON COLUMN pj_ledger_subject.data_source IS 'MANUAL=手工 / RECEIVED_DETAIL=实收明细自动 / PAYROLL_GROSS=工资应发自动';
CREATE INDEX IF NOT EXISTS idx_ledger_subject_parent ON pj_ledger_subject (direction, parent_id);

CREATE TABLE IF NOT EXISTS pj_ledger_entry (
    id            BIGINT        NOT NULL,
    subject_id    BIGINT        NOT NULL,
    dept_id       BIGINT        NOT NULL,
    direction     VARCHAR(16)   NOT NULL,
    amount        DECIMAL(18,2) NOT NULL DEFAULT 0,
    start_date    DATE          NOT NULL,
    end_date      DATE          NOT NULL,
    remark        VARCHAR(500),
    create_by     BIGINT,
    create_time   TIMESTAMP,
    update_time   TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE pj_ledger_entry IS '部门收支录入（正数=正常发生额，负数=冲减；起止日期含当天）';
CREATE INDEX IF NOT EXISTS idx_ledger_entry_period ON pj_ledger_entry (start_date, end_date);
CREATE INDEX IF NOT EXISTS idx_ledger_entry_dept ON pj_ledger_entry (dept_id, direction);
CREATE INDEX IF NOT EXISTS idx_ledger_entry_subject ON pj_ledger_entry (subject_id);
