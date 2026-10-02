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

