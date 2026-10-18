-- 工资条发送 + 员工核对留痕
-- 1) 批次发送：send_time/send_by 记录发送动作（人事点击"发送工资条"后，员工端工资查询可见）
--    可见性规则：send_time 非空 或 状态为 LOCKED/PAID（锁定/发放批次天然对员工可见，兼容历史数据）
-- 2) 员工核对：明细行 ack_time 非空即已核对（纯留痕，不影响审批流）；
--    批次锁定（LOCKED/PAID）后不允许再核对
ALTER TABLE pj_payroll_batch
    ADD COLUMN IF NOT EXISTS send_time timestamp,
    ADD COLUMN IF NOT EXISTS send_by varchar(64);

COMMENT ON COLUMN pj_payroll_batch.send_time IS '工资条发送时间（员工端可见的前提之一）';
COMMENT ON COLUMN pj_payroll_batch.send_by IS '工资条发送操作人（留痕）';

ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS ack_time timestamp;

COMMENT ON COLUMN pj_payroll_detail.ack_time IS '员工核对时间（纯留痕标记，不影响工资审批；批次锁定后不可核对）';

CREATE INDEX IF NOT EXISTS idx_payroll_detail_emp_batch
    ON pj_payroll_detail (employee_id, batch_id);
