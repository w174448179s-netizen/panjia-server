-- ============================================================================
-- V170001 盘家智管 · 实收域 建表（从 pj_perf_fact PERF_REAL 物理拆分）
--
-- 背景：pj_perf_fact 原双口径（PERF_EXPECT/PERF_REAL）设计中，PERF_REAL 事实
--       与 PERF_EXPECT 事实共用同一张表，导致结佣域查 PERF_REAL 时必须带
--       fact_type 过滤，且 ImportEngine 写入时必须分发双口径。本次拆表：
--         1. pj_perf_fact 只存 PERF_EXPECT（新签/应收业绩）
--         2. 实收数据独立到实收域两张表：pj_received_contract（合同级主表）+
--            pj_received_detail（角色人明细）
--         3. ImportBatchArchivedHandler 的 KE_RECEIVED/HISTORY_PAYROLL 分支
--            不再调 PerformanceEngine 建 PERF_REAL，改直接写实收表
--         4. CommissionQueryAdapter.findRealFacts 改从实收表查合同，再从
--            pj_perf_fact(PERF_EXPECT) 查明细
--         5. 结佣域 / 实收审批单的 PERF_REAL 依赖全部切到实收域新端口
--
-- 本迁移仅建表 + 索引 + 注释，**不迁移历史数据**（清库重建场景，无存量）。
-- ============================================================================

