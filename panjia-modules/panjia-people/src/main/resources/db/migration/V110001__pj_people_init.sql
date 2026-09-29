-- ============================================================
-- pj_people 员工域建表 DDL 最终态（清库重建版，一条 CREATE 即最终结构）
-- 合并自：V110002, V110004, V110008, V110009, V110012, V110013, V110014, V110015, V110016, V110017
-- 折叠说明：
--   * V110008 四个金额列并入 pj_people_salary_record；
--   * V110013 process_instance_id/snapshot 两列并入 pj_people_attendance_approval；
--   * V110015 process_instance_id/snapshot 两列并入 pj_people_score_approval；
--   * V110016 late_submit_count 并入 pj_people_performance_score；V110017 删除的
--     派生列（avg_points/grade/deduct_rate/points_fee）不再出现，
--     派生口径收敛到 ScoreGradePolicy 查询时实时计算；
--   * V110002 头部对旧 V1.4 表的 DROP 为清库后无效语句，已省略；
--   * 字典/导入模板 seed 分流至 V110004 合并文件，菜单/flow 种子分流至 V110002/V110003 合并文件。
-- ============================================================

BEGIN;

-- ---------- 一、员工主数据（一人一行） ----------
CREATE TABLE pj_people_employee (
    employee_id        BIGINT       PRIMARY KEY,                  -- 雪花 ID
    employee_code      VARCHAR(32)  NOT NULL,                     -- 工号（= sys_user.username）
    employee_name      VARCHAR(64)  NOT NULL,
    dept_id            BIGINT       NOT NULL,                     -- 归属部门（= sys_user.dept_id）
    phone              VARCHAR(20),
    id_card            VARCHAR(64),                               -- AES 加密存储
    report_date        DATE,                                      -- 报道时间
    hire_date          DATE         NOT NULL,                     -- 入职时间（fact 生效日）
    leave_date         DATE,                                      -- 离职时间（NULL=未离职）
    status             VARCHAR(16)  NOT NULL,                     -- ACTIVE/PARTTIME/LEFT/PENDING
    mentor_employee_id BIGINT,                                    -- 师傅员工 ID（NULL=无）
    remark             VARCHAR(512),
    user_id            BIGINT       UNIQUE,                       -- 关联 sys_user.user_id（建账户后回填）
    version            INT          NOT NULL DEFAULT 0,           -- 乐观锁
    create_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time        TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_employee_code ON pj_people_employee(employee_code);
CREATE INDEX idx_employee_dept ON pj_people_employee(dept_id);

COMMENT ON TABLE  pj_people_employee IS '员工主数据（V5.2：不存岗位/角色，岗位角色走 sys_user_post/sys_user_role）';
COMMENT ON COLUMN pj_people_employee.employee_code IS '工号，与 sys_user.user_name 一致';
COMMENT ON COLUMN pj_people_employee.status IS 'ACTIVE=在职 PARTTIME=兼职 LEFT=离职 PENDING=待入职';

-- ---------- 二、算薪当前态物化快照（界面展示，可由 salary_fact 全量重建） ----------
CREATE TABLE pj_people_salary_record (
    employee_id        BIGINT       PRIMARY KEY,                  -- 一对一 employee
    dept_id            BIGINT,
    status             VARCHAR(16),
    level_code         VARCHAR(16),
    social_insured     BOOLEAN,
    housing_insured    BOOLEAN,
    commercial_insured BOOLEAN,
    dormitory          BOOLEAN,
    is_part_time       BOOLEAN,
    mentor_employee_id BIGINT,
    social_fee         DECIMAL(12,2),                             -- 社保金额（V110008 并入）
    commercial_fee     DECIMAL(12,2),                             -- 商业保险金额（V110008 并入）
    housing_fund       DECIMAL(12,2),                             -- 公积金金额（V110008 并入）
    dormitory_fee      DECIMAL(12,2),                             -- 宿舍费金额（V110008 并入）
    refresh_time       TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE pj_people_salary_record IS '员工算薪当前态（salary_fact 最新切片物化，允许冗余可重建）';
COMMENT ON COLUMN pj_people_salary_record.social_fee     IS '社保金额（自定义；null=用全局默认算法）';
COMMENT ON COLUMN pj_people_salary_record.commercial_fee IS '商业保险金额（自定义；null=用全局默认 21 元）';
COMMENT ON COLUMN pj_people_salary_record.housing_fund   IS '公积金金额（自定义；null=用全局默认算法）';
COMMENT ON COLUMN pj_people_salary_record.dormitory_fee IS '宿舍费金额（自定义；null=用全局默认算法）';

-- ---------- 三、算薪事实（★一项一条，闭开区间 [effective_date, expire_date)） ----------
CREATE TABLE pj_people_salary_fact (
    fact_id        BIGINT       PRIMARY KEY,
    employee_id    BIGINT       NOT NULL,
    fact_type      VARCHAR(16)  NOT NULL,                        -- LEVEL/STATUS/SOCIAL/HOUSING/COMMERCIAL/DORMITORY/PARTTIME/MENTOR
    value          VARCHAR(128) NOT NULL,                        -- 布尔 "true"/"false"；MENTOR 存员工 ID；LEVEL 存职级编码
    effective_date DATE         NOT NULL,                        -- 闭
    expire_date    DATE,                                         -- 开，NULL=至今
    change_field   VARCHAR(32)  NOT NULL DEFAULT 'ALL',
    create_time    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_fact_emp_type  ON pj_people_salary_fact(employee_id, fact_type);
CREATE INDEX idx_fact_timerange ON pj_people_salary_fact(effective_date, expire_date);

COMMENT ON TABLE pj_people_salary_fact IS '算薪事实（分字段独立时间线，闭开区间 [effective_date, expire_date)）';

-- ---------- 四、变更审计（一变更一行） ----------
CREATE TABLE pj_people_change_log (
    log_id         BIGINT       PRIMARY KEY,
    employee_id    BIGINT       NOT NULL,
    change_field   VARCHAR(32)  NOT NULL,                        -- 变更项（fact_type / ALL）
    before_value   VARCHAR(128),
    after_value    VARCHAR(128),
    effective_date DATE         NOT NULL,
    expire_date    DATE,                                          -- 该变更记录的结束时间
    operator_id    BIGINT,
    create_time    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_changelog_emp ON pj_people_change_log(employee_id, effective_date);

COMMENT ON TABLE pj_people_change_log IS '员工变更审计日志';

-- ---------- 五、员工导入批次（独立状态机 PARSING→VALIDATING→IMPORTING→SUCCESS/FAILED） ----------
CREATE TABLE pj_people_import_batch (
    id                     BIGINT       PRIMARY KEY,                  -- 雪花 ID
    batch_no               VARCHAR(32)  NOT NULL,                     -- PEIMP+yyyyMMddHHmmss
    template_code          VARCHAR(32)  NOT NULL,                     -- EMPLOYEE
    template_version       VARCHAR(20)  NOT NULL,                     -- 批次创建时快照冻结
    file_name              VARCHAR(255),                              -- 原始文件名
    storage_path           VARCHAR(500),                              -- 归档路径（工具层返回）
    file_hash              VARCHAR(64),                               -- 文件 SHA-256
    total_rows             INT          NOT NULL DEFAULT 0,
    success_rows           INT          NOT NULL DEFAULT 0,
    failed_rows            INT          NOT NULL DEFAULT 0,
    status                 VARCHAR(16)  NOT NULL,                     -- PARSING/VALIDATING/IMPORTING/SUCCESS/FAILED
    operator_id            BIGINT,                                    -- 操作人（上传人）
    remark                 VARCHAR(500),
    superseded_by_batch_id BIGINT,                                    -- 被新批次替代后回填（主数据不物理删除）
    create_time            TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time            TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_pimp_batch_no ON pj_people_import_batch(batch_no);
CREATE INDEX idx_pimp_status ON pj_people_import_batch(status);

COMMENT ON TABLE  pj_people_import_batch IS '员工导入批次（V6.0 回迁，people 域独立状态机，与单据导入状态机无关）';
COMMENT ON COLUMN pj_people_import_batch.status IS 'PARSING 解析中 / VALIDATING 业务校验中 / IMPORTING 落地中 / SUCCESS 成功 / FAILED 终态失败';
COMMENT ON COLUMN pj_people_import_batch.superseded_by_batch_id IS '被新批次替代后回填；主数据不物理删除，仅标记';

-- ---------- 六、员工导入原始行（insert-only，禁止 UPDATE/DELETE） ----------
CREATE TABLE pj_people_import_raw (
    id          BIGINT      PRIMARY KEY,
    batch_id    BIGINT      NOT NULL REFERENCES pj_people_import_batch(id),
    row_no      INT         NOT NULL,                                 -- 数据行号（1-based）
    raw_json    JSONB       NOT NULL,                                 -- 全量原始值（工具层 rawValues）
    create_time TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_piraw_batch ON pj_people_import_raw(batch_id);

COMMENT ON TABLE pj_people_import_raw IS '员工导入原始行（只读审计锚点，只追加禁改禁删）';

-- ---------- 七、员工导入问题清单 ----------
CREATE TABLE pj_people_import_issue (
    id          BIGINT       PRIMARY KEY,
    batch_id    BIGINT       NOT NULL REFERENCES pj_people_import_batch(id),
    row_no      INT,                                                  -- 数据行号（文件级问题可空）
    issue_type  VARCHAR(32)  NOT NULL,                                -- REQUIRED_MISSING/COLUMN_TYPE_ERR/DUPLICATE_CODE/DEPT_PATH_INVALID/LEVEL_INVALID/MENTOR_NOT_FOUND
    field_name  VARCHAR(64),
    raw_value   VARCHAR(500),
    message     VARCHAR(1000),
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',                 -- OPEN/RESOLVED/IGNORED
    create_time TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_piissue_batch ON pj_people_import_issue(batch_id, status);

COMMENT ON TABLE pj_people_import_issue IS '员工导入问题清单（阻断性问题致批次 FAILED，issue 保留供排查）';

-- ---------- 八、员工导入模板（ColumnDef[] 直接存 JSONB，工具层解析消费） ----------
CREATE TABLE pj_people_import_template (
    id               BIGINT      PRIMARY KEY,
    template_code    VARCHAR(32) NOT NULL,                            -- EMPLOYEE
    template_version VARCHAR(20) NOT NULL,
    column_json      JSONB       NOT NULL,                            -- 工具层 ColumnDef[]
    enabled          SMALLINT    NOT NULL DEFAULT 1,                  -- 1 启用 0 停用
    create_time      TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_pitpl_code_ver UNIQUE (template_code, template_version)
);

COMMENT ON TABLE pj_people_import_template IS '员工导入模板（common-import-util ColumnDef 模型持久化）';

-- ---------- 九、月考勤汇总（一人一月一行；对齐钉钉月度汇总，服务薪酬扣款） ----------
CREATE TABLE pj_people_attendance (
    id                 BIGINT       PRIMARY KEY,                  -- 雪花 ID
    employee_id        BIGINT       NOT NULL,                     -- 员工 ID（pj_people_employee.employee_id）
    attend_month       DATE         NOT NULL,                     -- 考勤月份（当月 1 日）
    leave_days         NUMERIC(10,2) NOT NULL DEFAULT 0,          -- 请假天数（事假+病假合计，月合计）
    absent_days        NUMERIC(10,2) NOT NULL DEFAULT 0,          -- 旷工天数（月合计）
    late_count         INT          NOT NULL DEFAULT 0,           -- 迟到次数
    late_minutes       INT          NOT NULL DEFAULT 0,           -- 迟到时长（分钟，月合计）
    missing_card_count INT          NOT NULL DEFAULT 0,           -- 缺卡次数
    attend_days        NUMERIC(10,2),                             -- 出勤天数
    rest_days          NUMERIC(10,2),                             -- 休息天数
    data_source        VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',    -- 数据来源：MANUAL=人工登记（未来 DINGTALK=钉钉同步）
    remark             VARCHAR(512),                              -- 备注
    version            INT          NOT NULL DEFAULT 0,           -- 乐观锁
    create_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time        TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 同一员工同一月份唯一：防重复登记，也是未来同步 upsert 的锚点
CREATE UNIQUE INDEX uk_attendance_emp_month ON pj_people_attendance(employee_id, attend_month);
-- 月份区间查询
CREATE INDEX idx_attendance_month ON pj_people_attendance(attend_month);

COMMENT ON TABLE  pj_people_attendance IS '月考勤汇总（一人一月一行；对齐钉钉月度汇总，服务薪酬扣款）';
COMMENT ON COLUMN pj_people_attendance.employee_id IS '员工 ID，对应 pj_people_employee.employee_id';
COMMENT ON COLUMN pj_people_attendance.attend_month IS '考勤月份（当月 1 日）';
COMMENT ON COLUMN pj_people_attendance.leave_days IS '请假天数（事假+病假合计，月合计，参与算薪扣款）';
COMMENT ON COLUMN pj_people_attendance.absent_days IS '旷工天数（月合计）';
COMMENT ON COLUMN pj_people_attendance.late_count IS '迟到次数（月合计）';
COMMENT ON COLUMN pj_people_attendance.late_minutes IS '迟到时长（分钟，月合计）';
COMMENT ON COLUMN pj_people_attendance.missing_card_count IS '缺卡次数（月合计）';
COMMENT ON COLUMN pj_people_attendance.attend_days IS '出勤天数';
COMMENT ON COLUMN pj_people_attendance.rest_days IS '休息天数';
COMMENT ON COLUMN pj_people_attendance.data_source IS '数据来源：MANUAL=人工登记；预留 DINGTALK=未来钉钉同步';

-- ---------- 十、考勤审批单（一期间一行；审批动作收敛到「我的待办」warm-flow） ----------
CREATE TABLE pj_people_attendance_approval (
    id                  BIGINT       NOT NULL,
    period              VARCHAR(7)   NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    submit_by           BIGINT,
    submit_time         TIMESTAMP,
    approve_by          BIGINT,
    approve_time        TIMESTAMP,
    reject_reason       VARCHAR(500),
    version             INT          NOT NULL DEFAULT 0,
    create_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    process_instance_id VARCHAR(64),                              -- 流程实例（V110013 并入）
    snapshot            TEXT,                                     -- 异常考勤快照（V110013 并入）
    CONSTRAINT pk_people_attendance_approval PRIMARY KEY (id),
    CONSTRAINT uk_attendance_approval_period UNIQUE (period)
);

COMMENT ON TABLE pj_people_attendance_approval IS '考勤审批单：人事提交当月考勤，总监审批通过后该期间方可进入算薪';
COMMENT ON COLUMN pj_people_attendance_approval.id IS '主键（雪花）';
COMMENT ON COLUMN pj_people_attendance_approval.period IS '归属期间（YYYY-MM）';
COMMENT ON COLUMN pj_people_attendance_approval.status IS '审批状态：DRAFT-待提交 SUBMITTED-待总监审批 APPROVED-总监已通过 REJECTED-已驳回';
COMMENT ON COLUMN pj_people_attendance_approval.submit_by IS '提交人（人事）用户ID';
COMMENT ON COLUMN pj_people_attendance_approval.submit_time IS '提交时间';
COMMENT ON COLUMN pj_people_attendance_approval.approve_by IS '审批人（总监）用户ID';
COMMENT ON COLUMN pj_people_attendance_approval.approve_time IS '审批时间';
COMMENT ON COLUMN pj_people_attendance_approval.reject_reason IS '驳回原因';
COMMENT ON COLUMN pj_people_attendance_approval.version IS '乐观锁版本号';
COMMENT ON COLUMN pj_people_attendance_approval.create_time IS '创建时间';
COMMENT ON COLUMN pj_people_attendance_approval.update_time IS '更新时间';
COMMENT ON COLUMN pj_people_attendance_approval.process_instance_id IS 'Warm-Flow 流程实例ID(attendance_approval)';
COMMENT ON COLUMN pj_people_attendance_approval.snapshot IS '提交时异常考勤快照JSON（迟到/迟到分/缺卡/旷工/请假 >0 的行）';

-- ---------- 十一、绩效积分月度汇总（一人一月一行；积分日报导入聚合，服务薪酬绩效扣点） ----------
-- 注：平均积分/绩效等级/提成扣点/积分扣款为派生字段，查询时经 ScoreGradePolicy 实时计算，不落库
CREATE TABLE pj_people_performance_score (
    id                BIGINT        PRIMARY KEY,                  -- 雪花 ID
    employee_id       BIGINT        NOT NULL,                     -- 员工 ID（pj_people_employee.employee_id）
    score_month       DATE          NOT NULL,                     -- 积分月份（当月 1 日）
    total_points      NUMERIC(12,2) NOT NULL DEFAULT 0,           -- 当月总积分（日报「今日总积分」合计）
    attend_days       INT           NOT NULL DEFAULT 0,           -- 出勤天数（有日报的 DISTINCT 填报日期数）
    data_source       VARCHAR(16)   NOT NULL DEFAULT 'IMPORT',    -- 数据来源：IMPORT=积分日报导入同步
    version           INT           NOT NULL DEFAULT 0,           -- 乐观锁
    create_time       TIMESTAMP     NOT NULL DEFAULT NOW(),
    update_time       TIMESTAMP     NOT NULL DEFAULT NOW(),
    late_submit_count INTEGER       DEFAULT 0                     -- 晚提交次数（V110016 并入）
);

-- 同一员工同一月份唯一：导入同步 upsert 锚点
CREATE UNIQUE INDEX uk_score_emp_month ON pj_people_performance_score(employee_id, score_month);
-- 月份区间查询
CREATE INDEX idx_score_month ON pj_people_performance_score(score_month);

COMMENT ON TABLE  pj_people_performance_score IS '绩效积分月度汇总（一人一月一行；积分日报导入聚合，服务薪酬绩效扣点）';
COMMENT ON COLUMN pj_people_performance_score.employee_id IS '员工 ID，对应 pj_people_employee.employee_id';
COMMENT ON COLUMN pj_people_performance_score.score_month IS '积分月份（当月 1 日）';
COMMENT ON COLUMN pj_people_performance_score.total_points IS '当月总积分（日报「今日总积分」合计）';
COMMENT ON COLUMN pj_people_performance_score.attend_days IS '出勤天数（有日报的 DISTINCT 填报日期数）';
COMMENT ON COLUMN pj_people_performance_score.data_source IS '数据来源：IMPORT=积分日报导入同步';
COMMENT ON COLUMN pj_people_performance_score.late_submit_count IS '当月晚提交次数（填报时间晚于23:00的天数，每天最多1次）';

-- ---------- 十二、积分审批单（一期间一行；结构对齐考勤审批单） ----------
CREATE TABLE pj_people_score_approval (
    id                  BIGINT       NOT NULL,
    period              VARCHAR(7)   NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    submit_by           BIGINT,
    submit_time         TIMESTAMP,
    approve_by          BIGINT,
    approve_time        TIMESTAMP,
    reject_reason       VARCHAR(500),
    version             INT          NOT NULL DEFAULT 0,
    create_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    process_instance_id VARCHAR(64),                              -- 流程实例（V110015 并入）
    snapshot            TEXT,                                     -- 扣点行快照（V110015 并入）
    CONSTRAINT pk_people_score_approval PRIMARY KEY (id),
    CONSTRAINT uk_score_approval_period UNIQUE (period)
);

COMMENT ON TABLE pj_people_score_approval IS '积分审批单：人事提交当月积分，总监审批通过后该期间方可进入算薪';
COMMENT ON COLUMN pj_people_score_approval.id IS '主键（雪花）';
COMMENT ON COLUMN pj_people_score_approval.period IS '归属期间（YYYY-MM）';
COMMENT ON COLUMN pj_people_score_approval.status IS '审批状态：DRAFT-待提交 SUBMITTED-待总监审批 APPROVED-总监已通过 REJECTED-已驳回';
COMMENT ON COLUMN pj_people_score_approval.submit_by IS '提交人（人事）用户ID';
COMMENT ON COLUMN pj_people_score_approval.submit_time IS '提交时间';
COMMENT ON COLUMN pj_people_score_approval.approve_by IS '审批人（总监）用户ID';
COMMENT ON COLUMN pj_people_score_approval.approve_time IS '审批时间';
COMMENT ON COLUMN pj_people_score_approval.reject_reason IS '驳回原因';
COMMENT ON COLUMN pj_people_score_approval.version IS '乐观锁版本号';
COMMENT ON COLUMN pj_people_score_approval.process_instance_id IS 'Warm-Flow 流程实例ID(score_approval)';
COMMENT ON COLUMN pj_people_score_approval.snapshot IS '提交时扣点行快照JSON（绩效等级 B/C 的行，A 级免审）';

COMMIT;
