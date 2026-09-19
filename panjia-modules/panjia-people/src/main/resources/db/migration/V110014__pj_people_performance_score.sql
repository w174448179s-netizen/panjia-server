-- ============================================================
-- V110014: 绩效积分表 + 积分审批单表
--
-- 业务：人事导入《二手积分日报5.0版》（一人一天一行）→ 归档聚合为
--       一人一月同步至 pj_people_performance_score（总积分/出勤天数/
--       平均积分/绩效等级/绩效扣点，平均分 = 总积分 / 出勤天数）；
--       人事提交当月积分审批，总监通过后该期间方可创建薪酬批次。
--
-- 等级口径（需求书 V4.6 §绩效等级与扣点映射，与 V160001 policy.points 一致）：
--   平均分 ≥ 8        → A → 扣点 0（不扣）
--   6 ≤ 平均分 < 8    → B → 扣点 -2%
--   平均分 < 6        → C → 扣点 -4%
-- ============================================================

BEGIN;

-- 1. 绩效积分表（一人一月一行；由导入同步写入，无人工登记入口）
CREATE TABLE pj_people_performance_score (
    id             BIGINT        PRIMARY KEY,                  -- 雪花 ID
    employee_id    BIGINT        NOT NULL,                     -- 员工 ID（pj_people_employee.employee_id）
    score_month    DATE          NOT NULL,                     -- 积分月份（当月 1 日）
    total_points   NUMERIC(12,2) NOT NULL DEFAULT 0,           -- 当月总积分（日报「今日总积分」合计）
    attend_days    INT           NOT NULL DEFAULT 0,           -- 出勤天数（有日报的 DISTINCT 填报日期数）
    avg_points     NUMERIC(10,4),                              -- 平均积分 = 总积分 / 出勤天数（出勤 0 天为 NULL）
    grade          VARCHAR(2),                                 -- 绩效等级 A/B/C（出勤 0 天为 NULL）
    deduct_rate    NUMERIC(6,4),                               -- 提成扣点小数（0 / -0.02 / -0.04，展示用；算薪走规则快照）
    data_source    VARCHAR(16)   NOT NULL DEFAULT 'IMPORT',    -- 数据来源：IMPORT=积分日报导入同步
    version        INT           NOT NULL DEFAULT 0,           -- 乐观锁
    create_time    TIMESTAMP     NOT NULL DEFAULT NOW(),
    update_time    TIMESTAMP     NOT NULL DEFAULT NOW()
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
COMMENT ON COLUMN pj_people_performance_score.avg_points IS '平均积分 = 总积分 / 出勤天数';
COMMENT ON COLUMN pj_people_performance_score.grade IS '绩效等级：A(≥8) B(6~8) C(<6)';
COMMENT ON COLUMN pj_people_performance_score.deduct_rate IS '提成扣点小数：A=0 B=-0.02 C=-0.04（快照展示；算薪扣点走规则快照 policy.points）';
COMMENT ON COLUMN pj_people_performance_score.data_source IS '数据来源：IMPORT=积分日报导入同步';

-- 2. 积分审批单表（一期间一行，结构对齐 pj_people_attendance_approval）
CREATE TABLE pj_people_score_approval (
    id            BIGINT       NOT NULL,
    period        VARCHAR(7)   NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    submit_by     BIGINT,
    submit_time   TIMESTAMP,
    approve_by    BIGINT,
    approve_time  TIMESTAMP,
    reject_reason VARCHAR(500),
    version       INT          NOT NULL DEFAULT 0,
    create_time   TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time   TIMESTAMP    NOT NULL DEFAULT NOW(),
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

COMMIT;
