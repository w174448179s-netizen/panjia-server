-- ============================================================
-- 员工域 V6.0：员工导入回迁（本域自建 4 张导入表 + EMPLOYEE 模板种子）
-- 依据：盘家智管_员工域详细设计_V6.0.md §1.5~§1.8、§五
-- 说明：
--   1) 员工导入从导入域回迁 people 域：导入域只做交易业务单据
--      （业绩/考勤/积分/费用），员工主数据导入由本域承接；
--   2) 仅复用 common-import-util 工具层（解析/模板/归档/基础校验），
--      不依赖 panjia-import 域实现；
--   3) 4 张表：pj_people_import_batch（批次，独立状态机）
--      pj_people_import_raw（原始行，insert-only 审计锚点）
--      pj_people_import_issue（问题清单）
--      pj_people_import_template（ColumnDef[] 模板，工具层消费）；
--   4) 菜单按钮 people:employee:import 已由 V100017 播种，本脚本仅修正备注。
-- ============================================================

BEGIN;

-- ---------- 一、员工导入批次（独立状态机 PARSING→VALIDATING→IMPORTING→SUCCESS/FAILED） ----------
CREATE TABLE pj_people_import_batch (
    id                     BIGINT       PRIMARY KEY,                  -- 雪花 ID
    batch_no               VARCHAR(32)  NOT NULL,                     -- PEIMP+yyyyMMddHHmmss
    template_code          VARCHAR(32)  NOT NULL,                     -- EMPLOYEE
    template_version       VARCHAR(20)  NOT NULL,                     -- 批次创建时快照冻结
    file_name              VARCHAR(255),                              -- 原始文件名
    storage_path           VARCHAR(500),                              -- 归档路径（工具层返回）
    file_hash              VARCHAR(64),                               -- 文件 SHA-256
    total_rows             INT          NOT NULL DEFAULT 0,
    success_rows           INT          NOT NULL DEFAULT 0,
    failed_rows            INT          NOT NULL DEFAULT 0,
    status                 VARCHAR(16)  NOT NULL,                     -- PARSING/VALIDATING/IMPORTING/SUCCESS/FAILED
    operator_id            BIGINT,                                    -- 操作人（上传人）
    remark                 VARCHAR(500),
    superseded_by_batch_id BIGINT,                                    -- 被新批次替代后回填（主数据不物理删除）
    create_time            TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time            TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_pimp_batch_no ON pj_people_import_batch(batch_no);
CREATE INDEX idx_pimp_status ON pj_people_import_batch(status);

COMMENT ON TABLE  pj_people_import_batch IS '员工导入批次（V6.0 回迁，people 域独立状态机，与单据导入状态机无关）';
COMMENT ON COLUMN pj_people_import_batch.status IS 'PARSING 解析中 / VALIDATING 业务校验中 / IMPORTING 落地中 / SUCCESS 成功 / FAILED 终态失败';
COMMENT ON COLUMN pj_people_import_batch.superseded_by_batch_id IS '被新批次替代后回填；主数据不物理删除，仅标记';

-- ---------- 二、员工导入原始行（insert-only，禁止 UPDATE/DELETE） ----------
CREATE TABLE pj_people_import_raw (
    id          BIGINT      PRIMARY KEY,
    batch_id    BIGINT      NOT NULL REFERENCES pj_people_import_batch(id),
    row_no      INT         NOT NULL,                                 -- 数据行号（1-based）
    raw_json    JSONB       NOT NULL,                                 -- 全量原始值（工具层 rawValues）
    create_time TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_piraw_batch ON pj_people_import_raw(batch_id);

COMMENT ON TABLE pj_people_import_raw IS '员工导入原始行（只读审计锚点，只追加禁改禁删）';

-- ---------- 三、员工导入问题清单 ----------
CREATE TABLE pj_people_import_issue (
    id          BIGINT       PRIMARY KEY,
    batch_id    BIGINT       NOT NULL REFERENCES pj_people_import_batch(id),
    row_no      INT,                                                  -- 数据行号（文件级问题可空）
    issue_type  VARCHAR(32)  NOT NULL,                                -- REQUIRED_MISSING/COLUMN_TYPE_ERR/DUPLICATE_CODE/DEPT_PATH_INVALID/LEVEL_INVALID/MENTOR_NOT_FOUND
    field_name  VARCHAR(64),
    raw_value   VARCHAR(500),
    message     VARCHAR(1000),
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',                 -- OPEN/RESOLVED/IGNORED
    create_time TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_piissue_batch ON pj_people_import_issue(batch_id, status);

COMMENT ON TABLE pj_people_import_issue IS '员工导入问题清单（阻断性问题致批次 FAILED，issue 保留供排查）';

-- ---------- 四、员工导入模板（ColumnDef[] 直接存 JSONB，工具层解析消费） ----------
CREATE TABLE pj_people_import_template (
    id               BIGINT      PRIMARY KEY,
    template_code    VARCHAR(32) NOT NULL,                            -- EMPLOYEE
    template_version VARCHAR(20) NOT NULL,
    column_json      JSONB       NOT NULL,                            -- 工具层 ColumnDef[]
    enabled          SMALLINT    NOT NULL DEFAULT 1,                  -- 1 启用 0 停用
    create_time      TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_pitpl_code_ver UNIQUE (template_code, template_version)
);

COMMENT ON TABLE pj_people_import_template IS '员工导入模板（common-import-util ColumnDef 模型持久化）';

-- 种子：员工导入模板 V100（field 为 snake_case，与 ParsedRow.values/rawValues 的 key 对齐）
INSERT INTO pj_people_import_template (id, template_code, template_version, column_json, enabled)
VALUES (1761600000000000001, 'EMPLOYEE', 'V100', '[
  {"colName":"门店-组别","field":"dept_full","type":"STRING","required":true},
  {"colName":"工号","field":"employee_code","type":"STRING","required":true,"maxLength":32},
  {"colName":"姓名","field":"employee_name","type":"STRING","required":true,"maxLength":64},
  {"colName":"职级","field":"level","type":"STRING","required":true,"enumValues":["A0","A1","A2","A3","A4","A5","S1","S2"]},
  {"colName":"职位","field":"post_names","type":"STRING","required":true},
  {"colName":"电话","field":"phone","type":"STRING","required":false,"maxLength":20},
  {"colName":"身份证","field":"id_card","type":"STRING","required":false,"maxLength":64},
  {"colName":"报道时间","field":"report_date","type":"DATE","required":false,"dateFormat":"yyyy-MM-dd"},
  {"colName":"入职时间","field":"hire_date","type":"DATE","required":true,"dateFormat":"yyyy-MM-dd"},
  {"colName":"社保","field":"social","type":"BOOL","required":true},
  {"colName":"公积金","field":"housing","type":"BOOL","required":true},
  {"colName":"商业保险","field":"commercial","type":"BOOL","required":true},
  {"colName":"宿舍","field":"dormitory","type":"BOOL","required":true},
  {"colName":"兼职","field":"parttime","type":"BOOL","required":true},
  {"colName":"师傅工号","field":"mentor_code","type":"STRING","required":false,"maxLength":32}
]', 1)
ON CONFLICT (id) DO NOTHING;

-- ---------- 五、菜单备注修正（权限码 people:employee:import 已由 V100017 播种） ----------
UPDATE sys_menu
SET remark = '员工管理-导入按钮（V6.0 回迁：people 域员工导入向导，两阶段诊断+单一大事务落地）'
WHERE menu_id = 1761400000000002006;

COMMIT;
