-- ============================================================
-- 薪酬结算域（panjia-payroll）表结构 + 规则种子「最终态」
-- 12 张基础表（V160001）+ 提成点调整单（V160009），全部历史 ALTER/DROP 已折叠进 CREATE TABLE；
-- 规则 seed 为全部 INSERT/UPDATE/DELETE/重做链收敛后的最终值。
-- 合并自 V160001,V160002,V160004,V160005,V160006,V160007,V160008,V160009,V160010,V160011,V160012,V160013,V160014,V160015
-- ============================================================

-- 1. 工资批次（V160004 增加流程实例列）
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
    process_instance_id VARCHAR(64),
    version             INT          NOT NULL DEFAULT 0,
    create_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time         TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_batch_period_scope ON pj_payroll_batch(period, dept_scope);
CREATE INDEX idx_pbatch_status ON pj_payroll_batch(status);

-- 2. 工资明细（points_fee 经 V160008 DROP → V160010 重加，最终按 V160010 定义；
--    V160009/V160010/V160011/V160012/V160013/V160015 新增列全部并入）
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
    points_fee              NUMERIC(12,2) DEFAULT 0,
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
    new_sign_performance    NUMERIC(14,2) DEFAULT 0,
    commission_performance  NUMERIC(14,2) DEFAULT 0,
    new_sign_rate           NUMERIC(8,4)  DEFAULT 0,
    perf_deduct             NUMERIC(8,6)  DEFAULT 0,
    manual_adjust           NUMERIC(8,4)  NOT NULL DEFAULT 0,
    total_deduct            NUMERIC(8,6)  NOT NULL DEFAULT 0,
    rate_adjust_json        TEXT,
    dept_new_sign_total     NUMERIC(18,2) DEFAULT 0,
    dept_employer_social_total NUMERIC(18,2) DEFAULT 0,
    team_rate               NUMERIC(9,6),
    store_rate              NUMERIC(9,6),
    min_salary              NUMERIC(18,2) DEFAULT 0,
    full_attendance         NUMERIC(18,2) DEFAULT 0,
    director_store_items    TEXT,
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

