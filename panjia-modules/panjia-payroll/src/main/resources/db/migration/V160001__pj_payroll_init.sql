-- ============================================================
-- 薪酬结算域（panjia-payroll）初始化 DDL
-- 12 张表 + 三类规则种子数据（V4.4 默认参数）
-- ============================================================

-- 1. 工资批次
CREATE TABLE pj_payroll_batch (
    id                  BIGINT       PRIMARY KEY,
    period              VARCHAR(7)   NOT NULL,
    dept_scope          VARCHAR(32)  NOT NULL DEFAULT 'ALL',
    status              VARCHAR(16)  NOT NULL,
    rule_snapshot_id    BIGINT,
    employee_count      INT          NOT NULL DEFAULT 0,
    gross_total         NUMERIC(18,2) NOT NULL DEFAULT 0,
    deduct_total        NUMERIC(18,2) NOT NULL DEFAULT 0,
    tax_total           NUMERIC(18,2) NOT NULL DEFAULT 0,
    net_total           NUMERIC(18,2) NOT NULL DEFAULT 0,
    attempt             INT          NOT NULL DEFAULT 0,
    input_hash          VARCHAR(64),
    locked_at           TIMESTAMP,
    locked_by           BIGINT,
    operator_id         BIGINT,
    version             INT          NOT NULL DEFAULT 0,
    create_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time         TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_batch_period_scope ON pj_payroll_batch(period, dept_scope);
CREATE INDEX idx_pbatch_status ON pj_payroll_batch(status);

-- 2. 工资明细
CREATE TABLE pj_payroll_detail (
    id                      BIGINT        PRIMARY KEY,
    batch_id                BIGINT        NOT NULL,
    period                  VARCHAR(7)    NOT NULL,
    employee_id             BIGINT        NOT NULL,
    dept_id                 BIGINT        NOT NULL,
    level_code              VARCHAR(16),
    employee_role           VARCHAR(16),
    is_part_time            BOOLEAN       NOT NULL DEFAULT FALSE,
    commission_income       NUMERIC(18,2) NOT NULL DEFAULT 0,
    team_income             NUMERIC(18,2) NOT NULL DEFAULT 0,
    personal_newsign_income NUMERIC(18,2) NOT NULL DEFAULT 0,
    store_income            NUMERIC(18,2) NOT NULL DEFAULT 0,
    base_salary             NUMERIC(18,2) NOT NULL DEFAULT 0,
    guarantee_fill          NUMERIC(18,2) NOT NULL DEFAULT 0,
    mentor_bonus            NUMERIC(18,2) NOT NULL DEFAULT 0,
    bonus                   NUMERIC(18,2) NOT NULL DEFAULT 0,
    other_income            NUMERIC(18,2) NOT NULL DEFAULT 0,
    social_fee              NUMERIC(18,2) NOT NULL DEFAULT 0,
    housing_fund            NUMERIC(18,2) NOT NULL DEFAULT 0,
    attendance_fee          NUMERIC(18,2) NOT NULL DEFAULT 0,
    points_fee              NUMERIC(18,2) NOT NULL DEFAULT 0,
    commercial_insurance    NUMERIC(18,2) NOT NULL DEFAULT 0,
    dormitory_fee           NUMERIC(18,2) NOT NULL DEFAULT 0,
    negative_carryover      NUMERIC(18,2) NOT NULL DEFAULT 0,
    other_deduct            NUMERIC(18,2) NOT NULL DEFAULT 0,
    gross                   NUMERIC(18,2) NOT NULL DEFAULT 0,
    deduct                  NUMERIC(18,2) NOT NULL DEFAULT 0,
    tax                     NUMERIC(18,2) NOT NULL DEFAULT 0,
    net                     NUMERIC(18,2) NOT NULL DEFAULT 0,
    employer_social         NUMERIC(18,2) NOT NULL DEFAULT 0,
    final_rate              NUMERIC(9,6),
    perf_grade              VARCHAR(1),
    rule_snapshot_id        BIGINT,
    emp_snapshot_id         BIGINT,
    version                 INT          NOT NULL DEFAULT 0,
    create_time             TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time             TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_pdetail_batch_emp ON pj_payroll_detail(batch_id, employee_id);
CREATE INDEX idx_pdetail_emp ON pj_payroll_detail(period, employee_id);
CREATE INDEX idx_pdetail_dept ON pj_payroll_detail(period, dept_id);

-- 3. 算薪结果（不可变）
CREATE TABLE pj_payroll_calculation_result (
    id                BIGINT       PRIMARY KEY,
    batch_id          BIGINT       NOT NULL,
    employee_id       BIGINT       NOT NULL,
    attempt           INT          NOT NULL,
    rule_snapshot_id  BIGINT       NOT NULL,
    emp_snapshot_id   BIGINT       NOT NULL,
    input_hash        VARCHAR(64)  NOT NULL,
    content           JSONB        NOT NULL DEFAULT '{}',
    create_time       TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_pcalc_biz ON pj_payroll_calculation_result(batch_id, employee_id, attempt);

-- 4. 职级/提成规则
CREATE TABLE pj_payroll_rank_rule (
    id              BIGINT       PRIMARY KEY,
    level_code      VARCHAR(16)  NOT NULL,
    base_salary     NUMERIC(18,2) NOT NULL DEFAULT 0,
    base_rate       NUMERIC(9,6)  NOT NULL DEFAULT 0,
    min_salary      NUMERIC(18,2) NOT NULL DEFAULT 0,
    team_rate       NUMERIC(9,6),
    personal_rate   NUMERIC(9,6),
    rule_content    JSONB        NOT NULL DEFAULT '{}',
    effective_from  DATE         NOT NULL,
    effective_to    DATE         NOT NULL DEFAULT '9999-12-31',
    version         INT          NOT NULL DEFAULT 0,
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_prank_level ON pj_payroll_rank_rule(level_code, effective_from, effective_to);

-- 5. 客户政策规则
CREATE TABLE pj_payroll_policy_rule (
    id              BIGINT       PRIMARY KEY,
    scope_type      VARCHAR(16)  NOT NULL,
    scope_key       VARCHAR(64),
    base_social     NUMERIC(18,2) NOT NULL DEFAULT 1637.15,
    rule_content    JSONB        NOT NULL DEFAULT '{}',
    effective_from  DATE         NOT NULL,
    effective_to    DATE         NOT NULL DEFAULT '9999-12-31',
    version         INT          NOT NULL DEFAULT 0,
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ppolicy_scope ON pj_payroll_policy_rule(scope_type, scope_key, effective_from);

-- 6. 业绩折算规则
CREATE TABLE pj_payroll_conversion_rule (
    id              BIGINT       PRIMARY KEY,
    biz_type        VARCHAR(32)  NOT NULL,
    factor          NUMERIC(9,6) NOT NULL,
    effective_from  DATE         NOT NULL,
    effective_to    DATE         NOT NULL DEFAULT '9999-12-31',
    version         INT          NOT NULL DEFAULT 0,
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pconv_biz ON pj_payroll_conversion_rule(biz_type, effective_from, effective_to);

-- 7. 规则快照
CREATE TABLE pj_payroll_rule_snapshot (
    id              BIGINT       PRIMARY KEY,
    batch_id        BIGINT       NOT NULL,
    period          VARCHAR(7)   NOT NULL,
    snapshot_content JSONB       NOT NULL DEFAULT '{}',
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_prulesnap_batch ON pj_payroll_rule_snapshot(batch_id);

-- 8. 员工快照（算薪时点）
CREATE TABLE pj_payroll_employee_snapshot (
    id              BIGINT       PRIMARY KEY,
    batch_id        BIGINT       NOT NULL,
    employee_id     BIGINT       NOT NULL,
    snapshot_date   DATE         NOT NULL,
    snapshot_content JSONB       NOT NULL DEFAULT '{}',
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_pempsnap ON pj_payroll_employee_snapshot(batch_id, employee_id);

-- 9. 递延台账
CREATE TABLE pj_payroll_deferred_item (
    id               BIGINT        PRIMARY KEY,
    employee_id      BIGINT        NOT NULL,
    dept_id          BIGINT        NOT NULL,
    period           VARCHAR(7)    NOT NULL,
    deal_key         VARCHAR(255)  NOT NULL,
    source_fact_id   BIGINT,
    deferred_amount  NUMERIC(18,2) NOT NULL,
    final_rate       NUMERIC(9,6)  NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    released_period  VARCHAR(7),
    released_batch_id BIGINT,
    rule_snapshot_id BIGINT,
    version          INT           NOT NULL DEFAULT 0,
    create_time      TIMESTAMP     NOT NULL DEFAULT NOW(),
    update_time      TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_pdefer_deal ON pj_payroll_deferred_item(employee_id, deal_key);
CREATE INDEX idx_pdefer_status ON pj_payroll_deferred_item(status, deal_key);

-- 10. 负工资结转
CREATE TABLE pj_payroll_negative_balance (
    id            BIGINT        PRIMARY KEY,
    employee_id   BIGINT        NOT NULL,
    period        VARCHAR(7)    NOT NULL,
    amount        NUMERIC(18,2) NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    used_period   VARCHAR(7),
    version       INT           NOT NULL DEFAULT 0,
    create_time   TIMESTAMP     NOT NULL DEFAULT NOW(),
    update_time   TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pneg_emp ON pj_payroll_negative_balance(employee_id, status);

-- 11. 手工录入项
CREATE TABLE pj_payroll_manual_item (
    id            BIGINT        PRIMARY KEY,
    period        VARCHAR(7)    NOT NULL,
    employee_id   BIGINT        NOT NULL,
    item_type     VARCHAR(16)   NOT NULL,
    sub_type      VARCHAR(32),
    amount        NUMERIC(18,2) NOT NULL,
    reason        VARCHAR(512),
    status        VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    apply_by      BIGINT,
    approve_by    BIGINT,
    batch_id      BIGINT,
    create_time   TIMESTAMP     NOT NULL DEFAULT NOW(),
    update_time   TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pmanual_period ON pj_payroll_manual_item(period, employee_id, item_type, status);

-- 12. 调整/补发单
CREATE TABLE pj_payroll_adjust (
    id              BIGINT        PRIMARY KEY,
    source_batch_id BIGINT,
    target_period   VARCHAR(7)    NOT NULL,
    employee_id     BIGINT        NOT NULL,
    adjust_type     VARCHAR(16)   NOT NULL,
    amount          NUMERIC(18,2) NOT NULL,
    contract_id     BIGINT,
    source_fact_id  BIGINT,
    rule_snapshot_id BIGINT,
    bad_debt_flag   BOOLEAN       NOT NULL DEFAULT FALSE,
    bad_debt_amount NUMERIC(18,2),
    reason          VARCHAR(512)  NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    result_id       BIGINT,
    operator_id     BIGINT,
    version         INT           NOT NULL DEFAULT 0,
    create_time     TIMESTAMP     NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_padj_target ON pj_payroll_adjust(target_period, status);
CREATE INDEX idx_padj_src ON pj_payroll_adjust(source_batch_id, employee_id);

-- ============================================================
-- 规则种子数据（V4.4 默认参数）
-- ============================================================

-- 职级/提成规则
INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from) VALUES
(1762500000000000101, 'A0', 4500.00, 0.55, 4500.00, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000102, 'A1', 0, 0.55, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000103, 'A2', 0, 0.60, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000104, 'A3', 0, 0.65, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000105, 'A4', 0, 0.67, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000106, 'A5', 0, 0.70, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000107, 'S1', 0, 0.30, 8000.00, 0.10, 0.70, '{"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":0.02}', '2026-01-01'),
(1762500000000000108, 'S2', 0, 0.30, 8000.00, 0.10, 0.70, '{"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":0.02}', '2026-01-01'),
(1762500000000000109, 'D',  6000.00, 0.30, 0, NULL, NULL, '{"brackets":[{"min":0,"max":100000,"rate":0.06},{"min":100000,"max":200000,"rate":0.07},{"min":200000,"max":null,"rate":0.08}],"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":0.02}', '2026-01-01');

-- 客户政策规则（全局）
INSERT INTO pj_payroll_policy_rule (id, scope_type, scope_key, base_social, rule_content, effective_from) VALUES
(1762500000000000201, 'GLOBAL', NULL, 1637.15,
 '{"socialSettlementRatio":{"A0":0.20,"A1":0.55,"A2":0.60,"A3":0.65,"A4":0.67,"A5":0.70,"S1":0.30,"S2":0.30,"D":0.30},
   "parttimeExemptSocial":true,"parttimeExemptHousing":true,"housingFund":0,
   "attendance":{"lateFee":20,"absentNoBaseFee":50,"absentWithBaseTimes":3,"workDaysPerMonth":21.75},
   "points":{"penaltyFee":5,"gradeA":8.0,"gradeB":6.0,"deductA":0.0,"deductB":-0.02,"deductC":-0.04},
   "commercialInsurance":21,"dormitoryFee":0,
   "tax":{"threshold":5000,"deductSocial":true,
     "brackets":[{"min":0,"rate":0.03,"quick":0},{"min":3000,"rate":0.10,"quick":210},{"min":12000,"rate":0.20,"quick":1410},
                 {"min":25000,"rate":0.25,"quick":2660},{"min":35000,"rate":0.30,"quick":4410},
                 {"min":55000,"rate":0.35,"quick":7160},{"min":80000,"rate":0.45,"quick":15160}]}}',
 '2026-01-01');

-- 业绩折算规则
INSERT INTO pj_payroll_conversion_rule (id, biz_type, factor, effective_from) VALUES
(1762500000000000301, 'FIRST_HAND', 0.9024, '2026-01-01'),
(1762500000000000302, 'DEFAULT', 0.96, '2026-01-01');

COMMENT ON TABLE pj_payroll_batch IS '工资批次（薪酬结算域聚合根）';
COMMENT ON TABLE pj_payroll_detail IS '工资明细（一人一条，含收入/支出/汇总）';
COMMENT ON TABLE pj_payroll_calculation_result IS '算薪结果（不可变，重算=新增attempt）';
COMMENT ON COLUMN pj_payroll_detail.employer_social IS '公司承担社保，不进net，仅归集';
