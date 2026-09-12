-- ============================================================
-- 业绩域 V1.0 核心表结构
-- 段位：V140002（2026-09-11 由 V100020 重命名）
-- 包含：pj_perf_fact（业绩事实）+ pj_perf_adjust（调整单）+ pj_perf_consume_log（消费日志）+ pj_perf_period_close（期间封账）
-- 说明：
--   1) 主键用 BIGINT（雪花 ID，应用层 ASSIGN_ID 生成），非 BIGSERIAL；
--   2) 金额用 NUMERIC(18,2)；
--   3) 时间用 TIMESTAMP DEFAULT CURRENT_TIMESTAMP；
--      ★ 禁用 TIMESTAMP WITH TIME ZONE：与 RuoYi 基线（sys_user 等，TIMESTAMP 不带时区）保持一致，
--        PG JDBC 42.7+ 对 timestamptz → LocalDateTime 的 getObject 直接抛
--        "Cannot convert the column of type TIMESTAMPTZ"（有歧义转换），查询页会炸；
--   4) 所有表/列均加 COMMENT。
-- ============================================================

BEGIN;

-- ---------- 一、业绩事实表 ----------
CREATE TABLE pj_perf_fact (
    id                      BIGINT                 PRIMARY KEY,
    fact_type               VARCHAR(20)            NOT NULL,
    period                  VARCHAR(7)             NOT NULL,
    business_date           DATE                   NOT NULL,
    batch_id                BIGINT,
    normalized_record_id    BIGINT,
    source_key              VARCHAR(200)           NOT NULL,
    biz_type                VARCHAR(50),
    employee_id             BIGINT,
    employee_external_code  VARCHAR(50),
    dept_id                 BIGINT,
    role_type               VARCHAR(30),
    share_ratio             NUMERIC(10,6)          NOT NULL DEFAULT 1.000000,
    origin_amount           NUMERIC(18,2)          NOT NULL DEFAULT 0,
    conversion_rate         NUMERIC(10,6)          NOT NULL DEFAULT 1.000000,
    performance_amount      NUMERIC(18,2)          NOT NULL DEFAULT 0,
    effective_date          DATE                   NOT NULL,
    expire_date             DATE,
    fact_status             VARCHAR(20)            NOT NULL DEFAULT 'ACTIVE',
    source                  VARCHAR(20)            NOT NULL DEFAULT 'IMPORT',
    adjust_id               BIGINT,
    reversed_reason         VARCHAR(30),
    operator_id             BIGINT,
    version                 INT                    NOT NULL DEFAULT 0,
    create_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 幂等锚点：同一 fact_type + source_key 只允许一条 ACTIVE 记录
CREATE UNIQUE INDEX uk_perf_fact_source_key
    ON pj_perf_fact(fact_type, source_key, fact_status)
    WHERE fact_status = 'ACTIVE';

CREATE INDEX idx_pfact_emp     ON pj_perf_fact(employee_id, period, fact_type);
CREATE INDEX idx_pfact_dept    ON pj_perf_fact(dept_id, period, fact_type);
CREATE INDEX idx_pfact_batch   ON pj_perf_fact(batch_id);
CREATE INDEX idx_pfact_period  ON pj_perf_fact(period, fact_type);

COMMENT ON TABLE  pj_perf_fact IS '业绩事实表';
COMMENT ON COLUMN pj_perf_fact.fact_type IS '事实口径 PERF_REAL=结佣业绩(实收) PERF_EXPECT=新签业绩(应收)';
COMMENT ON COLUMN pj_perf_fact.period IS '归属期间 YYYY-MM';
COMMENT ON COLUMN pj_perf_fact.business_date IS '业务发生日';
COMMENT ON COLUMN pj_perf_fact.batch_id IS '来源导入批次ID';
COMMENT ON COLUMN pj_perf_fact.normalized_record_id IS '归一化记录ID';
COMMENT ON COLUMN pj_perf_fact.source_key IS '来源业务单号(幂等锚点)';
COMMENT ON COLUMN pj_perf_fact.biz_type IS '业务类型(产品/险种等)';
COMMENT ON COLUMN pj_perf_fact.employee_id IS '员工ID';
COMMENT ON COLUMN pj_perf_fact.employee_external_code IS '员工外部编码(工号)';
COMMENT ON COLUMN pj_perf_fact.dept_id IS '归属部门ID';
COMMENT ON COLUMN pj_perf_fact.role_type IS '角色类型(主筹/跟筹等)';
COMMENT ON COLUMN pj_perf_fact.share_ratio IS '分摊比例';
COMMENT ON COLUMN pj_perf_fact.origin_amount IS '原始金额';
COMMENT ON COLUMN pj_perf_fact.conversion_rate IS '折算系数';
COMMENT ON COLUMN pj_perf_fact.performance_amount IS '业绩金额(=原始金额×分摊比例×折算系数)';
COMMENT ON COLUMN pj_perf_fact.effective_date IS '生效起始日(闭区间)';
COMMENT ON COLUMN pj_perf_fact.expire_date IS '生效截止日(开区间，9999-12-31表示有效)';
COMMENT ON COLUMN pj_perf_fact.fact_status IS '事实状态 ACTIVE=有效 REVERSED=已冲销';
COMMENT ON COLUMN pj_perf_fact.source IS '来源 IMPORT=导入 MANUAL=手工';
COMMENT ON COLUMN pj_perf_fact.adjust_id IS '关联调整单ID';
COMMENT ON COLUMN pj_perf_fact.reversed_reason IS '冲销原因 SUPERSEDE=替换 RENORMALIZE=重归一化 MANUAL_ADJUST=手工调整 PERIOD_VOID=期间作废';
COMMENT ON COLUMN pj_perf_fact.operator_id IS '操作人ID';
COMMENT ON COLUMN pj_perf_fact.version IS '乐观锁版本号';
COMMENT ON COLUMN pj_perf_fact.create_time IS '创建时间';
COMMENT ON COLUMN pj_perf_fact.update_time IS '更新时间';

-- ---------- 二、业绩调整单 ----------
CREATE TABLE pj_perf_adjust (
    id                  BIGINT                 PRIMARY KEY,
    adjust_no           VARCHAR(32)            NOT NULL,
    fact_id             BIGINT,
    period              VARCHAR(7)             NOT NULL,
    employee_id         BIGINT                 NOT NULL,
    dept_id             BIGINT                 NOT NULL,
    adjust_type         VARCHAR(20)            NOT NULL,
    payload_json        TEXT,
    delta_amount        NUMERIC(18,2)          DEFAULT 0,
    target_dept_id      BIGINT,
    reason              VARCHAR(500),
    status              VARCHAR(20)            NOT NULL DEFAULT 'SUBMITTED',
    process_instance_id VARCHAR(64),
    applicant_id        BIGINT                 NOT NULL,
    approver_id         BIGINT,
    approve_time        TIMESTAMP,
    operator_id         BIGINT,
    execute_time        TIMESTAMP,
    create_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_padj_no ON pj_perf_adjust(adjust_no);
CREATE INDEX idx_padj_period   ON pj_perf_adjust(period, adjust_type, status);
CREATE INDEX idx_padj_employee ON pj_perf_adjust(employee_id, period);
CREATE INDEX idx_padj_fact     ON pj_perf_adjust(fact_id);

COMMENT ON TABLE  pj_perf_adjust IS '业绩调整单';
COMMENT ON COLUMN pj_perf_adjust.adjust_no IS '调整单号';
COMMENT ON COLUMN pj_perf_adjust.fact_id IS '关联业绩事实ID(冲销/调整类必填)';
COMMENT ON COLUMN pj_perf_adjust.period IS '归属期间';
COMMENT ON COLUMN pj_perf_adjust.employee_id IS '员工ID';
COMMENT ON COLUMN pj_perf_adjust.dept_id IS '原部门ID';
COMMENT ON COLUMN pj_perf_adjust.adjust_type IS '调整类型 AMOUNT=金额调整 VOID=冲销 TRANSFER=部门划转';
COMMENT ON COLUMN pj_perf_adjust.payload_json IS '调整详情JSON(不同类型结构不同)';
COMMENT ON COLUMN pj_perf_adjust.delta_amount IS '金额变动值(正增负减)';
COMMENT ON COLUMN pj_perf_adjust.target_dept_id IS '目标部门ID(划转类必填)';
COMMENT ON COLUMN pj_perf_adjust.reason IS '调整原因';
COMMENT ON COLUMN pj_perf_adjust.status IS '状态 SUBMITTED=已提交 APPROVED=已通过 REJECTED=已拒绝 CANCELLED=已取消 EXECUTED=已执行';
COMMENT ON COLUMN pj_perf_adjust.process_instance_id IS '审批流程实例ID';
COMMENT ON COLUMN pj_perf_adjust.applicant_id IS '申请人ID';
COMMENT ON COLUMN pj_perf_adjust.approver_id IS '审批人ID';
COMMENT ON COLUMN pj_perf_adjust.approve_time IS '审批时间';
COMMENT ON COLUMN pj_perf_adjust.operator_id IS '执行人ID';
COMMENT ON COLUMN pj_perf_adjust.execute_time IS '执行时间';
COMMENT ON COLUMN pj_perf_adjust.create_time IS '创建时间';
COMMENT ON COLUMN pj_perf_adjust.update_time IS '更新时间';

-- ---------- 三、消费日志 ----------
CREATE TABLE pj_perf_consume_log (
    id              BIGINT                 PRIMARY KEY,
    batch_id        BIGINT,
    event_type      VARCHAR(50)            NOT NULL,
    event_id        VARCHAR(64)            NOT NULL,
    period          VARCHAR(7),
    source_type     VARCHAR(30),
    status          VARCHAR(20)            NOT NULL DEFAULT 'RUNNING',
    total_rows      INT                    NOT NULL DEFAULT 0,
    success_rows    INT                    NOT NULL DEFAULT 0,
    failed_rows     INT                    NOT NULL DEFAULT 0,
    message         TEXT,
    operator_id     BIGINT,
    create_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_pcl_batch_event ON pj_perf_consume_log(batch_id, event_type);
CREATE UNIQUE INDEX uk_pcl_event_id    ON pj_perf_consume_log(event_id);
CREATE INDEX idx_pcl_period            ON pj_perf_consume_log(period);
-- period 放宽为可空：RUNNING 日志"先插后补 period"（消费完从归一化记录推导归属月），
-- 插入时若事件未携带 period（历史空 period 批次 / MANUAL_BUILD），强行 NOT NULL 会直接炸
COMMENT ON COLUMN pj_perf_consume_log.period IS '归属月 YYYY-MM；RUNNING 阶段可空，消费完成后回填';

COMMENT ON TABLE  pj_perf_consume_log IS '消费日志';
COMMENT ON COLUMN pj_perf_consume_log.batch_id IS '导入批次ID';
COMMENT ON COLUMN pj_perf_consume_log.event_type IS '事件类型 IMPORT_BATCH_ARCHIVED / IMPORT_BATCH_RENORMALIZED';
COMMENT ON COLUMN pj_perf_consume_log.event_id IS '事件ID(幂等锚点)';
COMMENT ON COLUMN pj_perf_consume_log.period IS '归属期间';
COMMENT ON COLUMN pj_perf_consume_log.source_type IS '来源类型(KE_SIGNED等)';
COMMENT ON COLUMN pj_perf_consume_log.status IS '消费状态 RUNNING=进行中 SUCCESS=成功 PARTIAL=部分成功 FAILED=失败';
COMMENT ON COLUMN pj_perf_consume_log.total_rows IS '总记录数';
COMMENT ON COLUMN pj_perf_consume_log.success_rows IS '成功数';
COMMENT ON COLUMN pj_perf_consume_log.failed_rows IS '失败数';
COMMENT ON COLUMN pj_perf_consume_log.message IS '错误信息/备注';
COMMENT ON COLUMN pj_perf_consume_log.operator_id IS '操作人ID';
COMMENT ON COLUMN pj_perf_consume_log.create_time IS '创建时间';
COMMENT ON COLUMN pj_perf_consume_log.update_time IS '更新时间';

-- ---------- 四、期间封账 ----------
CREATE TABLE pj_perf_period_close (
    id              BIGINT                 PRIMARY KEY,
    period          VARCHAR(7)             NOT NULL,
    status          VARCHAR(20)            NOT NULL DEFAULT 'OPEN',
    close_reason    VARCHAR(500),
    ref_batch_id    BIGINT,
    operator_id     BIGINT                 NOT NULL,
    close_time      TIMESTAMP,
    create_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_ppc_period ON pj_perf_period_close(period);

COMMENT ON TABLE  pj_perf_period_close IS '期间封账';
COMMENT ON COLUMN pj_perf_period_close.period IS '期间 YYYY-MM';
COMMENT ON COLUMN pj_perf_period_close.status IS '状态 OPEN=开启 CLOSED=已封账';
COMMENT ON COLUMN pj_perf_period_close.close_reason IS '封账原因';
COMMENT ON COLUMN pj_perf_period_close.ref_batch_id IS '关联薪资核算批次';
COMMENT ON COLUMN pj_perf_period_close.operator_id IS '操作人ID';
COMMENT ON COLUMN pj_perf_period_close.close_time IS '封账时间';
COMMENT ON COLUMN pj_perf_period_close.create_time IS '创建时间';
COMMENT ON COLUMN pj_perf_period_close.update_time IS '更新时间';

COMMIT;
