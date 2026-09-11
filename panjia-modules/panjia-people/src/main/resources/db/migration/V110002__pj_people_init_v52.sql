-- ============================================================
-- 员工域 V5.2 重建：事实数据模型（员工主数据 + 算薪事实）
-- 段位：V110002（2026-09-11 由 V100016 重命名）
-- 依据：盘家智管_员工域详细设计_V5.2.md 附录 A
-- 说明：
--   1) 旧 V1.4 表（employee/level/social_insurance/mentor_relation/
--      role_mapping/change_log）整体废弃，先 DROP 再按 V5.2 重建；
--   2) 4 张表：pj_people_employee（员工主数据）
--      pj_people_salary_record（当前态物化，可由 fact 重建）
--      pj_people_salary_fact（★算薪事实，闭开区间 [effective_date, expire_date)）
--      pj_people_change_log（变更审计）
--   3) 岗位/角色不进员工表，走 sys_user_post/sys_user_role（Port-Adapter 同步）；
--   4) 字典：职级 A0~A5/S1/S2（去掉旧 DIRECTOR），新增员工状态字典。
-- ============================================================

BEGIN;

-- ---------- 一、清理旧 V1.4 表 ----------
DROP TABLE IF EXISTS pj_people_role_mapping;
DROP TABLE IF EXISTS pj_people_mentor_relation;
DROP TABLE IF EXISTS pj_people_social_insurance;
DROP TABLE IF EXISTS pj_people_level;
DROP TABLE IF EXISTS pj_people_change_log;
DROP TABLE IF EXISTS pj_people_employee;

