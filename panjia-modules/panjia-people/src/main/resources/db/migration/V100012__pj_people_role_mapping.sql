-- ============================================================
-- Task-1-2: 业务角色 → 系统角色映射表
-- 归属域：panjia-people
-- 说明：业务角色（AGENT/STORE_MANAGER/DIRECTOR）与 sys_role 多对多映射，
--       角色变更联动通过此表查找需解绑/绑定的 sys_role_id。
--       支持热改：不同客户的角色体系不同，改配置不改代码。
-- 依赖：V100006（pj_people_employee）
-- 对齐：V1.4 §3.5.2.2
-- ============================================================

CREATE TABLE pj_people_role_mapping (
    id              BIGINT       PRIMARY KEY,                -- 雪花 ID
    employee_role   VARCHAR(16)  NOT NULL,                   -- 业务角色 code：AGENT/STORE_MANAGER/DIRECTOR
    sys_role_id     BIGINT       NOT NULL,                   -- RuoYi sys_role.role_id
    opt_lock_version INT         NOT NULL DEFAULT 1,          -- 乐观锁
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,      -- 是否启用
    remark          VARCHAR(255),                             -- 备注
    created_by      VARCHAR(64),                              -- 创建人
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(64),                              -- 更新人
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_role_mapping UNIQUE (employee_role, sys_role_id)
);

CREATE INDEX idx_role_mapping_role ON pj_people_role_mapping(employee_role, is_active);

COMMENT ON TABLE  pj_people_role_mapping IS '业务角色→系统角色映射表（多对多，热改配置）';
COMMENT ON COLUMN pj_people_role_mapping.employee_role IS '业务角色 code：AGENT/STORE_MANAGER/DIRECTOR';
COMMENT ON COLUMN pj_people_role_mapping.sys_role_id IS '关联 sys_role.role_id';
COMMENT ON COLUMN pj_people_role_mapping.is_active IS '是否启用（false=停用该映射）';

-- 默认映射种子（需根据实际 sys_role.role_id 调整）
INSERT INTO pj_people_role_mapping (id, employee_role, sys_role_id, remark) VALUES
(12001, 'AGENT', 100, '经纪人：仅本人数据权限'),
(12002, 'STORE_MANAGER', 110, '店长：本店数据权限'),
(12003, 'STORE_MANAGER', 130, '算薪人员：操作权限'),
(12004, 'DIRECTOR', 120, '总监：全部数据权限 + 审批'),
(12005, 'DIRECTOR', 130, '算薪人员：操作权限');
