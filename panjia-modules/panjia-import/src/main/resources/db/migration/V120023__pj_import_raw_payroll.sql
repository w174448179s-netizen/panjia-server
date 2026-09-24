-- ============================================================
-- 历史工资导入（HISTORY_PAYROLL）raw 分表：工资族行归档
-- 段位：V120023（2026-09-24）
-- 背景：历史工资导入重构为导入域标准管线（模板解析→raw→归一化→归档事件→各域消费），
--       原 doHistoryPayrollPhase 旁路（HistoryPayrollImportPort 直写 SQL）删除。
-- 分流设计（D2）：
--   · 工资族 6 个 sheet 行 → 本表（sheet_kind 区分来源，消费端 payroll 域合并）
--     WAGE=工资表 DIRECTOR=总监工资 MANAGER=店长工资
--     HR=人事数据补丁 PERF_LEFT=绩效和扣款左半 PERF_RIGHT=绩效和扣款右半
--   · 人事数据考勤口径 → 复用 pj_import_raw_attendance（月度汇总行，attendDate=归属月首日）
--   · 绩效和扣款积分口径 → 复用 pj_import_raw_points（月度总量行，pointDate=归属月首日）
--   · 新签/结佣业绩 → 复用 pj_import_raw_signed（HIST_EXPECT/HIST_REAL 口径标记在 raw_json.recordType）
-- 仿 V120004 raw_points DDL：雪花 ID、insert-only、raw_json 全量留痕。
-- ============================================================

BEGIN;

CREATE TABLE pj_import_raw_payroll (
    id              BIGINT       PRIMARY KEY,
    batch_id        BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no          INT          NOT NULL,
    raw_json        JSONB        NOT NULL,                         -- 全量原始行 JSON
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    employee_code   VARCHAR(32),                                   -- 工号（归一化期姓名富化回填）
    employee_name   VARCHAR(64),                                   -- 姓名（历史表按姓名匹配员工）
    sheet_kind      VARCHAR(32)  NOT NULL                          -- WAGE/DIRECTOR/MANAGER/HR/PERF_LEFT/PERF_RIGHT
);
CREATE INDEX idx_raw_payroll_batch ON pj_import_raw_payroll(batch_id);

COMMENT ON TABLE  pj_import_raw_payroll IS '历史工资原始归档（工资族 sheet 行，sheet_kind 区分来源，insert-only）';
COMMENT ON COLUMN pj_import_raw_payroll.employee_name IS '姓名（历史工资表无工号列，按姓名匹配员工主数据）';
COMMENT ON COLUMN pj_import_raw_payroll.sheet_kind IS 'WAGE=工资表 DIRECTOR=总监工资 MANAGER=店长工资 HR=人事数据补丁 PERF_LEFT=绩效和扣款左半 PERF_RIGHT=绩效和扣款右半';

COMMIT;
