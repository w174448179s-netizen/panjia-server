-- ============================================================
-- 导入域 V1.4 核心表结构
-- 依据：盘家智管_导入域详细设计_V1.4.md §六
-- 包含：pj_import_batch（批次）+ 6 张 RawData 分表 + pj_import_issue + pj_normalized_record
-- 说明：
--   1) 主键用 BIGINT（雪花 ID，应用层 ASSIGN_ID 生成），非 BIGSERIAL；
--   2) RawData 分表 insert-only，无 update/delete 列；
--   3) EMPLOYEE 不产 NormalizedRecord，走 people 域 EmployeeImportSink；
--   4) pj_import_template 表已由 V100002 建立，本脚本不重复建。
-- ============================================================

BEGIN;

-- ---------- 一、导入批次（聚合根） ----------
CREATE TABLE pj_import_batch (
    id                      BIGINT       PRIMARY KEY,                  -- 雪花 ID
    batch_no                VARCHAR(32)  NOT NULL,                     -- 批次号 IMP+yyyyMMdd+序列
    source_type             VARCHAR(20)  NOT NULL,                     -- KE_SIGNED/KE_NEW_SIGN/ATTENDANCE/POINTS/OTHERS/EMPLOYEE
    template_version        VARCHAR(20)  NOT NULL,                     -- 创建/解析时快照冻结
    file_name               VARCHAR(255),                              -- 存储文件名
    original_file_name      VARCHAR(255),                              -- 上传原名
    storage_path            VARCHAR(500),                              -- 文件存储路径
    period                  VARCHAR(7),                                -- 归属月 YYYY-MM（EMPLOYEE 可空）
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
COMMENT ON COLUMN pj_import_batch.source_type IS 'KE_SIGNED/KE_NEW_SIGN/ATTENDANCE/POINTS/OTHERS/EMPLOYEE';
COMMENT ON COLUMN pj_import_batch.template_version IS '创建/解析时快照冻结，不参与唯一性';
COMMENT ON COLUMN pj_import_batch.period IS '归属月 YYYY-MM，EMPLOYEE 可空';
COMMENT ON COLUMN pj_import_batch.status IS '0 PARSING 1 NORMALIZING 2 PENDING_CONFIRM 3 ARCHIVED 4 FAILED';
COMMENT ON COLUMN pj_import_batch.superseded_by_batch_id IS '被新批次废弃后回填，一经设置不可改（EMPLOYEE 触发物理清理）';

-- ---------- 二、RawData 公共结构（分表） ----------
-- 每张 raw 表公共列：id, batch_id, row_no, raw_json, create_time

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
    current_received    NUMERIC(18,2)                                  -- 当月实收
);
CREATE INDEX idx_raw_signed_batch ON pj_import_raw_signed(batch_id);

-- 2.2 贝壳新签明细（KE_NEW_SIGN）
CREATE TABLE pj_import_raw_new_sign (
    id                  BIGINT       PRIMARY KEY,
    batch_id            BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no              INT          NOT NULL,
    raw_json            JSONB        NOT NULL,
    create_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    arrive_month        VARCHAR(7),
    biz_type            VARCHAR(64),
    order_no            VARCHAR(128),
    contract_no         VARCHAR(128),
    role_sys_no         VARCHAR(64),
    role_name           VARCHAR(64),
    role_type           VARCHAR(64),
    share_ratio         NUMERIC(10,4),
    current_receivable  NUMERIC(18,2),
    current_received    NUMERIC(18,2)
);
CREATE INDEX idx_raw_new_sign_batch ON pj_import_raw_new_sign(batch_id);

-- 2.3 考勤（ATTENDANCE）
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
    leave_amount    NUMERIC(18,2)                                     -- 扣款金额
);
CREATE INDEX idx_raw_attendance_batch ON pj_import_raw_attendance(batch_id);