-- ============================================================
-- 一、实收合同主表（一个合同 period 一条，聚合级）
-- ============================================================
CREATE TABLE pj_received_contract (
    id                      BIGINT                 PRIMARY KEY,
    order_no                VARCHAR(64)            NOT NULL,
    contract_no             VARCHAR(100),
    biz_type                VARCHAR(50),
    period                  VARCHAR(7)             NOT NULL,
    business_date           TIMESTAMP              NOT NULL,
    batch_id                BIGINT,
    source_type             VARCHAR(30)            NOT NULL,
    dept_id                 BIGINT,
    property_address        VARCHAR(255),
    contract_amount         NUMERIC(18,2)          NOT NULL DEFAULT 0,
    period_total_received   NUMERIC(18,2)          NOT NULL DEFAULT 0,
    item_count              INT                    NOT NULL DEFAULT 0,
    received_apply_id       BIGINT,
    create_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 幂等锚点：同一 order_no + period + source_type 只允许一条记录
CREATE UNIQUE INDEX uk_received_contract_anchor
    ON pj_received_contract(order_no, period, source_type);

CREATE INDEX idx_rc_dept_period ON pj_received_contract(dept_id, period);
CREATE INDEX idx_rc_batch      ON pj_received_contract(batch_id);
CREATE INDEX idx_rc_apply      ON pj_received_contract(received_apply_id);

COMMENT ON TABLE  pj_received_contract IS '实收合同主表（从 pj_perf_fact PERF_REAL 拆分，一合同 period 一条）';
COMMENT ON COLUMN pj_received_contract.order_no IS '订单号（业务锚点，全部业务类型统一订单号优先）';
COMMENT ON COLUMN pj_received_contract.contract_no IS '合同号（可能为空，UI 展示用）';
COMMENT ON COLUMN pj_received_contract.period IS '归属期间 YYYY-MM';
COMMENT ON COLUMN pj_received_contract.source_type IS '来源类型：KE_RECEIVED=贝壳实收 / HISTORY_PAYROLL=历史工资';
COMMENT ON COLUMN pj_received_contract.period_total_received IS '本期实收合计（明细行 performance_amount 汇总）';
COMMENT ON COLUMN pj_received_contract.contract_amount IS '合同总金额（贝壳导入"合同金额"列）';
COMMENT ON COLUMN pj_received_contract.received_apply_id IS '关联实收审批单 ID（审批通过后回填）';

-- ============================================================
-- 二、实收明细表（角色人级，一个合同 period 下 N 行）
-- ============================================================
CREATE TABLE pj_received_detail (
    id                      BIGINT                 PRIMARY KEY,
    contract_id             BIGINT                 NOT NULL,
    employee_id             BIGINT,
    employee_external_code  VARCHAR(50),
    role_type               VARCHAR(50),
    role_name               VARCHAR(64),
    share_ratio             NUMERIC(10,6)          NOT NULL DEFAULT 1.000000,
    performance_amount      NUMERIC(18,2)          NOT NULL DEFAULT 0,
    fee_item                VARCHAR(100),
    period                  VARCHAR(7)             NOT NULL,
    effective_date          DATE                   NOT NULL,
    expire_date             DATE,
    source_key              VARCHAR(200)           NOT NULL,
    source_batch_id         BIGINT,
    normalized_record_id    BIGINT,
    received_apply_id       BIGINT,
    detail_status           VARCHAR(20)            NOT NULL DEFAULT 'ACTIVE',
    operator_id             BIGINT,
    create_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 幂等锚点：同一 source_key 只允许一条 ACTIVE 明细
CREATE UNIQUE INDEX uk_received_detail_anchor
    ON pj_received_detail(source_key)
    WHERE detail_status = 'ACTIVE';

CREATE INDEX idx_rd_contract ON pj_received_detail(contract_id);
CREATE INDEX idx_rd_emp_per   ON pj_received_detail(employee_id, period);
CREATE INDEX idx_rd_batch     ON pj_received_detail(source_batch_id);
CREATE INDEX idx_rd_apply     ON pj_received_detail(received_apply_id);

COMMENT ON TABLE  pj_received_detail IS '实收明细表（角色人级，对应理房通到账明细一行一人）';
COMMENT ON COLUMN pj_received_detail.contract_id IS '外键 → pj_received_contract.id';
COMMENT ON COLUMN pj_received_detail.employee_id IS '角色人员工 ID（空经纪人场景可为 NULL，仅展示不参与计算）';
COMMENT ON COLUMN pj_received_detail.performance_amount IS '实收金额（角色人到账金额，可为负）';
COMMENT ON COLUMN pj_received_detail.source_key IS '幂等锚点：order_no + employee_external_code + period + effective_date';
COMMENT ON COLUMN pj_received_detail.detail_status IS '明细状态：ACTIVE=有效 / REVERSED=已红冲（对应调整）';

-- ============================================================
-- 三、实收业绩审批单（合同维度，从 V140002 迁入实收域）
-- ============================================================
-- 业务流程（需求文档 §2）：
--   贝壳导入后若有实收，按「合同+结算月」自动生成并自动提交，流转 财务 → 总监；
--   店长/店助发起同此链路；财务发起直达总监；总监发起直接落点（审批通过）。
--   审批通过后，单内实收数据方可用于发起结佣。
CREATE TABLE pj_perf_received_apply (
    id                      BIGINT                 PRIMARY KEY,
    apply_no                VARCHAR(32)            NOT NULL,
    period                  VARCHAR(7)             NOT NULL,
    contract_no             VARCHAR(64)            NOT NULL,
    order_no                VARCHAR(64),
    biz_type                VARCHAR(32),
    property_address        VARCHAR(255),
    business_date           TIMESTAMP,
    dept_id                 BIGINT,
    batch_id                BIGINT,
    received_amount         NUMERIC(18,2)          NOT NULL DEFAULT 0,
    expected_amount         NUMERIC(18,2)          NOT NULL DEFAULT 0,
    item_count              INT                    NOT NULL DEFAULT 0,
    status                  VARCHAR(16)            NOT NULL,
    current_node            VARCHAR(16),
    process_instance_id     VARCHAR(64),
    applicant_id            BIGINT,
    approver_id             BIGINT,
    approve_time            TIMESTAMP,
    version                 INT                    NOT NULL DEFAULT 0,
    create_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 同一 (period, contract_no) 只允许一张未完结实收审批单（CANCELLED 后可重新发起）
CREATE UNIQUE INDEX uk_rapp_period_contract
    ON pj_perf_received_apply(period, contract_no)
    WHERE status IN ('DRAFT','SUBMITTED','APPROVED');
-- 同一 (period, order_no) 只允许一张未完结实收审批单（纯订单号聚合幂等，
-- 覆盖 contract_no 为 NULL 的一手房/家装荐客等订单，PG 视 NULL 为互异）
CREATE UNIQUE INDEX uk_rapp_period_order
    ON pj_perf_received_apply(period, order_no)
    WHERE status IN ('DRAFT','SUBMITTED','APPROVED');
CREATE INDEX idx_rapp_status ON pj_perf_received_apply(period, status);
CREATE INDEX idx_rapp_batch  ON pj_perf_received_apply(batch_id);
-- 合同维度聚合按 (contract_no, period) 取最新单据
CREATE INDEX idx_rapp_contract_period ON pj_perf_received_apply(contract_no, period, id);
-- 批量审批「合同号 OR 订单号」匹配：order_no 分支索引（PG BitmapOr 合并）
CREATE INDEX idx_rapp_period_order    ON pj_perf_received_apply(period, order_no);

COMMENT ON TABLE  pj_perf_received_apply IS '实收业绩审批单(合同+结算月粒度)';
COMMENT ON COLUMN pj_perf_received_apply.apply_no IS '审批单号 RCV+yyyyMMddHHmmssSSS';
COMMENT ON COLUMN pj_perf_received_apply.period IS '结算月 YYYY-MM';
COMMENT ON COLUMN pj_perf_received_apply.contract_no IS '合同号';
COMMENT ON COLUMN pj_perf_received_apply.order_no IS '订单号(快照)';
COMMENT ON COLUMN pj_perf_received_apply.biz_type IS '业务类型(一手房/二手买卖/租赁/租赁轻托管/写字楼租赁/轻托管推房等，建单时从实收数据快照)';
COMMENT ON COLUMN pj_perf_received_apply.property_address IS '物业地址(快照)';
COMMENT ON COLUMN pj_perf_received_apply.business_date IS '签约/业务时间(快照，取合同内最大)';
COMMENT ON COLUMN pj_perf_received_apply.dept_id IS '归属门店ID(跨门店合作单为空)';
COMMENT ON COLUMN pj_perf_received_apply.batch_id IS '首次生成该单的导入批次ID';
COMMENT ON COLUMN pj_perf_received_apply.received_amount IS '实收业绩合计(合同当月实收明细净额)';
COMMENT ON COLUMN pj_perf_received_apply.expected_amount IS '应收业绩合计(合同当月全部PERF_EXPECT事实净额，展示实收/应收差异用)';
COMMENT ON COLUMN pj_perf_received_apply.item_count IS '实收新签明细条数';
COMMENT ON COLUMN pj_perf_received_apply.status IS '状态 DRAFT=草稿 SUBMITTED=审批中 APPROVED=已通过 REJECTED=已驳回 CANCELLED=已作废';
COMMENT ON COLUMN pj_perf_received_apply.current_node IS '当前审批节点 FINANCE=财务审批 DIRECTOR=总监审批';
COMMENT ON COLUMN pj_perf_received_apply.process_instance_id IS 'Warm-Flow 流程实例ID';
COMMENT ON COLUMN pj_perf_received_apply.applicant_id IS '发起人ID(自动发起时为空/系统)';
COMMENT ON COLUMN pj_perf_received_apply.approver_id IS '终审人ID';
COMMENT ON COLUMN pj_perf_received_apply.approve_time IS '终审通过时间';

-- ============================================================
-- 四、pj_perf_fact 清理 PERF_REAL 残留（软删除，避免 FK 冲突）
-- ============================================================
-- 注意：清库重建场景 pj_perf_fact 是空的，此 DELETE 无实际影响，
--       但语句必须保留以兼容未来增量迁移（如有历史 PERF_REAL 数据
--       需要清理）。用软删除（fact_status='REVERSED'）替代物理删除，
--       避免其他表 FK 约束冲突。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pj_perf_fact WHERE fact_type = 'PERF_REAL') THEN
        UPDATE pj_perf_fact SET fact_status = 'REVERSED',
                                reversed_reason = 'REAL_SPLIT_TO_RECEIVED_DOMAIN'
        WHERE fact_type = 'PERF_REAL';
    END IF;
END $$;
