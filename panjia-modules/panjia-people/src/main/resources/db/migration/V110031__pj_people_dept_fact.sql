-- 员工归属部门时间轴：DEPT 事实（值 = dept_id，闭开区间 [effective_date, expire_date)，生效粒度按月）
-- 用途：员工跨店调店后，工资/实收报表按归属月解析「当时所属门店」，避免历史月份被按当前门店归集。
-- 存量基线：每个员工补一条 DEPT 事实，生效月 = 入职月，值 = 当前 dept_id；
-- 后续由员工档案「归属部门变更」写入新的 DEPT 事实（EmployeeServiceImpl.updateEmployee）。
INSERT INTO pj_people_salary_fact (fact_id, employee_id, fact_type, value, effective_date, change_field, create_time)
SELECT base.max_id + ROW_NUMBER() OVER (ORDER BY e.employee_id),
       e.employee_id,
       'DEPT',
       e.dept_id::text,
       date_trunc('month', e.hire_date)::date,
       'ALL',
       NOW()
FROM pj_people_employee e
CROSS JOIN (SELECT COALESCE(MAX(fact_id), 0) AS max_id FROM pj_people_salary_fact) base
WHERE NOT EXISTS (
    SELECT 1 FROM pj_people_salary_fact f
    WHERE f.employee_id = e.employee_id
      AND f.fact_type = 'DEPT'
);
