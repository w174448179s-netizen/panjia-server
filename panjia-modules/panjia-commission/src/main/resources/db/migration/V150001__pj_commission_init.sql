-- ============================================================
-- 结佣域 V1.0 核心表结构最终态（建表+索引一步到位）
-- 合并自 V150001,V150005,V150006,V150007,V150008
--   （V150001 建表；V150005 补合同维度查询索引；V150006 补订单号查询索引；
--     V150007 补审批时间列；V150008 调整单事实模型字段；
--     结佣审批流程语句不在本文件，见 V150003 工作流文件）
-- 说明：
--   1) 主键用 BIGINT（雪花 ID，应用层 ASSIGN_ID 生成），非 BIGSERIAL（CI C8）；
--   2) 金额为【结佣业绩金额】（实收业绩原样透传），非佣金金额，无任何提成/折算字段（CI C6/C7）；
--   3) 时间用 TIMESTAMP 不带时区（★ timestamptz 会炸 PG JDBC，与业绩域 V140002 同一约定）；
--   4) 不建 pj_commission_period_close：封账复用业绩域 pj_perf_period_close，走 Port 只读查询（CI C9）；
--   5) 所有表/列均加 COMMENT。
-- ============================================================

BEGIN;

-- ---------- 一、结佣申请单（合同+月粒度，需求文档 §3） ----------
CREATE TABLE pj_commission_application (
    id                  BIGINT        PRIMARY KEY,
    apply_no            VARCHAR(32)   NOT NULL,
    period              VARCHAR(7)    NOT NULL,
    contract_no         VARCHAR(64)   NOT NULL,
    order_no            VARCHAR(64),
    property_address    VARCHAR(255),
    business_date       TIMESTAMP,
    dept_id             BIGINT,
    item_count          INT           NOT NULL DEFAULT 0,
    total_amount        NUMERIC(18,2) NOT NULL DEFAULT 0,
    expected_amount     NUMERIC(18,2) NOT NULL DEFAULT 0,
    aligned             BOOLEAN       NOT NULL DEFAULT FALSE,
    status              VARCHAR(16)   NOT NULL,
    current_node        VARCHAR(16),
    approved_month      VARCHAR(7),
    process_instance_id VARCHAR(64),
    applicant_id        BIGINT,
    approver_id         BIGINT,
    approve_time        TIMESTAMP,
    lock_time           TIMESTAMP,
    version             INT           NOT NULL DEFAULT 0,
    create_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 同一 (period, contractNo) 只允许一张未完结申请单（REJECTED/CANCELLED 可重新发起）
CREATE UNIQUE INDEX uk_capp_period_contract ON pj_commission_application(period, contract_no)
    WHERE status IN ('DRAFT','SUBMITTED','APPROVED','LOCKED');
CREATE INDEX idx_capp_status ON pj_commission_application(period, status);

-- 合同维度聚合按 (contract_no, period) 取最新申请单（合并自 V150005）
CREATE INDEX IF NOT EXISTS idx_capp_contract_period
    ON pj_commission_application(contract_no, period, id);

-- 批量审批/批量发起按订单号查找（「合同号 OR 订单号」匹配，合并自 V150006）
CREATE INDEX IF NOT EXISTS idx_capp_period_order
    ON pj_commission_application(period, order_no);

COMMENT ON TABLE  pj_commission_application IS '结佣申请单(合同+业绩归属月粒度)';
COMMENT ON COLUMN pj_commission_application.id IS '雪花ID';
COMMENT ON COLUMN pj_commission_application.apply_no IS '申请单号 CAPP+yyyyMMdd+序列';
COMMENT ON COLUMN pj_commission_application.period IS '业绩归属月(结算月 YYYY-MM)';
COMMENT ON COLUMN pj_commission_application.contract_no IS '合同号(申请粒度=合同)';
COMMENT ON COLUMN pj_commission_application.order_no IS '订单号(快照)';
COMMENT ON COLUMN pj_commission_application.property_address IS '物业地址(快照)';
COMMENT ON COLUMN pj_commission_application.business_date IS '签约/业务时间(快照，取合同内最大)';
COMMENT ON COLUMN pj_commission_application.dept_id IS '门店ID(跨门店合作单为空)';
COMMENT ON COLUMN pj_commission_application.item_count IS '结佣明细条数';
COMMENT ON COLUMN pj_commission_application.total_amount IS '结佣业绩金额合计(实收业绩，非佣金金额；差异对齐后=应收合计)';
COMMENT ON COLUMN pj_commission_application.expected_amount IS '应收业绩合计(审批时与实收判定差异，§3.4/§3.5)';
COMMENT ON COLUMN pj_commission_application.aligned IS '是否已发生实收对齐应收(§3.5 自动对齐后置true)';
COMMENT ON COLUMN pj_commission_application.status IS '状态 DRAFT=草稿 SUBMITTED=审批中 APPROVED=已通过 LOCKED=已锁定(终态) REJECTED=已驳回 CANCELLED=已作废';
COMMENT ON COLUMN pj_commission_application.current_node IS '当前审批节点 DIRECTOR=总监审批 FINANCE=财务审批';
COMMENT ON COLUMN pj_commission_application.approved_month IS '审批通过月=工资归属月(YYYY-MM)';
COMMENT ON COLUMN pj_commission_application.process_instance_id IS '审批流程实例ID(Warm-Flow commission_apply)';
COMMENT ON COLUMN pj_commission_application.applicant_id IS '发起人ID';
COMMENT ON COLUMN pj_commission_application.approver_id IS '审批人ID';
COMMENT ON COLUMN pj_commission_application.approve_time IS '审批时间（最近节点办理留痕：总监通过/终审锁定/驳回）';
COMMENT ON COLUMN pj_commission_application.lock_time IS '锁定时间';
COMMENT ON COLUMN pj_commission_application.version IS '乐观锁版本号';
COMMENT ON COLUMN pj_commission_application.create_time IS '创建时间';
COMMENT ON COLUMN pj_commission_application.update_time IS '更新时间';

-- ---------- 二、结佣明细 ----------
CREATE TABLE pj_commission_item (
    id                  BIGINT        PRIMARY KEY,
    application_id      BIGINT        NOT NULL,
    performance_fact_id BIGINT,
    period              VARCHAR(7)    NOT NULL,
    contract_no         VARCHAR(64),
    approved_month      VARCHAR(7),
    employee_id         BIGINT        NOT NULL,
    dept_id             BIGINT,
    biz_type            VARCHAR(32),
    role_type           VARCHAR(32),
    fee_item            VARCHAR(32),
    amount              NUMERIC(18,2) NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    origin_reversed     BOOLEAN       NOT NULL DEFAULT FALSE,
    adjust_id           BIGINT,
    reversed_reason     VARCHAR(32),
    version             INT           NOT NULL DEFAULT 0,
    create_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ★ 必须排除 REVERSED（CI C10）：DISCOUNT 调整会「旧行 REVERSED + 新行沿用同一 performance_fact_id」，
--   若不排除 REVERSED，新行插入必然撞键；DIFF 差额行 performance_fact_id 为 NULL，天然不参与
CREATE UNIQUE INDEX uk_citem_fact_active ON pj_commission_item(performance_fact_id)
    WHERE performance_fact_id IS NOT NULL AND status <> 'REVERSED';
CREATE INDEX idx_citem_app  ON pj_commission_item(application_id, status);
CREATE INDEX idx_citem_emp  ON pj_commission_item(period, employee_id, status);
CREATE INDEX idx_citem_dept ON pj_commission_item(period, dept_id, status);

COMMENT ON TABLE  pj_commission_item IS '结佣明细(一行=一条 PERF_REAL 业绩事实的结佣确认)';
COMMENT ON COLUMN pj_commission_item.id IS '雪花ID';
COMMENT ON COLUMN pj_commission_item.application_id IS '所属申请单ID';
COMMENT ON COLUMN pj_commission_item.performance_fact_id IS '关联业绩事实ID(pj_perf_fact.id，只存ID不建FK；DIFF差额行为NULL)';
COMMENT ON COLUMN pj_commission_item.period IS '业绩归属月(结算月 YYYY-MM)；DIFF差额行为补发目标月';
COMMENT ON COLUMN pj_commission_item.contract_no IS '合同号(冻结快照)';
COMMENT ON COLUMN pj_commission_item.approved_month IS '工资归属月(审批通过月 YYYY-MM)';
COMMENT ON COLUMN pj_commission_item.employee_id IS '员工ID(冻结快照)';
COMMENT ON COLUMN pj_commission_item.dept_id IS '归属门店ID(冻结快照，跨门店合作明细可为空)';
COMMENT ON COLUMN pj_commission_item.biz_type IS '业务类型(冻结快照，六类)';
COMMENT ON COLUMN pj_commission_item.role_type IS '角色类型(冻结快照)';
COMMENT ON COLUMN pj_commission_item.fee_item IS '费用项(冻结快照)';
COMMENT ON COLUMN pj_commission_item.amount IS '结佣业绩金额(实收口径；§3.5对齐后按应收金额)';
COMMENT ON COLUMN pj_commission_item.status IS '状态 DRAFT=待提交 PENDING=待审批 APPROVED=已审批(金额冻结) REVERSED=已冲销(终态，永久保留)';
COMMENT ON COLUMN pj_commission_item.origin_reversed IS '源业绩事实已被冲销(已审批明细置TRUE+告警，金额不动，V4.2§9.2-4)';
COMMENT ON COLUMN pj_commission_item.adjust_id IS '来源结佣调整单ID';
COMMENT ON COLUMN pj_commission_item.reversed_reason IS '冲销原因 SUPERSEDE=批次替换 RENORMALIZE=重归一化 MANUAL_ADJUST=人工调整 PERIOD_VOID=期间作废';
COMMENT ON COLUMN pj_commission_item.version IS '乐观锁版本号';
COMMENT ON COLUMN pj_commission_item.create_time IS '创建时间';
COMMENT ON COLUMN pj_commission_item.update_time IS '更新时间';

-- ---------- 三、结佣调整单（含 V150008 事实模型字段最终态） ----------
-- V150008 说明：对齐新签调整（PerformanceAdjust），直接调整业绩事实（PERF_REAL + PERF_EXPECT）；
--   复用 new_amount 列存调整后金额，diff_amount 列存调整差额（展示用）
CREATE TABLE pj_commission_adjust (
    id                  BIGINT        PRIMARY KEY,
    adjust_no           VARCHAR(32)   NOT NULL,
    application_id      BIGINT        NOT NULL,
    item_id             BIGINT,
    period              VARCHAR(7)    NOT NULL,
    adjust_type         VARCHAR(16)   NOT NULL,
    new_amount          NUMERIC(18,2),
    diff_amount         NUMERIC(18,2),
    target_period       VARCHAR(7),
    payload_json        TEXT,
    reason              VARCHAR(512)  NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    process_instance_id VARCHAR(64),
    applicant_id        BIGINT,
    approver_id         BIGINT,
    original_amount     NUMERIC(18,2),
    contract_no         VARCHAR(64),
    fact_type           VARCHAR(32),
    adjust_scope        VARCHAR(16),
    fact_id             BIGINT,
    target_dept_id      BIGINT,
    create_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_cadj_no  ON pj_commission_adjust(adjust_no);
CREATE INDEX idx_cadj_app  ON pj_commission_adjust(application_id, status);
CREATE INDEX idx_cadj_item ON pj_commission_adjust(item_id, status);

COMMENT ON TABLE  pj_commission_adjust IS '结佣调整单(已审批结佣数据变更的唯一入口，V4.2§9.4)';
COMMENT ON COLUMN pj_commission_adjust.id IS '雪花ID';
COMMENT ON COLUMN pj_commission_adjust.adjust_no IS '调整单号 CADJ+yyyyMMdd+序列';
COMMENT ON COLUMN pj_commission_adjust.application_id IS '调整对象申请单ID';
COMMENT ON COLUMN pj_commission_adjust.item_id IS '调整对象结佣明细ID(整单级调整时为NULL，预留)';
COMMENT ON COLUMN pj_commission_adjust.period IS '调整明细所属业绩归属月(YYYY-MM)';
COMMENT ON COLUMN pj_commission_adjust.adjust_type IS '调整类型 DISCOUNT=折扣 DIFF=差额补发 VOID=作废';
COMMENT ON COLUMN pj_commission_adjust.new_amount IS '折后最终金额(DISCOUNT用，直接存折后值，非系数)';
COMMENT ON COLUMN pj_commission_adjust.diff_amount IS '差额金额(DIFF用，正补负扣)';
COMMENT ON COLUMN pj_commission_adjust.target_period IS '补发目标月(DIFF用 YYYY-MM；封账校验按此月判定，§2.5)';
COMMENT ON COLUMN pj_commission_adjust.payload_json IS '变更前后值快照JSON';
COMMENT ON COLUMN pj_commission_adjust.reason IS '调整原因(必填，审计；折扣种类如85折写此处)';
COMMENT ON COLUMN pj_commission_adjust.status IS '状态 SUBMITTED=已提交 APPROVED=已通过 REJECTED=已驳回 CANCELLED=已取消 EXECUTED=已执行(终态)';
COMMENT ON COLUMN pj_commission_adjust.process_instance_id IS '审批流程实例ID(预留 Warm-Flow)';
COMMENT ON COLUMN pj_commission_adjust.applicant_id IS '发起人ID';
COMMENT ON COLUMN pj_commission_adjust.approver_id IS '审批人ID';
COMMENT ON COLUMN pj_commission_adjust.original_amount IS '调整前金额（PERF_REAL 合计或单条明细金额）';
COMMENT ON COLUMN pj_commission_adjust.contract_no IS '调整对象合同号';
COMMENT ON COLUMN pj_commission_adjust.fact_type IS '事实口径（PERF_REAL，结佣调整固定为实收业绩）';
COMMENT ON COLUMN pj_commission_adjust.adjust_scope IS '调整范围（CONTRACT 合同级 / DETAIL 明细级）';
COMMENT ON COLUMN pj_commission_adjust.fact_id IS '明细级调整对应的业绩事实 ID（合同级为 NULL）';
COMMENT ON COLUMN pj_commission_adjust.target_dept_id IS '部门划转目标部门 ID（TRANSFER 用）';
COMMENT ON COLUMN pj_commission_adjust.create_time IS '创建时间';
COMMENT ON COLUMN pj_commission_adjust.update_time IS '更新时间';

-- ---------- 四、结佣消费日志 ----------
CREATE TABLE pj_commission_consume_log (
    id              BIGINT       PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(32)  NOT NULL,
    period          VARCHAR(7),
    fact_ids        TEXT,
    fact_count      INT          NOT NULL DEFAULT 0,
    affected_items  INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL,
    message         VARCHAR(1000),
    create_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_ccl_event ON pj_commission_consume_log(event_id);

COMMENT ON TABLE  pj_commission_consume_log IS '结佣消费日志(业绩域事件幂等锚点+冲销联动留痕)';
COMMENT ON COLUMN pj_commission_consume_log.id IS '雪花ID';
COMMENT ON COLUMN pj_commission_consume_log.event_id IS '事件ID(幂等锚点，唯一)';
COMMENT ON COLUMN pj_commission_consume_log.event_type IS '事件类型 FACT_CREATED=事实创建 FACT_REVERSED=事实冲销 INCREMENTAL_REFRESH=增量重拉';
COMMENT ON COLUMN pj_commission_consume_log.period IS '归属期间 YYYY-MM';
COMMENT ON COLUMN pj_commission_consume_log.fact_ids IS '事件携带的事实ID列表(逗号分隔，留痕)';
COMMENT ON COLUMN pj_commission_consume_log.fact_count IS '事件携带的事实数';
COMMENT ON COLUMN pj_commission_consume_log.affected_items IS '受影响的结佣明细数';
COMMENT ON COLUMN pj_commission_consume_log.status IS '消费状态 SUCCESS=成功 PARTIAL=部分成功 FAILED=失败';
COMMENT ON COLUMN pj_commission_consume_log.message IS '备注/失败原因';
COMMENT ON COLUMN pj_commission_consume_log.create_time IS '创建时间';
COMMENT ON COLUMN pj_commission_consume_log.update_time IS '更新时间';

COMMIT;
