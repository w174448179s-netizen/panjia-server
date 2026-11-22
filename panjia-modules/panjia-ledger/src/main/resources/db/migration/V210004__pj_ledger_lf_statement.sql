-- ============================================================
-- V210004 部门收支 · 理房通流水
-- 1) 门店对公账户配置（文件名前缀 → 门店/账户）
-- 2) 理房通流水导入批次 + 流水行
-- 3) 合同级匹配结果（理房通流水 vs 系统实收）
-- 4) 费用归集：给 pj_ledger_entry 增加来源标识，流水费用按记账流水号幂等覆盖
-- 5) 菜单与权限
-- ============================================================

-- ------------------------------------------------------------
-- 一、门店对公账户配置
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pj_lf_account (
    id            BIGINT       NOT NULL,
    store_dept_id BIGINT       NOT NULL,
    store_name    VARCHAR(100) NOT NULL,
    file_prefix   VARCHAR(200) NOT NULL,
    account_name  VARCHAR(200) NOT NULL,
    status        CHAR(1)      NOT NULL DEFAULT '0',
    remark        VARCHAR(500),
    create_by     BIGINT,
    create_time   TIMESTAMP,
    update_by     BIGINT,
    update_time   TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE pj_lf_account IS '理房通门店对公账户配置（文件名前缀唯一）';
COMMENT ON COLUMN pj_lf_account.store_dept_id IS '门店部门 ID（sys_dept）';
COMMENT ON COLUMN pj_lf_account.file_prefix IS '导出文件名前缀，如 353517343-B@qq.com';
COMMENT ON COLUMN pj_lf_account.account_name IS '对公账户名称';
CREATE UNIQUE INDEX IF NOT EXISTS uk_lf_account_prefix ON pj_lf_account (LOWER(file_prefix));
CREATE INDEX IF NOT EXISTS idx_lf_account_store ON pj_lf_account (store_dept_id);

-- ------------------------------------------------------------
-- 二、导入批次
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pj_lf_import_batch (
    id            BIGINT       NOT NULL,
    account_id    BIGINT       NOT NULL,
    store_dept_id BIGINT       NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    total_rows    INT          NOT NULL DEFAULT 0,
    success_rows  INT          NOT NULL DEFAULT 0,
    fail_rows     INT          NOT NULL DEFAULT 0,
    status        VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    error_msg     TEXT,
    operator_id   BIGINT,
    create_time   TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE pj_lf_import_batch IS '理房通流水导入批次（仅操作日志，不参与数据过滤）';
CREATE INDEX IF NOT EXISTS idx_lf_batch_store ON pj_lf_import_batch (store_dept_id, create_time);

-- ------------------------------------------------------------
-- 三、流水行
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pj_lf_statement_row (
    id                   BIGINT        NOT NULL,
    batch_id             BIGINT,
    account_id           BIGINT        NOT NULL,
    store_dept_id        BIGINT        NOT NULL,
    store_name           VARCHAR(100),
    period               VARCHAR(7)    NOT NULL,
    transaction_time     TIMESTAMP     NOT NULL,
    income_expense_type  VARCHAR(20)   NOT NULL,
    transaction_type     VARCHAR(50),
    transaction_amount   NUMERIC(18,2) NOT NULL DEFAULT 0,
    account_balance      NUMERIC(18,2),
    business_desc        VARCHAR(255),
    address              VARCHAR(500),
    house_no             VARCHAR(100),
    contract_no          VARCHAR(100),
    transaction_remark   VARCHAR(500),
    counterparty_name    VARCHAR(255),
    biz_order_no         VARCHAR(100),
    voucher_no           VARCHAR(100),
    payment_no           VARCHAR(100),
    raw_row_no           INT,
    category             VARCHAR(30)   NOT NULL DEFAULT 'NON_BUSINESS',
    decoration_flag      BOOLEAN       NOT NULL DEFAULT FALSE,
    subject_id           BIGINT,
    expense_dept_id      BIGINT,
    alloc_status         VARCHAR(20),
    alloc_by             BIGINT,
    alloc_time           TIMESTAMP,
    match_status         VARCHAR(30),
    match_diff_amount    NUMERIC(18,2),
    received_contract_id BIGINT,
    match_time           TIMESTAMP,
    create_time          TIMESTAMP,
    update_time          TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE pj_lf_statement_row IS '理房通对公账户流水（收入/支出/提现全量）';
COMMENT ON COLUMN pj_lf_statement_row.period IS '归属月 YYYY-MM（取动账时间）';
COMMENT ON COLUMN pj_lf_statement_row.category IS 'BUSINESS/REVERSAL/FEE/WITHDRAW/NON_BUSINESS';
COMMENT ON COLUMN pj_lf_statement_row.decoration_flag IS '是否售前房屋美化（去化模式）';
COMMENT ON COLUMN pj_lf_statement_row.subject_id IS '费用归集到的收支科目；空=待补科目';
COMMENT ON COLUMN pj_lf_statement_row.expense_dept_id IS '费用实际归属组（单组自动/多组手工）';
COMMENT ON COLUMN pj_lf_statement_row.alloc_status IS 'AUTO/PENDING/MANUAL';
COMMENT ON COLUMN pj_lf_statement_row.match_status IS 'MATCHED/AMOUNT_DIFF/NOT_FOUND/MULTI_MATCH/PENDING_SIGN_ROLE';
CREATE UNIQUE INDEX IF NOT EXISTS uk_lf_row_voucher
    ON pj_lf_statement_row (account_id, voucher_no)
    WHERE voucher_no IS NOT NULL AND voucher_no <> '';
CREATE INDEX IF NOT EXISTS idx_lf_row_store_period ON pj_lf_statement_row (store_dept_id, period);
CREATE INDEX IF NOT EXISTS idx_lf_row_contract ON pj_lf_statement_row (contract_no);
CREATE INDEX IF NOT EXISTS idx_lf_row_match ON pj_lf_statement_row (match_status);
CREATE INDEX IF NOT EXISTS idx_lf_row_alloc ON pj_lf_statement_row (alloc_status);

-- ------------------------------------------------------------
-- 四、合同级匹配结果
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pj_lf_match_result (
    id               BIGINT        NOT NULL,
    contract_no      VARCHAR(100)  NOT NULL,
    flow_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    received_amount  NUMERIC(18,2) NOT NULL DEFAULT 0,
    diff_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    flow_row_count   INT           NOT NULL DEFAULT 0,
    match_status     VARCHAR(30)   NOT NULL,
    last_match_time  TIMESTAMP,
    create_time      TIMESTAMP,
    update_time      TIMESTAMP,
    PRIMARY KEY (id)
);
COMMENT ON TABLE pj_lf_match_result IS '理房通流水按合同汇总与系统实收的匹配结果';
CREATE UNIQUE INDEX IF NOT EXISTS uk_lf_match_contract ON pj_lf_match_result (contract_no);
CREATE INDEX IF NOT EXISTS idx_lf_match_status ON pj_lf_match_result (match_status);

-- ------------------------------------------------------------
-- 五、费用写入 pj_ledger_entry 的来源标识
-- ------------------------------------------------------------
ALTER TABLE pj_ledger_entry ADD COLUMN IF NOT EXISTS source_type VARCHAR(30) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE pj_ledger_entry ADD COLUMN IF NOT EXISTS source_serial_no VARCHAR(100);
COMMENT ON COLUMN pj_ledger_entry.source_type IS 'MANUAL=手工 / LF_STATEMENT=理房通流水';
COMMENT ON COLUMN pj_ledger_entry.source_serial_no IS '来源幂等键：理房通记账流水号';
CREATE UNIQUE INDEX IF NOT EXISTS uk_ledger_entry_lf_source
    ON pj_ledger_entry (source_type, source_serial_no)
    WHERE source_type = 'LF_STATEMENT' AND source_serial_no IS NOT NULL;

-- ------------------------------------------------------------
-- 六、菜单与权限
-- 总监(10)/财务(12)：全量；店长(11)：只读
-- ------------------------------------------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002461, '理房通流水',     1761400000000002400, 3, 'lf',        'ledger/lf/index',        NULL, 'N', 'Y', 'C', '0', '0', 'ledger:lf:list',        'money', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '理房通流水导入/查询/匹配/费用划归'),
(1761400000000002462, '理房通账户配置', 1761400000000002400, 4, 'lfAccount', 'ledger/lfAccount/index', NULL, 'N', 'Y', 'C', '0', '0', 'ledger:lf:account:list', 'list', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '门店/文件名前缀/对公账户配置'),
(1761400000000002463, '导入流水',       1761400000000002461, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'ledger:lf:import',      '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '理房通流水-导入'),
(1761400000000002464, '费用划归',       1761400000000002461, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'ledger:lf:alloc',       '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '理房通流水-费用划归'),
(1761400000000002465, '导出流水',       1761400000000002461, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'ledger:lf:export',      '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '理房通流水-导出'),
(1761400000000002466, '匹配异常处理',   1761400000000002461, 4, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'ledger:lf:match',       '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '理房通流水-多匹配/挂起处理'),
(1761400000000002467, '新增账户',       1761400000000002462, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'ledger:lf:account:add',    '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '理房通账户配置-新增'),
(1761400000000002468, '编辑账户',       1761400000000002462, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'ledger:lf:account:edit',   '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '理房通账户配置-编辑'),
(1761400000000002469, '删除账户',       1761400000000002462, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'ledger:lf:account:remove', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '理房通账户配置-删除'),
(1761400000000002470, '查看全部门店',   1761400000000002461, 5, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'ledger:lf:all',         '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '无此权限时仅能查看本门店')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
VALUES
(1761300000000000001, 1761400000000002461),
(1761300000000000001, 1761400000000002462),
(1761300000000000001, 1761400000000002463),
(1761300000000000001, 1761400000000002464),
(1761300000000000001, 1761400000000002465),
(1761300000000000001, 1761400000000002466),
(1761300000000000001, 1761400000000002467),
(1761300000000000001, 1761400000000002468),
(1761300000000000001, 1761400000000002469),
(1761300000000000001, 1761400000000002470),
(1761300000000000010, 1761400000000002461),
(1761300000000000010, 1761400000000002462),
(1761300000000000010, 1761400000000002463),
(1761300000000000010, 1761400000000002464),
(1761300000000000010, 1761400000000002465),
(1761300000000000010, 1761400000000002466),
(1761300000000000010, 1761400000000002467),
(1761300000000000010, 1761400000000002468),
(1761300000000000010, 1761400000000002469),
(1761300000000000010, 1761400000000002470),
(1761300000000000012, 1761400000000002461),
(1761300000000000012, 1761400000000002462),
(1761300000000000012, 1761400000000002463),
(1761300000000000012, 1761400000000002464),
(1761300000000000012, 1761400000000002465),
(1761300000000000012, 1761400000000002466),
(1761300000000000012, 1761400000000002467),
(1761300000000000012, 1761400000000002468),
(1761300000000000012, 1761400000000002469),
(1761300000000000012, 1761400000000002470),
(1761300000000000011, 1761400000000002461)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ------------------------------------------------------------
-- 七、初始账户配置：按门店名称匹配 sys_dept，匹配不到则留待页面手工配置
-- ------------------------------------------------------------
INSERT INTO pj_lf_account (id, store_dept_id, store_name, file_prefix, account_name, status, remark, create_time, update_time)
SELECT v.id, d.dept_id, v.store_name, v.file_prefix, v.account_name, '0', '初始配置', now(), now()
FROM (VALUES
    (1761400000000002471::BIGINT, '花照云庭店', '353517343-B@qq.com',             '成都市人居天街房地产经纪有限公司'),
    (1761400000000002472::BIGINT, '龙湖店',     'cdshztjf401B@zijinjianguan.com', '成都市花照天街房地产经纪有限公司'),
    (1761400000000002473::BIGINT, '长庆店',     '876088274-B@qq.com',             '成都一亩田园房地产经纪有限公司'),
    (1761400000000002474::BIGINT, '锦城名都店', '360152646-B@qq.com',             '成都市有德有舍房地产经纪有限公司'),
    (1761400000000002475::BIGINT, '西派少城店', '475640901-B@qq.com',             '成都市有舍有德房地产经纪有限公司')
) AS v(id, store_name, file_prefix, account_name)
JOIN LATERAL (
    SELECT dept_id FROM sys_dept
    WHERE del_flag = '0' AND status = '0' AND dept_name = v.store_name
    ORDER BY dept_id LIMIT 1
) d ON TRUE
WHERE NOT EXISTS (SELECT 1 FROM pj_lf_account a WHERE LOWER(a.file_prefix) = LOWER(v.file_prefix));
