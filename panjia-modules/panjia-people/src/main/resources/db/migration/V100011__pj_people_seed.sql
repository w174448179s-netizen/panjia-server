-- ============================================================
-- Task-1-1: 员工域种子数据（字典 + 6 家门店管理层初始化）
-- 归属域：panjia-people
-- 说明：sys_dict_type 统一 panjia_ 前缀；经纪人（A 序列）由导入/手工录入，种子仅初始化管理层
-- 依赖：V100006~V100010
-- 版本号说明：任务卡概念版本 V11，全局实际 V100011
-- ============================================================

-- ==================== 字典类型 ====================
insert into sys_dict_type values(1761600000000000001, '员工职级', 'panjia_employee_level', 1761000000000000100, 1761100000000000001, now(), null, null, '员工职级列表（A0~A5/S1/S2/总监）');
insert into sys_dict_type values(1761600000000000002, '人员角色', 'panjia_employee_role', 1761000000000000100, 1761100000000000001, now(), null, null, '人员角色（经纪人/店长/总监）');
insert into sys_dict_type values(1761600000000000003, '兼职状态', 'panjia_part_time_status', 1761000000000000100, 1761100000000000001, now(), null, null, '兼职状态（全职/兼职）');

-- ==================== 字典数据：员工职级 ====================
insert into sys_dict_data values(1761600000000010001, 1, 'A0（新人）', 'A0', 'panjia_employee_level', null, 'default', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, 'base=4500, rate=20%');
insert into sys_dict_data values(1761600000000010002, 2, 'A1', 'A1', 'panjia_employee_level', null, 'default', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, 'rate=55%');
insert into sys_dict_data values(1761600000000010003, 3, 'A2', 'A2', 'panjia_employee_level', null, 'default', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, 'rate=60%');
insert into sys_dict_data values(1761600000000010004, 4, 'A3', 'A3', 'panjia_employee_level', null, 'default', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, 'rate=65%');
insert into sys_dict_data values(1761600000000010005, 5, 'A4', 'A4', 'panjia_employee_level', null, 'default', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, 'rate=67%');
insert into sys_dict_data values(1761600000000010006, 6, 'A5', 'A5', 'panjia_employee_level', null, 'default', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, 'rate=70%');
insert into sys_dict_data values(1761600000000010007, 7, 'S1（店长）', 'S1', 'panjia_employee_level', null, 'default', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, 'base=7000, rate=70%');
insert into sys_dict_data values(1761600000000010008, 8, 'S2（店长）', 'S2', 'panjia_employee_level', null, 'default', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, 'base=8000, rate=70%');
insert into sys_dict_data values(1761600000000010009, 9, '总监', 'DIRECTOR', 'panjia_employee_level', null, 'default', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, 'base=6000, rate=70%');

-- ==================== 字典数据：人员角色 ====================
insert into sys_dict_data values(1761600000000020001, 1, '经纪人', 'AGENT', 'panjia_employee_role', null, 'primary', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, null);
insert into sys_dict_data values(1761600000000020002, 2, '店长', 'STORE_MANAGER', 'panjia_employee_role', null, 'success', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, null);
insert into sys_dict_data values(1761600000000020003, 3, '总监', 'DIRECTOR', 'panjia_employee_role', null, 'danger', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, null);

-- ==================== 字典数据：兼职状态 ====================
insert into sys_dict_data values(1761600000000030001, 1, '全职', 'FULL_TIME', 'panjia_part_time_status', null, 'success', 'Y', 1761000000000000100, 1761100000000000001, now(), null, null, null);
insert into sys_dict_data values(1761600000000030002, 2, '兼职', 'PART_TIME', 'panjia_part_time_status', null, 'warning', 'N', 1761000000000000100, 1761100000000000001, now(), null, null, null);

-- ==================== 管理层员工（6 店长 + 1 总监） ====================
-- user_id 示例值待实施时与真实 sys_user.id 对齐；此处允许后续 UPDATE 绑定（§4.4 可后补）
INSERT INTO pj_people_employee (id, user_id, employee_code, name, dept_id, employee_role, part_time_status, status, hire_date, social_insurance_enabled, created_by) VALUES
(1001, 1001, 'FZ001', '王青松', 101, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE, 'admin'),
(1002, 1002, 'FZ002', '吴志龙', 102, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE, 'admin'),
(1003, 1003, 'FZ003', '王学正', 103, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE, 'admin'),
(1004, 1004, 'FZ004', '李润梅', 104, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE, 'admin'),
(1005, 1005, 'FZ005', '牟真琴', 105, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE, 'admin'),
(1006, 1006, 'FZ006', '周治江', 106, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE, 'admin'),
(1007, 1007, 'FZ007', '廖明',   100, 'DIRECTOR',      'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE, 'admin');

-- ==================== 职级记录（effective_from = 入职日） ====================
INSERT INTO pj_people_level (id, employee_id, level_code, level_name, base_salary, commission_rate, social_insurance_ratio, effective_from, change_reason, created_by) VALUES
(2001, 1001, 'S2', '高级店长', 8000, 0.70, 0.30, '2024-01-01', '初始化', 'admin'),
(2002, 1002, 'S2', '高级店长', 8000, 0.70, 0.30, '2024-01-01', '初始化', 'admin'),
(2003, 1003, 'S2', '高级店长', 8000, 0.70, 0.30, '2024-01-01', '初始化', 'admin'),
(2004, 1004, 'S2', '高级店长', 8000, 0.70, 0.30, '2024-01-01', '初始化', 'admin'),
(2005, 1005, 'S1', '初级店长', 7000, 0.70, 0.30, '2024-01-01', '初始化', 'admin'),
(2006, 1006, 'S1', '初级店长', 7000, 0.70, 0.30, '2024-01-01', '初始化', 'admin'),
(2007, 1007, 'DIRECTOR', '总监', 6000, 0.70, 0.30, '2024-01-01', '初始化', 'admin');

-- ==================== 社保档案 ====================
INSERT INTO pj_people_social_insurance (id, employee_id, social_base_amount, personal_ratio, company_ratio, housing_fund_amount, created_by) VALUES
(3001, 1001, 1637.15, 0.30, 0.70, 0, 'admin'),
(3002, 1002, 1637.15, 0.30, 0.70, 0, 'admin'),
(3003, 1003, 1637.15, 0.30, 0.70, 0, 'admin'),
(3004, 1004, 1637.15, 0.30, 0.70, 0, 'admin'),
(3005, 1005, 1637.15, 0.30, 0.70, 0, 'admin'),
(3006, 1006, 1637.15, 0.30, 0.70, 0, 'admin'),
(3007, 1007, 1637.15, 0.30, 0.70, 0, 'admin');
