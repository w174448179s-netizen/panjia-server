-- 工资查询密码（BCrypt hash，初始 NULL=未设置）
ALTER TABLE pj_people_employee
    ADD COLUMN salary_query_password VARCHAR(100) DEFAULT NULL;

COMMENT ON COLUMN pj_people_employee.salary_query_password IS '工资查询密码（BCrypt hash，空=未设置）';