-- ---------- 二、员工基本信息（一人一行） ----------
CREATE TABLE pj_people_employee (
    employee_id        BIGINT       PRIMARY KEY,                  -- 雪花 ID
    employee_code      VARCHAR(32)  NOT NULL,                     -- 工号（= sys_user.username）
    employee_name      VARCHAR(64)  NOT NULL,
    dept_id            BIGINT       NOT NULL,                     -- 归属部门（= sys_user.dept_id）
    phone              VARCHAR(20),
    id_card            VARCHAR(64),                               -- AES 加密存储
    report_date        DATE,                                      -- 报道时间
    hire_date          DATE         NOT NULL,                     -- 入职时间（fact 生效日）
    leave_date         DATE,                                      -- 离职时间（NULL=未离职）
    status             VARCHAR(16)  NOT NULL,                     -- ACTIVE/PARTTIME/LEFT/PENDING
    mentor_employee_id BIGINT,                                    -- 师傅员工 ID（NULL=无）
    remark             VARCHAR(512),
    user_id            BIGINT       UNIQUE,                       -- 关联 sys_user.user_id（建账户后回填）
    version            INT          NOT NULL DEFAULT 0,           -- 乐观锁
    create_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time        TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_employee_code ON pj_people_employee(employee_code);
CREATE INDEX idx_employee_dept ON pj_people_employee(dept_id);

COMMENT ON TABLE  pj_people_employee IS '员工主数据（V5.2：不存岗位/角色，岗位角色走 sys_user_post/sys_user_role）';
COMMENT ON COLUMN pj_people_employee.employee_code IS '工号，与 sys_user.user_name 一致';
COMMENT ON COLUMN pj_people_employee.status IS 'ACTIVE=在职 PARTTIME=兼职 LEFT=离职 PENDING=待入职';

-- ---------- 三、当前态物化快照（界面展示，可由 salary_fact 全量重建） ----------
CREATE TABLE pj_people_salary_record (
    employee_id        BIGINT       PRIMARY KEY,                  -- 一对一 employee
    dept_id            BIGINT,
    status             VARCHAR(16),
    level_code         VARCHAR(16),
    social_insured     BOOLEAN,
    housing_insured    BOOLEAN,
    commercial_insured BOOLEAN,
    dormitory          BOOLEAN,
    is_part_time       BOOLEAN,
    mentor_employee_id BIGINT,
    refresh_time       TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE pj_people_salary_record IS '员工算薪当前态（salary_fact 最新切片物化，允许冗余可重建）';

-- ---------- 四、算薪事实（★一项一条，闭开区间） ----------
CREATE TABLE pj_people_salary_fact (
    fact_id        BIGINT       PRIMARY KEY,
    employee_id    BIGINT       NOT NULL,
    fact_type      VARCHAR(16)  NOT NULL,                        -- LEVEL/STATUS/SOCIAL/HOUSING/COMMERCIAL/DORMITORY/PARTTIME/MENTOR
    value          VARCHAR(128) NOT NULL,                        -- 布尔 "true"/"false"；MENTOR 存员工 ID；LEVEL 存职级编码
    effective_date DATE         NOT NULL,                        -- 闭
    expire_date    DATE,                                         -- 开，NULL=至今
    change_field   VARCHAR(32)  NOT NULL DEFAULT 'ALL',
    create_time    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_fact_emp_type  ON pj_people_salary_fact(employee_id, fact_type);
CREATE INDEX idx_fact_timerange ON pj_people_salary_fact(effective_date, expire_date);

COMMENT ON TABLE pj_people_salary_fact IS '算薪事实（分字段独立时间线，闭开区间 [effective_date, expire_date)）';

-- ---------- 五、变更审计（一变更一行） ----------
CREATE TABLE pj_people_change_log (
    log_id         BIGINT       PRIMARY KEY,
    employee_id    BIGINT       NOT NULL,
    change_field   VARCHAR(32)  NOT NULL,                        -- 变更项（fact_type / ALL）
    before_value   VARCHAR(128),
    after_value    VARCHAR(128),
    effective_date DATE         NOT NULL,
    expire_date    DATE,                                          -- 该变更记录的结束时间
    operator_id    BIGINT,
    create_time    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_changelog_emp ON pj_people_change_log(employee_id, effective_date);

COMMENT ON TABLE pj_people_change_log IS '员工变更审计日志';

-- ---------- 六、字典对齐 V5.2 ----------
-- 6.1 旧模型字典说明（2026-09-11 段位重整时清理）
-- 原 V100016 代码包含：
--   DELETE FROM sys_dict_data WHERE dict_type IN ('panjia_employee_role', 'panjia_part_time_status');
--   DELETE FROM sys_dict_type WHERE dict_type IN ('panjia_employee_role', 'panjia_part_time_status');
-- 这两个字典在 V110002 执行前已被清理（岗位角色走 sys_post/sys_role，
-- 兼职状态并入 pj_people_employee.status 员工状态）。DELETE 属于幂等无害的脏代码，
-- 且存在未来误删风险（若重新引入同名字典会被静默删除），已在本版删除。
-- 注：未来若需要"人员角色"或"兼职状态"维度，必须改用：
--     - 人员角色 → sys_post（岗位）/ sys_role（角色）
--     - 兼职状态 → pj_people_employee.status（ACTIVE/PARTTIME/LEFT/PENDING）
-- ============================================================

-- 6.2 员工职级字典（A0~A5/S1/S2；旧 DIRECTOR 职级废弃——总监是岗位/角色不是职级）
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000001, '员工职级', 'panjia_employee_level', 1761000000000000100, 1761100000000000001, now(), '员工职级列表（A0~A5/S1/S2）；底薪/比例归 payroll 规则侧')
ON CONFLICT (dict_type) DO UPDATE SET dict_name = EXCLUDED.dict_name, remark = EXCLUDED.remark;

DELETE FROM sys_dict_data WHERE dict_type = 'panjia_employee_level';
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761600000000010001, 1, 'A0（新人）', 'A0', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010002, 2, 'A1', 'A1', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010003, 3, 'A2', 'A2', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010004, 4, 'A3', 'A3', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010005, 5, 'A4', 'A4', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010006, 6, 'A5', 'A5', 'panjia_employee_level', 'default', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010007, 7, 'S1', 'S1', 'panjia_employee_level', 'success', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000010008, 8, 'S2', 'S2', 'panjia_employee_level', 'success', 'N', 1761000000000000100, 1761100000000000001, now());

-- 6.3 员工状态字典（V5.2：ACTIVE/PARTTIME/LEFT/PENDING）
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000004, '员工状态', 'panjia_employee_status', 1761000000000000100, 1761100000000000001, now(), '员工状态（在职/兼职/离职/待入职）')
ON CONFLICT (dict_type) DO UPDATE SET dict_name = EXCLUDED.dict_name, remark = EXCLUDED.remark;

DELETE FROM sys_dict_data WHERE dict_type = 'panjia_employee_status';
INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761600000000040001, 1, '在职', 'ACTIVE', 'panjia_employee_status', 'success', 'Y', 1761000000000000100, 1761100000000000001, now()),
(1761600000000040002, 2, '兼职', 'PARTTIME', 'panjia_employee_status', 'warning', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000040003, 3, '离职', 'LEFT', 'panjia_employee_status', 'danger', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000040004, 4, '待入职', 'PENDING', 'panjia_employee_status', 'info', 'N', 1761000000000000100, 1761100000000000001, now());

COMMIT;
