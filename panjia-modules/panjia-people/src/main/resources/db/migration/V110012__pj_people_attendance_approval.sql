-- ============================================================
-- V110012: 考勤审批单表（pj_people_attendance_approval）
--
-- 业务：人事将当月考勤批量提交总监审批；总监通过后该期间才能创建
--       薪酬批次（进入算薪），未通过时 createBatch 卡点拒绝。
-- 语义：
--   一期一行（uk_attendance_approval_period），状态机：
--   DRAFT(初始/审批失效) → SUBMITTED(已提交待审批) → APPROVED(总监通过)
--                                                  → REJECTED(总监驳回，可改后重新提交)
--   考勤重新导入（syncAttendanceSummaries 覆盖该月数据）时，
--   SUBMITTED/APPROVED 单据自动失效回 DRAFT，防止"审批后偷偷改数"。
--   历史薪酬批次不受后续失效影响（卡点只在创建批次时校验）。
-- ============================================================

BEGIN;

CREATE TABLE pj_people_attendance_approval (
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

COMMIT;