-- 2.4 积分（POINTS）
CREATE TABLE pj_import_raw_points (
    id              BIGINT       PRIMARY KEY,
    batch_id        BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no          INT          NOT NULL,
    raw_json        JSONB        NOT NULL,
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    employee_code   VARCHAR(32),
    point_date      DATE,
    score           NUMERIC(18,2),
    violation_count INT                                               -- 违规次数
);
CREATE INDEX idx_raw_points_batch ON pj_import_raw_points(batch_id);

-- 2.5 手工录入（OTHERS/MANUAL）
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

-- 2.6 员工主数据（EMPLOYEE）
CREATE TABLE pj_import_raw_employee (
    id                  BIGINT       PRIMARY KEY,
    batch_id            BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no              INT          NOT NULL,
    raw_json            JSONB        NOT NULL,
    create_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    employee_code       VARCHAR(32),                                  -- 工号
    name                VARCHAR(64),                                  -- 姓名
    phone               VARCHAR(20),
    id_card             VARCHAR(32),
    dept_path           VARCHAR(255),                                 -- 部门全路径，- 分隔
    post_names          VARCHAR(255),                                 -- 岗位名，/ 分隔
    level               VARCHAR(20),                                  -- 职级
    social_insured      VARCHAR(10),                                  -- 是/否
    housing_insured     VARCHAR(10),
    commerce_insurance  NUMERIC(18,2),                                -- 商业保险金额
    dormitory           VARCHAR(10),                                  -- 有/无
    part_time           VARCHAR(10),                                  -- 是/否
    master              VARCHAR(32),                                  -- 师傅工号
    entry_date          DATE
);
CREATE INDEX idx_raw_employee_batch ON pj_import_raw_employee(batch_id);
COMMENT ON TABLE pj_import_raw_employee IS 'EMPLOYEE 原始归档（只读），业务产物由 EmployeeImportSink 落地 people 域';

-- ---------- 三、导入问题清单 ----------
CREATE TABLE pj_import_issue (
    id          BIGINT       PRIMARY KEY,
    batch_id    BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    row_no      INT,
    issue_type  VARCHAR(32)  NOT NULL,   -- EMPLOYEE_NOT_MATCH/COLUMN_TYPE_ERR/REQUIRED_MISSING/DUPLICATE_KEY/PERIOD_MISMATCH/DEPT_NOT_MATCH/POST_NOT_MATCH
    field_name  VARCHAR(64),
    raw_value   VARCHAR(500),
    message     VARCHAR(1000),
    status      SMALLINT     NOT NULL DEFAULT 0  -- 0 OPEN 1 RESOLVED 2 IGNORED
);
CREATE INDEX idx_issue_batch ON pj_import_issue(batch_id);

COMMENT ON TABLE pj_import_issue IS '批次级校验/归一化失败问题清单';
COMMENT ON COLUMN pj_import_issue.status IS '0 OPEN 1 RESOLVED 2 IGNORED';

-- ---------- 四、归一化记录（业绩类专用，不含 EMPLOYEE） ----------
CREATE TABLE pj_normalized_record (
    id                      BIGINT       PRIMARY KEY,
    batch_id                BIGINT       NOT NULL REFERENCES pj_import_batch(id),
    record_type             VARCHAR(20)  NOT NULL,   -- SIGNED/NEW_SIGN/ATTENDANCE/POINTS/MANUAL
    period                  VARCHAR(7),
    employee_id             BIGINT,                   -- 关联 Employee.id，匹配失败为 null
    employee_external_code  VARCHAR(64),              -- 外部编码（系统号/工号）
    source_key              VARCHAR(255),             -- 业务唯一键（订单号/合同号/考勤日期）
    biz_type                VARCHAR(64),
    receivable_amount       NUMERIC(18,2),
    received_amount         NUMERIC(18,2),
    share_ratio             NUMERIC(10,4),
    role_type               VARCHAR(64),
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

COMMENT ON TABLE pj_normalized_record IS '归一化记录（业绩类专用，EMPLOYEE 不产生）';
COMMENT ON COLUMN pj_normalized_record.record_type IS 'SIGNED/NEW_SIGN/ATTENDANCE/POINTS/MANUAL';

COMMIT;
