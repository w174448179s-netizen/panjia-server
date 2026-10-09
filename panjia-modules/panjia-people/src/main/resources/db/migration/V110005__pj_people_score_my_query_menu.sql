-- ========== 积分查询菜单（综合查询→积分查询） ==========
-- 数据权限：超管/总监全量；店长本门店子树；员工/经纪人/人事等仅本人
-- 接口：GET /people/score/my/list（people:score:my:query）
-- 前端：people/score/my/index.vue

-- 菜单：积分查询（综合查询 2700 下，考勤查询 2702 后，order=4）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002703, '积分查询', 1761400000000002700, 4, 'my-score', 'people/score/my', NULL, 'N', 'Y', 'C', '0', '0', 'people:score:my:query', 'star', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '积分查询（员工本人/店长本店/总监全量，含平均积分/等级/扣点/扣款）');

-- 角色授权：与考勤查询(2702)同口径，全员可见
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000010, 1761400000000002703),
(1761300000000000011, 1761400000000002703),
(1761300000000000012, 1761400000000002703),
(1761300000000000013, 1761400000000002703),
(1761300000000000014, 1761400000000002703);
