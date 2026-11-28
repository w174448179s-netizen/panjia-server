-- 部门收支：人工成本下新增宿舍费自动支出科目。
-- 金额 = 当月 DORMITORY=true 的在职/兼职员工宿舍费合计；
-- 单人宿舍费 = 员工自定义 DORMITORY_FEE → 门店 dormStandard → 全局 dormitoryFee。
INSERT INTO pj_ledger_subject (
    id, direction, parent_id, subject_code, subject_name, level, sort_order,
    data_source, status, remark, create_time, update_time
)
VALUES (
    1761500000000000107, 'EXPENSE', 1761500000000000101, 'ZC0106', '宿舍费', 2, 4,
    'PAYROLL_DORMITORY', '0',
    '住宿舍员工宿舍费合计：个人自定义金额优先，未配置取门店 dormStandard，再回退全局 dormitoryFee',
    now(), now()
)
ON CONFLICT (id) DO NOTHING;

-- 宿舍费紧随商业保险，原预置科目顺延，保持现有科目编码不变。
UPDATE pj_ledger_subject
SET sort_order = CASE id
    WHEN 1761500000000000104 THEN 5
    WHEN 1761500000000000105 THEN 6
END,
update_time = now()
WHERE id IN (1761500000000000104, 1761500000000000105);

COMMENT ON COLUMN pj_ledger_subject.data_source IS '数据来源：MANUAL/RECEIVED_DETAIL/PAYROLL_GROSS/PAYROLL_SOCIAL/PAYROLL_COMMERCIAL_INSURANCE/PAYROLL_DORMITORY';
