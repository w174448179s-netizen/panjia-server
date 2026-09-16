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
    performance_amount      NUMERIC(18,2)          NOT NULL DEFAULT 0,
    effective_date          DATE                   NOT NULL,
    expire_date             DATE,
    fact_status             VARCHAR(20)            NOT NULL DEFAULT 'ACTIVE',
    source                  VARCHAR(20)            NOT NULL DEFAULT 'IMPORT',
    adjust_id               BIGINT,
    reversed_reason         VARCHAR(30),
    reversal_type           VARCHAR(20),
    refund_of_fact_id       BIGINT,
    received_apply_id       BIGINT,
    operator_id             BIGINT,
    version                 INT                    NOT NULL DEFAULT 0,
    create_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 幂等锚点：同一 fact_type + source_key 只允许一条 ACTIVE 记录
CREATE UNIQUE INDEX uk_perf_fact_source_key
    ON pj_perf_fact(fact_type, source_key, fact_status)
    WHERE fact_status = 'ACTIVE';

-- 实收审批单关联索引（结佣发起时按 received_apply_id 反查/校验审批通过）
CREATE INDEX idx_pfact_received_apply ON pj_perf_fact(received_apply_id);

CREATE INDEX idx_pfact_emp     ON pj_perf_fact(employee_id, period, fact_type);
CREATE INDEX idx_pfact_dept    ON pj_perf_fact(dept_id, period, fact_type);
CREATE INDEX idx_pfact_batch   ON pj_perf_fact(batch_id);
CREATE INDEX idx_pfact_period  ON pj_perf_fact(period, fact_type);
CREATE INDEX idx_pfact_refund  ON pj_perf_fact(refund_of_fact_id);

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
COMMENT ON COLUMN pj_perf_fact.performance_amount IS '业绩金额(=原始金额×分摊比例×折算系数)';
COMMENT ON COLUMN pj_perf_fact.effective_date IS '生效起始日(闭区间)';
COMMENT ON COLUMN pj_perf_fact.expire_date IS '生效截止日(开区间，9999-12-31表示有效)';
COMMENT ON COLUMN pj_perf_fact.fact_status IS '事实状态 ACTIVE=有效 REVERSED=已冲销';
COMMENT ON COLUMN pj_perf_fact.source IS '来源 IMPORT=导入 MANUAL=手工';
COMMENT ON COLUMN pj_perf_fact.adjust_id IS '关联调整单ID';
COMMENT ON COLUMN pj_perf_fact.reversed_reason IS '冲销原因 SUPERSEDE=替换 RENORMALIZE=重归一化 MANUAL_ADJUST=手工调整 PERIOD_VOID=期间作废';
COMMENT ON COLUMN pj_perf_fact.reversal_type IS '红冲类型 NULL=正常事实 REDINK_REFUND=退单红冲负事实(按原事实冻结口径镜像，归属退单月)';
COMMENT ON COLUMN pj_perf_fact.refund_of_fact_id IS '红冲镜像的原正数事实ID(溯源链：退单负事实→成交月原事实)';
COMMENT ON COLUMN pj_perf_fact.received_apply_id IS '实收业绩审批单ID(PERF_REAL 事实归属的实收审批单；审批通过后该事实方可用于发起结佣)';
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
    adjust_scope        VARCHAR(20)            NOT NULL DEFAULT 'DETAIL',
    contract_no         VARCHAR(100),
    fact_type           VARCHAR(20),
    original_period     VARCHAR(7),
    payload_json        TEXT,
    target_amount        NUMERIC(18,2)          DEFAULT 0,
    original_amount        NUMERIC(18,2)          DEFAULT 0,
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
COMMENT ON COLUMN pj_perf_adjust.adjust_scope IS '调整范围 CONTRACT=合同级 DETAIL=明细级';
COMMENT ON COLUMN pj_perf_adjust.contract_no IS '合同号(合同级调整时填，用于定位该合同下全部明细)';
COMMENT ON COLUMN pj_perf_adjust.fact_type IS '事实口径(§4.1 业绩调整只允许 PERF_EXPECT 应收)';
COMMENT ON COLUMN pj_perf_adjust.original_period IS '原业绩归属月(合同级跨月调整定位原月事实；空=同月调整，§4.6)';
COMMENT ON COLUMN pj_perf_adjust.payload_json IS '调整详情JSON(不同类型结构不同)';
COMMENT ON COLUMN pj_perf_adjust.target_amount IS '变动后金额';
COMMENT ON COLUMN pj_perf_adjust.original_amount IS '调整前原始金额（创建时快照）';
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

