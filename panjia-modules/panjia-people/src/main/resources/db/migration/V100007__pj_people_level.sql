-- ============================================================
-- Task-1-1: 职级历史表 pj_people_level（追加式，禁止 UPDATE 旧记录）
-- 归属域：panjia-people
-- 说明：每次职级变更新增一条记录（带 effective_from），按历史时点取值
-- 依赖：V100006（pj_people_employee）
-- 版本号说明：任务卡概念版本 V7，全局实际 V100007
-- ============================================================

CREATE TABLE pj_people_level (
    id                      BIGINT        PRIMARY KEY,                  -- 雪花 ID
    employee_id             BIGINT        NOT NULL,                     -- 关联 pj_people_employee.id
    level_code              VARCHAR(16)   NOT NULL,                     -- A0~A5/S1/S2/DIRECTOR
    level_name              VARCHAR(64)   NOT NULL,                     -- 职级名称（冗余展示）
    base_salary             DECIMAL(12,2) NOT NULL DEFAULT 0,           -- 底薪
    commission_rate         DECIMAL(5,4)  NOT NULL DEFAULT 0,           -- 基础提成比例
    social_insurance_ratio  DECIMAL(5,4)  NOT NULL DEFAULT 0,           -- 社保个人承担比例
    effective_from          DATE          NOT NULL,                     -- 生效日期（职级变更日）
    effective_to            DATE,                                       -- 失效日期（NULL=当前有效）
    change_reason           VARCHAR(255),                               -- 变更原因（晋升/降级/初始化）
    created_by              VARCHAR(64)   NOT NULL DEFAULT 'admin',
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_level_employee_effective UNIQUE (employee_id, effective_from),
    CONSTRAINT chk_level_code      CHECK (level_code IN ('A0','A1','A2','A3','A4','A5','S1','S2','DIRECTOR')),
    CONSTRAINT chk_level_date_range CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_level_employee_time ON pj_people_level(employee_id, effective_from DESC);

-- 应用层保证：同一 employee_id 同一时点只有一条 effective_to IS NULL 记录（见 Backlog IMP-003）

COMMENT ON TABLE pj_people_level IS '职级历史表（追加式写入，禁止 UPDATE 旧记录）';
COMMENT ON COLUMN pj_people_level.effective_from IS '生效日期；旧记录失效日 = 新记录生效日';
COMMENT ON COLUMN pj_people_level.effective_to IS '失效日期，NULL = 当前有效';
