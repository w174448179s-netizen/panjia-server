-- ============================================================
-- Task-1-1: 员工主表 pj_people_employee（聚合根）
-- 归属域：panjia-people
-- 说明：员工档案（工号/入职离职/兼职/角色/sys_user 单向绑定）
-- 依赖：V1 基线
-- 版本号说明：任务卡概念版本 V6，全局实际 V100006
-- ============================================================

CREATE TABLE pj_people_employee (
    id                        BIGINT       PRIMARY KEY,                  -- 雪花 ID（应用层 ASSIGN_ID）
    user_id                   BIGINT       UNIQUE,                       -- 关联 sys_user.id（单向外键，可空=无登录账号）
    employee_code             VARCHAR(64)  NOT NULL UNIQUE,              -- 工号（业务唯一标识，导入匹配键）
    name                      VARCHAR(64)  NOT NULL,                     -- 姓名
    phone                     VARCHAR(20),                               -- 手机号
    id_card_no                VARCHAR(64),                               -- 身份证号（加密存储）
    dept_id                   BIGINT       NOT NULL,                     -- 所属门店/部门 = sys_dept.dept_id
    post_id                   BIGINT,                                    -- 岗位 = sys_post.post_id（可空）
    employee_role             VARCHAR(16)  NOT NULL,                     -- AGENT/STORE_MANAGER/DIRECTOR
    part_time_status          VARCHAR(16)  NOT NULL DEFAULT 'FULL_TIME', -- FULL_TIME/PART_TIME
    status                    VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE/RESIGNED/ON_LEAVE
    hire_date                 DATE         NOT NULL,                     -- 入职日期
    resign_date               DATE,                                      -- 离职日期（status=RESIGNED 时必填）
    social_insurance_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,        -- 是否缴纳社保（兼职=false）
    housing_fund_amount       DECIMAL(12,2) NOT NULL DEFAULT 0,          -- 公积金自缴金额
    commercial_insurance      BOOLEAN      NOT NULL DEFAULT FALSE,       -- 是否购买商业保险
    dormitory_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,       -- 是否住宿舍
    remark                    VARCHAR(500),                              -- 备注
    created_by                VARCHAR(64)  NOT NULL DEFAULT 'admin',     -- 创建人 login_name
    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                VARCHAR(64),
    updated_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    opt_lock_version          INT          NOT NULL DEFAULT 1,           -- 乐观锁（MP @Version，无触发器）

    CONSTRAINT chk_employee_status   CHECK (status IN ('ACTIVE','RESIGNED','ON_LEAVE')),
    CONSTRAINT chk_employee_role     CHECK (employee_role IN ('AGENT','STORE_MANAGER','DIRECTOR')),
    CONSTRAINT chk_part_time_status  CHECK (part_time_status IN ('FULL_TIME','PART_TIME')),
    CONSTRAINT chk_resign_date       CHECK (resign_date IS NULL OR status = 'RESIGNED'),
    CONSTRAINT chk_hire_resign_order CHECK (resign_date IS NULL OR resign_date >= hire_date)
);

CREATE INDEX idx_employee_dept       ON pj_people_employee(dept_id, status);
CREATE INDEX idx_employee_role       ON pj_people_employee(employee_role, status);
CREATE INDEX idx_employee_code_lower ON pj_people_employee(LOWER(employee_code));

COMMENT ON TABLE  pj_people_employee IS '员工主表（people 域聚合根，user_id 单向引用 sys_user）';
COMMENT ON COLUMN pj_people_employee.user_id IS '关联 sys_user.id，可空（无登录账号员工）；禁止级联删除';
COMMENT ON COLUMN pj_people_employee.employee_code IS '工号，业务唯一标识，导入匹配键';
COMMENT ON COLUMN pj_people_employee.employee_role IS '人员角色：AGENT(经纪人)/STORE_MANAGER(店长)/DIRECTOR(总监)，决定算薪策略路由';
COMMENT ON COLUMN pj_people_employee.part_time_status IS '兼职状态，独立字段，与底薪=0 逻辑无关';
COMMENT ON COLUMN pj_people_employee.opt_lock_version IS '乐观锁版本号（MyBatis-Plus @Version）';
