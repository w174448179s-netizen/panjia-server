-- ============================================================
-- Task-1-1: 师徒关系表 pj_people_mentor_relation（独立实体，有独立生命周期）
-- 归属域：panjia-people
-- 说明：导师-学员绑定；徒弟离职→关系失效（已发奖励不追回）
-- 依赖：V100006（pj_people_employee）
-- 版本号说明：任务卡概念版本 V9，全局实际 V100009
-- ============================================================

CREATE TABLE pj_people_mentor_relation (
    id                            BIGINT        PRIMARY KEY,
    mentor_id                     BIGINT        NOT NULL,               -- 师傅 employee_id
    apprentice_id                 BIGINT        NOT NULL,               -- 徒弟 employee_id
    apprentice_industry_years     DECIMAL(4,1)  NOT NULL DEFAULT 0,     -- 徒弟行业经验年数（推荐时）
    recommend_date                DATE          NOT NULL,               -- 推荐日期
    is_active                     BOOLEAN       NOT NULL DEFAULT TRUE,  -- 是否有效（徒弟离职→false）
    deactivated_at                TIMESTAMP,                            -- 失效时间
    created_by                    VARCHAR(64)   NOT NULL DEFAULT 'admin',
    created_at                    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_mentor_apprentice UNIQUE (mentor_id, apprentice_id),
    CONSTRAINT chk_not_self_ref    CHECK (mentor_id != apprentice_id)
);

CREATE INDEX idx_mentor_relation_mentor     ON pj_people_mentor_relation(mentor_id, is_active);
CREATE INDEX idx_mentor_relation_apprentice ON pj_people_mentor_relation(apprentice_id);

-- 业务规则（应用层校验）：
-- 1. apprentice_industry_years >= 2 → 才有招聘奖励资格
-- 2. 同一徒弟只能有一个有效师傅（is_active=true）
-- 3. 师傅最多 +10%（5 个合格徒弟）

COMMENT ON TABLE pj_people_mentor_relation IS '师徒关系表（独立实体，徒弟离职→失效，已发奖励不追回）';
