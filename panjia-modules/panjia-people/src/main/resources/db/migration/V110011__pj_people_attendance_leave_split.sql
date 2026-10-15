-- ============================================================
-- 考勤汇总：病假按证明类型拆分为两列（对齐钉钉《月度汇总》V210）
--   personal_leave_days   事假天数
--   sick_clinic_days      病假（诊所类证明）天数
--   sick_hospital_days    病假（二甲医院以上）天数
-- 既有 leave_days 保留为三类合计（旧数据/展示口径），存量行分项默认 0，
-- 差额由算薪引擎按旧 leaveFee 规则兜底，不重算历史工资。
-- ============================================================
ALTER TABLE pj_people_attendance ADD COLUMN IF NOT EXISTS personal_leave_days NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE pj_people_attendance ADD COLUMN IF NOT EXISTS sick_clinic_days    NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE pj_people_attendance ADD COLUMN IF NOT EXISTS sick_hospital_days  NUMERIC(10,2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN pj_people_attendance.personal_leave_days IS '事假天数（月合计，V110011 拆分）';
COMMENT ON COLUMN pj_people_attendance.sick_clinic_days IS '病假天数-诊所类证明（月合计，V110011 拆分，钉钉V210）';
COMMENT ON COLUMN pj_people_attendance.sick_hospital_days IS '病假天数-二甲医院以上（月合计，V110011 拆分，钉钉V210）';
COMMENT ON COLUMN pj_people_attendance.leave_days IS '请假天数（事假+病假三类合计，月合计，参与算薪扣款）';
