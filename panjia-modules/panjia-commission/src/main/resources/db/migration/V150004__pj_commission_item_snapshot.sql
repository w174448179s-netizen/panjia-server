-- 结佣明细冗余事实快照列：与 pj_perf_fact 对齐，工资明细/结佣列表查询直接取本表，
-- 不再跨域 JOIN 事实表；批次撤销物理删事实后快照仍在，不产生悬空展示问题。
ALTER TABLE pj_commission_item
    ADD COLUMN fact_type        character varying(20),
    ADD COLUMN business_date    timestamp without time zone,
    ADD COLUMN order_no         character varying(64),
    ADD COLUMN property_address character varying(255),
    ADD COLUMN share_ratio      numeric(10,6),
    ADD COLUMN employee_code    character varying(50),
    ADD COLUMN role_name        character varying(64),
    ADD COLUMN source_key       character varying(200),
    ADD COLUMN batch_id         bigint,
    ADD COLUMN source           character varying(20) NOT NULL DEFAULT 'IMPORT',
    ADD COLUMN received_apply_id bigint;

-- 回填①：fact_id 命中新签事实（pj_perf_fact）
UPDATE pj_commission_item ci
SET fact_type        = f.fact_type,
    business_date    = f.business_date,
    order_no         = f.order_no,
    property_address = f.property_address,
    share_ratio      = f.share_ratio,
    employee_code    = f.employee_external_code,
    role_name        = f.role_name,
    source_key       = f.source_key,
    batch_id         = f.batch_id,
    source           = f.source,
    received_apply_id = f.received_apply_id
FROM pj_perf_fact f
WHERE ci.performance_fact_id = f.id
  AND ci.business_date IS NULL;

-- 回填②：fact_id 命中原 PERF_REAL 明细（历史单，实收合同级 business_date）
UPDATE pj_commission_item ci
SET fact_type        = 'PERF_REAL',
    business_date    = rc.business_date,
    order_no         = rc.order_no,
    property_address = rc.property_address,
    share_ratio      = rd.share_ratio,
    employee_code    = rd.employee_external_code,
    role_name        = rd.role_name,
    source_key       = rd.source_key,
    batch_id         = rd.source_batch_id,
    source           = 'IMPORT',
    received_apply_id = rd.received_apply_id
FROM pj_received_detail rd
JOIN pj_received_contract rc ON rc.id = rd.contract_id
WHERE ci.performance_fact_id = rd.id
  AND ci.business_date IS NULL;

-- 回填③：悬空行（批次撤销物理删事实，fact_id 已不存在）按
-- 「合同/订单键 + 员工 + 角色 + 金额相等」重配当前 ACTIVE 新签事实
UPDATE pj_commission_item ci
SET fact_type        = 'PERF_EXPECT',
    business_date    = m.business_date,
    order_no         = m.order_no,
    property_address = m.property_address,
    share_ratio      = m.share_ratio,
    employee_code    = m.employee_code,
    role_name        = m.role_name,
    source_key       = m.source_key,
    batch_id         = m.batch_id,
    source           = m.source,
    received_apply_id = m.received_apply_id
FROM (
    SELECT DISTINCT ON (ci2.id)
           ci2.id AS item_id,
           f.business_date, f.order_no, f.property_address, f.share_ratio,
           f.employee_external_code AS employee_code, f.role_name,
           f.source_key, f.batch_id, f.source, f.received_apply_id
    FROM pj_commission_item ci2
    LEFT JOIN pj_people_employee e ON e.employee_id = ci2.employee_id
    JOIN pj_perf_fact f
      ON f.fact_status = 'ACTIVE'
     AND f.fact_type = 'PERF_EXPECT'
     AND (f.contract_no = ci2.contract_no OR f.order_no = ci2.contract_no)
     AND f.role_type IS NOT DISTINCT FROM ci2.role_type
     AND f.performance_amount = ci2.amount
     AND (f.employee_id = ci2.employee_id
          OR f.employee_external_code = e.employee_code)
    WHERE ci2.business_date IS NULL
      AND ci2.contract_no IS NOT NULL
      AND ci2.performance_fact_id IS NOT NULL
    ORDER BY ci2.id, f.id
) m
WHERE ci.id = m.item_id
  AND ci.business_date IS NULL;