-- ---------- 五、实收业绩审批单（合同维度） ----------
-- 业务流程（需求文档 §2）：
--   贝壳导入后若有实收，按「合同+结算月」自动生成并自动提交，流转 财务 → 总监；
--   店长/店助发起同此链路；财务发起直达总监；总监发起直接落点（审批通过）。
--   审批通过后，单内 PERF_REAL 事实方可用于发起结佣。
CREATE TABLE pj_perf_received_apply (
    id                      BIGINT                 PRIMARY KEY,
    apply_no                VARCHAR(32)            NOT NULL,
    period                  VARCHAR(7)             NOT NULL,
    contract_no             VARCHAR(64)            NOT NULL,
    order_no                VARCHAR(64),
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
CREATE INDEX idx_rapp_status ON pj_perf_received_apply(period, status);
CREATE INDEX idx_rapp_batch  ON pj_perf_received_apply(batch_id);

COMMENT ON TABLE  pj_perf_received_apply IS '实收业绩审批单(合同+结算月粒度)';
COMMENT ON COLUMN pj_perf_received_apply.apply_no IS '审批单号 RCV+yyyyMMddHHmmssSSS';
COMMENT ON COLUMN pj_perf_received_apply.period IS '结算月 YYYY-MM';
COMMENT ON COLUMN pj_perf_received_apply.contract_no IS '合同号';
COMMENT ON COLUMN pj_perf_received_apply.order_no IS '订单号(快照)';
COMMENT ON COLUMN pj_perf_received_apply.property_address IS '物业地址(快照)';
COMMENT ON COLUMN pj_perf_received_apply.business_date IS '签约/业务时间(快照，取合同内最大)';
COMMENT ON COLUMN pj_perf_received_apply.dept_id IS '归属门店ID(跨门店合作单为空)';
COMMENT ON COLUMN pj_perf_received_apply.batch_id IS '首次生成该单的导入批次ID';
COMMENT ON COLUMN pj_perf_received_apply.received_amount IS '实收业绩合计(合同当月全部PERF_REAL事实净额)';
COMMENT ON COLUMN pj_perf_received_apply.expected_amount IS '应收业绩合计(合同当月全部PERF_EXPECT事实净额，展示实收/应收差异用)';
COMMENT ON COLUMN pj_perf_received_apply.item_count IS '实收新签明细条数';
COMMENT ON COLUMN pj_perf_received_apply.status IS '状态 DRAFT=草稿 SUBMITTED=审批中 APPROVED=已通过 REJECTED=已驳回 CANCELLED=已作废';
COMMENT ON COLUMN pj_perf_received_apply.current_node IS '当前审批节点 FINANCE=财务审批 DIRECTOR=总监审批';
COMMENT ON COLUMN pj_perf_received_apply.process_instance_id IS 'Warm-Flow 流程实例ID';
COMMENT ON COLUMN pj_perf_received_apply.applicant_id IS '发起人ID(自动发起时为空/系统)';
COMMENT ON COLUMN pj_perf_received_apply.approver_id IS '终审人ID';
COMMENT ON COLUMN pj_perf_received_apply.approve_time IS '终审通过时间';

-- ---------- 六、审批流程参数（需求文档 §5） ----------
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000001, '是否跳过财务审批节点', 'panjia.flow.skip_finance', 'false', 'Y', 1761000000000000100, 1761100000000000001, now(),
        'true=实收/结佣审批均跳过财务节点；false=按标准链路流转')
ON CONFLICT (config_id) DO NOTHING;
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000002, '总监审批自动通过超时(小时)', 'panjia.flow.director_timeout_hours', '0', 'Y', 1761000000000000100, 1761100000000000001, now(),
        '总监节点任务超过该小时数未办理则系统自动审批通过；0=关闭')
