-- ============================================================
-- 清理 V100011 种子数据中的管理层员工及关联记录
-- 归属域：panjia-people
-- 说明：字典数据（sys_dict_type / sys_dict_data）保留，仅清除业务种子
--       产品化部署时不携带任何客户数据，上线新客户需干净库
-- 依赖：V100011
-- ============================================================

-- 清除师徒关系（mentor_id / apprentice_id 可能指向种子员工）
DELETE FROM pj_people_mentor_relation WHERE mentor_id IN (1001,1002,1003,1004,1005,1006,1007)
   OR apprentice_id IN (1001,1002,1003,1004,1005,1006,1007);

-- 清除变更日志
DELETE FROM pj_people_change_log WHERE employee_id IN (1001,1002,1003,1004,1005,1006,1007);

-- 清除社保档案
DELETE FROM pj_people_social_insurance WHERE employee_id IN (1001,1002,1003,1004,1005,1006,1007);

-- 清除职级记录
DELETE FROM pj_people_level WHERE employee_id IN (1001,1002,1003,1004,1005,1006,1007);

-- 清除员工档案
DELETE FROM pj_people_employee WHERE id IN (1001,1002,1003,1004,1005,1006,1007);