-- 13. 提成点调整单（V160009）
CREATE TABLE pj_payroll_rate_adjust (
    id                  BIGINT        PRIMARY KEY,
    employee_id         BIGINT        NOT NULL,
    adjust_type         VARCHAR(32)   NOT NULL,
    adjust_rate         NUMERIC(8,4)  NOT NULL,
    start_month         VARCHAR(7)    NOT NULL,
    end_month           VARCHAR(7),
    reason              VARCHAR(500)  NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    process_instance_id VARCHAR(64),
    apply_by            BIGINT,
    apply_time          TIMESTAMP,
    approve_by          BIGINT,
    approve_time        TIMESTAMP,
    reject_reason       VARCHAR(500),
    version             INT           NOT NULL DEFAULT 0,
    create_time         TIMESTAMP     NOT NULL DEFAULT NOW(),
    update_time         TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_prate_emp    ON pj_payroll_rate_adjust(employee_id, start_month);
CREATE INDEX idx_prate_status ON pj_payroll_rate_adjust(status);

-- ============================================================
-- 规则种子数据（最终态）
-- ============================================================

-- 职级/提成规则：以 V160014 的 DELETE 全表 + 重插为最终口径
-- （V160002 的 A0/S1 修正与 V160006 的 C 系初版均被 V160014 重做覆盖）
INSERT INTO pj_payroll_rank_rule (id, level_code, base_salary, base_rate, min_salary, team_rate, personal_rate, rule_content, effective_from) VALUES
(1762500000000000101, 'A0', 4500.00, 0.55, 4500.00, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000102, 'A1', 0, 0.6, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000103, 'A2', 0, 0.7, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000104, 'A3', 0, 0.7, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000105, 'A4', 0, 0.7, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000106, 'A5', 0, 0.70, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000107, 'S1', 0, 0.30, 8000.00, 0.10, 0.70, '{"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":0.02}', '2026-01-01'),
(1762500000000000108, 'S2', 0, 0.30, 8000.00, 0.10, 0.70, '{"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":0.02}', '2026-01-01'),
(1762500000000000109, 'D',  6000.00, 0.30, 0, NULL, NULL, '{"brackets":[{"min":0,"max":100000,"rate":0.06},{"min":100000,"max":200000,"rate":0.07},{"min":200000,"max":null,"rate":0.08}],"mentorBonusMode":"AMOUNT_RATIO","mentorBonusAmountRatio":0.02}', '2026-01-01'),
(1762500000000000111, 'C0', 2000.00, 0.30, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000112, 'C1', 0, 0.6, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000113, 'C2', 0, 0.7, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01'),
(1762500000000000114, 'C3', 0, 0.70, 0, NULL, NULL, '{"mentorBonusMode":"RATE_ADD","mentorBonusRateAdd":0.02,"mentorBonusRateCap":0.10}', '2026-01-01');

-- 客户政策规则（全局）：V160005 社保口径 + V160007 leaveFee + V160009 noSocialDeduct 折叠
INSERT INTO pj_payroll_policy_rule (id, scope_type, scope_key, base_social, rule_content, effective_from) VALUES
(1762500000000000201, 'GLOBAL', NULL, 1630.57,
 '{"socialSettlementRatio":{"A0":0.70,"A1":0.70,"A2":0.70,"A3":0.70,"A4":0.70,"A5":0.70,
   "C0":0,"C1":0,"C2":0,"C3":0,
   "S1":0,"S2":0,
   "D":0.30},
   "socialFixedFee":{"S1":477.15,"S2":477.15},
   "parttimeExemptSocial":true,"parttimeExemptHousing":true,"housingFund":0,
   "attendance":{"lateFee":20,"absentNoBaseFee":50,"absentWithBaseTimes":3,"workDaysPerMonth":21.75,"leaveFee":50},
   "points":{"penaltyFee":5,"gradeA":8.0,"gradeB":6.0,"deductA":0.0,"deductB":-0.02,"deductC":-0.04},
   "noSocialDeduct":-0.02,
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

-- ============================================================
-- 注释（最终语义）
-- ============================================================
COMMENT ON TABLE pj_payroll_batch IS '工资批次（薪酬结算域聚合根）';
COMMENT ON TABLE pj_payroll_detail IS '工资明细（一人一条，含收入/支出/汇总）';
COMMENT ON TABLE pj_payroll_calculation_result IS '算薪结果（不可变，重算=新增attempt）';
COMMENT ON TABLE pj_payroll_rate_adjust IS '提成点调整单（员工业绩扣点，总监审批后按期间生效）';
COMMENT ON COLUMN pj_payroll_batch.process_instance_id IS 'Warm-Flow 流程实例ID(payroll_batch)';
COMMENT ON COLUMN pj_payroll_detail.employer_social IS '公司承担社保，不进net，仅归集';
COMMENT ON COLUMN pj_payroll_detail.points_fee IS '积分扣款（晚提交处罚：次数×5元/次）';
COMMENT ON COLUMN pj_payroll_detail.new_sign_performance IS '当月新签业绩（折算后金额；店长=个人新签业绩）';
COMMENT ON COLUMN pj_payroll_detail.commission_performance IS '当月结佣业绩（不折算，贝壳实收到手值）';
COMMENT ON COLUMN pj_payroll_detail.new_sign_rate IS '当月新签业绩提成比例（职级personalRate）';
COMMENT ON COLUMN pj_payroll_detail.perf_deduct IS '绩效提成扣点（积分等级A/B/C对应扣点，A=0/B=-2%/C=-4%，负=扣点）';
COMMENT ON COLUMN pj_payroll_detail.manual_adjust IS '提成点调整合计（未参保自动扣点+审批通过人工项，负=扣点）';
COMMENT ON COLUMN pj_payroll_detail.total_deduct IS '所有扣点合计（等级扣点+未参保自动扣点+人工调整，负=扣点，前端绩效提成扣点列统一取此值）';
COMMENT ON COLUMN pj_payroll_detail.rate_adjust_json IS '提成点调整命中项溯源JSON（adjustId/type/rate/reason/source）';
COMMENT ON COLUMN pj_payroll_detail.dept_new_sign_total IS '门店当月新签计薪业绩合计（折算后，店长/总监展示用）';
COMMENT ON COLUMN pj_payroll_detail.dept_employer_social_total IS '门店社保业绩扣款（门店全员公司承担社保合计）';
COMMENT ON COLUMN pj_payroll_detail.team_rate IS '店长团队提成比例（职级规则 teamRate）';
COMMENT ON COLUMN pj_payroll_detail.store_rate IS '总监门店提成比例（跳点命中档 rate）';
COMMENT ON COLUMN pj_payroll_detail.min_salary IS '店长保底工资（职级规则 minSalary）';
COMMENT ON COLUMN pj_payroll_detail.full_attendance IS '总监全勤奖（policy.fullAttendance 默认 500）';
COMMENT ON COLUMN pj_payroll_detail.director_store_items IS '总监各门店提成明细 JSON（deptId/newSign/social/billable/rate/income，导出按门店分行）';
COMMENT ON COLUMN pj_payroll_rate_adjust.adjust_type IS '调整类型（字典 rate_adjust_type：NO_SOCIAL/PHONE_CHECK/PERSONAL）';
COMMENT ON COLUMN pj_payroll_rate_adjust.adjust_rate IS '调整点数（负值=扣点，如 -0.02 扣 2 个点）';
COMMENT ON COLUMN pj_payroll_rate_adjust.start_month IS '生效起始月 YYYY-MM（含）';
COMMENT ON COLUMN pj_payroll_rate_adjust.end_month IS '生效结束月 YYYY-MM（含，null=长期有效至撤销）';
COMMENT ON COLUMN pj_payroll_rate_adjust.reason IS '调整原因（必填）';
COMMENT ON COLUMN pj_payroll_rate_adjust.status IS '状态：DRAFT/SUBMITTED/APPROVED/REJECTED/CANCELLED';
