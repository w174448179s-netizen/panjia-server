-- 部门收支：人工成本下新增商业保险自动支出科目。
-- 金额 = 归属月首日已交纳商业保险的在职/兼职人数 × 当月有效的门店 commercialStandard。
INSERT INTO pj_ledger_subject (
    id, direction, parent_id, subject_code, subject_name, level, sort_order,
    data_source, status, remark, create_time, update_time
)
VALUES (
    1761500000000000106, 'EXPENSE', 1761500000000000101, 'ZC0105', '商业保险', 2, 3,
    'PAYROLL_COMMERCIAL_INSURANCE', '0',
    '门店 commercialStandard × 当月 COMMERCIAL=true 的在职及兼职人数',
    now(), now()
)
ON CONFLICT (id) DO NOTHING;

-- 商业保险紧随社保，原预置科目顺延，保持现有科目编码不变。
UPDATE pj_ledger_subject
SET sort_order = CASE id
    WHEN 1761500000000000104 THEN 4
    WHEN 1761500000000000105 THEN 5
END,
update_time = now()
WHERE id IN (1761500000000000104, 1761500000000000105);

COMMENT ON COLUMN pj_ledger_subject.data_source IS '数据来源：MANUAL/RECEIVED_DETAIL/PAYROLL_GROSS/PAYROLL_SOCIAL/PAYROLL_COMMERCIAL_INSURANCE';
