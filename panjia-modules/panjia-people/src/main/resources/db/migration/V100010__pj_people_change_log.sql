-- ============================================================
-- Task-1-1: 变更日志表 pj_people_change_log（全量审计，应用层写入，无触发器）
-- 归属域：panjia-people
-- 说明：员工档案任何写操作必须写日志（谁/何时/改了什么/旧值→新值）
-- 依赖：V100006（pj_people_employee）
-- 版本号说明：任务卡概念版本 V10，全局实际 V100010
-- ============================================================

CREATE TABLE pj_people_change_log (
    id            BIGINT       PRIMARY KEY,
    employee_id   BIGINT       NOT NULL,                  -- 关联员工
    change_type   VARCHAR(32)  NOT NULL,                  -- 见 EmployeeChangeTypeEnum 枚举名
    field_name    VARCHAR(64),                            -- 变更字段名（UPDATE 时）
    old_value     TEXT,                                   -- 旧值（JSON 序列化）
    new_value     TEXT,                                   -- 新值（JSON 序列化）
    change_reason VARCHAR(500),                           -- 变更原因
    operator      VARCHAR(64)  NOT NULL,                  -- 操作人 login_name
    operated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_change_type CHECK (change_type IN (
        'CREATE','UPDATE_LEVEL','UPDATE_SOCIAL','UPDATE_BASE',
        'RESIGN','TRANSFER','MENTOR_CREATE','MENTOR_DEACTIVATE','PART_TIME_CHANGE','ROLE_CHANGE'))
);

CREATE INDEX idx_change_log_employee_time ON pj_people_change_log(employee_id, operated_at DESC);

COMMENT ON TABLE pj_people_change_log IS '员工变更日志表（应用层显式写入，禁止触发器）';
COMMENT ON COLUMN pj_people_change_log.change_type IS '变更类型，存 EmployeeChangeTypeEnum 枚举名，禁止魔法字符串';
