-- 部门收支「社保」改为自动取数：薪酬配置 DEPT 政策 socialStandard × 该部门在职/兼职人数
UPDATE pj_ledger_subject
SET data_source = 'PAYROLL_SOCIAL',
    remark = '取薪酬配置门店社保扣减标准 × 该部门在职/兼职人数',
    update_time = now()
WHERE id = 1761500000000000103
   OR (direction = 'EXPENSE' AND subject_code = 'ZC0102');

COMMENT ON COLUMN pj_ledger_subject.data_source IS 'MANUAL=手工 / RECEIVED_DETAIL=实收明细自动 / PAYROLL_GROSS=工资应发自动 / PAYROLL_SOCIAL=社保费用自动';
