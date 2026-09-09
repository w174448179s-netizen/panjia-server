-- ============================================================
-- Task-1-1: 社保档案表 pj_people_social_insurance
-- 归属域：panjia-people
-- 说明：社保双口径（个人承担=基数×个人比例，公司承担=基数×公司比例，归集部门收支）
-- 依赖：V100006（pj_people_employee）
-- 版本号说明：任务卡概念版本 V8，全局实际 V100008
-- ============================================================

CREATE TABLE pj_people_social_insurance (
    id                            BIGINT        PRIMARY KEY,
    employee_id                   BIGINT        NOT NULL UNIQUE,        -- 一对一
    social_base_amount            DECIMAL(12,2) NOT NULL DEFAULT 1637.15, -- 社保基数
    personal_ratio                DECIMAL(5,4)  NOT NULL DEFAULT 0.20,    -- 个人承担比例
    company_ratio                 DECIMAL(5,4)  NOT NULL DEFAULT 0.80,    -- 公司承担比例
    housing_fund_amount           DECIMAL(12,2) NOT NULL DEFAULT 0,       -- 公积金自缴金额
    commercial_insurance_amount   DECIMAL(12,2) NOT NULL DEFAULT 21,      -- 商业保险费（月）
    dormitory_fee                 DECIMAL(12,2) NOT NULL DEFAULT 0,       -- 宿舍管理费（月）
    effective_from                DATE          NOT NULL DEFAULT '2026-01-01',
    effective_to                  DATE,
    created_by                    VARCHAR(64)   NOT NULL DEFAULT 'admin',
    created_at                    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                    VARCHAR(64),
    updated_at                    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_social_ratio_sum CHECK (personal_ratio + company_ratio = 1.0)
);

COMMENT ON TABLE pj_people_social_insurance IS '社保档案表（个人承担从工资扣，公司承担归集部门收支）';
COMMENT ON COLUMN pj_people_social_insurance.social_base_amount IS '社保基数，默认 1637.15';
