-- 品效商圈权益作为部门收入，不再参与合同实收匹配

INSERT INTO pj_ledger_subject (
    id, direction, parent_id, subject_code, subject_name, level,
    sort_order, data_source, status, remark, create_time, update_time
)
SELECT 1761500000000000010, 'INCOME', 1761500000000000001, 'SR0104', '品效商圈权益', 2,
       4, 'MANUAL', '0', '理房通流水自动归集部门收入', now(), now()
WHERE NOT EXISTS (
    SELECT 1 FROM pj_ledger_subject
    WHERE direction = 'INCOME' AND (subject_code = 'SR0104' OR subject_name = '品效商圈权益')
)
ON CONFLICT (id) DO NOTHING;

-- 历史流水重分类，并清除该流水自身的合同匹配状态
UPDATE pj_lf_statement_row r
SET category = 'DEPT_INCOME',
    decoration_flag = FALSE,
    subject_id = (
        SELECT s.id FROM pj_ledger_subject s
        WHERE s.direction = 'INCOME'
          AND (s.subject_code = 'SR0104' OR s.subject_name = '品效商圈权益')
        ORDER BY s.id LIMIT 1
    ),
    expense_dept_id = r.store_dept_id,
    alloc_status = 'AUTO',
    alloc_by = NULL,
    alloc_time = NULL,
    match_status = NULL,
    match_diff_amount = NULL,
    received_contract_id = NULL,
    match_time = NULL,
    update_time = now()
WHERE btrim(r.transaction_remark) = '佣金分账-居间代理费-品效商圈权益';

-- 合同已无任何业务流水时，删除不再有效的旧匹配结果
DELETE FROM pj_lf_match_result m
WHERE EXISTS (
    SELECT 1 FROM pj_lf_statement_row r
    WHERE r.contract_no = m.contract_no
      AND btrim(r.transaction_remark) = '佣金分账-居间代理费-品效商圈权益'
)
AND NOT EXISTS (
    SELECT 1 FROM pj_lf_statement_row r
    WHERE r.contract_no = m.contract_no
      AND r.category IN ('BUSINESS', 'REVERSAL')
);

-- 幂等重建历史部门收入，避免重复导入前产生重复记录
DELETE FROM pj_ledger_entry e
USING pj_lf_statement_row r
WHERE e.source_type = 'LF_STATEMENT'
  AND e.source_serial_no = r.voucher_no
  AND btrim(r.transaction_remark) = '佣金分账-居间代理费-品效商圈权益';

INSERT INTO pj_ledger_entry (
    id, subject_id, dept_id, direction, amount, start_date, end_date,
    remark, source_type, source_serial_no, create_by, create_time, update_time
)
SELECT r.id,
       (
           SELECT s.id FROM pj_ledger_subject s
           WHERE s.direction = 'INCOME'
             AND (s.subject_code = 'SR0104' OR s.subject_name = '品效商圈权益')
           ORDER BY s.id LIMIT 1
       ),
       r.store_dept_id,
       'INCOME',
       r.transaction_amount,
       (r.period || '-01')::date,
       (date_trunc('month', (r.period || '-01')::date) + interval '1 month - 1 day')::date,
       left('理房通流水'
           || CASE WHEN r.business_desc IS NULL OR r.business_desc = '' THEN '' ELSE ' · ' || r.business_desc END
           || ' · ' || r.transaction_remark
           || CASE WHEN r.counterparty_name IS NULL OR r.counterparty_name = '' THEN '' ELSE ' · ' || r.counterparty_name END,
           500),
       'LF_STATEMENT',
       r.voucher_no,
       b.operator_id,
       COALESCE(r.create_time, now()),
       now()
FROM pj_lf_statement_row r
LEFT JOIN pj_lf_import_batch b ON b.id = r.batch_id
WHERE btrim(r.transaction_remark) = '佣金分账-居间代理费-品效商圈权益'
  AND r.store_dept_id IS NOT NULL
  AND r.transaction_amount IS NOT NULL
  AND r.transaction_amount <> 0
  AND r.voucher_no IS NOT NULL
  AND r.voucher_no <> ''
ON CONFLICT (id) DO NOTHING;
