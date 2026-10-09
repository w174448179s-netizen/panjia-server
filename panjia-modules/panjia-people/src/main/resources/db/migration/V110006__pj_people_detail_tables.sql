-- ============================================================
-- 积分每日明细 + 考勤每日明细（一员工一天一行）
-- 积分：日报导入同步时保存每日积分/填报时间/晚提交标记
-- 考勤：月度汇总导入时从 Q+ 列解析每日考勤状态保存
-- ============================================================

-- ---------- 积分每日明细 ----------
CREATE TABLE pj_people_score_detail (
    id              BIGINT       PRIMARY KEY,                  -- 雪花 ID
    employee_id     BIGINT       NOT NULL,                     -- 员工 ID
    score_month     DATE         NOT NULL,                     -- 积分月份（当月 1 日，冗余便于按月清理）
    point_date      DATE         NOT NULL,                     -- 填报日期
    submit_time     TIMESTAMP,                                 -- 填报时间（含时分秒）
    score           NUMERIC(12,2) NOT NULL DEFAULT 0,           -- 当日积分
    is_valid        BOOLEAN      NOT NULL DEFAULT TRUE,        -- 是否计入总积分（早于 19:30 提交为 false）
    is_late_submit  BOOLEAN      NOT NULL DEFAULT FALSE,       -- 是否晚提交（晚于 23:00 为 true）
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 一员工一天唯一（导入去重锚点）
CREATE UNIQUE INDEX uk_score_detail_emp_date ON pj_people_score_detail(employee_id, point_date);
-- 按月查询辅助索引
CREATE INDEX idx_score_detail_emp_month ON pj_people_score_detail(employee_id, score_month);

COMMENT ON TABLE  pj_people_score_detail IS '积分每日明细（一员工一天一行；导入同步时保存，覆盖更新）';
COMMENT ON COLUMN pj_people_score_detail.employee_id IS '员工 ID，对应 pj_people_employee.employee_id';
COMMENT ON COLUMN pj_people_score_detail.score_month IS '积分月份（当月 1 日），冗余便于按月清理';
COMMENT ON COLUMN pj_people_score_detail.point_date IS '填报日期（日报对应的天）';
COMMENT ON COLUMN pj_people_score_detail.submit_time IS '填报时间（含时分秒，用于判定提交窗口）';
COMMENT ON COLUMN pj_people_score_detail.score IS '当日积分（日报「今日总积分」）';
COMMENT ON COLUMN pj_people_score_detail.is_valid IS '是否计入总积分（早于 19:30 提交为 false，积分不计）';
COMMENT ON COLUMN pj_people_score_detail.is_late_submit IS '是否晚提交（晚于 23:00 为 true，算薪扣款 5 元/次）';

-- ---------- 考勤每日明细 ----------
CREATE TABLE pj_people_attendance_detail (
    id              BIGINT       PRIMARY KEY,                  -- 雪花 ID
    employee_id     BIGINT       NOT NULL,                     -- 员工 ID
    attend_month    DATE         NOT NULL,                     -- 考勤月份（当月 1 日，冗余便于按月清理）
    attend_date     DATE         NOT NULL,                     -- 考勤日期
    status          VARCHAR(64),                               -- 考勤状态原文（正常/迟到/缺卡/旷工/请假/休息）
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 一员工一天唯一（导入去重锚点）
CREATE UNIQUE INDEX uk_attend_detail_emp_date ON pj_people_attendance_detail(employee_id, attend_date);
-- 按月查询辅助索引
CREATE INDEX idx_attend_detail_emp_month ON pj_people_attendance_detail(employee_id, attend_month);

COMMENT ON TABLE  pj_people_attendance_detail IS '考勤每日明细（一员工一天一行；从月度汇总 Q+ 列解析保存）';
COMMENT ON COLUMN pj_people_attendance_detail.employee_id IS '员工 ID，对应 pj_people_employee.employee_id';
COMMENT ON COLUMN pj_people_attendance_detail.attend_month IS '考勤月份（当月 1 日），冗余便于按月清理';
COMMENT ON COLUMN pj_people_attendance_detail.attend_date IS '考勤日期';
COMMENT ON COLUMN pj_people_attendance_detail.status IS '考勤状态原文（正常/迟到/缺卡/旷工/请假/休息，钉钉单元格文本）';

COMMIT;
