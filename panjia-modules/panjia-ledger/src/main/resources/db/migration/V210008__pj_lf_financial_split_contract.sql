-- 金融分润拆单：交易备注“金融分润<合同号>-...”中的合同号作为实际匹配合同
-- 保留兜底记账流水号不变；受影响的合同由后续导入或定时匹配重新汇总

CREATE TEMP TABLE pj_lf_financial_split_affected (
    row_id BIGINT PRIMARY KEY,
    old_contract_no VARCHAR(64),
    new_contract_no VARCHAR(64)
) ON COMMIT DROP;

INSERT INTO pj_lf_financial_split_affected (row_id, old_contract_no, new_contract_no)
SELECT id, contract_no, split_contract_no
FROM (
    SELECT id,
           contract_no,
           NULLIF(BTRIM(substring(transaction_remark FROM '金融分润[[:space:]]*([A-Za-z0-9]+)(?:-|$)')), '') AS split_contract_no
    FROM pj_lf_statement_row
    WHERE category IN ('BUSINESS', 'REVERSAL')
      AND transaction_remark LIKE '%金融分润%'
) parsed
WHERE split_contract_no IS NOT NULL
  AND contract_no IS DISTINCT FROM split_contract_no;

UPDATE pj_lf_statement_row r
SET contract_no = a.new_contract_no,
    match_status = NULL,
    diff_amount = NULL,
    update_time = NOW()
FROM pj_lf_financial_split_affected a
WHERE a.row_id = r.id;

-- 删除新旧合同旧快照，避免原合同已没有流水后仍显示历史差异
DELETE FROM pj_lf_match_result m
WHERE EXISTS (
    SELECT 1
    FROM pj_lf_financial_split_affected a
    WHERE m.contract_no IN (a.old_contract_no, a.new_contract_no)
);

-- 新旧合同仍有流水时，清除行级旧状态，等待定时任务按新归属重建
UPDATE pj_lf_statement_row r
SET match_status = NULL,
    diff_amount = NULL,
    update_time = NOW()
WHERE EXISTS (
    SELECT 1
    FROM pj_lf_financial_split_affected a
    WHERE r.contract_no IN (a.old_contract_no, a.new_contract_no)
);
