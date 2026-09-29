-- ============================================================
-- 盘家智管 · 数据导入域建表 DDL 最终态（清库重建版）
-- 模板表 + 导入批次 + 原始归档分表 + 问题清单 + 归一化记录 + 历史工资 raw 分表
-- 合并自 V120002, V120004, V120007, V120011, V120012, V120019, V120022, V120023
-- 说明：所有 ALTER ADD COLUMN 已折叠进最终 CREATE TABLE；
--       V120007 删除的贝壳新签 raw 分表不再创建；
--       表/列注释取链上最后一次 COMMENT 的最终文本。
-- ============================================================

-- ---------- 〇、导入模板配置表（V120002） ----------
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
    column_mapping      JSONB        NOT NULL,                  -- 列映射数组
    validation_rules    JSONB,                                  -- 校验规则
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

-- ---------- 一、导入批次（聚合根，V120004） ----------
CREATE TABLE pj_import_batch (
    id                      BIGINT       PRIMARY KEY,                  -- 雪花 ID
    batch_no                VARCHAR(64)  NOT NULL,                     -- 批次号 IMP+yyyyMMdd+序列
    source_type             VARCHAR(20)  NOT NULL,                     -- KE_SIGNED/KE_RECEIVED/ATTENDANCE/POINTS/OTHERS/HISTORY_PAYROLL
    template_version        VARCHAR(20)  NOT NULL,                     -- 创建/解析时快照冻结
    file_name               VARCHAR(255),                              -- 存储文件名
    original_file_name      VARCHAR(255),                              -- 上传原名
    storage_path            VARCHAR(500),                              -- 文件存储路径
    period                  VARCHAR(7),                                -- 归属月 YYYY-MM
    total_rows              INT          NOT NULL DEFAULT 0,
    success_rows            INT          NOT NULL DEFAULT 0,
    failed_rows             INT          NOT NULL DEFAULT 0,
    status                  SMALLINT     NOT NULL DEFAULT 0,           -- 0 PARSING 1 NORMALIZING 2 PENDING_CONFIRM 3 ARCHIVED 4 FAILED
    archive_status          SMALLINT,                                  -- 归档状态
    operator_id             BIGINT,                                    -- 操作人 sys_user.id
    dept_id                 BIGINT,                                    -- 归属部门
    remark                  VARCHAR(500),
    version                 INT          NOT NULL DEFAULT 0,           -- 乐观锁
    superseded_by_batch_id  BIGINT       REFERENCES pj_import_batch(id), -- 被新批次废弃后回填
    create_time             TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time             TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_import_batch_no ON pj_import_batch(batch_no);
-- 同一 source_type + period + dept 只允许一个未被废弃的归档批次
CREATE UNIQUE INDEX uk_import_batch_type_period_dept
    ON pj_import_batch(source_type, period, dept_id)
    WHERE superseded_by_batch_id IS NULL AND status = 3;

COMMENT ON TABLE  pj_import_batch IS '导入批次（聚合根，统领 RawData/NormalizedRecord/ImportIssue）';
COMMENT ON COLUMN pj_import_batch.source_type IS 'KE_SIGNED/ATTENDANCE/POINTS/OTHERS（业绩来源唯一：贝壳新签明细表 KE_SIGNED，一行双口径）';
COMMENT ON COLUMN pj_import_batch.template_version IS '创建/解析时快照冻结，不参与唯一性';
COMMENT ON COLUMN pj_import_batch.period IS '归属月 YYYY-MM';
COMMENT ON COLUMN pj_import_batch.status IS '0 PARSING 1 NORMALIZING 2 PENDING_CONFIRM 3 ARCHIVED 4 FAILED';
COMMENT ON COLUMN pj_import_batch.superseded_by_batch_id IS '被新批次废弃后回填，一经设置不可改';

-- ---------- 二、RawData 公共结构（分表，V120004） ----------
-- 每张 raw 表公共列：id, batch_id, row_no, raw_json, create_time
-- 注：贝壳新签（KE_NEW_SIGN）来源及其 raw 分表已按 V120007 删除，不再创建。

-- 2.1 贝壳结佣明细（KE_SIGNED）
CREATE TABLE pj_import_raw_signed (
    id                  BIGINT       PRIMARY KEY,
    batch_id            BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no              INT          NOT NULL,
    raw_json            JSONB        NOT NULL,                         -- 全量原始行 JSON
    create_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    arrive_month        VARCHAR(7),                                    -- 到账月
    biz_type            VARCHAR(64),                                   -- 业务类型
    order_no            VARCHAR(128),                                  -- 订单号
    contract_no         VARCHAR(128),                                  -- 合同号
    role_sys_no         VARCHAR(64),                                   -- 角色人系统号
    role_name           VARCHAR(64),                                   -- 角色人姓名
    role_type           VARCHAR(64),                                   -- 角色类型
    share_ratio         NUMERIC(10,4),                                 -- 业绩比例
    current_receivable  NUMERIC(18,2),                                 -- 当月应收
    current_received    NUMERIC(18,2),                                 -- 当月实收
    total_receivable    NUMERIC(18,2)                                  -- 总应收业绩（合同累计口径，跨月应收增量认定用）
);
CREATE INDEX idx_raw_signed_batch ON pj_import_raw_signed(batch_id);

-- 2.2 考勤（ATTENDANCE；leave_days 由 V120012 折叠）
CREATE TABLE pj_import_raw_attendance (
    id              BIGINT       PRIMARY KEY,
    batch_id        BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no          INT          NOT NULL,
    raw_json        JSONB        NOT NULL,
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    employee_code   VARCHAR(32),                                      -- 工号
    attend_date     DATE,                                             -- 考勤日期
    late_count      INT,                                              -- 迟到次数
    absent_days     NUMERIC(10,2),                                    -- 旷工天数
    leave_amount    NUMERIC(18,2),                                    -- 扣款金额
    leave_days      NUMERIC(10,2)                                     -- 请假天数（事假+病假合计，参与算薪）
);
CREATE INDEX idx_raw_attendance_batch ON pj_import_raw_attendance(batch_id);
COMMENT ON COLUMN pj_import_raw_attendance.leave_days IS '请假天数（事假+病假合计，参与算薪）';

-- 2.3 积分（POINTS；submit_time 由 V120019 折叠）
CREATE TABLE pj_import_raw_points (
    id              BIGINT       PRIMARY KEY,
    batch_id        BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no          INT          NOT NULL,
    raw_json        JSONB        NOT NULL,
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    employee_code   VARCHAR(32),
    point_date      DATE,
    score           NUMERIC(18,2),
    violation_count INT,                                              -- 违规次数
    submit_time     TIMESTAMP                                         -- 完整填报时间（日报提交窗口判定）
);
CREATE INDEX idx_raw_points_batch ON pj_import_raw_points(batch_id);

-- 2.4 手工录入（OTHERS/MANUAL）
CREATE TABLE pj_import_raw_manual (
    id              BIGINT       PRIMARY KEY,
    batch_id        BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no          INT          NOT NULL,
    raw_json        JSONB        NOT NULL,
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    employee_code   VARCHAR(32),
    item_type       VARCHAR(32),                                      -- 费用类型
    amount          NUMERIC(18,2),
    reason          VARCHAR(255)
);
CREATE INDEX idx_raw_manual_batch ON pj_import_raw_manual(batch_id);

-- ---------- 三、导入问题清单（V120004） ----------
CREATE TABLE pj_import_issue (
    id          BIGINT       PRIMARY KEY,
    batch_id    BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no      INT,
    issue_type  VARCHAR(32)  NOT NULL,   -- EMPLOYEE_NOT_MATCH/COLUMN_TYPE_ERR/REQUIRED_MISSING/DUPLICATE_KEY/PERIOD_MISMATCH
    field_name  VARCHAR(64),
    raw_value   VARCHAR(500),
    message     VARCHAR(1000),
    status      SMALLINT     NOT NULL DEFAULT 0,  -- 0 OPEN 1 RESOLVED 2 IGNORED
    phase       VARCHAR(16)  NOT NULL DEFAULT 'PARSE'  -- PARSE=解析/基础校验，NORMALIZE=归一化
);
CREATE INDEX idx_issue_batch ON pj_import_issue(batch_id);

COMMENT ON TABLE  pj_import_issue IS '批次级校验/归一化失败问题清单';
COMMENT ON COLUMN pj_import_issue.status IS '0 OPEN 1 RESOLVED 2 IGNORED';
COMMENT ON COLUMN pj_import_issue.phase IS '问题来源阶段：PARSE=解析/基础校验，NORMALIZE=归一化';

-- ---------- 四、归一化记录（V120004；加列折叠自 V120011/V120022） ----------
CREATE TABLE pj_normalized_record (
    id                      BIGINT       PRIMARY KEY,
    batch_id                BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    record_type             VARCHAR(20)  NOT NULL,   -- SIGNED/ATTENDANCE/POINTS/MANUAL
    period                  VARCHAR(7),
    employee_id             BIGINT,                   -- 关联 Employee.id，匹配失败为 null
    employee_external_code  VARCHAR(64),              -- 外部编码（系统号/工号）
    source_key              VARCHAR(255),             -- 业务唯一键（订单号/合同号/考勤日期）
    biz_type                VARCHAR(64),
    receivable_amount       NUMERIC(18,2),
    received_amount         NUMERIC(18,2),
    total_receivable_amount NUMERIC(18,2),   -- 合同累计应收（贝壳「总应收业绩」列，跨月应收只认一次的增量基准）
    total_received_amount   NUMERIC(18,2),   -- 总实收业绩（贝壳 Q 列「总实收业绩」，V120011）
    share_ratio             NUMERIC(10,4),
    role_type               VARCHAR(64),
    order_no                VARCHAR(64),              -- 订单号（贝壳原始行 order_no，V120022）
    contract_no             VARCHAR(100),             -- 合同号（贝壳原始行 contract_no，V120022）
    property_address        VARCHAR(255),             -- 物业地址（raw_json.propertyAddress，V120022）
    sign_date               VARCHAR(32),              -- 签约(成销)时间原始字符串（raw_json.signDate，V120022）
    fee_item                VARCHAR(100),             -- 费用项（raw_json.feeItem，V120022）
    role_name               VARCHAR(64),              -- 角色人姓名（冗余自 raw_signed，V120022）
    extra_json              JSONB,                    -- 扩展字段（扣款、考勤细分等）
    raw_data_id             BIGINT,                   -- 对应 RawData 行（手工录入为 null）
    validation_status       SMALLINT     DEFAULT 0,   -- 0 PENDING 1 PASSED 2 FAILED
    validation_msg          VARCHAR(1000),
    create_time             TIMESTAMP    DEFAULT NOW()
);
CREATE INDEX idx_norm_batch ON pj_normalized_record(batch_id);
CREATE INDEX idx_norm_employee ON pj_normalized_record(employee_id);
-- 手工录入 source_key 可为 null，唯一索引仅对非 null 生效
CREATE UNIQUE INDEX uk_norm_source_key
    ON pj_normalized_record(batch_id, source_key)
    WHERE source_key IS NOT NULL;

COMMENT ON TABLE pj_normalized_record IS '归一化记录（交易单据归一化产物：SIGNED/ATTENDANCE/POINTS/MANUAL）';
COMMENT ON COLUMN pj_normalized_record.record_type IS 'SIGNED/ATTENDANCE/POINTS/MANUAL';
COMMENT ON COLUMN pj_normalized_record.order_no         IS '订单号(贝壳原始行 order_no，业绩域快照到 pj_perf_fact.order_no)';
COMMENT ON COLUMN pj_normalized_record.contract_no      IS '合同号(贝壳原始行 contract_no，业绩域快照到 pj_perf_fact.contract_no)';
COMMENT ON COLUMN pj_normalized_record.property_address IS '物业地址(raw_json.propertyAddress，业绩域快照到 pj_perf_fact.property_address)';
COMMENT ON COLUMN pj_normalized_record.sign_date        IS '签约(成销)时间原始字符串(raw_json.signDate，业绩层解析为 pj_perf_fact.business_date)';
COMMENT ON COLUMN pj_normalized_record.fee_item         IS '费用项(raw_json.feeItem，业绩域快照到 pj_perf_fact.fee_item)';
COMMENT ON COLUMN pj_normalized_record.role_name        IS '角色人姓名(冗余自raw_signed，消除关联)';

-- ---------- 五、历史工资原始归档（V120023） ----------
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
