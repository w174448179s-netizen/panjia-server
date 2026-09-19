-- ============================================================
-- 员工域 V110011：考勤表由人天维度重构为人月维度
--
-- 背景：钉钉导入为《月度汇总》（一人一月一行），日维度明细与导入粒度不对齐，
-- 且本期目标是先跑通薪酬（请假/旷工扣款），人事管理弱化。故将
-- pj_people_attendance 由「一人一天一行」改为「一人一月一行」。
--
-- 变更：
--   1) attend_date → attend_month（DATE，存当月 1 日）；
--   2) 删日维度字段：attend_result / check_in_time / check_out_time /
--      early_minutes（月度汇总无打卡时间与早退单列）；
--   3) 调整 leave_days / absent_days 精度为 NUMERIC(10,2)（月合计可 > 1）；
--   4) 新增月汇总字段：late_count（迟到次数）、late_minutes（迟到时长，分钟）、
--      missing_card_count（缺卡次数）、attend_days（出勤天数）、rest_days（休息天数）；
--   5) 唯一索引由 (employee_id, attend_date) 改为 (employee_id, attend_month)。
--
-- 开发环境表刚建立无正式数据，采用 DROP + CREATE 重建；生产首次部署为空库，无影响。
-- ============================================================

BEGIN;



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

COMMIT;
