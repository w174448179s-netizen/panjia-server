-- ============================================================
-- V110012: 员工档案扩展（性别/工资卡）+ 员工合同管理
--   1) pj_people_employee 加列：gender / bank_name / bank_branch / bank_card_no
--   2) 新建 pj_people_contract（劳动合同/兼职合同/其他；续签=新增记录，历史保留）
--   3) 字典：panjia_employee_gender、panjia_contract_type
--   4) 菜单：基础档案下「合同管理」C 菜单 + 3 个 F 按钮，绑定人事/总监，
--      并补绑 OSS 文件查询/上传按钮（身份证扫描件上传回显依赖）
-- 幂等：全部 ON CONFLICT / IF NOT EXISTS。
-- ============================================================

BEGIN;

-- ---------- 一、员工主数据加列 ----------
ALTER TABLE pj_people_employee ADD COLUMN IF NOT EXISTS gender       VARCHAR(10);  -- 性别（字典 panjia_employee_gender：MALE/FEMALE）
ALTER TABLE pj_people_employee ADD COLUMN IF NOT EXISTS bank_name    VARCHAR(100); -- 开户银行（工资卡）
ALTER TABLE pj_people_employee ADD COLUMN IF NOT EXISTS bank_branch  VARCHAR(200); -- 开户支行（工资卡）
ALTER TABLE pj_people_employee ADD COLUMN IF NOT EXISTS bank_card_no VARCHAR(64);  -- 工资卡号

COMMENT ON COLUMN pj_people_employee.gender       IS '性别（字典 panjia_employee_gender：MALE=男 FEMALE=女）';
COMMENT ON COLUMN pj_people_employee.bank_name    IS '工资卡开户银行';
COMMENT ON COLUMN pj_people_employee.bank_branch  IS '工资卡开户支行';
COMMENT ON COLUMN pj_people_employee.bank_card_no IS '工资卡卡号（本人）';

-- ---------- 二、员工合同表 ----------
CREATE TABLE IF NOT EXISTS pj_people_contract (
    contract_id     BIGINT       PRIMARY KEY,                  -- 雪花 ID
    employee_id     BIGINT       NOT NULL,                     -- 员工 ID
    contract_type   VARCHAR(20)  NOT NULL,                     -- LABOR=劳动合同 PARTTIME=兼职合同 OTHER=其他
    dept_id         BIGINT       NOT NULL,                     -- 签约门店（部门 ID）
    start_date      DATE         NOT NULL,                     -- 合同开始日
    end_date        DATE,                                      -- 合同结束日（NULL=无固定期限）
    id_card_oss_ids VARCHAR(500),                              -- 身份证扫描件 ossId 串（逗号分隔）
    remark          VARCHAR(500),
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_contract_emp_type_start ON pj_people_contract(employee_id, contract_type, start_date);
CREATE INDEX IF NOT EXISTS idx_contract_emp ON pj_people_contract(employee_id);
CREATE INDEX IF NOT EXISTS idx_contract_end ON pj_people_contract(end_date);

COMMENT ON TABLE  pj_people_contract                 IS '员工合同（劳动合同/兼职合同/其他；续签=新增记录，历史保留）';
COMMENT ON COLUMN pj_people_contract.contract_type   IS 'LABOR=劳动合同 PARTTIME=兼职合同 OTHER=其他（字典 panjia_contract_type）';
COMMENT ON COLUMN pj_people_contract.dept_id         IS '签约门店（部门 ID）';
COMMENT ON COLUMN pj_people_contract.end_date        IS '合同结束日（NULL=无固定期限）';
COMMENT ON COLUMN pj_people_contract.id_card_oss_ids IS '身份证扫描件 sys_oss.oss_id 串（逗号分隔）';

-- ---------- 三、字典 ----------
-- 性别
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000005, '员工性别', 'panjia_employee_gender', 1761000000000000100, 1761100000000000001, now(), '员工性别（男/女）')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761600000000011001, 1, '男', 'MALE',   'panjia_employee_gender', 'primary', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000011002, 2, '女', 'FEMALE', 'panjia_employee_gender', 'danger',  'N', 1761000000000000100, 1761100000000000001, now())
ON CONFLICT (dict_code) DO NOTHING;

-- 合同类型
INSERT INTO sys_dict_type (dict_id, dict_name, dict_type, create_dept, create_by, create_time, remark)
VALUES (1761600000000000006, '员工合同类型', 'panjia_contract_type', 1761000000000000100, 1761100000000000001, now(), '员工合同类型（劳动合同/兼职合同/其他）')
ON CONFLICT (dict_type) DO NOTHING;

INSERT INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, list_class, is_default, create_dept, create_by, create_time)
VALUES
(1761600000000012001, 1, '劳动合同', 'LABOR',    'panjia_contract_type', 'primary', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000012002, 2, '兼职合同', 'PARTTIME', 'panjia_contract_type', 'warning', 'N', 1761000000000000100, 1761100000000000001, now()),
(1761600000000012003, 3, '其他',     'OTHER',    'panjia_contract_type', 'info',    'N', 1761000000000000100, 1761100000000000001, now())
ON CONFLICT (dict_code) DO NOTHING;

-- ---------- 四、菜单（2800 段；parent=基础档案目录 1761400000000002000） ----------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark)
VALUES (1761400000000002800, '合同管理', 1761400000000002000, 5, 'contract', 'people/contract/index', NULL, 'N', 'Y', 'C', '0', '0', 'people:contract:list', 'documentation', '', '', 1761000000000000100, 1761100000000000001, now(), '员工合同管理（劳动合同/兼职合同/其他；打印合同、身份证扫描件、到期30天提醒）')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark)
VALUES (1761400000000002801, '合同新增', 1761400000000002800, 1, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:contract:add', '#', '', '', 1761000000000000100, 1761100000000000001, now(), '合同管理-新增按钮')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark)
VALUES (1761400000000002802, '合同修改', 1761400000000002800, 2, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:contract:edit', '#', '', '', 1761000000000000100, 1761100000000000001, now(), '合同管理-修改按钮')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, remark)
VALUES (1761400000000002803, '合同删除', 1761400000000002800, 3, '', '', NULL, 'N', 'Y', 'F', '0', '0', 'people:contract:delete', '#', '', '', 1761000000000000100, 1761100000000000001, now(), '合同管理-删除按钮（错录修正）')
ON CONFLICT (menu_id) DO NOTHING;

-- ---------- 五、角色绑定（人事/总监全权；含 OSS 文件查询/上传按钮，扫描件上传回显依赖） ----------
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES
(1761300000000000013, 1761400000000002800),  -- 人事-合同管理
(1761300000000000013, 1761400000000002801),  -- 人事-合同新增
(1761300000000000013, 1761400000000002802),  -- 人事-合同修改
(1761300000000000013, 1761400000000002803),  -- 人事-合同删除
(1761300000000000010, 1761400000000002800),  -- 总监-合同管理
(1761300000000000010, 1761400000000002801),  -- 总监-合同新增
(1761300000000000010, 1761400000000002802),  -- 总监-合同修改
(1761300000000000010, 1761400000000002803),  -- 总监-合同删除
(1761300000000000013, 1761400000000001600),  -- 人事-文件查询（system:oss:query，扫描件回显）
(1761300000000000013, 1761400000000001601),  -- 人事-文件上传（system:oss:upload）
(1761300000000000010, 1761400000000001600),  -- 总监-文件查询
(1761300000000000010, 1761400000000001601)   -- 总监-文件上传
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