ON CONFLICT (config_id) DO NOTHING;

-- ============================================================
-- 新签调整审批流程（perf_adjust）
-- 链路：开始 → 申请人(${initiator}) → 总监审批(role:1761300000000000010) → 结束
-- 审批通过后由 AdjustWorkflowListener 自动执行业绩调整
-- ============================================================
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000801, 'perf_adjust', '新签调整审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/workflow/processDefinition/index', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000810, 0, 1762400000000000801, 'perf_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000811, 1, 1762400000000000801, 'perf_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000812, 1, 1762400000000000801, 'perf_director', '总监审批', 'role:1761300000000000010', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000813, 2, 1762400000000000801, 'perf_end', '结束', NULL, '0.000', '720,200|720,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000820, 1762400000000000801, 'perf_start', 0, 'perf_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000821, 1762400000000000801, 'perf_applicant', 1, 'perf_director', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000823, 1762400000000000801, 'perf_director', 1, 'perf_end', 2, NULL, 'PASS', NULL, '590,200;700,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000824, 1762400000000000801, 'perf_director', 1, 'perf_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

-- ============================================================
-- 实收业绩审批流程（perf_received）
-- 链路（需求文档 §2.1/§2.2）：
--   开始 → 申请人(${initiator}) → 财务审批(role:finance) → 总监审批(role:director) → 结束
--   导入自动提交 / 店长发起：走完整链路（自动提交时申请人节点由系统完成）
--   财务发起：后端自动完成财务节点，直达总监
--   总监发起：后端连续完成财务+总监节点，直接落点
--   panjia.flow.skip_finance=true 时财务节点由系统自动完成
-- 审批结果由 ReceivedWorkflowListener 回调：finish→APPROVED，back→REJECTED
-- ============================================================
INSERT INTO flow_definition (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, form_path, activity_status, listener_type, listener_path, ext, create_time, create_by, update_time, update_by, del_flag, tenant_id)
VALUES (1762400000000000901, 'perf_received', '实收业绩审批', 'CLASSICS', '1762300000000000200', '1', 1, 'N', '/workflow/processDefinition/index', 1, NULL, NULL, NULL, now(), '1761100000000000001', NULL, NULL, '0', '000000');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000910, 0, 1762400000000000901, 'rcv_start', '开始', NULL, '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000911, 1, 1762400000000000901, 'rcv_applicant', '申请人', '${initiator}', '0.000', '360,200|360,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000912, 1, 1762400000000000901, 'rcv_finance', '财务审批', 'role:1761300000000000012', '0.000', '540,200|540,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000913, 1, 1762400000000000901, 'rcv_director', '总监审批', 'role:1761300000000000010', '0.000', '720,200|720,200', NULL, '', '', 'N', NULL, '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, form_path, "version", ext, del_flag, tenant_id, create_time, create_by)
VALUES (1762400000000000914, 2, 1762400000000000901, 'rcv_end', '结束', NULL, '0.000', '900,200|900,200', NULL, NULL, NULL, 'N', NULL, '1', '[]', '0', '000000', now(), '1761100000000000001');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000920, 1762400000000000901, 'rcv_start', 0, 'rcv_applicant', 1, NULL, 'PASS', NULL, '220,200;310,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000921, 1762400000000000901, 'rcv_applicant', 1, 'rcv_finance', 1, NULL, 'PASS', NULL, '410,200;490,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000922, 1762400000000000901, 'rcv_finance', 1, 'rcv_director', 1, NULL, 'PASS', NULL, '590,200;670,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000923, 1762400000000000901, 'rcv_director', 1, 'rcv_end', 2, NULL, 'PASS', NULL, '770,200;880,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000924, 1762400000000000901, 'rcv_finance', 1, 'rcv_applicant', 1, '驳回', 'REJECT', NULL, '540,200;360,200', now(), '1761100000000000001', '0', '000000');

INSERT INTO flow_skip (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, create_time, create_by, del_flag, tenant_id)
VALUES (1762400000000000925, 1762400000000000901, 'rcv_director', 1, 'rcv_applicant', 1, '驳回', 'REJECT', NULL, '720,200;360,200', now(), '1761100000000000001', '0', '000000');

COMMIT;
