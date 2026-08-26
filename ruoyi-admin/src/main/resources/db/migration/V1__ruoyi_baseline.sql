-- ================================================================
-- Flyway 基线脚本：RuoYi-Vue-Plus v6.0.0 PostgreSQL 全量初始化
-- 版本：V1（不可变，不可修改）
-- 来源：script/sql/postgres/ 合并
-- 方言：PostgreSQL 15+
-- ================================================================

-- ========== postgres_ry_vue.sql（核心系统表）==========
-- ----------------------------
-- 第三方平台授权表
-- ----------------------------
create table sys_social
(
    id                 int8             not null,
    user_id            int8             not null,
    auth_id            varchar(255)     not null,
    source             varchar(255)     not null,
    open_id            varchar(255)     default null::varchar,
    user_name          varchar(30)      not null,
    nick_name          varchar(30)      default ''::varchar,
    email              varchar(255)     default ''::varchar,
    avatar             varchar(500)     default ''::varchar,
    access_token       varchar(2000)    not null,
    expire_in          int8             default null,
    refresh_token      varchar(2000)    default null::varchar,
    access_code        varchar(255)     default null::varchar,
    union_id           varchar(255)     default null::varchar,
    scope              varchar(255)     default null::varchar,
    token_type         varchar(255)     default null::varchar,
    id_token           varchar(2000)    default null::varchar,
    mac_algorithm      varchar(255)     default null::varchar,
    mac_key            varchar(255)     default null::varchar,
    code               varchar(255)     default null::varchar,
    oauth_token        varchar(255)     default null::varchar,
    oauth_token_secret varchar(255)     default null::varchar,
    create_dept        int8,
    create_by          int8,
    create_time        timestamp,
    update_by          int8,
    update_time        timestamp,
    del_flag           char             default '0'::bpchar,
    constraint "pk_sys_social" primary key (id)
);

comment on table   sys_social                   is '社会化关系表';
comment on column  sys_social.id                is '主键';
comment on column  sys_social.user_id           is '用户ID';
comment on column  sys_social.auth_id           is '平台+平台唯一id';
comment on column  sys_social.source            is '用户来源';
comment on column  sys_social.open_id           is '平台编号唯一id';
comment on column  sys_social.user_name         is '登录账号';
comment on column  sys_social.nick_name         is '用户昵称';
comment on column  sys_social.email             is '用户邮箱';
comment on column  sys_social.avatar            is '头像地址';
comment on column  sys_social.access_token      is '用户的授权令牌';
comment on column  sys_social.expire_in         is '用户的授权令牌的有效期，部分平台可能没有';
comment on column  sys_social.refresh_token     is '刷新令牌，部分平台可能没有';
comment on column  sys_social.access_code       is '平台的授权信息，部分平台可能没有';
comment on column  sys_social.union_id          is '用户的 unionid';
comment on column  sys_social.scope             is '授予的权限，部分平台可能没有';
comment on column  sys_social.token_type        is '个别平台的授权信息，部分平台可能没有';
comment on column  sys_social.id_token          is 'id token，部分平台可能没有';
comment on column  sys_social.mac_algorithm     is '小米平台用户的附带属性，部分平台可能没有';
comment on column  sys_social.mac_key           is '小米平台用户的附带属性，部分平台可能没有';
comment on column  sys_social.code              is '用户的授权code，部分平台可能没有';
comment on column  sys_social.oauth_token       is 'Twitter平台用户的附带属性，部分平台可能没有';
comment on column  sys_social.oauth_token_secret is 'Twitter平台用户的附带属性，部分平台可能没有';
comment on column  sys_social.create_dept       is '创建部门';
comment on column  sys_social.create_by         is '创建者';
comment on column  sys_social.create_time       is '创建时间';
comment on column  sys_social.update_by         is '更新者';
comment on column  sys_social.update_time       is '更新时间';
comment on column  sys_social.del_flag          is '删除标志（0代表存在 1代表删除）';

-- ----------------------------
-- 1、部门表
-- ----------------------------
create table if not exists sys_dept
(
    dept_id     int8,
    parent_id   int8        default 0,
    ancestors   varchar(500)default ''::varchar,
    dept_name   varchar(30) default ''::varchar,
    dept_category varchar(100) default null::varchar,
    order_num   int4        default 0,
    leader      int8        default null,
    phone       varchar(11) default null::varchar,
    email       varchar(50) default null::varchar,
    status      char        default '0'::bpchar,
    del_flag    char        default '0'::bpchar,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    constraint "sys_dept_pk" primary key (dept_id)
);

create index idx_sys_dept_parent_id ON sys_dept (parent_id);

comment on table sys_dept               is '部门表';
comment on column sys_dept.dept_id      is '部门ID';
comment on column sys_dept.parent_id    is '父部门ID';
comment on column sys_dept.ancestors    is '祖级列表';
comment on column sys_dept.dept_name    is '部门名称';
comment on column sys_dept.dept_category    is '部门类别编码';
comment on column sys_dept.order_num    is '显示顺序';
comment on column sys_dept.leader       is '负责人';
comment on column sys_dept.phone        is '联系电话';
comment on column sys_dept.email        is '邮箱';
comment on column sys_dept.status       is '部门状态（0正常 1停用）';
comment on column sys_dept.del_flag     is '删除标志（0代表存在 1代表删除）';
comment on column sys_dept.create_dept  is '创建部门';
comment on column sys_dept.create_by    is '创建者';
comment on column sys_dept.create_time  is '创建时间';
comment on column sys_dept.update_by    is '更新者';
comment on column sys_dept.update_time  is '更新时间';

-- ----------------------------
-- 初始化-部门表数据
-- ----------------------------
insert into sys_dept values(1761000000000000100, 0, '0', 'XXX科技', null, 0, null, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);
insert into sys_dept values(1761000000000000101, 1761000000000000100, '0,1761000000000000100', '深圳总公司', null, 1, null, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);
insert into sys_dept values(1761000000000000102, 1761000000000000100, '0,1761000000000000100', '长沙分公司', null, 2, null, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);
insert into sys_dept values(1761000000000000103, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '研发部门', null, 1, 1761100000000000001, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);
insert into sys_dept values(1761000000000000104, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '市场部门', null, 2, null, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);
insert into sys_dept values(1761000000000000105, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '测试部门', null, 3, null, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);
insert into sys_dept values(1761000000000000106, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '财务部门', null, 4, null, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);
insert into sys_dept values(1761000000000000107, 1761000000000000101, '0,1761000000000000100,1761000000000000101', '运维部门', null, 5, null, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);
insert into sys_dept values(1761000000000000108, 1761000000000000102, '0,1761000000000000100,1761000000000000102', '市场部门', null, 1, null, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);
insert into sys_dept values(1761000000000000109, 1761000000000000102, '0,1761000000000000100,1761000000000000102', '财务部门', null, 2, null, '15888888888', 'xxx@qq.com', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null);

-- ----------------------------
-- 2、用户信息表
-- ----------------------------
create table if not exists sys_user
(
    user_id     int8,
    dept_id     int8,
    user_name   varchar(30)  not null,
    nick_name   varchar(30)  not null,
    user_type   varchar(10)  default 'sys_user'::varchar,
    email       varchar(50)  default ''::varchar,
    phone_number varchar(11) default ''::varchar,
    gender      char         default '0'::bpchar,
    avatar      int8,
    password    varchar(100) default ''::varchar,
    status      char         default '0'::bpchar,
    del_flag    char         default '0'::bpchar,
    login_ip    varchar(128) default ''::varchar,
    login_date  timestamp,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default null::varchar,
    constraint "sys_user_pk" primary key (user_id)
);

create index idx_sys_user_dept_id ON sys_user (dept_id);
create index idx_sys_user_create_by ON sys_user (create_by);
create index idx_sys_user_user_name ON sys_user (user_name);
create index idx_sys_user_phone ON sys_user (phone_number);

comment on table sys_user               is '用户信息表';
comment on column sys_user.user_id      is '用户ID';
comment on column sys_user.dept_id      is '部门ID';
comment on column sys_user.user_name    is '用户账号';
comment on column sys_user.nick_name    is '用户昵称';
comment on column sys_user.user_type    is '用户类型（sys_user系统用户）';
comment on column sys_user.email        is '用户邮箱';
comment on column sys_user.phone_number is '手机号码';
comment on column sys_user.gender       is '用户性别（0男 1女 2未知）';
comment on column sys_user.avatar       is '头像地址';
comment on column sys_user.password     is '密码';
comment on column sys_user.status       is '账号状态（0正常 1停用）';
comment on column sys_user.del_flag     is '删除标志（0代表存在 1代表删除）';
comment on column sys_user.login_ip     is '最后登陆IP';
comment on column sys_user.login_date   is '最后登陆时间';
comment on column sys_user.create_dept  is '创建部门';
comment on column sys_user.create_by    is '创建者';
comment on column sys_user.create_time  is '创建时间';
comment on column sys_user.update_by    is '更新者';
comment on column sys_user.update_time  is '更新时间';
comment on column sys_user.remark       is '备注';

-- ----------------------------

-- 初始化-用户信息表数据
-- ----------------------------
insert into sys_user values(1761100000000000001, 1761000000000000103, 'admin', '疯狂的狮子Li', 'sys_user', 'crazyLionLi@163.com', '15888888888', '1', null, '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '0', '0', '127.0.0.1', now(), 1761000000000000103, 1761100000000000001, now(), null, null, '管理员');
insert into sys_user VALUES(1761100000000000003, 1761000000000000108, 'test', '本部门及以下 密码666666', 'sys_user', '', '', '0', null, '$2a$10$b8yUzN0C71sbz.PhNOCgJe.Tu1yWC3RNrTyjSQ8p1W0.aaUXUJ.Ne', '0', '0', '127.0.0.1', now(), 1761000000000000103, 1761100000000000001, now(), 1761100000000000003, now(), NULL);
insert into sys_user VALUES(1761100000000000004, 1761000000000000102, 'test1', '仅本人 密码666666', 'sys_user', '', '', '0', null, '$2a$10$b8yUzN0C71sbz.PhNOCgJe.Tu1yWC3RNrTyjSQ8p1W0.aaUXUJ.Ne', '0', '0', '127.0.0.1', now(), 1761000000000000103, 1761100000000000001, now(), 1761100000000000004, now(), NULL);

-- ----------------------------
-- 3、岗位信息表
-- ----------------------------
create table if not exists sys_post
(
    post_id     int8,
    dept_id     int8,
    post_code   varchar(64) not null,
    post_category   varchar(100) default null,
    post_name   varchar(50) not null,
    post_sort   int4        not null,
    status      char        not null,
    del_flag    char        default '0'::bpchar,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default null::varchar,
    constraint "sys_post_pk" primary key (post_id)
);

create index idx_sys_post_dept_id ON sys_post (dept_id);

comment on table sys_post               is '岗位信息表';
comment on column sys_post.post_id      is '岗位ID';
comment on column sys_post.dept_id      is '部门id';
comment on column sys_post.post_code    is '岗位编码';
comment on column sys_post.post_category is '岗位类别编码';
comment on column sys_post.post_name    is '岗位名称';
comment on column sys_post.post_sort    is '显示顺序';
comment on column sys_post.status       is '状态（0正常 1停用）';
comment on column sys_post.del_flag     is '删除标志（0代表存在 1代表删除）';
comment on column sys_post.create_dept  is '创建部门';
comment on column sys_post.create_by    is '创建者';
comment on column sys_post.create_time  is '创建时间';
comment on column sys_post.update_by    is '更新者';
comment on column sys_post.update_time  is '更新时间';
comment on column sys_post.remark       is '备注';

-- ----------------------------
-- 初始化-岗位信息表数据
-- ----------------------------
insert into sys_post values(1761200000000000001, 1761000000000000103, 'ceo', null, '董事长', 1, '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_post values(1761200000000000002, 1761000000000000100, 'se', null, '项目经理', 2, '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_post values(1761200000000000003, 1761000000000000100, 'hr', null, '人力资源', 3, '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_post values(1761200000000000004, 1761000000000000100, 'user', null, '普通员工', 4, '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null, '');

-- ----------------------------
-- 4、角色信息表
-- ----------------------------
create table if not exists sys_role
(
    role_id             int8,
    role_name           varchar(30)  not null,
    role_key            varchar(100) not null,
    role_sort           int4         not null,
    data_scope          char         default '1'::bpchar,
    menu_check_strictly bool         default true,
    dept_check_strictly bool         default true,
    status              char         not null,
    del_flag            char         default '0'::bpchar,
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    remark              varchar(500) default null::varchar,
    constraint "sys_role_pk" primary key (role_id)
);

create index idx_sys_role_create_dept ON sys_role (create_dept);
create index idx_sys_role_create_by ON sys_role (create_by);

comment on table sys_role                       is '角色信息表';
comment on column sys_role.role_id              is '角色ID';
comment on column sys_role.role_name            is '角色名称';
comment on column sys_role.role_key             is '角色权限字符串';
comment on column sys_role.role_sort            is '显示顺序';
comment on column sys_role.data_scope           is '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限 5：仅本人数据权限 6：部门及以下或本人数据权限）';
comment on column sys_role.menu_check_strictly  is '菜单树选择项是否关联显示';
comment on column sys_role.dept_check_strictly  is '部门树选择项是否关联显示';
comment on column sys_role.status               is '角色状态（0正常 1停用）';
comment on column sys_role.del_flag             is '删除标志（0代表存在 1代表删除）';
comment on column sys_role.create_dept          is '创建部门';
comment on column sys_role.create_by            is '创建者';
comment on column sys_role.create_time          is '创建时间';
comment on column sys_role.update_by            is '更新者';
comment on column sys_role.update_time          is '更新时间';
comment on column sys_role.remark               is '备注';

-- ----------------------------
-- 初始化-角色信息表数据
-- ----------------------------
insert into sys_role values(1761300000000000001, '超级管理员', 'superadmin', 1, '1', 't', 't', '0', '0', 1761000000000000103, 1761100000000000001, now(), null, null, '超级管理员');
insert into sys_role values(1761300000000000003, '本部门及以下', 'test1', 3, '4', 't', 't', '0', '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_role values(1761300000000000004, '仅本人', 'test2', 4, '5', 't', 't', '0', '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');

-- ----------------------------
-- 5、菜单权限表
-- ----------------------------
create table if not exists sys_menu
(
    menu_id     int8,
    menu_name   varchar(50) not null,
    parent_id   int8         default 0,
    order_num   int4         default 0,
    path        varchar(200) default ''::varchar,
    component   varchar(255) default null::varchar,
    query_param varchar(255) default null::varchar,
    is_frame    char         default 'N'::bpchar,
    is_cache    char         default 'Y'::bpchar,
    menu_type   char         default ''::bpchar,
    visible     char         default '0'::bpchar,
    status      char         default '0'::bpchar,
    perms       varchar(100) default null::varchar,
    icon        varchar(100) default '#'::varchar,
    active_menu varchar(255) default ''::varchar,
    ext         varchar(2000) default ''::varchar,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default ''::varchar,
    constraint "sys_menu_pk" primary key (menu_id)
);

comment on table sys_menu               is '菜单权限表';
comment on column sys_menu.menu_id      is '菜单ID';
comment on column sys_menu.menu_name    is '菜单名称';
comment on column sys_menu.parent_id    is '父菜单ID';
comment on column sys_menu.order_num    is '显示顺序';
comment on column sys_menu.path         is '路由地址';
comment on column sys_menu.component    is '组件路径';
comment on column sys_menu.query_param  is '路由参数';
comment on column sys_menu.is_frame     is '是否为外链（Y是 N否）';
comment on column sys_menu.is_cache     is '是否缓存（Y缓存 N不缓存）';
comment on column sys_menu.menu_type    is '菜单类型（M目录 C菜单 F按钮）';
comment on column sys_menu.visible      is '显示状态（0显示 1隐藏）';
comment on column sys_menu.status       is '菜单状态（0正常 1停用）';
comment on column sys_menu.perms        is '权限标识';
comment on column sys_menu.icon         is '菜单图标';
comment on column sys_menu.create_dept  is '创建部门';
comment on column sys_menu.create_by    is '创建者';
comment on column sys_menu.create_time  is '创建时间';
comment on column sys_menu.update_by    is '更新者';
comment on column sys_menu.update_time  is '更新时间';
comment on column sys_menu.active_menu  is '激活菜单路径';
comment on column sys_menu.ext          is '扩展字段';
comment on column sys_menu.remark       is '备注';

-- ----------------------------
-- 初始化-菜单信息表数据
-- ----------------------------
-- 一级菜单
insert into sys_menu values(1761400000000000001, '系统管理', 0, 1, 'system', null, '', 'N', 'Y', 'M', '0', '0', '', 'system', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '系统管理目录');
insert into sys_menu values(1761400000000000002, '系统监控', 0, 3, 'monitor', null, '', 'N', 'Y', 'M', '0', '0', '', 'monitor', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '系统监控目录');
insert into sys_menu values(1761400000000000003, '系统工具', 0, 4, 'tool', null, '', 'N', 'Y', 'M', '0', '0', '', 'tool', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '系统工具目录');
insert into sys_menu values(1761400000000000005, '测试菜单', 0, 5, 'demo', null, '', 'N', 'Y', 'M', '0', '0', null, 'star', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '测试菜单');
insert into sys_menu values(1761400000000000006, 'AI会话',  0, 8, 'aichat', 'ai/chat/index', '', 'N', 'Y', 'C', '0', '0', '', 'checkbox', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, 'AI聊天菜单');
insert into sys_menu values(1761400000000000004, 'PLUS官网', 0, 9, 'https://gitee.com/dromara/RuoYi-Vue-Plus', null, '', 'Y', 'Y', 'M', '0', '0', '', 'guide', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, 'RuoYi-Vue-Plus官网地址');
-- 二级菜单
insert into sys_menu values(1761400000000000100, '用户管理', 1761400000000000001, 1, 'user', 'system/user/index', '', 'N', 'Y', 'C', '0', '0', 'system:user:list', 'user', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '用户管理菜单');
insert into sys_menu values(1761400000000000101, '角色管理', 1761400000000000001, 2, 'role', 'system/role/index', '', 'N', 'Y', 'C', '0', '0', 'system:role:list', 'peoples', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '角色管理菜单');
insert into sys_menu values(1761400000000000102, '菜单管理', 1761400000000000001, 3, 'menu', 'system/menu/index', '', 'N', 'Y', 'C', '0', '0', 'system:menu:list', 'tree-table', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '菜单管理菜单');
insert into sys_menu values(1761400000000000103, '部门管理', 1761400000000000001, 4, 'dept', 'system/dept/index', '', 'N', 'Y', 'C', '0', '0', 'system:dept:list', 'tree', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '部门管理菜单');
insert into sys_menu values(1761400000000000104, '岗位管理', 1761400000000000001, 5, 'post', 'system/post/index', '', 'N', 'Y', 'C', '0', '0', 'system:post:list', 'post', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '岗位管理菜单');
insert into sys_menu values(1761400000000000105, '字典管理', 1761400000000000001, 6, 'dict', 'system/dict/index', '', 'N', 'Y', 'C', '0', '0', 'system:dict:list', 'dict', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '字典管理菜单');
insert into sys_menu values(1761400000000000106, '参数设置', 1761400000000000001, 7, 'config', 'system/config/index', '', 'N', 'Y', 'C', '0', '0', 'system:config:list', 'edit', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '参数设置菜单');
insert into sys_menu values(1761400000000000107, '通知公告', 1761400000000000001, 8, 'notice', 'system/notice/index', '', 'N', 'Y', 'C', '0', '0', 'system:notice:list', 'message', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '通知公告菜单');
insert into sys_menu values(1761400000000000108, '日志管理', 1761400000000000001, 9, 'log', '', '', 'N', 'Y', 'M', '0', '0', '', 'log', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '日志管理菜单');
insert into sys_menu values(1761400000000000109, '在线用户', 1761400000000000002, 1, 'online', 'monitor/online/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:online:list', 'online', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '在线用户菜单');
insert into sys_menu values(1761400000000000113, '缓存监控', 1761400000000000002, 5, 'cache', 'monitor/cache/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:cache:list', 'redis', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '缓存监控菜单');
insert into sys_menu values(1761400000000000115, '代码生成', 1761400000000000003, 2, 'gen', 'tool/gen/index', '', 'N', 'Y', 'C', '0', '0', 'tool:gen:list', 'code', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '代码生成菜单');
insert into sys_menu values(1761400000000000123, '客户端管理', 1761400000000000001, 11, 'client', 'system/client/index', '', 'N', 'Y', 'C', '0', '0', 'system:client:list', 'international', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '客户端管理菜单');
insert into sys_menu values(1761400000000000116, '修改生成配置', 1761400000000000003, 2, 'gen-edit/index/:tableId', 'tool/gen/editTable', '', 'N', 'N', 'C', '1', '0', 'tool:gen:edit', '#', '/tool/gen', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000000130, '分配用户', 1761400000000000001, 2, 'role-auth/user/:roleId', 'system/role/authUser', '', 'N', 'N', 'C', '1', '0', 'system:role:edit', '#', '/system/role', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000000131, '分配角色', 1761400000000000001, 1, 'user-auth/role/:userId', 'system/user/authRole', '', 'N', 'N', 'C', '1', '0', 'system:user:edit', '#', '/system/user', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000000133, '文件配置管理', 1761400000000000001, 10, 'oss-config/index', 'system/oss/config', '', 'N', 'N', 'C', '1', '0', 'system:ossConfig:list', '#', '/system/oss', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');

-- springboot-admin监控
insert into sys_menu values(1761400000000000117, 'Admin监控', 1761400000000000002, 5, 'Admin', 'monitor/admin/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:admin:list', 'dashboard', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, 'Admin监控菜单');
-- oss菜单
insert into sys_menu values(1761400000000000118, '文件管理', 1761400000000000001, 10, 'oss', 'system/oss/index', '', 'N', 'Y', 'C', '0', '0', 'system:oss:list', 'upload', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '文件管理菜单');
-- snail-job server控制台
insert into sys_menu values(1761400000000000120, '任务调度中心', 1761400000000000002, 6, 'snailjob', 'monitor/snailjob/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:snailjob:list', 'job', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, 'SnailJob控制台菜单');
-- snail-ai server控制台
insert into sys_menu values(1761400000000000121, 'AI控制台', 1761400000000000002, 7, 'snailai', 'monitor/snailai/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:snailai:list', 'checkbox', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, 'AI控制台菜单');

-- 三级菜单
insert into sys_menu values(1761400000000000500, '操作日志', 1761400000000000108, 1, 'operlog', 'monitor/operlog/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:operlog:list', 'form', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '操作日志菜单');
insert into sys_menu values(1761400000000000501, '登录日志', 1761400000000000108, 2, 'logininfo', 'monitor/logininfo/index', '', 'N', 'Y', 'C', '0', '0', 'monitor:logininfo:list', 'logininfo', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '登录日志菜单');
-- 用户管理按钮
insert into sys_menu values(1761400000000001001, '用户查询', 1761400000000000100, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001002, '用户新增', 1761400000000000100, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001003, '用户修改', 1761400000000000100, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001004, '用户删除', 1761400000000000100, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001005, '用户导出', 1761400000000000100, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001006, '用户导入', 1761400000000000100, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:import', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001007, '重置密码', 1761400000000000100, 7, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:user:resetPwd', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 角色管理按钮
insert into sys_menu values(1761400000000001008, '角色查询', 1761400000000000101, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001009, '角色新增', 1761400000000000101, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001010, '角色修改', 1761400000000000101, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001011, '角色删除', 1761400000000000101, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001012, '角色导出', 1761400000000000101, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:role:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 菜单管理按钮
insert into sys_menu values(1761400000000001013, '菜单查询', 1761400000000000102, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:menu:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001014, '菜单新增', 1761400000000000102, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:menu:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001015, '菜单修改', 1761400000000000102, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:menu:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001016, '菜单删除', 1761400000000000102, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:menu:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 部门管理按钮
insert into sys_menu values(1761400000000001017, '部门查询', 1761400000000000103, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001018, '部门新增', 1761400000000000103, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001019, '部门修改', 1761400000000000103, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001020, '部门删除', 1761400000000000103, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:dept:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 岗位管理按钮
insert into sys_menu values(1761400000000001021, '岗位查询', 1761400000000000104, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001022, '岗位新增', 1761400000000000104, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001023, '岗位修改', 1761400000000000104, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001024, '岗位删除', 1761400000000000104, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001025, '岗位导出', 1761400000000000104, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'system:post:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 字典管理按钮
insert into sys_menu values(1761400000000001026, '字典查询', 1761400000000000105, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001027, '字典新增', 1761400000000000105, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001028, '字典修改', 1761400000000000105, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001029, '字典删除', 1761400000000000105, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001030, '字典导出', 1761400000000000105, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:dict:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 参数设置按钮
insert into sys_menu values(1761400000000001031, '参数查询', 1761400000000000106, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001032, '参数新增', 1761400000000000106, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001033, '参数修改', 1761400000000000106, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001034, '参数删除', 1761400000000000106, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001035, '参数导出', 1761400000000000106, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:config:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 通知公告按钮
insert into sys_menu values(1761400000000001036, '公告查询', 1761400000000000107, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:notice:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001037, '公告新增', 1761400000000000107, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:notice:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001038, '公告修改', 1761400000000000107, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:notice:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001039, '公告删除', 1761400000000000107, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:notice:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 操作日志按钮
insert into sys_menu values(1761400000000001040, '操作查询', 1761400000000000500, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:operlog:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001041, '操作删除', 1761400000000000500, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:operlog:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001042, '日志导出', 1761400000000000500, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:operlog:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 登录日志按钮
insert into sys_menu values(1761400000000001043, '登录查询', 1761400000000000501, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:logininfo:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001044, '登录删除', 1761400000000000501, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:logininfo:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001045, '日志导出', 1761400000000000501, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:logininfo:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001050, '账户解锁', 1761400000000000501, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:logininfo:unlock', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 在线用户按钮
insert into sys_menu values(1761400000000001046, '在线查询', 1761400000000000109, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:online:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001047, '批量强退', 1761400000000000109, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:online:batchLogout', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001048, '单条强退', 1761400000000000109, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'monitor:online:forceLogout', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 代码生成按钮
insert into sys_menu values(1761400000000001055, '生成查询', 1761400000000000115, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001056, '生成修改', 1761400000000000115, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001057, '生成删除', 1761400000000000115, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001058, '导入代码', 1761400000000000115, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:import', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001059, '预览代码', 1761400000000000115, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:preview', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001060, '生成代码', 1761400000000000115, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'tool:gen:code', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- oss相关按钮
insert into sys_menu values(1761400000000001600, '文件查询', 1761400000000000118, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:oss:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001601, '文件上传', 1761400000000000118, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:oss:upload', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001602, '文件下载', 1761400000000000118, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:oss:download', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001603, '文件删除', 1761400000000000118, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:oss:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001620, '配置列表', 1761400000000000118, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:ossConfig:list', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001621, '配置添加', 1761400000000000118, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:ossConfig:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001622, '配置编辑', 1761400000000000118, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:ossConfig:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001623, '配置删除', 1761400000000000118, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:ossConfig:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 客户端管理按钮
insert into sys_menu values(1761400000000001061, '客户端管理查询', 1761400000000000123, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001062, '客户端管理新增', 1761400000000000123, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001063, '客户端管理修改', 1761400000000000123, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001064, '客户端管理删除', 1761400000000000123, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
insert into sys_menu values(1761400000000001065, '客户端管理导出', 1761400000000000123, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'system:client:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), null, null, '');
-- 测试菜单
insert into sys_menu values(1761400000000001500, '测试单表', 1761400000000000005, 1, 'demo', 'demo/demo/index', '', 'N', 'Y', 'C', '0', '0', 'demo:demo:list', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '测试单表菜单');
insert into sys_menu values(1761400000000001501, '测试单表查询', 1761400000000001500, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_menu values(1761400000000001502, '测试单表新增', 1761400000000001500, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_menu values(1761400000000001503, '测试单表修改', 1761400000000001500, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_menu values(1761400000000001504, '测试单表删除', 1761400000000001500, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_menu values(1761400000000001505, '测试单表导出', 1761400000000001500, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:demo:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_menu values(1761400000000001506, '测试树表', 1761400000000000005, 1, 'tree', 'demo/tree/index', '', 'N', 'Y', 'C', '0', '0', 'demo:tree:list', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '测试树表菜单');
insert into sys_menu values(1761400000000001507, '测试树表查询', 1761400000000001506, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_menu values(1761400000000001508, '测试树表新增', 1761400000000001506, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_menu values(1761400000000001509, '测试树表修改', 1761400000000001506, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_menu values(1761400000000001510, '测试树表删除', 1761400000000001506, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
insert into sys_menu values(1761400000000001511, '测试树表导出', 1761400000000001506, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'demo:tree:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');


-- ----------------------------
-- 6、用户和角色关联表  用户N-1角色
-- ----------------------------
create table if not exists sys_user_role
(
    user_id int8 not null,
    role_id int8 not null,
    constraint sys_user_role_pk primary key (user_id, role_id)
);

create index idx_sys_user_role_rid ON sys_user_role (role_id);

comment on table sys_user_role              is '用户和角色关联表';
comment on column sys_user_role.user_id     is '用户ID';
comment on column sys_user_role.role_id     is '角色ID';

-- ----------------------------
-- 初始化-用户和角色关联表数据
-- ----------------------------
insert into sys_user_role values (1761100000000000001, 1761300000000000001);
insert into sys_user_role values (1761100000000000003, 1761300000000000003);
insert into sys_user_role values (1761100000000000004, 1761300000000000004);

-- ----------------------------
-- 7、角色和菜单关联表  角色1-N菜单
-- ----------------------------
create table if not exists sys_role_menu
(
    role_id int8 not null,
    menu_id int8 not null,
    constraint sys_role_menu_pk primary key (role_id, menu_id)
);

comment on table sys_role_menu              is '角色和菜单关联表';
comment on column sys_role_menu.role_id     is '角色ID';
comment on column sys_role_menu.menu_id     is '菜单ID';

-- ----------------------------
-- 初始化-角色和菜单关联表数据
-- ----------------------------
insert into sys_role_menu values (1761300000000000003, 1761400000000000001);
insert into sys_role_menu values (1761300000000000003, 1761400000000000005);
insert into sys_role_menu values (1761300000000000003, 1761400000000000100);
insert into sys_role_menu values (1761300000000000003, 1761400000000000101);
insert into sys_role_menu values (1761300000000000003, 1761400000000000102);
insert into sys_role_menu values (1761300000000000003, 1761400000000000103);
insert into sys_role_menu values (1761300000000000003, 1761400000000000104);
insert into sys_role_menu values (1761300000000000003, 1761400000000000105);
insert into sys_role_menu values (1761300000000000003, 1761400000000000106);
insert into sys_role_menu values (1761300000000000003, 1761400000000000107);
insert into sys_role_menu values (1761300000000000003, 1761400000000000108);
insert into sys_role_menu values (1761300000000000003, 1761400000000000118);
insert into sys_role_menu values (1761300000000000003, 1761400000000000123);
insert into sys_role_menu values (1761300000000000003, 1761400000000000130);
insert into sys_role_menu values (1761300000000000003, 1761400000000000131);
insert into sys_role_menu values (1761300000000000003, 1761400000000000133);
insert into sys_role_menu values (1761300000000000003, 1761400000000000500);
insert into sys_role_menu values (1761300000000000003, 1761400000000000501);
insert into sys_role_menu values (1761300000000000003, 1761400000000001001);
insert into sys_role_menu values (1761300000000000003, 1761400000000001002);
insert into sys_role_menu values (1761300000000000003, 1761400000000001003);
insert into sys_role_menu values (1761300000000000003, 1761400000000001004);
insert into sys_role_menu values (1761300000000000003, 1761400000000001005);
insert into sys_role_menu values (1761300000000000003, 1761400000000001006);
insert into sys_role_menu values (1761300000000000003, 1761400000000001007);
insert into sys_role_menu values (1761300000000000003, 1761400000000001008);
insert into sys_role_menu values (1761300000000000003, 1761400000000001009);
insert into sys_role_menu values (1761300000000000003, 1761400000000001010);
insert into sys_role_menu values (1761300000000000003, 1761400000000001011);
insert into sys_role_menu values (1761300000000000003, 1761400000000001012);
insert into sys_role_menu values (1761300000000000003, 1761400000000001013);
insert into sys_role_menu values (1761300000000000003, 1761400000000001014);
insert into sys_role_menu values (1761300000000000003, 1761400000000001015);
insert into sys_role_menu values (1761300000000000003, 1761400000000001016);
insert into sys_role_menu values (1761300000000000003, 1761400000000001017);
insert into sys_role_menu values (1761300000000000003, 1761400000000001018);
insert into sys_role_menu values (1761300000000000003, 1761400000000001019);
insert into sys_role_menu values (1761300000000000003, 1761400000000001020);
insert into sys_role_menu values (1761300000000000003, 1761400000000001021);
insert into sys_role_menu values (1761300000000000003, 1761400000000001022);
insert into sys_role_menu values (1761300000000000003, 1761400000000001023);
insert into sys_role_menu values (1761300000000000003, 1761400000000001024);
insert into sys_role_menu values (1761300000000000003, 1761400000000001025);
insert into sys_role_menu values (1761300000000000003, 1761400000000001026);
insert into sys_role_menu values (1761300000000000003, 1761400000000001027);
insert into sys_role_menu values (1761300000000000003, 1761400000000001028);
insert into sys_role_menu values (1761300000000000003, 1761400000000001029);
insert into sys_role_menu values (1761300000000000003, 1761400000000001030);
insert into sys_role_menu values (1761300000000000003, 1761400000000001031);
insert into sys_role_menu values (1761300000000000003, 1761400000000001032);
insert into sys_role_menu values (1761300000000000003, 1761400000000001033);
insert into sys_role_menu values (1761300000000000003, 1761400000000001034);
insert into sys_role_menu values (1761300000000000003, 1761400000000001035);
insert into sys_role_menu values (1761300000000000003, 1761400000000001036);
insert into sys_role_menu values (1761300000000000003, 1761400000000001037);
insert into sys_role_menu values (1761300000000000003, 1761400000000001038);
insert into sys_role_menu values (1761300000000000003, 1761400000000001039);
insert into sys_role_menu values (1761300000000000003, 1761400000000001040);
insert into sys_role_menu values (1761300000000000003, 1761400000000001041);
insert into sys_role_menu values (1761300000000000003, 1761400000000001042);
insert into sys_role_menu values (1761300000000000003, 1761400000000001043);
insert into sys_role_menu values (1761300000000000003, 1761400000000001044);
insert into sys_role_menu values (1761300000000000003, 1761400000000001045);
insert into sys_role_menu values (1761300000000000003, 1761400000000001050);
insert into sys_role_menu values (1761300000000000003, 1761400000000001061);
insert into sys_role_menu values (1761300000000000003, 1761400000000001062);
insert into sys_role_menu values (1761300000000000003, 1761400000000001063);
insert into sys_role_menu values (1761300000000000003, 1761400000000001064);
insert into sys_role_menu values (1761300000000000003, 1761400000000001065);
insert into sys_role_menu values (1761300000000000003, 1761400000000001500);
insert into sys_role_menu values (1761300000000000003, 1761400000000001501);
insert into sys_role_menu values (1761300000000000003, 1761400000000001502);
insert into sys_role_menu values (1761300000000000003, 1761400000000001503);
insert into sys_role_menu values (1761300000000000003, 1761400000000001504);
insert into sys_role_menu values (1761300000000000003, 1761400000000001505);
insert into sys_role_menu values (1761300000000000003, 1761400000000001506);
insert into sys_role_menu values (1761300000000000003, 1761400000000001507);
insert into sys_role_menu values (1761300000000000003, 1761400000000001508);
insert into sys_role_menu values (1761300000000000003, 1761400000000001509);
insert into sys_role_menu values (1761300000000000003, 1761400000000001510);
insert into sys_role_menu values (1761300000000000003, 1761400000000001511);
insert into sys_role_menu values (1761300000000000003, 1761400000000001600);
insert into sys_role_menu values (1761300000000000003, 1761400000000001601);
insert into sys_role_menu values (1761300000000000003, 1761400000000001602);
insert into sys_role_menu values (1761300000000000003, 1761400000000001603);
insert into sys_role_menu values (1761300000000000003, 1761400000000001620);
insert into sys_role_menu values (1761300000000000003, 1761400000000001621);
insert into sys_role_menu values (1761300000000000003, 1761400000000001622);
insert into sys_role_menu values (1761300000000000003, 1761400000000001623);
insert into sys_role_menu values (1761300000000000003, 1761400000000011616);
insert into sys_role_menu values (1761300000000000003, 1761400000000011618);
insert into sys_role_menu values (1761300000000000003, 1761400000000011619);
insert into sys_role_menu values (1761300000000000003, 1761400000000011622);
insert into sys_role_menu values (1761300000000000003, 1761400000000011623);
insert into sys_role_menu values (1761300000000000003, 1761400000000011629);
insert into sys_role_menu values (1761300000000000003, 1761400000000011632);
insert into sys_role_menu values (1761300000000000003, 1761400000000011633);
insert into sys_role_menu values (1761300000000000003, 1761400000000011638);
insert into sys_role_menu values (1761300000000000003, 1761400000000011639);
insert into sys_role_menu values (1761300000000000003, 1761400000000011640);
insert into sys_role_menu values (1761300000000000003, 1761400000000011641);
insert into sys_role_menu values (1761300000000000003, 1761400000000011642);
insert into sys_role_menu values (1761300000000000003, 1761400000000011643);
insert into sys_role_menu values (1761300000000000003, 1761400000000011701);
insert into sys_role_menu values (1761300000000000004, 1761400000000000005);
insert into sys_role_menu values (1761300000000000004, 1761400000000001500);
insert into sys_role_menu values (1761300000000000004, 1761400000000001501);
insert into sys_role_menu values (1761300000000000004, 1761400000000001502);
insert into sys_role_menu values (1761300000000000004, 1761400000000001503);
insert into sys_role_menu values (1761300000000000004, 1761400000000001504);
insert into sys_role_menu values (1761300000000000004, 1761400000000001505);
insert into sys_role_menu values (1761300000000000004, 1761400000000001506);
insert into sys_role_menu values (1761300000000000004, 1761400000000001507);
insert into sys_role_menu values (1761300000000000004, 1761400000000001508);
insert into sys_role_menu values (1761300000000000004, 1761400000000001509);
insert into sys_role_menu values (1761300000000000004, 1761400000000001510);
insert into sys_role_menu values (1761300000000000004, 1761400000000001511);

-- ----------------------------
-- 8、角色和部门关联表  角色1-N部门
-- ----------------------------
create table if not exists sys_role_dept
(
    role_id int8 not null,
    dept_id int8 not null,
    constraint sys_role_dept_pk primary key (role_id, dept_id)
);

comment on table sys_role_dept              is '角色和部门关联表';
comment on column sys_role_dept.role_id     is '角色ID';
comment on column sys_role_dept.dept_id     is '部门ID';


-- ----------------------------
-- 9、用户与岗位关联表  用户1-N岗位
-- ----------------------------
create table if not exists sys_user_post
(
    user_id int8 not null,
    post_id int8 not null,
    constraint sys_user_post_pk primary key (user_id, post_id)
);

comment on table sys_user_post              is '用户与岗位关联表';
comment on column sys_user_post.user_id     is '用户ID';
comment on column sys_user_post.post_id     is '岗位ID';

-- ----------------------------
-- 初始化-用户与岗位关联表数据
-- ----------------------------
insert into sys_user_post values (1761100000000000001, 1761200000000000001);

-- ----------------------------
-- 10、操作日志记录
-- ----------------------------
create table if not exists sys_oper_log
(
    oper_id        int8,
    title          varchar(50)   default ''::varchar,
    business_type  int4          default 0,
    method         varchar(100)  default ''::varchar,
    request_method varchar(10)   default ''::varchar,
    operator_type  int4          default 0,
    oper_name      varchar(50)   default ''::varchar,
    user_id        int8,
    dept_id        int8,
    dept_name      varchar(50)   default ''::varchar,
    client_key     varchar(32)   default ''::varchar,
    device_type    varchar(32)   default ''::varchar,
    browser        varchar(50)   default ''::varchar,
    os             varchar(50)   default ''::varchar,
    oper_url       varchar(255)  default ''::varchar,
    oper_ip        varchar(128)  default ''::varchar,
    oper_location  varchar(255)  default ''::varchar,
    oper_param     varchar(4000) default ''::varchar,
    json_result    varchar(4000) default ''::varchar,
    status         int4          default 0,
    error_msg      varchar(4000) default ''::varchar,
    oper_time      timestamp,
    cost_time      int8          default 0,
    constraint sys_oper_log_pk primary key (oper_id)
);

create index idx_sys_oper_log_bt ON sys_oper_log (business_type);
create index idx_sys_oper_log_uid ON sys_oper_log (user_id);
create index idx_sys_oper_log_s ON sys_oper_log (status);
create index idx_sys_oper_log_ot ON sys_oper_log (oper_time);

comment on table sys_oper_log                   is '操作日志记录';
comment on column sys_oper_log.oper_id          is '日志主键';
comment on column sys_oper_log.title            is '模块标题';
comment on column sys_oper_log.business_type    is '业务类型（0其它 1新增 2修改 3删除）';
comment on column sys_oper_log.method           is '方法名称';
comment on column sys_oper_log.request_method   is '请求方式';
comment on column sys_oper_log.operator_type    is '操作类别（0其它 1后台用户 2手机端用户）';
comment on column sys_oper_log.oper_name        is '操作人员';
comment on column sys_oper_log.user_id          is '操作用户ID';
comment on column sys_oper_log.dept_id          is '操作部门ID';
comment on column sys_oper_log.dept_name        is '部门名称';
comment on column sys_oper_log.client_key       is '客户端';
comment on column sys_oper_log.device_type      is '设备类型';
comment on column sys_oper_log.browser          is '浏览器类型';
comment on column sys_oper_log.os               is '操作系统';
comment on column sys_oper_log.oper_url         is '请求URL';
comment on column sys_oper_log.oper_ip          is '主机地址';
comment on column sys_oper_log.oper_location    is '操作地点';
comment on column sys_oper_log.oper_param       is '请求参数';
comment on column sys_oper_log.json_result      is '返回参数';
comment on column sys_oper_log.status           is '操作状态（0正常 1异常）';
comment on column sys_oper_log.error_msg        is '错误消息';
comment on column sys_oper_log.oper_time        is '操作时间';
comment on column sys_oper_log.cost_time        is '消耗时间';

-- ----------------------------
-- 11、字典类型表
-- ----------------------------
create table if not exists sys_dict_type
(
    dict_id     int8,
    dict_name   varchar(100) default ''::varchar,
    dict_type   varchar(100) default ''::varchar,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default null::varchar,
    constraint sys_dict_type_pk primary key (dict_id)
);

create unique index sys_dict_type_index1 ON sys_dict_type (dict_type);

comment on table sys_dict_type                  is '字典类型表';
comment on column sys_dict_type.dict_id         is '字典主键';
comment on column sys_dict_type.dict_name       is '字典名称';
comment on column sys_dict_type.dict_type       is '字典类型';
comment on column sys_dict_type.create_dept     is '创建部门';
comment on column sys_dict_type.create_by       is '创建者';
comment on column sys_dict_type.create_time     is '创建时间';
comment on column sys_dict_type.update_by       is '更新者';
comment on column sys_dict_type.update_time     is '更新时间';
comment on column sys_dict_type.remark          is '备注';

insert into sys_dict_type values(1761500000000000001, '用户性别', 'sys_user_gender', 1761000000000000103, 1761100000000000001, now(), null, null, '用户性别列表');
insert into sys_dict_type values(1761500000000000002, '菜单状态', 'sys_show_hide', 1761000000000000103, 1761100000000000001, now(), null, null, '菜单状态列表');
insert into sys_dict_type values(1761500000000000003, '系统开关', 'sys_normal_disable', 1761000000000000103, 1761100000000000001, now(), null, null, '系统开关列表');
insert into sys_dict_type values(1761500000000000006, '系统是否', 'sys_yes_no', 1761000000000000103, 1761100000000000001, now(), null, null, '系统是否列表');
insert into sys_dict_type values(1761500000000000007, '通知类型', 'sys_notice_type', 1761000000000000103, 1761100000000000001, now(), null, null, '通知类型列表');
insert into sys_dict_type values(1761500000000000008, '通知状态', 'sys_notice_status', 1761000000000000103, 1761100000000000001, now(), null, null, '通知状态列表');
insert into sys_dict_type values(1761500000000000009, '操作类型', 'sys_oper_type', 1761000000000000103, 1761100000000000001, now(), null, null, '操作类型列表');
insert into sys_dict_type values(1761500000000000010, '系统状态', 'sys_common_status', 1761000000000000103, 1761100000000000001, now(), null, null, '登录状态列表');
insert into sys_dict_type values(1761500000000000011, '授权类型', 'sys_grant_type', 1761000000000000103, 1761100000000000001, now(), null, null, '认证授权类型');
insert into sys_dict_type values(1761500000000000012, '设备类型', 'sys_device_type', 1761000000000000103, 1761100000000000001, now(), null, null, '客户端设备类型');

-- ----------------------------
-- 12、字典数据表
-- ----------------------------
create table if not exists sys_dict_data
(
    dict_code   int8,
    dict_sort   int4         default 0,
    dict_label  varchar(100) default ''::varchar,
    dict_value  varchar(100) default ''::varchar,
    dict_type   varchar(100) default ''::varchar,
    css_class   varchar(100) default null::varchar,
    list_class  varchar(100) default null::varchar,
    is_default  char         default 'N'::bpchar,
    create_dept int8,
    create_by   int8,
    create_time timestamp,
    update_by   int8,
    update_time timestamp,
    remark      varchar(500) default null::varchar,
    constraint sys_dict_data_pk primary key (dict_code)
);

create index idx_sys_dict_data_type ON sys_dict_data (dict_type);

comment on table sys_dict_data                  is '字典数据表';
comment on column sys_dict_data.dict_code       is '字典编码';
comment on column sys_dict_data.dict_sort       is '字典排序';
comment on column sys_dict_data.dict_label      is '字典标签';
comment on column sys_dict_data.dict_value      is '字典键值';
comment on column sys_dict_data.dict_type       is '字典类型';
comment on column sys_dict_data.css_class       is '样式属性（其他样式扩展）';
comment on column sys_dict_data.list_class      is '表格回显样式';
comment on column sys_dict_data.is_default      is '是否默认（Y是 N否）';
comment on column sys_dict_data.create_dept     is '创建部门';
comment on column sys_dict_data.create_by       is '创建者';
comment on column sys_dict_data.create_time     is '创建时间';
comment on column sys_dict_data.update_by       is '更新者';
comment on column sys_dict_data.update_time     is '更新时间';
comment on column sys_dict_data.remark          is '备注';

insert into sys_dict_data values(1761600000000000001, 1, '男', '0', 'sys_user_gender', '', '', 'Y', 1761000000000000103, 1761100000000000001, now(), null, null, '性别男');
insert into sys_dict_data values(1761600000000000002, 2, '女', '1', 'sys_user_gender', '', '', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '性别女');
insert into sys_dict_data values(1761600000000000003, 3, '未知', '2', 'sys_user_gender', '', '', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '性别未知');
insert into sys_dict_data values(1761600000000000004, 1, '显示', '0', 'sys_show_hide', '', 'primary', 'Y', 1761000000000000103, 1761100000000000001, now(), null, null, '显示菜单');
insert into sys_dict_data values(1761600000000000005, 2, '隐藏', '1', 'sys_show_hide', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '隐藏菜单');
insert into sys_dict_data values(1761600000000000006, 1, '正常', '0', 'sys_normal_disable', '', 'primary', 'Y', 1761000000000000103, 1761100000000000001, now(), null, null, '正常状态');
insert into sys_dict_data values(1761600000000000007, 2, '停用', '1', 'sys_normal_disable', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '停用状态');
insert into sys_dict_data values(1761600000000000012, 1, '是', 'Y', 'sys_yes_no', '', 'primary', 'Y', 1761000000000000103, 1761100000000000001, now(), null, null, '系统默认是');
insert into sys_dict_data values(1761600000000000013, 2, '否', 'N', 'sys_yes_no', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '系统默认否');
insert into sys_dict_data values(1761600000000000014, 1, '通知', '1', 'sys_notice_type', '', 'warning', 'Y', 1761000000000000103, 1761100000000000001, now(), null, null, '通知');
insert into sys_dict_data values(1761600000000000015, 2, '公告', '2', 'sys_notice_type', '', 'success', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '公告');
insert into sys_dict_data values(1761600000000000016, 1, '正常', '0', 'sys_notice_status', '', 'primary', 'Y', 1761000000000000103, 1761100000000000001, now(), null, null, '正常状态');
insert into sys_dict_data values(1761600000000000017, 2, '关闭', '1', 'sys_notice_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '关闭状态');
insert into sys_dict_data values(1761600000000000029, 99, '其他', '0', 'sys_oper_type', '', 'info', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '其他操作');
insert into sys_dict_data values(1761600000000000018, 1, '新增', '1', 'sys_oper_type', '', 'info', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '新增操作');
insert into sys_dict_data values(1761600000000000019, 2, '修改', '2', 'sys_oper_type', '', 'info', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '修改操作');
insert into sys_dict_data values(1761600000000000020, 3, '删除', '3', 'sys_oper_type', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '删除操作');
insert into sys_dict_data values(1761600000000000021, 4, '授权', '4', 'sys_oper_type', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '授权操作');
insert into sys_dict_data values(1761600000000000022, 5, '导出', '5', 'sys_oper_type', '', 'warning', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '导出操作');
insert into sys_dict_data values(1761600000000000023, 6, '导入', '6', 'sys_oper_type', '', 'warning', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '导入操作');
insert into sys_dict_data values(1761600000000000024, 7, '强退', '7', 'sys_oper_type', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '强退操作');
insert into sys_dict_data values(1761600000000000025, 8, '生成代码', '8', 'sys_oper_type', '', 'warning', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '生成操作');
insert into sys_dict_data values(1761600000000000026, 9, '清空数据', '9', 'sys_oper_type', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '清空操作');
insert into sys_dict_data values(1761600000000000027, 1, '成功', '0', 'sys_common_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '正常状态');
insert into sys_dict_data values(1761600000000000028, 2, '失败', '1', 'sys_common_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '停用状态');
insert into sys_dict_data values(1761600000000000030, 0, '密码认证', 'password', 'sys_grant_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '密码认证');
insert into sys_dict_data values(1761600000000000031, 0, '短信认证', 'sms', 'sys_grant_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '短信认证');
insert into sys_dict_data values(1761600000000000032, 0, '邮件认证', 'email', 'sys_grant_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '邮件认证');
insert into sys_dict_data values(1761600000000000033, 0, '小程序认证', 'xcx', 'sys_grant_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '小程序认证');
insert into sys_dict_data values(1761600000000000034, 0, '三方登录认证', 'social', 'sys_grant_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '三方登录认证');
insert into sys_dict_data values(1761600000000000035, 0, 'PC', 'pc', 'sys_device_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, 'PC');
insert into sys_dict_data values(1761600000000000036, 0, '安卓', 'android', 'sys_device_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '安卓');
insert into sys_dict_data values(1761600000000000037, 0, 'iOS', 'ios', 'sys_device_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, 'iOS');
insert into sys_dict_data values(1761600000000000038, 0, '小程序', 'xcx', 'sys_device_type', '', 'default', 'N', 1761000000000000103, 1761100000000000001, now(), null, null, '小程序');


-- ----------------------------
-- 13、参数配置表
-- ----------------------------
create table if not exists sys_config
(
    config_id    int8,
    config_name  varchar(100) default ''::varchar,
    config_key   varchar(100) default ''::varchar,
    config_value varchar(500) default ''::varchar,
    config_type  char         default 'N'::bpchar,
    create_dept  int8,
    create_by    int8,
    create_time  timestamp,
    update_by    int8,
    update_time  timestamp,
    remark       varchar(500) default null::varchar,
    constraint sys_config_pk primary key (config_id)
);

comment on table sys_config                 is '参数配置表';
comment on column sys_config.config_id      is '参数主键';
comment on column sys_config.config_name    is '参数名称';
comment on column sys_config.config_key     is '参数键名';
comment on column sys_config.config_value   is '参数键值';
comment on column sys_config.config_type    is '系统内置（Y是 N否）';
comment on column sys_config.create_dept    is '创建部门';
comment on column sys_config.create_by      is '创建者';
comment on column sys_config.create_time    is '创建时间';
comment on column sys_config.update_by      is '更新者';
comment on column sys_config.update_time    is '更新时间';
comment on column sys_config.remark         is '备注';

insert into sys_config values(1761700000000000001, '用户管理-账号初始密码', 'sys.user.initPassword', '123456', 'Y', 1761000000000000103, 1761100000000000001, now(), null, null, '初始化密码 123456');
insert into sys_config values(1761700000000000002, '账号自助-是否开启用户注册功能', 'sys.account.registerUser', 'false', 'Y', 1761000000000000103, 1761100000000000001, now(), null, null, '是否开启注册用户功能（true开启，false关闭）');
insert into sys_config values(1761700000000000003, 'OSS预览列表资源开关', 'sys.oss.previewListResource', 'true', 'Y', 1761000000000000103, 1761100000000000001, now(), null, null, 'true:开启, false:关闭');


-- ----------------------------
-- 14、系统访问记录
-- ----------------------------
create table if not exists sys_login_info
(
    info_id        int8,
    user_name      varchar(50)  default ''::varchar,
    client_key     varchar(32)  default ''::varchar,
    device_type    varchar(32)  default ''::varchar,
    ipaddr         varchar(128) default ''::varchar,
    login_location varchar(255) default ''::varchar,
    browser        varchar(50)  default ''::varchar,
    os             varchar(50)  default ''::varchar,
    status         char         default '0'::bpchar,
    msg            varchar(255) default ''::varchar,
    login_time     timestamp,
    constraint sys_login_info_pk primary key (info_id)
);

create index idx_sys_login_info_s ON sys_login_info (status);
create index idx_sys_login_info_lt ON sys_login_info (login_time);

comment on table sys_login_info                 is '系统访问记录';
comment on column sys_login_info.info_id        is '访问ID';
comment on column sys_login_info.user_name      is '用户账号';
comment on column sys_login_info.client_key     is '客户端';
comment on column sys_login_info.device_type    is '设备类型';
comment on column sys_login_info.ipaddr         is '登录IP地址';
comment on column sys_login_info.login_location is '登录地点';
comment on column sys_login_info.browser        is '浏览器类型';
comment on column sys_login_info.os             is '操作系统';
comment on column sys_login_info.status         is '登录状态（0正常 1异常）';
comment on column sys_login_info.msg            is '提示消息';
comment on column sys_login_info.login_time     is '访问时间';

-- ----------------------------
-- 17、通知公告表
-- ----------------------------
create table if not exists sys_notice
(
    notice_id      int8,
    notice_title   varchar(50)  not null,
    notice_type    char         not null,
    notice_content text,
    status         char         default '0'::bpchar,
    create_dept    int8,
    create_by      int8,
    create_time    timestamp,
    update_by      int8,
    update_time    timestamp,
    remark         varchar(255) default null::varchar,
    constraint sys_notice_pk primary key (notice_id)
);

comment on table sys_notice                 is '通知公告表';
comment on column sys_notice.notice_id      is '公告ID';
comment on column sys_notice.notice_title   is '公告标题';
comment on column sys_notice.notice_type    is '公告类型（1通知 2公告）';
comment on column sys_notice.notice_content is '公告内容';
comment on column sys_notice.status         is '公告状态（0正常 1关闭）';
comment on column sys_notice.create_dept    is '创建部门';
comment on column sys_notice.create_by      is '创建者';
comment on column sys_notice.create_time    is '创建时间';
comment on column sys_notice.update_by      is '更新者';
comment on column sys_notice.update_time    is '更新时间';
comment on column sys_notice.remark         is '备注';

-- ----------------------------
-- 初始化-公告信息表数据
-- ----------------------------
insert into sys_notice values(1761800000000000001, '温馨提醒：2018-07-01 新版本发布啦', '2', '新版本内容', '0', 1761000000000000103, 1761100000000000001, now(), null, null, '管理员');
insert into sys_notice values(1761800000000000002, '维护通知：2018-07-01 系统凌晨维护', '1', '维护内容', '0', 1761000000000000103, 1761100000000000001, now(), null, null, '管理员');


-- ----------------------------
-- 18、消息记录表
-- ----------------------------
create table if not exists sys_message
(
    message_id    int8,
    category      varchar(20)   not null,
    type          varchar(20)   not null,
    source        varchar(20)   not null,
    title         varchar(100)  default ''::varchar,
    message       varchar(500)  default ''::varchar,
    content       text,
    data_json     text,
    path          varchar(500)  default null::varchar,
    send_user_ids varchar(2000) not null default '0'::varchar,
    create_dept   int8,
    create_by     int8,
    create_time   timestamp,
    update_by     int8,
    update_time   timestamp,
    constraint sys_message_pk primary key (message_id)
);

create index if not exists idx_sys_message_category_time on sys_message (category, create_time);

comment on table sys_message                   is '消息记录表';
comment on column sys_message.message_id       is '消息ID';
comment on column sys_message.category         is '消息分组(system/notice/workflow)';
comment on column sys_message.type             is '消息类型';
comment on column sys_message.source           is '消息来源';
comment on column sys_message.title            is '标题';
comment on column sys_message.message          is '摘要消息';
comment on column sys_message.content          is '详细内容';
comment on column sys_message.data_json        is '扩展数据JSON';
comment on column sys_message.path             is '前端跳转路径';
comment on column sys_message.send_user_ids    is '目标用户ID串，0表示全局';
comment on column sys_message.create_dept      is '创建部门';
comment on column sys_message.create_by        is '创建者';
comment on column sys_message.create_time      is '创建时间';
comment on column sys_message.update_by        is '更新者';
comment on column sys_message.update_time      is '更新时间';


-- ----------------------------
-- 19、代码生成业务表
-- ----------------------------
create table if not exists gen_table
(
    table_id          int8,
    data_name         varchar(200)  default ''::varchar,
    table_name        varchar(200)  default ''::varchar,
    table_comment     varchar(500)  default ''::varchar,
    class_name        varchar(100)  default ''::varchar,
    tpl_category      varchar(200)  default 'crud'::varchar,
    frontend_type     varchar(50)   default 'vue'::varchar,
    package_name      varchar(100)  default null::varchar,
    module_name       varchar(30)   default null::varchar,
    business_name     varchar(30)   default null::varchar,
    function_name     varchar(50)   default null::varchar,
    function_author   varchar(50)   default null::varchar,
    gen_type          char          default '0'::bpchar not null,
    gen_path          varchar(200)  default '/'::varchar,
    options           varchar(1000) default null::varchar,
    create_dept       int8,
    create_by         int8,
    create_time       timestamp,
    update_by         int8,
    update_time       timestamp,
    remark            varchar(500)  default null::varchar,
    constraint gen_table_pk primary key (table_id)
);

comment on table gen_table is '代码生成业务表';
comment on column gen_table.table_id is '编号';
comment on column gen_table.data_name is '数据源名称';
comment on column gen_table.table_name is '表名称';
comment on column gen_table.table_comment is '表描述';
comment on column gen_table.class_name is '实体类名称';
comment on column gen_table.tpl_category is '使用的模板（CRUD单表操作 TREE树表操作）';
comment on column gen_table.frontend_type is '前端模板类型，对应 vm 下的模板目录';
comment on column gen_table.package_name is '生成包路径';
comment on column gen_table.module_name is '生成模块名';
comment on column gen_table.business_name is '生成业务名';
comment on column gen_table.function_name is '生成功能名';
comment on column gen_table.function_author is '生成功能作者';
comment on column gen_table.gen_type is '生成代码方式（0zip压缩包 1自定义路径）';
comment on column gen_table.gen_path is '生成路径（不填默认项目路径）';
comment on column gen_table.options is '其它生成选项';
comment on column gen_table.create_dept is '创建部门';
comment on column gen_table.create_by is '创建者';
comment on column gen_table.create_time is '创建时间';
comment on column gen_table.update_by is '更新者';
comment on column gen_table.update_time is '更新时间';
comment on column gen_table.remark is '备注';

-- ----------------------------
-- 20、代码生成业务表字段
-- ----------------------------
create table if not exists gen_table_column
(
    column_id      int8,
    table_id       int8,
    column_name    varchar(200) default null::varchar,
    column_comment varchar(500) default null::varchar,
    column_type    varchar(100) default null::varchar,
    java_type      varchar(500) default null::varchar,
    java_field     varchar(200) default null::varchar,
    is_pk          char         default null::bpchar,
    is_increment   char         default null::bpchar,
    is_required    char         default null::bpchar,
    is_insert      char         default null::bpchar,
    is_edit        char         default null::bpchar,
    is_list        char         default null::bpchar,
    is_query       char         default null::bpchar,
    query_type     varchar(200) default 'EQ'::varchar,
    html_type      varchar(200) default null::varchar,
    dict_type      varchar(200) default ''::varchar,
    sort           int4,
    create_dept    int8,
    create_by      int8,
    create_time    timestamp,
    update_by      int8,
    update_time    timestamp,
    constraint gen_table_column_pk primary key (column_id)
);

comment on table gen_table_column is '代码生成业务表字段';
comment on column gen_table_column.column_id is '编号';
comment on column gen_table_column.table_id is '归属表编号';
comment on column gen_table_column.column_name is '列名称';
comment on column gen_table_column.column_comment is '列描述';
comment on column gen_table_column.column_type is '列类型';
comment on column gen_table_column.java_type is 'JAVA类型';
comment on column gen_table_column.java_field is 'JAVA字段名';
comment on column gen_table_column.is_pk is '是否主键（1是）';
comment on column gen_table_column.is_increment is '是否自增（1是）';
comment on column gen_table_column.is_required is '是否必填（1是）';
comment on column gen_table_column.is_insert is '是否为插入字段（1是）';
comment on column gen_table_column.is_edit is '是否编辑字段（1是）';
comment on column gen_table_column.is_list is '是否列表字段（1是）';
comment on column gen_table_column.is_query is '是否查询字段（1是）';
comment on column gen_table_column.query_type is '查询方式（等于、不等于、大于、小于、范围）';
comment on column gen_table_column.html_type is '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）';
comment on column gen_table_column.dict_type is '字典类型';
comment on column gen_table_column.sort is '排序';
comment on column gen_table_column.create_dept is '创建部门';
comment on column gen_table_column.create_by is '创建者';
comment on column gen_table_column.create_time is '创建时间';
comment on column gen_table_column.update_by is '更新者';
comment on column gen_table_column.update_time is '更新时间';

-- ----------------------------
-- OSS对象存储表
-- ----------------------------
create table if not exists sys_oss
(
    oss_id        int8,
    file_name     varchar(255) default ''::varchar not null,
    original_name varchar(255) default ''::varchar not null,
    file_suffix   varchar(10)  default ''::varchar not null,
    url           varchar(500) default ''::varchar not null,
    ext1          varchar(500) default ''::varchar,
    create_dept   int8,
    create_by     int8,
    create_time   timestamp,
    update_by     int8,
    update_time   timestamp,
    service       varchar(20)  default 'minio'::varchar,
    constraint sys_oss_pk primary key (oss_id)
);

comment on table sys_oss                    is 'OSS对象存储表';
comment on column sys_oss.oss_id            is '对象存储主键';
comment on column sys_oss.file_name         is '文件名';
comment on column sys_oss.original_name     is '原名';
comment on column sys_oss.file_suffix       is '文件后缀名';
comment on column sys_oss.url               is 'URL地址';
comment on column sys_oss.ext1              is '扩展字段';
comment on column sys_oss.create_by         is '上传人';
comment on column sys_oss.create_dept       is '创建部门';
comment on column sys_oss.create_time       is '创建时间';
comment on column sys_oss.update_by         is '更新者';
comment on column sys_oss.update_time       is '更新时间';
comment on column sys_oss.service           is '服务商';

-- ----------------------------
-- OSS对象存储动态配置表
-- ----------------------------
create table if not exists sys_oss_config
(
    oss_config_id int8,
    config_key    varchar(20)  default ''::varchar not null,
    access_key    varchar(255) default ''::varchar,
    secret_key    varchar(255) default ''::varchar,
    bucket_name   varchar(255) default ''::varchar,
    prefix        varchar(255) default ''::varchar,
    endpoint      varchar(255) default ''::varchar,
    domain_url    varchar(255) default ''::varchar,
    is_https      char         default 'N'::bpchar,
    region        varchar(255) default ''::varchar,
    access_policy char(1)      default '1'::bpchar not null,
    status        char         default 'N'::bpchar,
    ext1          varchar(255) default ''::varchar,
    create_dept   int8,
    create_by     int8,
    create_time   timestamp,
    update_by     int8,
    update_time   timestamp,
    remark        varchar(500) default ''::varchar,
    constraint sys_oss_config_pk primary key (oss_config_id)
);

comment on table sys_oss_config                 is '对象存储配置表';
comment on column sys_oss_config.oss_config_id  is '主键';
comment on column sys_oss_config.config_key     is '配置key';
comment on column sys_oss_config.access_key     is 'accessKey';
comment on column sys_oss_config.secret_key     is '秘钥';
comment on column sys_oss_config.bucket_name    is '桶名称';
comment on column sys_oss_config.prefix         is '前缀';
comment on column sys_oss_config.endpoint       is '访问站点';
comment on column sys_oss_config.domain_url     is '自定义域名';
comment on column sys_oss_config.is_https       is '是否https（Y=是,N=否）';
comment on column sys_oss_config.region         is '域';
comment on column sys_oss_config.access_policy  is '桶权限类型(0=private 1=public 2=custom)';
comment on column sys_oss_config.status         is '是否默认（Y=是,N=否）';
comment on column sys_oss_config.ext1           is '扩展字段';
comment on column sys_oss_config.create_dept    is '创建部门';
comment on column sys_oss_config.create_by      is '创建者';
comment on column sys_oss_config.create_time    is '创建时间';
comment on column sys_oss_config.update_by      is '更新者';
comment on column sys_oss_config.update_time    is '更新时间';
comment on column sys_oss_config.remark         is '备注';

insert into sys_oss_config values (1761900000000000001, 'minio', 'ruoyi', 'ruoyi123', 'ruoyi', '', '127.0.0.1:9000', '', 'N', '', '1', 'Y', '', 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now(), null);
insert into sys_oss_config values (1761900000000000002, 'qiniu', 'XXXXXXXXXXXXXXX', 'XXXXXXXXXXXXXXX', 'ruoyi', '', 's3-cn-north-1.qiniucs.com', '', 'N', '', '1', 'N', '', 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now(), null);
insert into sys_oss_config values (1761900000000000003, 'aliyun', 'XXXXXXXXXXXXXXX', 'XXXXXXXXXXXXXXX', 'ruoyi', '', 'oss-cn-beijing.aliyuncs.com', '', 'N', '', '1', 'N', '', 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now(), null);
insert into sys_oss_config values (1761900000000000004, 'qcloud', 'XXXXXXXXXXXXXXX', 'XXXXXXXXXXXXXXX', 'ruoyi-1240000000', '', 'cos.ap-beijing.myqcloud.com', '', 'N', 'ap-beijing', '1', 'N', '', 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now(), null);
insert into sys_oss_config values (1761900000000000005, 'image', 'ruoyi', 'ruoyi123', 'ruoyi', 'image', '127.0.0.1:9000', '', 'N', '', '1', 'N', '', 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now(), NULL);

-- ----------------------------
-- 系统授权表
-- ----------------------------
create table sys_client (
    id                  int8,
    client_id           varchar(64)   default ''::varchar,
    client_key          varchar(32)   default ''::varchar,
    client_secret       varchar(255)  default ''::varchar,
    grant_type          varchar(255)  default ''::varchar,
    device_type         varchar(32)   default ''::varchar,
    access_path         varchar(2000) default ''::varchar,
    ip_whitelist        varchar(1000) default ''::varchar,
    active_timeout      int4          default 1800,
    timeout             int4          default 604800,
    status              char(1)       default '0'::bpchar,
    del_flag            char(1)       default '0'::bpchar,
    create_dept         int8,
    create_by           int8,
    create_time         timestamp,
    update_by           int8,
    update_time         timestamp,
    constraint sys_client_pk primary key (id)
);

comment on table sys_client                         is '系统授权表';
comment on column sys_client.id                     is '主键';
comment on column sys_client.client_id              is '客户端id';
comment on column sys_client.client_key             is '客户端key';
comment on column sys_client.client_secret          is '客户端秘钥';
comment on column sys_client.grant_type             is '授权类型';
comment on column sys_client.device_type            is '设备类型';
comment on column sys_client.access_path            is '允许访问路径';
comment on column sys_client.ip_whitelist           is 'IP白名单';
comment on column sys_client.active_timeout         is 'token活跃超时时间';
comment on column sys_client.timeout                is 'token固定超时';
comment on column sys_client.status                 is '状态（0正常 1停用）';
comment on column sys_client.del_flag               is '删除标志（0代表存在 1代表删除）';
comment on column sys_client.create_dept            is '创建部门';
comment on column sys_client.create_by              is '创建者';
comment on column sys_client.create_time            is '创建时间';
comment on column sys_client.update_by              is '更新者';
comment on column sys_client.update_time            is '更新时间';

insert into sys_client values (1762000000000000001, 'e5cd7e4891bf95d1d19206ce24a7b32e', 'pc', 'pc123', 'password,social', 'pc', '', '', 1800, 604800, 0, 0, 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now());
insert into sys_client values (1762000000000000002, '428a8310cd442757ae699df5d894f051', 'app', 'app123', 'password,sms,social', 'android', '/app/**', '', 1800, 604800, 0, 0, 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now());

create table if not exists test_demo
(
    id          int8,
    dept_id     int8,
    user_id     int8,
    order_num   int4            default 0,
    test_key    varchar(255),
    value       varchar(255),
    version     int4            default 0,
    create_dept int8,
    create_time timestamp,
    create_by   int8,
    update_time timestamp,
    update_by   int8,
    del_flag    int4            default 0
);

comment on table test_demo is '测试单表';
comment on column test_demo.id is '主键';
comment on column test_demo.dept_id is '部门id';
comment on column test_demo.user_id is '用户id';
comment on column test_demo.order_num is '排序号';
comment on column test_demo.test_key is 'key键';
comment on column test_demo.value is '值';
comment on column test_demo.version is '版本';
comment on column test_demo.create_dept  is '创建部门';
comment on column test_demo.create_time is '创建时间';
comment on column test_demo.create_by is '创建人';
comment on column test_demo.update_time is '更新时间';
comment on column test_demo.update_by is '更新人';
comment on column test_demo.del_flag is '删除标志';

create table if not exists test_tree
(
    id          int8,
    parent_id   int8            default 0,
    dept_id     int8,
    user_id     int8,
    tree_name   varchar(255),
    version     int4            default 0,
    create_dept int8,
    create_time timestamp,
    create_by   int8,
    update_time timestamp,
    update_by   int8,
    del_flag    integer         default 0
);

comment on table test_tree is '测试树表';
comment on column test_tree.id is '主键';
comment on column test_tree.parent_id is '父id';
comment on column test_tree.dept_id is '部门id';
comment on column test_tree.user_id is '用户id';
comment on column test_tree.tree_name is '值';
comment on column test_tree.version is '版本';
comment on column test_tree.create_dept  is '创建部门';
comment on column test_tree.create_time is '创建时间';
comment on column test_tree.create_by is '创建人';
comment on column test_tree.update_time is '更新时间';
comment on column test_tree.update_by is '更新人';
comment on column test_tree.del_flag is '删除标志';

INSERT INTO test_demo VALUES (1762100000000000001, 1761000000000000102, 1761100000000000004, 1, '测试数据权限', '测试', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000002, 1761000000000000102, 1761100000000000003, 2, '子节点1', '111', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000003, 1761000000000000102, 1761100000000000003, 3, '子节点2', '222', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000004, 1761000000000000108, 1761100000000000004, 4, '测试数据', 'demo', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000005, 1761000000000000108, 1761100000000000003, 13, '子节点11', '1111', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000006, 1761000000000000108, 1761100000000000003, 12, '子节点22', '2222', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000007, 1761000000000000108, 1761100000000000003, 11, '子节点33', '3333', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000008, 1761000000000000108, 1761100000000000003, 10, '子节点44', '4444', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000009, 1761000000000000108, 1761100000000000003, 9, '子节点55', '5555', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000010, 1761000000000000108, 1761100000000000003, 8, '子节点66', '6666', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000011, 1761000000000000108, 1761100000000000003, 7, '子节点77', '7777', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000012, 1761000000000000108, 1761100000000000003, 6, '子节点88', '8888', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_demo VALUES (1762100000000000013, 1761000000000000108, 1761100000000000003, 5, '子节点99', '9999', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);

INSERT INTO test_tree VALUES (1762200000000000001, 0, 1761000000000000102, 1761100000000000004, '测试数据权限', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000002, 1762200000000000001, 1761000000000000102, 1761100000000000003, '子节点1', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000003, 1762200000000000002, 1761000000000000102, 1761100000000000003, '子节点2', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000004, 0, 1761000000000000108, 1761100000000000004, '测试树1', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000005, 1762200000000000004, 1761000000000000108, 1761100000000000003, '子节点11', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000006, 1762200000000000004, 1761000000000000108, 1761100000000000003, '子节点22', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000007, 1762200000000000004, 1761000000000000108, 1761100000000000003, '子节点33', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000008, 1762200000000000005, 1761000000000000108, 1761100000000000003, '子节点44', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000009, 1762200000000000006, 1761000000000000108, 1761100000000000003, '子节点55', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000010, 1762200000000000007, 1761000000000000108, 1761100000000000003, '子节点66', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000011, 1762200000000000007, 1761000000000000108, 1761100000000000003, '子节点77', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000012, 1762200000000000010, 1761000000000000108, 1761100000000000003, '子节点88', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);
INSERT INTO test_tree VALUES (1762200000000000013, 1762200000000000010, 1761000000000000108, 1761100000000000003, '子节点99', 0, 1761000000000000103, now(), 1761100000000000001, NULL, NULL, 0);

-- 字符串自动转时间 避免框架时间查询报错问题
create or replace function cast_varchar_to_timestamp(varchar) returns timestamptz as $$
select to_timestamp($1, 'yyyy-mm-dd hh24:mi:ss');
$$ language sql strict ;

create cast (varchar as timestamptz) with function cast_varchar_to_timestamp as IMPLICIT;


-- ========== postgres_ry_job.sql（调度任务表）==========
/*
 SnailJob Database Transfer Tool
 Source Server Type    : MySQL
 Target Server Type    : PostgreSQL
 Date: 2025-06-21 23:23:10
*/


-- sj_namespace
CREATE TABLE sj_namespace
(
    id          bigserial PRIMARY KEY,
    name        varchar(64)  NOT NULL,
    unique_id   varchar(64)  NOT NULL,
    description varchar(256) NOT NULL DEFAULT '',
    deleted     smallint     NOT NULL DEFAULT 0,
    create_dt   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_namespace_01 ON sj_namespace (name);

COMMENT ON COLUMN sj_namespace.id IS '主键';
COMMENT ON COLUMN sj_namespace.name IS '名称';
COMMENT ON COLUMN sj_namespace.unique_id IS '唯一id';
COMMENT ON COLUMN sj_namespace.description IS '描述';
COMMENT ON COLUMN sj_namespace.deleted IS '逻辑删除 1、删除';
COMMENT ON COLUMN sj_namespace.create_dt IS '创建时间';
COMMENT ON COLUMN sj_namespace.update_dt IS '修改时间';
COMMENT ON TABLE sj_namespace IS '命名空间';

INSERT INTO sj_namespace VALUES (1, 'Development', 'dev', '', 0, now(), now());
INSERT INTO sj_namespace VALUES (2, 'Production', 'prod', '', 0, now(), now());

-- sj_group_config
CREATE TABLE sj_group_config
(
    id                bigserial PRIMARY KEY,
    namespace_id      varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name        varchar(64)  NOT NULL DEFAULT '',
    description       varchar(256) NOT NULL DEFAULT '',
    token             varchar(64)  NOT NULL DEFAULT 'SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT',
    group_status      smallint     NOT NULL DEFAULT 0,
    version           int          NOT NULL,
    group_partition   int          NOT NULL,
    id_generator_mode smallint     NOT NULL DEFAULT 1,
    init_scene        smallint     NOT NULL DEFAULT 0,
    create_dt         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_sj_group_config_01 ON sj_group_config (namespace_id, group_name);

COMMENT ON COLUMN sj_group_config.id IS '主键';
COMMENT ON COLUMN sj_group_config.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_group_config.group_name IS '组名称';
COMMENT ON COLUMN sj_group_config.description IS '组描述';
COMMENT ON COLUMN sj_group_config.token IS 'token';
COMMENT ON COLUMN sj_group_config.group_status IS '组状态 0、未启用 1、启用';
COMMENT ON COLUMN sj_group_config.version IS '版本号';
COMMENT ON COLUMN sj_group_config.group_partition IS '分区';
COMMENT ON COLUMN sj_group_config.id_generator_mode IS '唯一id生成模式 默认号段模式';
COMMENT ON COLUMN sj_group_config.init_scene IS '是否初始化场景 0:否 1:是';
COMMENT ON COLUMN sj_group_config.create_dt IS '创建时间';
COMMENT ON COLUMN sj_group_config.update_dt IS '修改时间';
COMMENT ON TABLE sj_group_config IS '组配置';

INSERT INTO sj_group_config VALUES (1, 'dev', 'ruoyi_group', '', 'SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT', 1, 1, 0, 1, 1,  now(), now());
INSERT INTO sj_group_config VALUES (2, 'prod', 'ruoyi_group', '', 'SJ_cKqBTPzCsWA3VyuCfFoccmuIEGXjr5KT', 1, 1, 0, 1, 1,  now(), now());

-- sj_notify_config
CREATE TABLE sj_notify_config
(
    id                     bigserial PRIMARY KEY,
    namespace_id           varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name             varchar(64)  NOT NULL,
    notify_name            varchar(64)  NOT NULL DEFAULT '',
    system_task_type       smallint     NOT NULL DEFAULT 3,
    notify_status          smallint     NOT NULL DEFAULT 0,
    recipient_ids          varchar(128) NOT NULL,
    notify_threshold       int          NOT NULL DEFAULT 0,
    notify_scene           smallint     NOT NULL DEFAULT 0,
    rate_limiter_status    smallint     NOT NULL DEFAULT 0,
    rate_limiter_threshold int          NOT NULL DEFAULT 0,
    description            varchar(256) NOT NULL DEFAULT '',
    create_dt              timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt              timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_notify_config_01 ON sj_notify_config (namespace_id, group_name);

COMMENT ON COLUMN sj_notify_config.id IS '主键';
COMMENT ON COLUMN sj_notify_config.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_notify_config.group_name IS '组名称';
COMMENT ON COLUMN sj_notify_config.notify_name IS '通知名称';
COMMENT ON COLUMN sj_notify_config.system_task_type IS '任务类型 1. 重试任务 2. 重试回调 3、JOB任务 4、WORKFLOW任务';
COMMENT ON COLUMN sj_notify_config.notify_status IS '通知状态 0、未启用 1、启用';
COMMENT ON COLUMN sj_notify_config.recipient_ids IS '接收人id列表';
COMMENT ON COLUMN sj_notify_config.notify_threshold IS '通知阈值';
COMMENT ON COLUMN sj_notify_config.notify_scene IS '通知场景';
COMMENT ON COLUMN sj_notify_config.rate_limiter_status IS '限流状态 0、未启用 1、启用';
COMMENT ON COLUMN sj_notify_config.rate_limiter_threshold IS '每秒限流阈值';
COMMENT ON COLUMN sj_notify_config.description IS '描述';
COMMENT ON COLUMN sj_notify_config.create_dt IS '创建时间';
COMMENT ON COLUMN sj_notify_config.update_dt IS '修改时间';
COMMENT ON TABLE sj_notify_config IS '通知配置';

-- sj_notify_recipient
CREATE TABLE sj_notify_recipient
(
    id               bigserial PRIMARY KEY,
    namespace_id     varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    recipient_name   varchar(64)  NOT NULL,
    notify_type      smallint     NOT NULL DEFAULT 0,
    notify_attribute varchar(512) NOT NULL,
    description      varchar(256) NOT NULL DEFAULT '',
    create_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_notify_recipient_01 ON sj_notify_recipient (namespace_id);

COMMENT ON COLUMN sj_notify_recipient.id IS '主键';
COMMENT ON COLUMN sj_notify_recipient.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_notify_recipient.recipient_name IS '接收人名称';
COMMENT ON COLUMN sj_notify_recipient.notify_type IS '通知类型 1、钉钉 2、邮件 3、企业微信 4 飞书 5 webhook';
COMMENT ON COLUMN sj_notify_recipient.notify_attribute IS '配置属性';
COMMENT ON COLUMN sj_notify_recipient.description IS '描述';
COMMENT ON COLUMN sj_notify_recipient.create_dt IS '创建时间';
COMMENT ON COLUMN sj_notify_recipient.update_dt IS '修改时间';
COMMENT ON TABLE sj_notify_recipient IS '告警通知接收人';

-- sj_retry_dead_letter
CREATE TABLE sj_retry_dead_letter
(
    id              bigserial PRIMARY KEY,
    namespace_id    varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name      varchar(64)  NOT NULL,
    group_id        bigint       NOT NULL,
    scene_name      varchar(64)  NOT NULL,
    scene_id        bigint       NOT NULL,
    idempotent_id   varchar(64)  NOT NULL,
    biz_no          varchar(64)  NOT NULL DEFAULT '',
    executor_name   varchar(512) NOT NULL DEFAULT '',
    serializer_name varchar(32)  NOT NULL DEFAULT 'jackson',
    args_str        text         NOT NULL,
    ext_attrs       text         NOT NULL,
    create_dt       timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_retry_dead_letter_01 ON sj_retry_dead_letter (namespace_id, group_name, scene_name);
CREATE INDEX idx_sj_retry_dead_letter_02 ON sj_retry_dead_letter (idempotent_id);
CREATE INDEX idx_sj_retry_dead_letter_03 ON sj_retry_dead_letter (biz_no);
CREATE INDEX idx_sj_retry_dead_letter_04 ON sj_retry_dead_letter (create_dt);

COMMENT ON COLUMN sj_retry_dead_letter.id IS '主键';
COMMENT ON COLUMN sj_retry_dead_letter.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_retry_dead_letter.group_name IS '组名称';
COMMENT ON COLUMN sj_retry_dead_letter.group_id IS '组Id';
COMMENT ON COLUMN sj_retry_dead_letter.scene_name IS '场景名称';
COMMENT ON COLUMN sj_retry_dead_letter.scene_id IS '场景ID';
COMMENT ON COLUMN sj_retry_dead_letter.idempotent_id IS '幂等id';
COMMENT ON COLUMN sj_retry_dead_letter.biz_no IS '业务编号';
COMMENT ON COLUMN sj_retry_dead_letter.executor_name IS '执行器名称';
COMMENT ON COLUMN sj_retry_dead_letter.serializer_name IS '执行方法参数序列化器名称';
COMMENT ON COLUMN sj_retry_dead_letter.args_str IS '执行方法参数';
COMMENT ON COLUMN sj_retry_dead_letter.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_retry_dead_letter.create_dt IS '创建时间';
COMMENT ON TABLE sj_retry_dead_letter IS '死信队列表';

-- sj_retry
CREATE TABLE sj_retry
(
    id              bigserial PRIMARY KEY,
    namespace_id    varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name      varchar(64)  NOT NULL,
    group_id        bigint       NOT NULL,
    scene_name      varchar(64)  NOT NULL,
    scene_id        bigint       NOT NULL,
    idempotent_id   varchar(64)  NOT NULL,
    biz_no          varchar(64)  NOT NULL DEFAULT '',
    executor_name   varchar(512) NOT NULL DEFAULT '',
    args_str        text         NOT NULL,
    ext_attrs       text         NOT NULL,
    serializer_name varchar(32)  NOT NULL DEFAULT 'jackson',
    next_trigger_at bigint       NOT NULL,
    retry_count     int          NOT NULL DEFAULT 0,
    retry_status    smallint     NOT NULL DEFAULT 0,
    task_type       smallint     NOT NULL DEFAULT 1,
    bucket_index    int          NOT NULL DEFAULT 0,
    parent_id       bigint       NOT NULL DEFAULT 0,
    deleted         bigint       NOT NULL DEFAULT 0,
    create_dt       timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt       timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_sj_retry_01 ON sj_retry (scene_id, task_type, idempotent_id, deleted);

CREATE INDEX idx_sj_retry_01 ON sj_retry (biz_no);
CREATE INDEX idx_sj_retry_02 ON sj_retry (idempotent_id);
CREATE INDEX idx_sj_retry_03 ON sj_retry (retry_status, bucket_index);
CREATE INDEX idx_sj_retry_04 ON sj_retry (parent_id);
CREATE INDEX idx_sj_retry_05 ON sj_retry (create_dt);

COMMENT ON COLUMN sj_retry.id IS '主键';
COMMENT ON COLUMN sj_retry.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_retry.group_name IS '组名称';
COMMENT ON COLUMN sj_retry.group_id IS '组Id';
COMMENT ON COLUMN sj_retry.scene_name IS '场景名称';
COMMENT ON COLUMN sj_retry.scene_id IS '场景ID';
COMMENT ON COLUMN sj_retry.idempotent_id IS '幂等id';
COMMENT ON COLUMN sj_retry.biz_no IS '业务编号';
COMMENT ON COLUMN sj_retry.executor_name IS '执行器名称';
COMMENT ON COLUMN sj_retry.args_str IS '执行方法参数';
COMMENT ON COLUMN sj_retry.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_retry.serializer_name IS '执行方法参数序列化器名称';
COMMENT ON COLUMN sj_retry.next_trigger_at IS '下次触发时间';
COMMENT ON COLUMN sj_retry.retry_count IS '重试次数';
COMMENT ON COLUMN sj_retry.retry_status IS '重试状态 0、重试中 1、成功 2、最大重试次数';
COMMENT ON COLUMN sj_retry.task_type IS '任务类型 1、重试数据 2、回调数据';
COMMENT ON COLUMN sj_retry.bucket_index IS 'bucket';
COMMENT ON COLUMN sj_retry.parent_id IS '父节点id';
COMMENT ON COLUMN sj_retry.deleted IS '逻辑删除';
COMMENT ON COLUMN sj_retry.create_dt IS '创建时间';
COMMENT ON COLUMN sj_retry.update_dt IS '修改时间';
COMMENT ON TABLE sj_retry IS '重试信息表';

-- sj_retry_task
CREATE TABLE sj_retry_task
(
    id               bigserial PRIMARY KEY,
    namespace_id     varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name       varchar(64)  NOT NULL,
    scene_name       varchar(64)  NOT NULL,
    retry_id         bigint       NOT NULL,
    ext_attrs        text         NOT NULL,
    task_status      smallint     NOT NULL DEFAULT 1,
    task_type        smallint     NOT NULL DEFAULT 1,
    operation_reason smallint     NOT NULL DEFAULT 0,
    client_info      varchar(128) NULL     DEFAULT NULL,
    create_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_retry_task_01 ON sj_retry_task (namespace_id, group_name, scene_name);
CREATE INDEX idx_sj_retry_task_02 ON sj_retry_task (task_status);
CREATE INDEX idx_sj_retry_task_03 ON sj_retry_task (create_dt);
CREATE INDEX idx_sj_retry_task_04 ON sj_retry_task (retry_id);

COMMENT ON COLUMN sj_retry_task.id IS '主键';
COMMENT ON COLUMN sj_retry_task.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_retry_task.group_name IS '组名称';
COMMENT ON COLUMN sj_retry_task.scene_name IS '场景名称';
COMMENT ON COLUMN sj_retry_task.retry_id IS '重试信息Id';
COMMENT ON COLUMN sj_retry_task.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_retry_task.task_status IS '重试状态';
COMMENT ON COLUMN sj_retry_task.task_type IS '任务类型 1、重试数据 2、回调数据';
COMMENT ON COLUMN sj_retry_task.operation_reason IS '操作原因';
COMMENT ON COLUMN sj_retry_task.client_info IS '客户端地址 clientId#ip:port';
COMMENT ON COLUMN sj_retry_task.create_dt IS '创建时间';
COMMENT ON COLUMN sj_retry_task.update_dt IS '修改时间';
COMMENT ON TABLE sj_retry_task IS '重试任务表';

-- sj_retry_task_log_message
CREATE TABLE sj_retry_task_log_message
(
    id            bigserial PRIMARY KEY,
    namespace_id  varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name    varchar(64) NOT NULL,
    retry_id      bigint      NOT NULL,
    retry_task_id bigint      NOT NULL,
    message       text        NOT NULL,
    log_num       int         NOT NULL DEFAULT 1,
    real_time     bigint      NOT NULL DEFAULT 0,
    create_dt     timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_retry_task_log_message_01 ON sj_retry_task_log_message (namespace_id, group_name, retry_task_id);
CREATE INDEX idx_sj_retry_task_log_message_02 ON sj_retry_task_log_message (create_dt);

COMMENT ON COLUMN sj_retry_task_log_message.id IS '主键';
COMMENT ON COLUMN sj_retry_task_log_message.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_retry_task_log_message.group_name IS '组名称';
COMMENT ON COLUMN sj_retry_task_log_message.retry_id IS '重试信息Id';
COMMENT ON COLUMN sj_retry_task_log_message.retry_task_id IS '重试任务Id';
COMMENT ON COLUMN sj_retry_task_log_message.message IS '异常信息';
COMMENT ON COLUMN sj_retry_task_log_message.log_num IS '日志数量';
COMMENT ON COLUMN sj_retry_task_log_message.real_time IS '上报时间';
COMMENT ON COLUMN sj_retry_task_log_message.create_dt IS '创建时间';
COMMENT ON TABLE sj_retry_task_log_message IS '任务调度日志信息记录表';

-- sj_retry_scene_config
CREATE TABLE sj_retry_scene_config
(
    id                  bigserial PRIMARY KEY,
    namespace_id        varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    scene_name          varchar(64)  NOT NULL,
    group_name          varchar(64)  NOT NULL,
    scene_status        smallint     NOT NULL DEFAULT 0,
    max_retry_count     int          NOT NULL DEFAULT 5,
    back_off            smallint     NOT NULL DEFAULT 1,
    trigger_interval    varchar(16)  NOT NULL DEFAULT '',
    notify_ids          varchar(128) NOT NULL DEFAULT '',
    deadline_request    bigint       NOT NULL DEFAULT 60000,
    executor_timeout    int          NOT NULL DEFAULT 5,
    route_key           smallint     NOT NULL DEFAULT 4,
    block_strategy      smallint     NOT NULL DEFAULT 1,
    cb_status           smallint     NOT NULL DEFAULT 0,
    cb_trigger_type     smallint     NOT NULL DEFAULT 1,
    cb_max_count        int          NOT NULL DEFAULT 16,
    cb_trigger_interval varchar(16)  NOT NULL DEFAULT '',
    owner_id            bigint       NULL     DEFAULT NULL,
    labels              varchar(512) NULL     DEFAULT '',
    description         varchar(256) NOT NULL DEFAULT '',
    create_dt           timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt           timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_sj_retry_scene_config_01 ON sj_retry_scene_config (namespace_id, group_name, scene_name);

COMMENT ON COLUMN sj_retry_scene_config.id IS '主键';
COMMENT ON COLUMN sj_retry_scene_config.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_retry_scene_config.scene_name IS '场景名称';
COMMENT ON COLUMN sj_retry_scene_config.group_name IS '组名称';
COMMENT ON COLUMN sj_retry_scene_config.scene_status IS '组状态 0、未启用 1、启用';
COMMENT ON COLUMN sj_retry_scene_config.max_retry_count IS '最大重试次数';
COMMENT ON COLUMN sj_retry_scene_config.back_off IS '1、默认等级 2、固定间隔时间 3、CRON 表达式';
COMMENT ON COLUMN sj_retry_scene_config.trigger_interval IS '间隔时长';
COMMENT ON COLUMN sj_retry_scene_config.notify_ids IS '通知告警场景配置id列表';
COMMENT ON COLUMN sj_retry_scene_config.deadline_request IS 'Deadline Request 调用链超时 单位毫秒';
COMMENT ON COLUMN sj_retry_scene_config.executor_timeout IS '任务执行超时时间，单位秒';
COMMENT ON COLUMN sj_retry_scene_config.route_key IS '路由策略';
COMMENT ON COLUMN sj_retry_scene_config.block_strategy IS '阻塞策略 1、丢弃 2、覆盖 3、并行';
COMMENT ON COLUMN sj_retry_scene_config.cb_status IS '回调状态 0、不开启 1、开启';
COMMENT ON COLUMN sj_retry_scene_config.cb_trigger_type IS '1、默认等级 2、固定间隔时间 3、CRON 表达式';
COMMENT ON COLUMN sj_retry_scene_config.cb_max_count IS '回调的最大执行次数';
COMMENT ON COLUMN sj_retry_scene_config.cb_trigger_interval IS '回调的最大执行次数';
COMMENT ON COLUMN sj_retry_scene_config.owner_id IS '负责人id';
COMMENT ON COLUMN sj_retry_scene_config.labels IS '标签';
COMMENT ON COLUMN sj_retry_scene_config.description IS '描述';
COMMENT ON COLUMN sj_retry_scene_config.create_dt IS '创建时间';
COMMENT ON COLUMN sj_retry_scene_config.update_dt IS '修改时间';
COMMENT ON TABLE sj_retry_scene_config IS '场景配置';

-- sj_server_node
CREATE TABLE sj_server_node
(
    id           bigserial PRIMARY KEY,
    namespace_id varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name   varchar(64)  NOT NULL,
    host_id      varchar(64)  NOT NULL,
    host_ip      varchar(64)  NOT NULL,
    host_port    int          NOT NULL,
    expire_at    timestamp    NOT NULL,
    node_type    smallint     NOT NULL,
    ext_attrs    varchar(256) NULL     DEFAULT '',
    labels       varchar(512) NULL     DEFAULT '',
    create_dt    timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt    timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_sj_server_node_01 ON sj_server_node (host_id, host_ip);

CREATE INDEX idx_sj_server_node_01 ON sj_server_node (namespace_id, group_name);
CREATE INDEX idx_sj_server_node_02 ON sj_server_node (expire_at, node_type);

COMMENT ON COLUMN sj_server_node.id IS '主键';
COMMENT ON COLUMN sj_server_node.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_server_node.group_name IS '组名称';
COMMENT ON COLUMN sj_server_node.host_id IS '主机id';
COMMENT ON COLUMN sj_server_node.host_ip IS '机器ip';
COMMENT ON COLUMN sj_server_node.host_port IS '机器端口';
COMMENT ON COLUMN sj_server_node.expire_at IS '过期时间';
COMMENT ON COLUMN sj_server_node.node_type IS '节点类型 1、客户端 2、是服务端';
COMMENT ON COLUMN sj_server_node.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_server_node.labels IS '标签';
COMMENT ON COLUMN sj_server_node.create_dt IS '创建时间';
COMMENT ON COLUMN sj_server_node.update_dt IS '修改时间';
COMMENT ON TABLE sj_server_node IS '服务器节点';

-- sj_distributed_lock
CREATE TABLE sj_distributed_lock
(
    name       varchar(64)  NOT NULL PRIMARY KEY,
    lock_until timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_at  timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  varchar(255) NOT NULL,
    create_dt  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN sj_distributed_lock.name IS '锁名称';
COMMENT ON COLUMN sj_distributed_lock.lock_until IS '锁定时长';
COMMENT ON COLUMN sj_distributed_lock.locked_at IS '锁定时间';
COMMENT ON COLUMN sj_distributed_lock.locked_by IS '锁定者';
COMMENT ON COLUMN sj_distributed_lock.create_dt IS '创建时间';
COMMENT ON COLUMN sj_distributed_lock.update_dt IS '修改时间';
COMMENT ON TABLE sj_distributed_lock IS '锁定表';

-- sj_system_user
CREATE TABLE sj_system_user
(
    id        bigserial PRIMARY KEY,
    username  varchar(64)  NOT NULL,
    password  varchar(128) NOT NULL,
    role      smallint     NOT NULL DEFAULT 0,
    create_dt timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN sj_system_user.id IS '主键';
COMMENT ON COLUMN sj_system_user.username IS '账号';
COMMENT ON COLUMN sj_system_user.password IS '密码';
COMMENT ON COLUMN sj_system_user.role IS '角色：1-普通用户、2-管理员';
COMMENT ON COLUMN sj_system_user.create_dt IS '创建时间';
COMMENT ON COLUMN sj_system_user.update_dt IS '修改时间';
COMMENT ON TABLE sj_system_user IS '系统用户表';

INSERT INTO sj_system_user (username, password, role)
VALUES ('admin', '465c194afb65670f38322df087f0a9bb225cc257e43eb4ac5a0c98ef5b3173ac', 2);

-- sj_system_user_permission
CREATE TABLE sj_system_user_permission
(
    id             bigserial PRIMARY KEY,
    group_name     varchar(64) NOT NULL,
    namespace_id   varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    system_user_id bigint      NOT NULL,
    create_dt      timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt      timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_sj_system_user_permission_01 ON sj_system_user_permission (namespace_id, group_name, system_user_id);

COMMENT ON COLUMN sj_system_user_permission.id IS '主键';
COMMENT ON COLUMN sj_system_user_permission.group_name IS '组名称';
COMMENT ON COLUMN sj_system_user_permission.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_system_user_permission.system_user_id IS '系统用户id';
COMMENT ON COLUMN sj_system_user_permission.create_dt IS '创建时间';
COMMENT ON COLUMN sj_system_user_permission.update_dt IS '修改时间';
COMMENT ON TABLE sj_system_user_permission IS '系统用户权限表';

-- sj_job
CREATE TABLE sj_job
(
    id               bigserial PRIMARY KEY,
    namespace_id     varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    biz_id           varchar(64)  NOT NULL,
    group_name       varchar(64)  NOT NULL,
    job_name         varchar(64)  NOT NULL,
    args_str         text         NULL     DEFAULT NULL,
    args_type        smallint     NOT NULL DEFAULT 1,
    next_trigger_at  bigint       NOT NULL,
    job_status       smallint     NOT NULL DEFAULT 1,
    task_type        smallint     NOT NULL DEFAULT 1,
    route_key        smallint     NOT NULL DEFAULT 4,
    executor_type    smallint     NOT NULL DEFAULT 1,
    executor_info    varchar(255) NULL     DEFAULT NULL,
    trigger_type     smallint     NOT NULL,
    trigger_interval varchar(255) NOT NULL,
    block_strategy   smallint     NOT NULL DEFAULT 1,
    executor_timeout int          NOT NULL DEFAULT 0,
    max_retry_times  int          NOT NULL DEFAULT 0,
    parallel_num     int          NOT NULL DEFAULT 1,
    retry_interval   int          NOT NULL DEFAULT 0,
    bucket_index     int          NOT NULL DEFAULT 0,
    resident         smallint     NOT NULL DEFAULT 0,
    notify_ids       varchar(128) NOT NULL DEFAULT '',
    owner_id         bigint       NULL     DEFAULT NULL,
    labels           varchar(512) NULL     DEFAULT '',
    description      varchar(256) NOT NULL DEFAULT '',
    ext_attrs        varchar(256) NULL     DEFAULT '',
    deleted          smallint     NOT NULL DEFAULT 0,
    create_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_job_01 ON sj_job (namespace_id, group_name);
CREATE INDEX idx_sj_job_02 ON sj_job (job_status, bucket_index);
CREATE INDEX idx_sj_job_03 ON sj_job (create_dt);
CREATE UNIQUE INDEX uk_sj_job_01 ON sj_job (namespace_id, biz_id);

COMMENT ON COLUMN sj_job.id IS '主键';
COMMENT ON COLUMN sj_job.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_job.biz_id IS '业务ID';
COMMENT ON COLUMN sj_job.group_name IS '组名称';
COMMENT ON COLUMN sj_job.job_name IS '名称';
COMMENT ON COLUMN sj_job.args_str IS '执行方法参数';
COMMENT ON COLUMN sj_job.args_type IS '参数类型 ';
COMMENT ON COLUMN sj_job.next_trigger_at IS '下次触发时间';
COMMENT ON COLUMN sj_job.job_status IS '任务状态 0、关闭、1、开启';
COMMENT ON COLUMN sj_job.task_type IS '任务类型 1、集群 2、广播 3、切片';
COMMENT ON COLUMN sj_job.route_key IS '路由策略';
COMMENT ON COLUMN sj_job.executor_type IS '执行器类型';
COMMENT ON COLUMN sj_job.executor_info IS '执行器名称';
COMMENT ON COLUMN sj_job.trigger_type IS '触发类型 1.CRON 表达式 2. 固定时间';
COMMENT ON COLUMN sj_job.trigger_interval IS '间隔时长';
COMMENT ON COLUMN sj_job.block_strategy IS '阻塞策略 1、丢弃 2、覆盖 3、并行 4、恢复';
COMMENT ON COLUMN sj_job.executor_timeout IS '任务执行超时时间，单位秒';
COMMENT ON COLUMN sj_job.max_retry_times IS '最大重试次数';
COMMENT ON COLUMN sj_job.parallel_num IS '并行数';
COMMENT ON COLUMN sj_job.retry_interval IS '重试间隔 ( s)';
COMMENT ON COLUMN sj_job.bucket_index IS 'bucket';
COMMENT ON COLUMN sj_job.resident IS '是否是常驻任务';
COMMENT ON COLUMN sj_job.notify_ids IS '通知告警场景配置id列表';
COMMENT ON COLUMN sj_job.owner_id IS '负责人id';
COMMENT ON COLUMN sj_job.labels IS '标签';
COMMENT ON COLUMN sj_job.description IS '描述';
COMMENT ON COLUMN sj_job.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_job.deleted IS '逻辑删除 1、删除';
COMMENT ON COLUMN sj_job.create_dt IS '创建时间';
COMMENT ON COLUMN sj_job.update_dt IS '修改时间';
COMMENT ON TABLE sj_job IS '任务信息';

INSERT INTO sj_job VALUES (1, 'dev', 'demo-job', 'ruoyi_group', 'demo-job', null, 1, 1710344035622, 1, 1, 4, 1, 'testJobExecutor', 2, '60', 1, 60, 3, 1, 1, 116, 0, '', 1, '', '', '', 0, now(), now());

-- sj_job_log_message
CREATE TABLE sj_job_log_message
(
    id            bigserial PRIMARY KEY,
    namespace_id  varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name    varchar(64)  NOT NULL,
    job_id        bigint       NOT NULL,
    task_batch_id bigint       NOT NULL,
    task_id       bigint       NOT NULL,
    message       text         NOT NULL,
    log_num       int          NOT NULL DEFAULT 1,
    real_time     bigint       NOT NULL DEFAULT 0,
    ext_attrs     varchar(256) NULL     DEFAULT '',
    create_dt     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_job_log_message_01 ON sj_job_log_message (task_batch_id, task_id);
CREATE INDEX idx_sj_job_log_message_02 ON sj_job_log_message (create_dt);
CREATE INDEX idx_sj_job_log_message_03 ON sj_job_log_message (namespace_id, group_name);

COMMENT ON COLUMN sj_job_log_message.id IS '主键';
COMMENT ON COLUMN sj_job_log_message.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_job_log_message.group_name IS '组名称';
COMMENT ON COLUMN sj_job_log_message.job_id IS '任务信息id';
COMMENT ON COLUMN sj_job_log_message.task_batch_id IS '任务批次id';
COMMENT ON COLUMN sj_job_log_message.task_id IS '调度任务id';
COMMENT ON COLUMN sj_job_log_message.message IS '调度信息';
COMMENT ON COLUMN sj_job_log_message.log_num IS '日志数量';
COMMENT ON COLUMN sj_job_log_message.real_time IS '上报时间';
COMMENT ON COLUMN sj_job_log_message.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_job_log_message.create_dt IS '创建时间';
COMMENT ON TABLE sj_job_log_message IS '调度日志';

-- sj_job_task
CREATE TABLE sj_job_task
(
    id             bigserial PRIMARY KEY,
    namespace_id   varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name     varchar(64)  NOT NULL,
    job_id         bigint       NOT NULL,
    task_batch_id  bigint       NOT NULL,
    parent_id      bigint       NOT NULL DEFAULT 0,
    task_status    smallint     NOT NULL DEFAULT 0,
    retry_count    int          NOT NULL DEFAULT 0,
    mr_stage       smallint     NULL     DEFAULT NULL,
    leaf           smallint     NOT NULL DEFAULT '1',
    task_name      varchar(255) NOT NULL DEFAULT '',
    client_info    varchar(128) NULL     DEFAULT NULL,
    wf_context     text         NULL     DEFAULT NULL,
    result_message text         NOT NULL,
    args_str       text         NULL     DEFAULT NULL,
    args_type      smallint     NOT NULL DEFAULT 1,
    ext_attrs      varchar(256) NULL     DEFAULT '',
    create_dt      timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt      timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_job_task_01 ON sj_job_task (task_batch_id, task_status);
CREATE INDEX idx_sj_job_task_02 ON sj_job_task (create_dt);
CREATE INDEX idx_sj_job_task_03 ON sj_job_task (namespace_id, group_name);

COMMENT ON COLUMN sj_job_task.id IS '主键';
COMMENT ON COLUMN sj_job_task.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_job_task.group_name IS '组名称';
COMMENT ON COLUMN sj_job_task.job_id IS '任务信息id';
COMMENT ON COLUMN sj_job_task.task_batch_id IS '调度任务id';
COMMENT ON COLUMN sj_job_task.parent_id IS '父执行器id';
COMMENT ON COLUMN sj_job_task.task_status IS '执行的状态 0、失败 1、成功';
COMMENT ON COLUMN sj_job_task.retry_count IS '重试次数';
COMMENT ON COLUMN sj_job_task.mr_stage IS '动态分片所处阶段 1:map 2:reduce 3:mergeReduce';
COMMENT ON COLUMN sj_job_task.leaf IS '叶子节点';
COMMENT ON COLUMN sj_job_task.task_name IS '任务名称';
COMMENT ON COLUMN sj_job_task.client_info IS '客户端地址 clientId#ip:port';
COMMENT ON COLUMN sj_job_task.wf_context IS '工作流全局上下文';
COMMENT ON COLUMN sj_job_task.result_message IS '执行结果';
COMMENT ON COLUMN sj_job_task.args_str IS '执行方法参数';
COMMENT ON COLUMN sj_job_task.args_type IS '参数类型 ';
COMMENT ON COLUMN sj_job_task.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_job_task.create_dt IS '创建时间';
COMMENT ON COLUMN sj_job_task.update_dt IS '修改时间';
COMMENT ON TABLE sj_job_task IS '任务实例';

-- sj_job_task_batch
CREATE TABLE sj_job_task_batch
(
    id                      bigserial PRIMARY KEY,
    namespace_id            varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name              varchar(64)  NOT NULL,
    job_id                  bigint       NOT NULL,
    workflow_node_id        bigint       NOT NULL DEFAULT 0,
    parent_workflow_node_id bigint       NOT NULL DEFAULT 0,
    workflow_task_batch_id  bigint       NOT NULL DEFAULT 0,
    task_batch_status       smallint     NOT NULL DEFAULT 0,
    operation_reason        smallint     NOT NULL DEFAULT 0,
    execution_at            bigint       NOT NULL DEFAULT 0,
    system_task_type        smallint     NOT NULL DEFAULT 3,
    parent_id               varchar(64)  NOT NULL DEFAULT '',
    ext_attrs               varchar(256) NULL     DEFAULT '',
    deleted                 smallint     NOT NULL DEFAULT 0,
    create_dt               timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt               timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_job_task_batch_01 ON sj_job_task_batch (job_id, task_batch_status);
CREATE INDEX idx_sj_job_task_batch_02 ON sj_job_task_batch (create_dt);
CREATE INDEX idx_sj_job_task_batch_03 ON sj_job_task_batch (namespace_id, group_name);
CREATE INDEX idx_sj_job_task_batch_04 ON sj_job_task_batch (workflow_task_batch_id, workflow_node_id);

COMMENT ON COLUMN sj_job_task_batch.id IS '主键';
COMMENT ON COLUMN sj_job_task_batch.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_job_task_batch.group_name IS '组名称';
COMMENT ON COLUMN sj_job_task_batch.job_id IS '任务id';
COMMENT ON COLUMN sj_job_task_batch.workflow_node_id IS '工作流节点id';
COMMENT ON COLUMN sj_job_task_batch.parent_workflow_node_id IS '工作流任务父批次id';
COMMENT ON COLUMN sj_job_task_batch.workflow_task_batch_id IS '工作流任务批次id';
COMMENT ON COLUMN sj_job_task_batch.task_batch_status IS '任务批次状态 0、失败 1、成功';
COMMENT ON COLUMN sj_job_task_batch.operation_reason IS '操作原因';
COMMENT ON COLUMN sj_job_task_batch.execution_at IS '任务执行时间';
COMMENT ON COLUMN sj_job_task_batch.system_task_type IS '任务类型 3、JOB任务 4、WORKFLOW任务';
COMMENT ON COLUMN sj_job_task_batch.parent_id IS '父节点';
COMMENT ON COLUMN sj_job_task_batch.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_job_task_batch.deleted IS '逻辑删除 1、删除';
COMMENT ON COLUMN sj_job_task_batch.create_dt IS '创建时间';
COMMENT ON COLUMN sj_job_task_batch.update_dt IS '修改时间';
COMMENT ON TABLE sj_job_task_batch IS '任务批次';

-- sj_job_summary
CREATE TABLE sj_job_summary
(
    id               bigserial PRIMARY KEY,
    namespace_id     varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name       varchar(64)  NOT NULL DEFAULT '',
    business_id      bigint       NOT NULL,
    system_task_type smallint     NOT NULL DEFAULT 3,
    trigger_at       timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    success_num      int          NOT NULL DEFAULT 0,
    fail_num         int          NOT NULL DEFAULT 0,
    fail_reason      varchar(512) NOT NULL DEFAULT '',
    stop_num         int          NOT NULL DEFAULT 0,
    stop_reason      varchar(512) NOT NULL DEFAULT '',
    cancel_num       int          NOT NULL DEFAULT 0,
    cancel_reason    varchar(512) NOT NULL DEFAULT '',
    create_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_sj_job_summary_01 ON sj_job_summary (trigger_at, system_task_type, business_id);

CREATE INDEX idx_sj_job_summary_01 ON sj_job_summary (namespace_id, group_name, business_id);

COMMENT ON COLUMN sj_job_summary.id IS '主键';
COMMENT ON COLUMN sj_job_summary.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_job_summary.group_name IS '组名称';
COMMENT ON COLUMN sj_job_summary.business_id IS '业务id  ( job_id或workflow_id)';
COMMENT ON COLUMN sj_job_summary.system_task_type IS '任务类型 3、JOB任务 4、WORKFLOW任务';
COMMENT ON COLUMN sj_job_summary.trigger_at IS '统计时间';
COMMENT ON COLUMN sj_job_summary.success_num IS '执行成功-日志数量';
COMMENT ON COLUMN sj_job_summary.fail_num IS '执行失败-日志数量';
COMMENT ON COLUMN sj_job_summary.fail_reason IS '失败原因';
COMMENT ON COLUMN sj_job_summary.stop_num IS '执行失败-日志数量';
COMMENT ON COLUMN sj_job_summary.stop_reason IS '失败原因';
COMMENT ON COLUMN sj_job_summary.cancel_num IS '执行失败-日志数量';
COMMENT ON COLUMN sj_job_summary.cancel_reason IS '失败原因';
COMMENT ON COLUMN sj_job_summary.create_dt IS '创建时间';
COMMENT ON COLUMN sj_job_summary.update_dt IS '修改时间';
COMMENT ON TABLE sj_job_summary IS 'DashBoard_Job';

-- sj_retry_summary
CREATE TABLE sj_retry_summary
(
    id            bigserial PRIMARY KEY,
    namespace_id  varchar(64) NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name    varchar(64) NOT NULL DEFAULT '',
    scene_name    varchar(64) NOT NULL DEFAULT '',
    trigger_at    timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    running_num   int         NOT NULL DEFAULT 0,
    finish_num    int         NOT NULL DEFAULT 0,
    max_count_num int         NOT NULL DEFAULT 0,
    suspend_num   int         NOT NULL DEFAULT 0,
    create_dt     timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt     timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_sj_retry_summary_01 ON sj_retry_summary (namespace_id, group_name, scene_name, trigger_at);

CREATE INDEX idx_sj_retry_summary_01 ON sj_retry_summary (trigger_at);

COMMENT ON COLUMN sj_retry_summary.id IS '主键';
COMMENT ON COLUMN sj_retry_summary.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_retry_summary.group_name IS '组名称';
COMMENT ON COLUMN sj_retry_summary.scene_name IS '场景名称';
COMMENT ON COLUMN sj_retry_summary.trigger_at IS '统计时间';
COMMENT ON COLUMN sj_retry_summary.running_num IS '重试中-日志数量';
COMMENT ON COLUMN sj_retry_summary.finish_num IS '重试完成-日志数量';
COMMENT ON COLUMN sj_retry_summary.max_count_num IS '重试到达最大次数-日志数量';
COMMENT ON COLUMN sj_retry_summary.suspend_num IS '暂停重试-日志数量';
COMMENT ON COLUMN sj_retry_summary.create_dt IS '创建时间';
COMMENT ON COLUMN sj_retry_summary.update_dt IS '修改时间';
COMMENT ON TABLE sj_retry_summary IS 'DashBoard_Retry';

-- sj_workflow
CREATE TABLE sj_workflow
(
    id               bigserial PRIMARY KEY,
    workflow_name    varchar(64)  NOT NULL,
    namespace_id     varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    biz_id           varchar(64)  NOT NULL,
    group_name       varchar(64)  NOT NULL,
    workflow_status  smallint     NOT NULL DEFAULT 1,
    trigger_type     smallint     NOT NULL,
    trigger_interval varchar(255) NOT NULL,
    next_trigger_at  bigint       NOT NULL,
    block_strategy   smallint     NOT NULL DEFAULT 1,
    executor_timeout int          NOT NULL DEFAULT 0,
    description      varchar(256) NOT NULL DEFAULT '',
    flow_info        text         NULL     DEFAULT NULL,
    wf_context       text         NULL     DEFAULT NULL,
    notify_ids       varchar(128) NOT NULL DEFAULT '',
    bucket_index     int          NOT NULL DEFAULT 0,
    version          int          NOT NULL,
    owner_id         bigint       NULL     DEFAULT NULL,
    ext_attrs        varchar(256) NULL     DEFAULT '',
    deleted          smallint     NOT NULL DEFAULT 0,
    create_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt        timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_workflow_01 ON sj_workflow (create_dt);
CREATE INDEX idx_sj_workflow_02 ON sj_workflow (namespace_id, group_name);
CREATE UNIQUE INDEX uk_sj_workflow_01 ON sj_workflow (namespace_id, biz_id);

COMMENT ON COLUMN sj_workflow.id IS '主键';
COMMENT ON COLUMN sj_workflow.workflow_name IS '工作流名称';
COMMENT ON COLUMN sj_workflow.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_workflow.biz_id IS '业务ID';
COMMENT ON COLUMN sj_workflow.group_name IS '组名称';
COMMENT ON COLUMN sj_workflow.workflow_status IS '工作流状态 0、关闭、1、开启';
COMMENT ON COLUMN sj_workflow.trigger_type IS '触发类型 1.CRON 表达式 2. 固定时间';
COMMENT ON COLUMN sj_workflow.trigger_interval IS '间隔时长';
COMMENT ON COLUMN sj_workflow.next_trigger_at IS '下次触发时间';
COMMENT ON COLUMN sj_workflow.block_strategy IS '阻塞策略 1、丢弃 2、覆盖 3、并行';
COMMENT ON COLUMN sj_workflow.executor_timeout IS '任务执行超时时间，单位秒';
COMMENT ON COLUMN sj_workflow.description IS '描述';
COMMENT ON COLUMN sj_workflow.flow_info IS '流程信息';
COMMENT ON COLUMN sj_workflow.wf_context IS '上下文';
COMMENT ON COLUMN sj_workflow.notify_ids IS '通知告警场景配置id列表';
COMMENT ON COLUMN sj_workflow.bucket_index IS 'bucket';
COMMENT ON COLUMN sj_workflow.version IS '版本号';
COMMENT ON COLUMN sj_workflow.owner_id IS '负责人id';
COMMENT ON COLUMN sj_workflow.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_workflow.deleted IS '逻辑删除 1、删除';
COMMENT ON COLUMN sj_workflow.create_dt IS '创建时间';
COMMENT ON COLUMN sj_workflow.update_dt IS '修改时间';
COMMENT ON TABLE sj_workflow IS '工作流';

-- sj_workflow_node
CREATE TABLE sj_workflow_node
(
    id                   bigserial PRIMARY KEY,
    namespace_id         varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    node_name            varchar(64)  NOT NULL,
    group_name           varchar(64)  NOT NULL,
    job_id               bigint       NOT NULL,
    workflow_id          bigint       NOT NULL,
    node_type            smallint     NOT NULL DEFAULT 1,
    expression_type      smallint     NOT NULL DEFAULT 0,
    fail_strategy        smallint     NOT NULL DEFAULT 1,
    workflow_node_status smallint     NOT NULL DEFAULT 1,
    priority_level       int          NOT NULL DEFAULT 1,
    node_info            text         NULL     DEFAULT NULL,
    version              int          NOT NULL,
    ext_attrs            varchar(256) NULL     DEFAULT '',
    deleted              smallint     NOT NULL DEFAULT 0,
    create_dt            timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt            timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_workflow_node_01 ON sj_workflow_node (create_dt);
CREATE INDEX idx_sj_workflow_node_02 ON sj_workflow_node (namespace_id, group_name);

COMMENT ON COLUMN sj_workflow_node.id IS '主键';
COMMENT ON COLUMN sj_workflow_node.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_workflow_node.node_name IS '节点名称';
COMMENT ON COLUMN sj_workflow_node.group_name IS '组名称';
COMMENT ON COLUMN sj_workflow_node.job_id IS '任务信息id';
COMMENT ON COLUMN sj_workflow_node.workflow_id IS '工作流ID';
COMMENT ON COLUMN sj_workflow_node.node_type IS '1、任务节点 2、条件节点';
COMMENT ON COLUMN sj_workflow_node.expression_type IS '1、SpEl、2、Aviator 3、QL';
COMMENT ON COLUMN sj_workflow_node.fail_strategy IS '失败策略 1、跳过 2、阻塞';
COMMENT ON COLUMN sj_workflow_node.workflow_node_status IS '工作流节点状态 0、关闭、1、开启';
COMMENT ON COLUMN sj_workflow_node.priority_level IS '优先级';
COMMENT ON COLUMN sj_workflow_node.node_info IS '节点信息 ';
COMMENT ON COLUMN sj_workflow_node.version IS '版本号';
COMMENT ON COLUMN sj_workflow_node.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_workflow_node.deleted IS '逻辑删除 1、删除';
COMMENT ON COLUMN sj_workflow_node.create_dt IS '创建时间';
COMMENT ON COLUMN sj_workflow_node.update_dt IS '修改时间';
COMMENT ON TABLE sj_workflow_node IS '工作流节点';

-- sj_workflow_task_batch
CREATE TABLE sj_workflow_task_batch
(
    id                bigserial PRIMARY KEY,
    namespace_id      varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name        varchar(64)  NOT NULL,
    workflow_id       bigint       NOT NULL,
    task_batch_status smallint     NOT NULL DEFAULT 0,
    operation_reason  smallint     NOT NULL DEFAULT 0,
    flow_info         text         NULL     DEFAULT NULL,
    wf_context        text         NULL     DEFAULT NULL,
    execution_at      bigint       NOT NULL DEFAULT 0,
    ext_attrs         varchar(256) NULL     DEFAULT '',
    version           int          NOT NULL DEFAULT 1,
    deleted           smallint     NOT NULL DEFAULT 0,
    create_dt         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_workflow_task_batch_01 ON sj_workflow_task_batch (workflow_id, task_batch_status);
CREATE INDEX idx_sj_workflow_task_batch_02 ON sj_workflow_task_batch (create_dt);
CREATE INDEX idx_sj_workflow_task_batch_03 ON sj_workflow_task_batch (namespace_id, group_name);

COMMENT ON COLUMN sj_workflow_task_batch.id IS '主键';
COMMENT ON COLUMN sj_workflow_task_batch.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_workflow_task_batch.group_name IS '组名称';
COMMENT ON COLUMN sj_workflow_task_batch.workflow_id IS '工作流任务id';
COMMENT ON COLUMN sj_workflow_task_batch.task_batch_status IS '任务批次状态 0、失败 1、成功';
COMMENT ON COLUMN sj_workflow_task_batch.operation_reason IS '操作原因';
COMMENT ON COLUMN sj_workflow_task_batch.flow_info IS '流程信息';
COMMENT ON COLUMN sj_workflow_task_batch.wf_context IS '全局上下文';
COMMENT ON COLUMN sj_workflow_task_batch.execution_at IS '任务执行时间';
COMMENT ON COLUMN sj_workflow_task_batch.ext_attrs IS '扩展字段';
COMMENT ON COLUMN sj_workflow_task_batch.version IS '版本号';
COMMENT ON COLUMN sj_workflow_task_batch.deleted IS '逻辑删除 1、删除';
COMMENT ON COLUMN sj_workflow_task_batch.create_dt IS '创建时间';
COMMENT ON COLUMN sj_workflow_task_batch.update_dt IS '修改时间';
COMMENT ON TABLE sj_workflow_task_batch IS '工作流批次';

-- sj_job_executor
CREATE TABLE sj_job_executor
(
    id            bigserial PRIMARY KEY,
    namespace_id  varchar(64)  NOT NULL DEFAULT '764d604ec6fc45f68cd92514c40e9e1a',
    group_name    varchar(64)  NOT NULL,
    executor_info varchar(256) NOT NULL,
    executor_type varchar(3)   NOT NULL,
    create_dt     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt     timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sj_job_executor_01 ON sj_job_executor (namespace_id, group_name);
CREATE INDEX idx_sj_job_executor_02 ON sj_job_executor (create_dt);

COMMENT ON COLUMN sj_job_executor.id IS '主键';
COMMENT ON COLUMN sj_job_executor.namespace_id IS '命名空间id';
COMMENT ON COLUMN sj_job_executor.group_name IS '组名称';
COMMENT ON COLUMN sj_job_executor.executor_info IS '任务执行器名称';
COMMENT ON COLUMN sj_job_executor.executor_type IS '1:java 2:python 3:go';
COMMENT ON COLUMN sj_job_executor.create_dt IS '创建时间';
COMMENT ON COLUMN sj_job_executor.update_dt IS '修改时间';
COMMENT ON TABLE sj_job_executor IS '任务执行器信息';

-- ========== postgres_ry_workflow.sql（工作流表）==========
CREATE TABLE flow_definition
(
    id              int8         NOT NULL,
    flow_code       varchar(40)  NOT NULL,
    flow_name       varchar(100) NOT NULL,
    model_value     varchar(40)  NOT NULL DEFAULT 'CLASSICS',
    category        varchar(100) NULL,
    "version"       varchar(20)  NOT NULL,
    is_publish      int2         NOT NULL DEFAULT 0,
    form_custom     bpchar(1)    NULL     DEFAULT 'N':: character varying,
    form_path       varchar(100) NULL,
    activity_status int2         NOT NULL DEFAULT 1,
    listener_type   varchar(100) NULL,
    listener_path   varchar(400) NULL,
    ext             varchar(500) NULL,
    create_time     timestamp    NULL,
    create_by       varchar(64)  NULL     DEFAULT '':: character varying,
    update_time     timestamp    NULL,
    update_by       varchar(64)  NULL     DEFAULT '':: character varying,
    del_flag        bpchar(1)    NULL     DEFAULT '0':: character varying,
    tenant_id       varchar(40)  NULL,
    CONSTRAINT flow_definition_pkey PRIMARY KEY (id)
);
COMMENT ON TABLE flow_definition IS '流程定义表';

COMMENT ON COLUMN flow_definition.id IS '主键id';
COMMENT ON COLUMN flow_definition.flow_code IS '流程编码';
COMMENT ON COLUMN flow_definition.flow_name IS '流程名称';
COMMENT ON COLUMN flow_definition.model_value IS '设计器模型（CLASSICS经典模型 MIMIC仿钉钉模型）';
COMMENT ON COLUMN flow_definition.category IS '流程类别';
COMMENT ON COLUMN flow_definition."version" IS '流程版本';
COMMENT ON COLUMN flow_definition.is_publish IS '是否发布（0未发布 1已发布 9失效）';
COMMENT ON COLUMN flow_definition.form_custom IS '审批表单是否自定义（Y是 N否）';
COMMENT ON COLUMN flow_definition.form_path IS '审批表单路径';
COMMENT ON COLUMN flow_definition.activity_status IS '流程激活状态（0挂起 1激活）';
COMMENT ON COLUMN flow_definition.listener_type IS '监听器类型';
COMMENT ON COLUMN flow_definition.listener_path IS '监听器路径';
COMMENT ON COLUMN flow_definition.ext IS '扩展字段，预留给业务系统使用';
COMMENT ON COLUMN flow_definition.create_time IS '创建时间';
COMMENT ON COLUMN flow_definition.create_by IS '创建人';
COMMENT ON COLUMN flow_definition.update_time IS '更新时间';
COMMENT ON COLUMN flow_definition.update_by IS '更新人';
COMMENT ON COLUMN flow_definition.del_flag IS '删除标志';
COMMENT ON COLUMN flow_definition.tenant_id IS '租户id';

CREATE TABLE flow_node
(
    id              int8          NOT NULL,
    node_type       int2          NOT NULL,
    definition_id   int8          NOT NULL,
    node_code       varchar(100)  NOT NULL,
    node_name       varchar(100)  NULL,
    permission_flag varchar(200)  NULL,
    node_ratio      varchar(200)  NULL,
    coordinate      varchar(100)  NULL,
    any_node_skip   varchar(100)  NULL,
    listener_type   varchar(100)  NULL,
    listener_path   varchar(400)  NULL,
    form_custom     bpchar(1)     NULL DEFAULT 'N':: character varying,
    form_path       varchar(100)  NULL,
    "version"       varchar(20)   NOT NULL,
    create_time     timestamp    NULL,
    create_by       varchar(64)  NULL DEFAULT '':: character varying,
    update_time     timestamp    NULL,
    update_by       varchar(64)  NULL DEFAULT '':: character varying,
    ext             text         NULL,
    del_flag        bpchar(1)     NULL DEFAULT '0':: character varying,
    tenant_id       varchar(40)   NULL,
    CONSTRAINT flow_node_pkey PRIMARY KEY (id)
);
COMMENT ON TABLE flow_node IS '流程节点表';

COMMENT ON COLUMN flow_node.id IS '主键id';
COMMENT ON COLUMN flow_node.node_type IS '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';
COMMENT ON COLUMN flow_node.definition_id IS '流程定义id';
COMMENT ON COLUMN flow_node.node_code IS '流程节点编码';
COMMENT ON COLUMN flow_node.node_name IS '流程节点名称';
COMMENT ON COLUMN flow_node.permission_flag IS '权限标识（权限类型:权限标识，可以多个，用@@隔开)';
COMMENT ON COLUMN flow_node.node_ratio IS '流程签署比例值';
COMMENT ON COLUMN flow_node.coordinate IS '坐标';
COMMENT ON COLUMN flow_node.any_node_skip IS '任意结点跳转';
COMMENT ON COLUMN flow_node.listener_type IS '监听器类型';
COMMENT ON COLUMN flow_node.listener_path IS '监听器路径';
COMMENT ON COLUMN flow_node.form_custom IS '审批表单是否自定义（Y是 N否）';
COMMENT ON COLUMN flow_node.form_path IS '审批表单路径';
COMMENT ON COLUMN flow_node."version" IS '版本';
COMMENT ON COLUMN flow_node.create_time IS '创建时间';
COMMENT ON COLUMN flow_node.create_by IS '创建人';
COMMENT ON COLUMN flow_node.update_time IS '更新时间';
COMMENT ON COLUMN flow_node.update_by IS '更新人';
COMMENT ON COLUMN flow_node.ext IS '节点扩展属性';
COMMENT ON COLUMN flow_node.del_flag IS '删除标志';
COMMENT ON COLUMN flow_node.tenant_id IS '租户id';


CREATE TABLE flow_skip
(
    id             int8         NOT NULL,
    definition_id  int8         NOT NULL,
    now_node_code  varchar(100) NOT NULL,
    now_node_type  int2         NULL,
    next_node_code varchar(100) NOT NULL,
    next_node_type int2         NULL,
    skip_name      varchar(100) NULL,
    skip_type      varchar(40)  NULL,
    skip_condition varchar(200) NULL,
    coordinate     varchar(100) NULL,
    create_time    timestamp    NULL,
    create_by      varchar(64)  NULL DEFAULT '':: character varying,
    update_time    timestamp    NULL,
    update_by      varchar(64)  NULL DEFAULT '':: character varying,
    del_flag       bpchar(1)    NULL DEFAULT '0':: character varying,
    tenant_id      varchar(40)  NULL,
    CONSTRAINT flow_skip_pkey PRIMARY KEY (id)
);
COMMENT ON TABLE flow_skip IS '节点跳转关联表';

COMMENT ON COLUMN flow_skip.id IS '主键id';
COMMENT ON COLUMN flow_skip.definition_id IS '流程定义id';
COMMENT ON COLUMN flow_skip.now_node_code IS '当前流程节点的编码';
COMMENT ON COLUMN flow_skip.now_node_type IS '当前节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';
COMMENT ON COLUMN flow_skip.next_node_code IS '下一个流程节点的编码';
COMMENT ON COLUMN flow_skip.next_node_type IS '下一个节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';
COMMENT ON COLUMN flow_skip.skip_name IS '跳转名称';
COMMENT ON COLUMN flow_skip.skip_type IS '跳转类型（PASS审批通过 REJECT退回）';
COMMENT ON COLUMN flow_skip.skip_condition IS '跳转条件';
COMMENT ON COLUMN flow_skip.coordinate IS '坐标';
COMMENT ON COLUMN flow_skip.create_time IS '创建时间';
COMMENT ON COLUMN flow_skip.create_by IS '创建人';
COMMENT ON COLUMN flow_skip.update_time IS '更新时间';
COMMENT ON COLUMN flow_skip.update_by IS '更新人';
COMMENT ON COLUMN flow_skip.del_flag IS '删除标志';
COMMENT ON COLUMN flow_skip.tenant_id IS '租户id';

CREATE TABLE flow_instance
(
    id              int8         NOT NULL,
    definition_id   int8         NOT NULL,
    business_id     varchar(40)  NOT NULL,
    node_type       int2         NOT NULL,
    node_code       varchar(40)  NOT NULL,
    node_name       varchar(100) NULL,
    variable        text         NULL,
    flow_status     varchar(20)  NOT NULL,
    activity_status int2         NOT NULL DEFAULT 1,
    def_json        text         NULL,
    create_time     timestamp    NULL,
    create_by       varchar(64)  NULL DEFAULT '':: character varying,
    update_time     timestamp    NULL,
    update_by       varchar(64)  NULL DEFAULT '':: character varying,
    ext             varchar(500) NULL,
    del_flag        bpchar(1)    NULL     DEFAULT '0':: character varying,
    tenant_id       varchar(40)  NULL,
    CONSTRAINT flow_instance_pkey PRIMARY KEY (id)
);
COMMENT ON TABLE flow_instance IS '流程实例表';

COMMENT ON COLUMN flow_instance.id IS '主键id';
COMMENT ON COLUMN flow_instance.definition_id IS '对应flow_definition表的id';
COMMENT ON COLUMN flow_instance.business_id IS '业务id';
COMMENT ON COLUMN flow_instance.node_type IS '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';
COMMENT ON COLUMN flow_instance.node_code IS '流程节点编码';
COMMENT ON COLUMN flow_instance.node_name IS '流程节点名称';
COMMENT ON COLUMN flow_instance.variable IS '任务变量';
COMMENT ON COLUMN flow_instance.flow_status IS '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）';
COMMENT ON COLUMN flow_instance.activity_status IS '流程激活状态（0挂起 1激活）';
COMMENT ON COLUMN flow_instance.def_json IS '流程定义json';
COMMENT ON COLUMN flow_instance.create_time IS '创建时间';
COMMENT ON COLUMN flow_instance.create_by IS '创建人';
COMMENT ON COLUMN flow_instance.update_time IS '更新时间';
COMMENT ON COLUMN flow_instance.update_by IS '更新人';
COMMENT ON COLUMN flow_instance.ext IS '扩展字段，预留给业务系统使用';
COMMENT ON COLUMN flow_instance.del_flag IS '删除标志';
COMMENT ON COLUMN flow_instance.tenant_id IS '租户id';

CREATE TABLE flow_task
(
    id            int8         NOT NULL,
    definition_id int8         NOT NULL,
    instance_id   int8         NOT NULL,
    node_code     varchar(100) NOT NULL,
    node_name     varchar(100) NULL,
    node_type     int2         NOT NULL,
    flow_status   varchar(20)  NOT NULL,
    form_custom   bpchar(1)    NULL DEFAULT 'N':: character varying,
    form_path     varchar(100) NULL,
    create_time   timestamp    NULL,
    create_by     varchar(64)  NULL DEFAULT '':: character varying,
    update_time   timestamp    NULL,
    update_by     varchar(64)  NULL DEFAULT '':: character varying,
    del_flag      bpchar(1)    NULL DEFAULT '0':: character varying,
    tenant_id     varchar(40)  NULL,
    CONSTRAINT flow_task_pkey PRIMARY KEY (id)
);
COMMENT ON TABLE flow_task IS '待办任务表';

COMMENT ON COLUMN flow_task.id IS '主键id';
COMMENT ON COLUMN flow_task.definition_id IS '对应flow_definition表的id';
COMMENT ON COLUMN flow_task.instance_id IS '对应flow_instance表的id';
COMMENT ON COLUMN flow_task.node_code IS '节点编码';
COMMENT ON COLUMN flow_task.node_name IS '节点名称';
COMMENT ON COLUMN flow_task.node_type IS '节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';
COMMENT ON COLUMN flow_task.flow_status IS '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）';
COMMENT ON COLUMN flow_task.form_custom IS '审批表单是否自定义（Y是 N否）';
COMMENT ON COLUMN flow_task.form_path IS '审批表单路径';
COMMENT ON COLUMN flow_task.create_time IS '创建时间';
COMMENT ON COLUMN flow_task.create_by IS '创建人';
COMMENT ON COLUMN flow_task.update_time IS '更新时间';
COMMENT ON COLUMN flow_task.update_by IS '更新人';
COMMENT ON COLUMN flow_task.del_flag IS '删除标志';
COMMENT ON COLUMN flow_task.tenant_id IS '租户id';

CREATE TABLE flow_his_task
(
    id               int8         NOT NULL,
    definition_id    int8         NOT NULL,
    instance_id      int8         NOT NULL,
    task_id          int8         NOT NULL,
    node_code        varchar(100) NULL,
    node_name        varchar(100) NULL,
    node_type        int2         NULL,
    target_node_code varchar(200) NULL,
    target_node_name varchar(200) NULL,
    approver         varchar(40)  NULL,
    cooperate_type   int2         NOT NULL DEFAULT 0,
    collaborator     varchar(500)  NULL,
    skip_type        varchar(10)  NULL,
    flow_status      varchar(20)  NOT NULL,
    form_custom      bpchar(1)    NULL     DEFAULT 'N':: character varying,
    form_path        varchar(100) NULL,
    ext              text         NULL,
    message          varchar(500) NULL,
    variable         text         NULL,
    create_time      timestamp    NULL,
    update_time      timestamp    NULL,
    del_flag         bpchar(1)    NULL     DEFAULT '0':: character varying,
    tenant_id        varchar(40)  NULL,
    CONSTRAINT flow_his_task_pkey PRIMARY KEY (id)
);
COMMENT ON TABLE flow_his_task IS '历史任务记录表';

COMMENT ON COLUMN flow_his_task.id IS '主键id';
COMMENT ON COLUMN flow_his_task.definition_id IS '对应flow_definition表的id';
COMMENT ON COLUMN flow_his_task.instance_id IS '对应flow_instance表的id';
COMMENT ON COLUMN flow_his_task.task_id IS '对应flow_task表的id';
COMMENT ON COLUMN flow_his_task.node_code IS '开始节点编码';
COMMENT ON COLUMN flow_his_task.node_name IS '开始节点名称';
COMMENT ON COLUMN flow_his_task.node_type IS '开始节点类型（0开始节点 1中间节点 2结束节点 3互斥网关 4并行网关）';
COMMENT ON COLUMN flow_his_task.target_node_code IS '目标节点编码';
COMMENT ON COLUMN flow_his_task.target_node_name IS '结束节点名称';
COMMENT ON COLUMN flow_his_task.approver IS '审批者';
COMMENT ON COLUMN flow_his_task.cooperate_type IS '协作方式(1审批 2转办 3委派 4会签 5票签 6加签 7减签)';
COMMENT ON COLUMN flow_his_task.collaborator IS '协作人';
COMMENT ON COLUMN flow_his_task.skip_type IS '流转类型（PASS通过 REJECT退回 NONE无动作）';
COMMENT ON COLUMN flow_his_task.flow_status IS '流程状态（0待提交 1审批中 2审批通过 4终止 5作废 6撤销 8已完成 9已退回 10失效 11拿回）';
COMMENT ON COLUMN flow_his_task.form_custom IS '审批表单是否自定义（Y是 N否）';
COMMENT ON COLUMN flow_his_task.form_path IS '审批表单路径';
COMMENT ON COLUMN flow_his_task.message IS '审批意见';
COMMENT ON COLUMN flow_his_task.variable IS '任务变量';
COMMENT ON COLUMN flow_his_task.ext IS '扩展字段，预留给业务系统使用';
COMMENT ON COLUMN flow_his_task.create_time IS '任务开始时间';
COMMENT ON COLUMN flow_his_task.update_time IS '审批完成时间';
COMMENT ON COLUMN flow_his_task.del_flag IS '删除标志';
COMMENT ON COLUMN flow_his_task.tenant_id IS '租户id';

CREATE TABLE flow_user
(
    id           int8        NOT NULL,
    "type"       bpchar(1)   NOT NULL,
    processed_by varchar(80) NULL,
    associated   int8        NOT NULL,
    create_time  timestamp   NULL,
    create_by    varchar(64)  NULL     DEFAULT '':: character varying,
    update_time  timestamp   NULL,
    update_by    varchar(64)  NULL DEFAULT '':: character varying,
    del_flag     bpchar(1)   NULL DEFAULT '0':: character varying,
    tenant_id    varchar(40) NULL,
    CONSTRAINT flow_user_pk PRIMARY KEY (id)
);
CREATE INDEX user_processed_type ON flow_user USING btree (processed_by, type);
CREATE INDEX user_associated_idx ON FLOW_USER USING btree (associated);
COMMENT ON TABLE flow_user IS '流程用户表';

COMMENT ON COLUMN flow_user.id IS '主键id';
COMMENT ON COLUMN flow_user."type" IS '人员类型（1待办任务的审批人权限 2待办任务的转办人权限 3待办任务的委托人权限）';
COMMENT ON COLUMN flow_user.processed_by IS '权限人';
COMMENT ON COLUMN flow_user.associated IS '任务表id';
COMMENT ON COLUMN flow_user.create_time IS '创建时间';
COMMENT ON COLUMN flow_user.create_by IS '创建人';
COMMENT ON COLUMN flow_user.update_time IS '更新时间';
COMMENT ON COLUMN flow_user.update_by IS '更新人';
COMMENT ON COLUMN flow_user.del_flag IS '删除标志';
COMMENT ON COLUMN flow_user.tenant_id IS '租户id';

-- ----------------------------
-- 流程分类表
-- ----------------------------
CREATE TABLE flow_category
(
    category_id   int8         NOT NULL,
    parent_id     int8         DEFAULT 0,
    ancestors     VARCHAR(500) DEFAULT ''::varchar,
    category_name VARCHAR(30)  NOT NULL,
    order_num     INT          DEFAULT 0,
    del_flag      CHAR         DEFAULT '0'::bpchar,
    create_dept   int8,
    create_by     int8,
    create_time   TIMESTAMP,
    update_by     int8,
    update_time   TIMESTAMP,
    PRIMARY KEY (category_id)
);

COMMENT ON TABLE flow_category IS '流程分类';
COMMENT ON COLUMN flow_category.category_id IS '流程分类ID';
COMMENT ON COLUMN flow_category.parent_id IS '父流程分类id';
COMMENT ON COLUMN flow_category.ancestors IS '祖级列表';
COMMENT ON COLUMN flow_category.category_name IS '流程分类名称';
COMMENT ON COLUMN flow_category.order_num IS '显示顺序';
COMMENT ON COLUMN flow_category.del_flag IS '删除标志（0代表存在 1代表删除）';
COMMENT ON COLUMN flow_category.create_dept IS '创建部门';
COMMENT ON COLUMN flow_category.create_by IS '创建者';
COMMENT ON COLUMN flow_category.create_time IS '创建时间';
COMMENT ON COLUMN flow_category.update_by IS '更新者';
COMMENT ON COLUMN flow_category.update_time IS '更新时间';

INSERT INTO flow_category VALUES (1762300000000000100, 0, '0', 'OA审批', 0, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);
INSERT INTO flow_category VALUES (1762300000000000101, 1762300000000000100, '0,1762300000000000100', '假勤管理', 0, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);
INSERT INTO flow_category VALUES (1762300000000000102, 1762300000000000100, '0,1762300000000000100', '人事管理', 1, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);
INSERT INTO flow_category VALUES (1762300000000000103, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '请假', 0, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);
INSERT INTO flow_category VALUES (1762300000000000104, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '出差', 1, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);
INSERT INTO flow_category VALUES (1762300000000000105, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '加班', 2, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);
INSERT INTO flow_category VALUES (1762300000000000106, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '换班', 3, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);
INSERT INTO flow_category VALUES (1762300000000000107, 1762300000000000101, '0,1762300000000000100,1762300000000000101', '外出', 4, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);
INSERT INTO flow_category VALUES (1762300000000000108, 1762300000000000102, '0,1762300000000000100,1762300000000000102', '转正', 1, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);
INSERT INTO flow_category VALUES (1762300000000000109, 1762300000000000102, '0,1762300000000000100,1762300000000000102', '离职', 2, '0', 1761000000000000103, 1761100000000000001, now(), NULL, NULL);

-- ----------------------------
-- 流程spel表达式定义表
-- ----------------------------
CREATE TABLE flow_spel (
    id int8 NOT NULL,
    component_name VARCHAR(255),
    method_name VARCHAR(255),
    method_params VARCHAR(255),
    view_spel VARCHAR(255),
    remark VARCHAR(255),
    status CHAR(1) DEFAULT '0',
    del_flag CHAR(1) DEFAULT '0',
    create_dept int8,
    create_by int8,
    create_time TIMESTAMP,
    update_by int8,
    update_time TIMESTAMP,
    PRIMARY KEY (id)
);

COMMENT ON TABLE flow_spel IS '流程spel表达式定义表';
COMMENT ON COLUMN flow_spel.id IS '主键id';
COMMENT ON COLUMN flow_spel.component_name IS '组件名称';
COMMENT ON COLUMN flow_spel.method_name IS '方法名';
COMMENT ON COLUMN flow_spel.method_params IS '参数';
COMMENT ON COLUMN flow_spel.view_spel IS '预览spel表达式';
COMMENT ON COLUMN flow_spel.remark IS '备注';
COMMENT ON COLUMN flow_spel.status IS '状态（0正常 1停用）';
COMMENT ON COLUMN flow_spel.del_flag IS '删除标志';
COMMENT ON COLUMN flow_spel.create_dept IS '创建部门';
COMMENT ON COLUMN flow_spel.create_by IS '创建者';
COMMENT ON COLUMN flow_spel.create_time IS '创建时间';
COMMENT ON COLUMN flow_spel.update_by IS '更新者';
COMMENT ON COLUMN flow_spel.update_time IS '更新时间';

INSERT INTO flow_spel VALUES (1762400000000000001, 'spelRuleComponent', 'selectDeptLeaderById', 'initiatorDeptId', '#{@spelRuleComponent.selectDeptLeaderById(#initiatorDeptId)}', '根据部门id获取部门负责人', '0', '0', 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now());
INSERT INTO flow_spel VALUES (1762400000000000002, NULL, NULL, 'initiator', '${initiator}', '流程发起人', '0', '0', 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now());

-- ----------------------------
-- 流程实例业务扩展表
-- ----------------------------
CREATE TABLE flow_instance_biz_ext (
    id             int8,
    create_dept    int8,
    create_by      int8,
    create_time    TIMESTAMP,
    update_by      int8,
    update_time    TIMESTAMP,
    business_code  VARCHAR(255),
    business_title VARCHAR(1000),
    del_flag       CHAR(1)       DEFAULT '0',
    instance_id    int8,
    business_id    VARCHAR(255),
    PRIMARY KEY (id)
);

COMMENT ON TABLE flow_instance_biz_ext IS '流程实例业务扩展表';
COMMENT ON COLUMN flow_instance_biz_ext.id  IS '主键id';
COMMENT ON COLUMN flow_instance_biz_ext.create_dept  IS '创建部门';
COMMENT ON COLUMN flow_instance_biz_ext.create_by  IS '创建者';
COMMENT ON COLUMN flow_instance_biz_ext.create_time  IS '创建时间';
COMMENT ON COLUMN flow_instance_biz_ext.update_by  IS '更新者';
COMMENT ON COLUMN flow_instance_biz_ext.update_time  IS '更新时间';
COMMENT ON COLUMN flow_instance_biz_ext.business_code  IS '业务编码';
COMMENT ON COLUMN flow_instance_biz_ext.business_title  IS '业务标题';
COMMENT ON COLUMN flow_instance_biz_ext.del_flag  IS '删除标志（0代表存在 1代表删除）';
COMMENT ON COLUMN flow_instance_biz_ext.instance_id  IS '流程实例Id';
COMMENT ON COLUMN flow_instance_biz_ext.business_id  IS '业务Id';

-- ----------------------------
-- 请假单信息
-- ----------------------------
CREATE TABLE test_leave
(
    id          int8         NOT NULL,
    apply_code  VARCHAR(50)  NOT NULL,
    leave_type  VARCHAR(255) NOT NULL,
    start_date  TIMESTAMP    NOT NULL,
    end_date    TIMESTAMP    NOT NULL,
    leave_days  int2          NOT NULL,
    remark      VARCHAR(255),
    status      VARCHAR(255),
    create_dept int8,
    create_by   int8,
    create_time TIMESTAMP,
    update_by   int8,
    update_time TIMESTAMP,
    PRIMARY KEY (id)
);

COMMENT ON TABLE test_leave IS '请假申请表';
COMMENT ON COLUMN test_leave.id IS 'id';
COMMENT ON COLUMN test_leave.apply_code IS '申请编号';
COMMENT ON COLUMN test_leave.leave_type IS '请假类型';
COMMENT ON COLUMN test_leave.start_date IS '开始时间';
COMMENT ON COLUMN test_leave.end_date IS '结束时间';
COMMENT ON COLUMN test_leave.leave_days IS '请假天数';
COMMENT ON COLUMN test_leave.remark IS '请假原因';
COMMENT ON COLUMN test_leave.status IS '状态';
COMMENT ON COLUMN test_leave.create_dept IS '创建部门';
COMMENT ON COLUMN test_leave.create_by IS '创建者';
COMMENT ON COLUMN test_leave.create_time IS '创建时间';
COMMENT ON COLUMN test_leave.update_by IS '更新者';
COMMENT ON COLUMN test_leave.update_time IS '更新时间';

INSERT INTO sys_menu VALUES (1761400000000011616, '工作流', 0, 6, 'workflow', '', '', 'N', 'Y', 'M', '0', '0', '', 'workflow', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011618, '我的任务', 0, 7, 'task', '', '', 'N', 'Y', 'M', '0', '0', '', 'my-task', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011619, '我的待办', 1761400000000011618, 2, 'taskWaiting', 'workflow/task/taskWaiting', '', 'N', 'N', 'C', '0', '0', '', 'waiting', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011632, '我的已办', 1761400000000011618, 3, 'taskFinish', 'workflow/task/taskFinish', '', 'N', 'N', 'C', '0', '0', '', 'finish', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011633, '我的抄送', 1761400000000011618, 4, 'taskCopyList', 'workflow/task/taskCopyList', '', 'N', 'N', 'C', '0', '0', '', 'my-copy', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011620, '流程定义', 1761400000000011616, 3, 'processDefinition', 'workflow/processDefinition/index', '', 'N', 'N', 'C', '0', '0', 'workflow:definition:list', 'process-definition', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011621, '流程实例', 1761400000000011630, 1, 'processInstance', 'workflow/processInstance/index', '', 'N', 'N', 'C', '0', '0', 'workflow:instance:list', 'tree-table', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011622, '流程分类', 1761400000000011616, 1, 'category', 'workflow/category/index', '', 'N', 'Y', 'C', '0', '0', 'workflow:category:list', 'category', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011629, '我发起的', 1761400000000011618, 1, 'myDocument', 'workflow/task/myDocument', '', 'N', 'N', 'C', '0', '0', 'workflow:instance:currentList', 'guide', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011630, '流程监控', 1761400000000011616, 4, 'processMonitor', '', '', 'N', 'Y', 'M', '0', '0', '', 'monitor', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011631, '待办任务', 1761400000000011630, 2, 'allTaskWaiting', 'workflow/task/allTaskWaiting', '', 'N', 'N', 'C', '0', '0', 'workflow:task:list', 'waiting', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011660, '待办任务修改', 1761400000000011631, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:task:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011700, '流程设计', 1761400000000011616, 5, 'design/index', 'workflow/processDefinition/design', '', 'N', 'N', 'C', '1', '0', 'workflow:leave:edit', '#', '/workflow/processDefinition', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011701, '请假申请', 1761400000000011616, 6, 'leaveEdit/index', 'workflow/leave/leaveEdit', '', 'N', 'N', 'C', '1', '0', 'workflow:leave:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu VALUES (1761400000000011623, '流程分类查询', 1761400000000011622, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011624, '流程分类新增', 1761400000000011622, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011625, '流程分类修改', 1761400000000011622, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011626, '流程分类删除', 1761400000000011622, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011627, '流程分类导出', 1761400000000011622, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:category:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');

-- 流程实例管理相关按钮
INSERT INTO sys_menu VALUES (1761400000000011653, '流程实例查询', 1761400000000011621, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011654, '流程变量查询', 1761400000000011621, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:variableQuery', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011655, '流程变量修改', 1761400000000011621, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:variable', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011656, '流程实例激活/挂起', 1761400000000011621, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:active', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011657, '流程实例删除', 1761400000000011621, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011658, '流程实例作废', 1761400000000011621, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:invalid', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011659, '流程实例撤销', 1761400000000011621, 7, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:instance:cancel', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');

-- 流程定义管理相关按钮
INSERT INTO sys_menu VALUES (1761400000000011644, '流程定义查询', 1761400000000011620, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011645, '流程定义新增', 1761400000000011620, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011646, '流程定义修改', 1761400000000011620, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011647, '流程定义删除', 1761400000000011620, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011648, '流程定义导出', 1761400000000011620, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011649, '流程定义导入', 1761400000000011620, 6, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:import', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011650, '流程定义发布/取消发布', 1761400000000011620, 7, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:publish', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011651, '流程定义复制', 1761400000000011620, 8, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:copy', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011652, '流程定义激活/挂起', 1761400000000011620, 9, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:definition:active', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu VALUES (1761400000000011801, '流程表达式', 1761400000000011616, 2, 'spel', 'workflow/spel/index', '', 'N', 'Y', 'C', '0', '0', 'workflow:spel:list', 'input', '', '', 1761000000000000103, 1761100000000000001, now(), 1761100000000000001, now(), '流程达式定义菜单');
INSERT INTO sys_menu VALUES (1761400000000011802, '流程spel表达式定义查询', 1761400000000011801, 1, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011803, '流程spel表达式定义新增', 1761400000000011801, 2, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011804, '流程spel表达式定义修改', 1761400000000011801, 3, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011805, '流程spel表达式定义删除', 1761400000000011801, 4, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011806, '流程spel表达式定义导出', 1761400000000011801, 5, '#', '', NULL, 'N', 'Y', 'F', '0', '0', 'workflow:spel:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_menu VALUES (1761400000000011638, '请假申请', 1761400000000000005, 1, 'leave', 'workflow/leave/index', '', 'N', 'Y', 'C', '0', '0', 'workflow:leave:list', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '请假申请菜单');
INSERT INTO sys_menu VALUES (1761400000000011639, '请假申请查询', 1761400000000011638, 1, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:query', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011640, '请假申请新增', 1761400000000011638, 2, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:add', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011641, '请假申请修改', 1761400000000011638, 3, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:edit', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011642, '请假申请删除', 1761400000000011638, 4, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:remove', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');
INSERT INTO sys_menu VALUES (1761400000000011643, '请假申请导出', 1761400000000011638, 5, '#', '', '', 'N', 'Y', 'F', '0', '0', 'workflow:leave:export', '#', '', '', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '');

INSERT INTO sys_dict_type VALUES (1761500000000000013, '业务状态', 'wf_business_status', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '业务状态列表');
INSERT INTO sys_dict_type VALUES (1761500000000000014, '表单类型', 'wf_form_type', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '表单类型列表');
INSERT INTO sys_dict_type VALUES (1761500000000000015, '任务状态', 'wf_task_status', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '任务状态');
INSERT INTO sys_dict_data VALUES (1761600000000000039, 1, '已撤销', 'cancel', 'wf_business_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '已撤销');
INSERT INTO sys_dict_data VALUES (1761600000000000040, 2, '草稿', 'draft', 'wf_business_status', '', 'info', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '草稿');
INSERT INTO sys_dict_data VALUES (1761600000000000041, 3, '待审核', 'waiting', 'wf_business_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '待审核');
INSERT INTO sys_dict_data VALUES (1761600000000000042, 4, '已完成', 'finish', 'wf_business_status', '', 'success', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '已完成');
INSERT INTO sys_dict_data VALUES (1761600000000000043, 5, '已作废', 'invalid', 'wf_business_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '已作废');
INSERT INTO sys_dict_data VALUES (1761600000000000044, 6, '已退回', 'back', 'wf_business_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '已退回');
INSERT INTO sys_dict_data VALUES (1761600000000000045, 7, '已终止', 'termination', 'wf_business_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '已终止');
INSERT INTO sys_dict_data VALUES (1761600000000000046, 1, '自定义表单', 'static', 'wf_form_type', '', 'success', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '自定义表单');
INSERT INTO sys_dict_data VALUES (1761600000000000047, 2, '动态表单', 'dynamic', 'wf_form_type', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '动态表单');
INSERT INTO sys_dict_data VALUES (1761600000000000048, 1, '撤销', 'cancel', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '撤销');
INSERT INTO sys_dict_data VALUES (1761600000000000049, 2, '通过', 'pass', 'wf_task_status', '', 'success', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '通过');
INSERT INTO sys_dict_data VALUES (1761600000000000050, 3, '待审核', 'waiting', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '待审核');
INSERT INTO sys_dict_data VALUES (1761600000000000051, 4, '作废', 'invalid', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '作废');
INSERT INTO sys_dict_data VALUES (1761600000000000052, 5, '退回', 'back', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '退回');
INSERT INTO sys_dict_data VALUES (1761600000000000053, 6, '终止', 'termination', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '终止');
INSERT INTO sys_dict_data VALUES (1761600000000000054, 7, '转办', 'transfer', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '转办');
INSERT INTO sys_dict_data VALUES (1761600000000000055, 8, '委托', 'depute', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '委托');
INSERT INTO sys_dict_data VALUES (1761600000000000056, 9, '抄送', 'copy', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '抄送');
INSERT INTO sys_dict_data VALUES (1761600000000000057, 10, '加签', 'sign', 'wf_task_status', '', 'primary', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '加签');
INSERT INTO sys_dict_data VALUES (1761600000000000058, 11, '减签', 'sign_off', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '减签');
INSERT INTO sys_dict_data VALUES (1761600000000000059, 11, '超时', 'timeout', 'wf_task_status', '', 'danger', 'N', 1761000000000000103, 1761100000000000001, now(), NULL, NULL, '超时');


-- ========== postgres_ry_ai.sql（AI 业务表）==========
-- ============================================================
-- Snail AI PostgreSQL 全量建表脚本（仅 CREATE，无 ALTER）
-- 使用：psql -U user -d snail_ai -f postgres_ry_ai.sql
-- 结构来源：snail_ai_schema.sql
-- ============================================================

-- ============================================================
-- 公共触发器函数：模拟 MySQL ON UPDATE CURRENT_TIMESTAMP
-- ============================================================
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_dt = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION update_timestamp_updated()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_dt = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION update_timestamp_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- 一、用户与权限
-- ============================================================

-- 1.1 用户表
CREATE TABLE sai_user
(
    id BIGSERIAL PRIMARY KEY,
    role INT,
    totals INT,
    username VARCHAR(255),
    nickname VARCHAR(128) DEFAULT NULL,
    email VARCHAR(64),
    password VARCHAR(255) NOT NULL,
    resource_id BIGINT DEFAULT NULL,
    create_dt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_username UNIQUE (username)
);

COMMENT ON COLUMN sai_user.nickname IS '用户昵称';
COMMENT ON COLUMN sai_user.resource_id IS '头像资源ID，关联 sai_resource.id';

CREATE TRIGGER trigger_sai_user_update
    BEFORE UPDATE ON sai_user
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

-- 1.2 OpenAPI 外部用户映射表
CREATE TABLE sai_openapi_user
(
    id BIGSERIAL PRIMARY KEY,
    app_id VARCHAR(128) NOT NULL,
    open_id VARCHAR(64) NOT NULL,
    platform_user_id BIGINT NOT NULL,
    external_id VARCHAR(256) DEFAULT NULL,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_open UNIQUE (app_id, open_id),
    CONSTRAINT uk_app_external UNIQUE (app_id, external_id)
);

COMMENT ON TABLE sai_openapi_user IS 'OpenAPI 外部用户映射表';
COMMENT ON COLUMN sai_openapi_user.app_id IS '关联 sai_app.app_id';
COMMENT ON COLUMN sai_openapi_user.open_id IS '平台分配的唯一标识（UUID）';
COMMENT ON COLUMN sai_openapi_user.platform_user_id IS '关联 sai_user.id，注册时自动创建';
COMMENT ON COLUMN sai_openapi_user.external_id IS '外部系统的用户标识（可选，幂等用）';

CREATE TRIGGER trigger_sai_openapi_user_update
    BEFORE UPDATE ON sai_openapi_user
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_open_id ON sai_openapi_user (open_id);
CREATE INDEX idx_platform_user ON sai_openapi_user (platform_user_id);

-- ============================================================
-- 二、AI 模型管理
-- ============================================================

-- 2.1 AI 模型提供商表
CREATE TABLE IF NOT EXISTS sai_model_provider
(
    id BIGSERIAL PRIMARY KEY,
    provider_name VARCHAR(255) NOT NULL,
    provider_key VARCHAR(50) NOT NULL,
    description TEXT,
    icon_url VARCHAR(500),
    is_enabled BOOLEAN DEFAULT TRUE,
    created_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_provider_name UNIQUE (provider_name),
    CONSTRAINT uk_provider_key UNIQUE (provider_key)
);

COMMENT ON TABLE sai_model_provider IS 'AI模型提供商表';
COMMENT ON COLUMN sai_model_provider.provider_name IS '提供商名称';
COMMENT ON COLUMN sai_model_provider.provider_key IS '提供商标识符';
COMMENT ON COLUMN sai_model_provider.description IS '提供商描述';
COMMENT ON COLUMN sai_model_provider.icon_url IS 'LOGO图标URL';
COMMENT ON COLUMN sai_model_provider.is_enabled IS '是否启用';
COMMENT ON COLUMN sai_model_provider.created_dt IS '创建时间';
COMMENT ON COLUMN sai_model_provider.updated_dt IS '更新时间';

CREATE TRIGGER trigger_sai_model_provider_update
    BEFORE UPDATE ON sai_model_provider
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp_updated();

CREATE INDEX idx_provider_key ON sai_model_provider (provider_key);
CREATE INDEX idx_is_enabled ON sai_model_provider (is_enabled);

-- 2.2 AI模型配置表
CREATE TABLE IF NOT EXISTS sai_model_config
(
    id BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    model_key VARCHAR(100) NOT NULL,
    model_type VARCHAR(50) NOT NULL,
    adapter_key VARCHAR(100),
    description VARCHAR(1000),
    api_key VARCHAR(1000),
    api_endpoint VARCHAR(500),
    config_json TEXT,
    owner_id BIGINT,
    scope VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
    is_default BOOLEAN DEFAULT FALSE,
    is_enabled BOOLEAN DEFAULT TRUE,
    created_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sai_model_config IS 'AI模型配置表';
COMMENT ON COLUMN sai_model_config.provider_id IS '提供商ID';
COMMENT ON COLUMN sai_model_config.model_name IS '模型名称';
COMMENT ON COLUMN sai_model_config.model_key IS '模型标识符';
COMMENT ON COLUMN sai_model_config.model_type IS '模型类型(CHAT/EMBEDDING/RERANKER/IMAGE/SPEECH)';
COMMENT ON COLUMN sai_model_config.adapter_key IS '底层协议适配器标识(openai-compatible/http等)';
COMMENT ON COLUMN sai_model_config.description IS '模型描述';
COMMENT ON COLUMN sai_model_config.api_key IS 'API密钥(加密存储)';
COMMENT ON COLUMN sai_model_config.api_endpoint IS 'API端点URL';
COMMENT ON COLUMN sai_model_config.config_json IS '模型参数配置(JSON格式)';
COMMENT ON COLUMN sai_model_config.owner_id IS '所有者ID(NULL=全局,具体值=用户ID)';
COMMENT ON COLUMN sai_model_config.scope IS '作用域(GLOBAL/PERSONAL)';
COMMENT ON COLUMN sai_model_config.is_default IS '是否为默认模型';
COMMENT ON COLUMN sai_model_config.is_enabled IS '是否启用';
COMMENT ON COLUMN sai_model_config.created_dt IS '创建时间';
COMMENT ON COLUMN sai_model_config.updated_dt IS '更新时间';

CREATE TRIGGER trigger_sai_model_config_update
    BEFORE UPDATE ON sai_model_config
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp_updated();

CREATE INDEX fk_provider_id ON sai_model_config (provider_id);

CREATE INDEX idx_provider_model_type ON sai_model_config (provider_id, model_type);
CREATE INDEX idx_model_type_enabled ON sai_model_config (model_type, is_enabled);
CREATE INDEX idx_owner_id ON sai_model_config (owner_id);
CREATE INDEX idx_is_default ON sai_model_config (is_default);
CREATE INDEX idx_scope ON sai_model_config (scope);
CREATE INDEX idx_model_key ON sai_model_config (model_key);

-- 2.3 模型使用统计表
CREATE TABLE IF NOT EXISTS sai_model_usage_stat
(
    id BIGSERIAL PRIMARY KEY,
    model_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    total_calls BIGINT DEFAULT 0,
    success_calls BIGINT DEFAULT 0,
    failed_calls BIGINT DEFAULT 0,
    total_tokens_used BIGINT DEFAULT 0,
    total_cost DECIMAL(18, 8) DEFAULT 0,
    avg_response_time BIGINT DEFAULT 0,
    last_used_dt TIMESTAMP NULL DEFAULT NULL,
    created_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_model_user UNIQUE (model_id, user_id)
);

COMMENT ON TABLE sai_model_usage_stat IS '模型使用统计表';
COMMENT ON COLUMN sai_model_usage_stat.model_id IS '模型ID';
COMMENT ON COLUMN sai_model_usage_stat.user_id IS '用户ID';
COMMENT ON COLUMN sai_model_usage_stat.total_calls IS '总调用次数';
COMMENT ON COLUMN sai_model_usage_stat.success_calls IS '成功调用次数';
COMMENT ON COLUMN sai_model_usage_stat.failed_calls IS '失败调用次数';
COMMENT ON COLUMN sai_model_usage_stat.total_tokens_used IS '总Token使用量';
COMMENT ON COLUMN sai_model_usage_stat.total_cost IS '总费用';
COMMENT ON COLUMN sai_model_usage_stat.avg_response_time IS '平均响应时间(毫秒)';
COMMENT ON COLUMN sai_model_usage_stat.last_used_dt IS '最后使用时间';
COMMENT ON COLUMN sai_model_usage_stat.created_dt IS '创建时间';
COMMENT ON COLUMN sai_model_usage_stat.updated_dt IS '更新时间';

CREATE TRIGGER trigger_sai_model_usage_stat_update
    BEFORE UPDATE ON sai_model_usage_stat
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp_updated();

CREATE INDEX fk_stat_model_id ON sai_model_usage_stat (model_id);

CREATE INDEX idx_model_id ON sai_model_usage_stat (model_id);
CREATE INDEX idx_user_id ON sai_model_usage_stat (user_id);
CREATE INDEX idx_last_used_dt ON sai_model_usage_stat (last_used_dt);

-- ============================================================
-- 三、智能体（Agent）
-- ============================================================

-- 3.1 智能体主表
CREATE TABLE IF NOT EXISTS sai_agent
(
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    avatar VARCHAR(512),
    instruction TEXT,
    greeting TEXT,
    preset_questions TEXT,
    chat_model_id BIGINT,
    memory_enabled BOOLEAN DEFAULT FALSE,
    mcp_enabled BOOLEAN DEFAULT FALSE,
    skill_enabled BOOLEAN DEFAULT FALSE,
    web_search_enabled BOOLEAN DEFAULT FALSE,
    rag_enabled BOOLEAN DEFAULT FALSE,
    rag_ids VARCHAR(64) NULL,
    rag_call_mode SMALLINT DEFAULT 1,
    short_term_memory_size INT DEFAULT 20,
    creator_id BIGINT,
    is_featured BOOLEAN DEFAULT FALSE,
    view_count INT DEFAULT 0,
    status SMALLINT DEFAULT 1,
    config TEXT,
    app_id VARCHAR(128) NULL,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sai_agent IS '智能体表';
COMMENT ON COLUMN sai_agent.name IS '智能体名称';
COMMENT ON COLUMN sai_agent.description IS '智能体描述';
COMMENT ON COLUMN sai_agent.avatar IS '头像URL';
COMMENT ON COLUMN sai_agent.instruction IS '系统指令(System Prompt)';
COMMENT ON COLUMN sai_agent.greeting IS '欢迎语';
COMMENT ON COLUMN sai_agent.preset_questions IS '预设问题列表（JSON数组字符串）';
COMMENT ON COLUMN sai_agent.chat_model_id IS '关联的对话模型ID';
COMMENT ON COLUMN sai_agent.memory_enabled IS '是否启用记忆库';
COMMENT ON COLUMN sai_agent.mcp_enabled IS '是否启用MCP';
COMMENT ON COLUMN sai_agent.skill_enabled IS '是否启用Skill';
COMMENT ON COLUMN sai_agent.web_search_enabled IS '是否启用联网搜索';
COMMENT ON COLUMN sai_agent.rag_enabled IS '是否启用RAG';
COMMENT ON COLUMN sai_agent.rag_ids IS '绑定的RAG ID列表，逗号分隔，最多5个';
COMMENT ON COLUMN sai_agent.rag_call_mode IS 'RAG调用方式: 1=智能调用 2=强制调用';
COMMENT ON COLUMN sai_agent.short_term_memory_size IS '短期记忆滑动窗口保留条数';
COMMENT ON COLUMN sai_agent.creator_id IS '创建者用户ID';
COMMENT ON COLUMN sai_agent.is_featured IS '是否精选';
COMMENT ON COLUMN sai_agent.view_count IS '浏览次数';
COMMENT ON COLUMN sai_agent.status IS '状态: 1-活跃 2-非活跃 3-已废弃 4-已禁用';
COMMENT ON COLUMN sai_agent.config IS '扩展配置(预留)';
COMMENT ON COLUMN sai_agent.app_id IS '关联应用ID(NULL=本地执行)';

CREATE TRIGGER trigger_sai_agent_update
    BEFORE UPDATE ON sai_agent
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_agent_creator ON sai_agent (creator_id);
CREATE INDEX idx_agent_featured ON sai_agent (is_featured);

-- 3.2 智能体对话表
CREATE TABLE IF NOT EXISTS sai_agent_conversation
(
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    title VARCHAR(255),
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conv_id UNIQUE (conversation_id)
);

COMMENT ON TABLE sai_agent_conversation IS '智能体对话表';
COMMENT ON COLUMN sai_agent_conversation.agent_id IS '智能体ID';
COMMENT ON COLUMN sai_agent_conversation.user_id IS '用户ID';
COMMENT ON COLUMN sai_agent_conversation.conversation_id IS '对话ID(UUID)';
COMMENT ON COLUMN sai_agent_conversation.title IS '对话标题';

CREATE TRIGGER trigger_sai_agent_conversation_update
    BEFORE UPDATE ON sai_agent_conversation
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_agent_conv_agent ON sai_agent_conversation (agent_id);
CREATE INDEX idx_agent_conv_user ON sai_agent_conversation (user_id);

-- 3.3 智能体对话消息记录表
CREATE TABLE IF NOT EXISTS sai_agent_conversation_record
(
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) DEFAULT 'user',
    content TEXT,
    thinking TEXT,
    metadata TEXT DEFAULT NULL,
    status INT DEFAULT 1,
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    cache_tokens INT DEFAULT 0,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sai_agent_conversation_record IS '智能体对话消息记录';
COMMENT ON COLUMN sai_agent_conversation_record.agent_id IS '智能体ID';
COMMENT ON COLUMN sai_agent_conversation_record.conversation_id IS '对话ID';
COMMENT ON COLUMN sai_agent_conversation_record.user_id IS '用户ID';
COMMENT ON COLUMN sai_agent_conversation_record.role IS 'user/assistant';
COMMENT ON COLUMN sai_agent_conversation_record.content IS '消息内容';
COMMENT ON COLUMN sai_agent_conversation_record.thinking IS '思考过程（仅assistant）';
COMMENT ON COLUMN sai_agent_conversation_record.metadata IS '消息扩展元数据JSON';
COMMENT ON COLUMN sai_agent_conversation_record.status IS '1=成功,2=失败,3=进行中';
COMMENT ON COLUMN sai_agent_conversation_record.input_tokens IS '输入Token数（prompt）';
COMMENT ON COLUMN sai_agent_conversation_record.output_tokens IS '输出Token数（completion）';
COMMENT ON COLUMN sai_agent_conversation_record.cache_tokens IS '缓存命中Token数';

CREATE INDEX idx_agent_rec_conv ON sai_agent_conversation_record (conversation_id);

-- 3.4 智能体使用统计表
CREATE TABLE IF NOT EXISTS sai_agent_usage_stat
(
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    message_count INT DEFAULT 0,
    conversation_count INT DEFAULT 0,
    stat_date DATE NOT NULL,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_user_date UNIQUE (agent_id, user_id, stat_date)
);

COMMENT ON TABLE sai_agent_usage_stat IS '智能体使用统计';
COMMENT ON COLUMN sai_agent_usage_stat.agent_id IS '智能体ID';
COMMENT ON COLUMN sai_agent_usage_stat.user_id IS '用户ID';
COMMENT ON COLUMN sai_agent_usage_stat.message_count IS '消息条数';
COMMENT ON COLUMN sai_agent_usage_stat.conversation_count IS '对话轮次';
COMMENT ON COLUMN sai_agent_usage_stat.stat_date IS '统计日期';

CREATE TRIGGER trigger_sai_agent_usage_stat_update
    BEFORE UPDATE ON sai_agent_usage_stat
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_usage_agent ON sai_agent_usage_stat (agent_id);
CREATE INDEX idx_usage_date ON sai_agent_usage_stat (stat_date);

-- 3.5 用户订阅的智能体（多对多）
CREATE TABLE IF NOT EXISTS sai_user_agent
(
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_agent UNIQUE (user_id, agent_id)
);

COMMENT ON TABLE sai_user_agent IS '用户订阅的智能体';
COMMENT ON COLUMN sai_user_agent.user_id IS '用户ID';
COMMENT ON COLUMN sai_user_agent.agent_id IS '智能体ID';

CREATE INDEX idx_user_agent_user ON sai_user_agent (user_id);

-- ============================================================
-- 四、RAG 知识库
-- ============================================================

-- 4.1 知识库主表
CREATE TABLE sai_rag
(
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    icon VARCHAR(512),
    embedding_model_id BIGINT NOT NULL,
    dimension_of_vector_model INT NOT NULL,
    rerank_model_id BIGINT,
    search_engine_instance_id BIGINT,
    vector_store_instance_id BIGINT,
    search_engine_enable BOOLEAN DEFAULT FALSE,
    delimiter VARCHAR(32) DEFAULT E'\n\n',
    rag_enhancement TEXT,
    config TEXT DEFAULT NULL,
    dedup_strategy SMALLINT NOT NULL DEFAULT 2,
    dedup_action SMALLINT NOT NULL DEFAULT 0,
    upload_confirm BOOLEAN NOT NULL DEFAULT TRUE,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN sai_rag.dimension_of_vector_model IS '向量维度';
COMMENT ON COLUMN sai_rag.config IS 'RAG检索和问答的页面配置参数';
COMMENT ON COLUMN sai_rag.dedup_strategy IS '去重策略: 0=NONE 1=BY_NAME 2=BY_CONTENT 3=BY_NAME_OR_CONTENT';
COMMENT ON COLUMN sai_rag.dedup_action IS '冲突动作: 0=REJECT 1=SKIP 2=OVERWRITE';
COMMENT ON COLUMN sai_rag.upload_confirm IS '上传前二次确认: 0-关 1-开';

CREATE TRIGGER trigger_sai_rag_update
    BEFORE UPDATE ON sai_rag
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

-- 4.2 RAG 文档表
CREATE TABLE sai_rag_document
(
    id BIGSERIAL PRIMARY KEY,
    rag_id BIGINT NOT NULL,
    name VARCHAR(255),
    file_type VARCHAR(32),
    source_type VARCHAR(32),
    source_path VARCHAR(1024),
    storage_path VARCHAR(1024),
    storage_type VARCHAR(32) DEFAULT 'LOCAL',
    file_size BIGINT DEFAULT 0,
    content TEXT,
    status SMALLINT DEFAULT 0,
    error_msg TEXT,
    chunk_count INT DEFAULT 0,
    page_count INT DEFAULT 0,
    element_count INT DEFAULT 0,
    table_count INT DEFAULT 0,
    image_count INT DEFAULT 0,
    parse_time INT DEFAULT 0,
    md_content TEXT DEFAULT NULL,
    doc_metadata TEXT DEFAULT NULL,
    content_hash VARCHAR(64) DEFAULT NULL,
    resource_id BIGINT DEFAULT NULL,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN sai_rag_document.status IS '状态: 0-待处理 1-解析中 2-处理中 3-处理完成 4-处理失败';
COMMENT ON COLUMN sai_rag_document.content_hash IS '文件内容SHA-256哈希，用于去重';
COMMENT ON COLUMN sai_rag_document.resource_id IS '关联资源库 sai_resource.id';

CREATE TRIGGER trigger_sai_rag_document_update
    BEFORE UPDATE ON sai_rag_document
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_rag_doc_rag ON sai_rag_document (rag_id);
CREATE INDEX idx_rag_content_hash ON sai_rag_document (rag_id, content_hash);
CREATE INDEX idx_rag_name ON sai_rag_document (rag_id, name);
CREATE INDEX idx_rag_doc_resource ON sai_rag_document (resource_id);

CREATE TABLE sai_rag_document_image
(
    id BIGSERIAL PRIMARY KEY,
    rag_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_id BIGINT DEFAULT NULL,
    resource_id BIGINT DEFAULT NULL,
    image_index INT,
    image_url VARCHAR(1024),
    caption TEXT,
    figure_no VARCHAR(64),
    figure_title VARCHAR(512),
    section_title VARCHAR(512),
    source_page INT,
    document_name VARCHAR(255),
    ocr_text TEXT,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sai_rag_document_image IS 'RAG document parsed image table';
COMMENT ON COLUMN sai_rag_document_image.resource_id IS 'Linked resource id in sai_resource';

CREATE TRIGGER trigger_sai_rag_document_image_update
    BEFORE UPDATE ON sai_rag_document_image
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_rag_doc_image_rag ON sai_rag_document_image (rag_id);
CREATE INDEX idx_rag_doc_image_document ON sai_rag_document_image (document_id);
CREATE INDEX idx_rag_doc_image_chunk ON sai_rag_document_image (chunk_id);
CREATE INDEX idx_rag_doc_image_resource ON sai_rag_document_image (resource_id);

-- 4.3 RAG 分块表
CREATE TABLE sai_rag_chunk
(
    id BIGSERIAL PRIMARY KEY,
    rag_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    paragraph_index INT,
    chunk_index INT,
    content TEXT,
    token_count INT,
    vector_id VARCHAR(128),
    content_hash VARCHAR(64) DEFAULT NULL,
    source_type VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN sai_rag_chunk.content_hash IS 'chunk内容SHA-256，用于向量去重';
COMMENT ON COLUMN sai_rag_chunk.source_type IS 'chunk来源类型：TEXT文本、IMAGE图片';

CREATE TRIGGER trigger_sai_rag_chunk_update
    BEFORE UPDATE ON sai_rag_chunk
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_rag_chunk_rag ON sai_rag_chunk (rag_id);
CREATE INDEX idx_rag_chunk_document ON sai_rag_chunk (document_id);
CREATE INDEX idx_chunk_rag_hash ON sai_rag_chunk (rag_id, content_hash);
CREATE INDEX idx_chunk_rag_source_type ON sai_rag_chunk (rag_id, source_type);

-- ============================================================
-- 五、MCP 服务管理
-- ============================================================

-- 5.1 MCP 服务配置表
CREATE TABLE IF NOT EXISTS sai_mcp_server
(
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    transport_type SMALLINT DEFAULT 1,
    base_uri VARCHAR(1024),
    endpoint VARCHAR(1024),
    command VARCHAR(1024),
    args TEXT,
    env_vars TEXT,
    timeout BIGINT DEFAULT 60000,
    headers TEXT,
    last_connect_dt TIMESTAMP NULL,
    creator_id BIGINT,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sai_mcp_server IS 'MCP服务配置表';
COMMENT ON COLUMN sai_mcp_server.name IS 'MCP服务名称';
COMMENT ON COLUMN sai_mcp_server.description IS 'MCP服务描述';
COMMENT ON COLUMN sai_mcp_server.transport_type IS '传输类型: 1-SSE 2-Streamable HTTP 3-Stdio';
COMMENT ON COLUMN sai_mcp_server.base_uri IS '服务基础地址(SSE/Streamable HTTP时使用)';
COMMENT ON COLUMN sai_mcp_server.endpoint IS '端点路径(SSE/Streamable HTTP时可选)';
COMMENT ON COLUMN sai_mcp_server.command IS 'Stdio命令(Stdio时必填)';
COMMENT ON COLUMN sai_mcp_server.args IS 'Stdio命令参数(JSON数组)';
COMMENT ON COLUMN sai_mcp_server.env_vars IS 'Stdio环境变量(JSON对象)';
COMMENT ON COLUMN sai_mcp_server.timeout IS '超时时间(毫秒)';
COMMENT ON COLUMN sai_mcp_server.headers IS '请求头(JSON对象)';
COMMENT ON COLUMN sai_mcp_server.last_connect_dt IS '最后连接时间';
COMMENT ON COLUMN sai_mcp_server.creator_id IS '创建者用户ID';

CREATE TRIGGER trigger_sai_mcp_server_update
    BEFORE UPDATE ON sai_mcp_server
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_mcp_server_creator ON sai_mcp_server (creator_id);

-- 5.2 智能体与MCP服务关联表（多对多）
CREATE TABLE IF NOT EXISTS sai_agent_mcp_server
(
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    mcp_server_id BIGINT NOT NULL,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_mcp UNIQUE (agent_id, mcp_server_id)
);

COMMENT ON TABLE sai_agent_mcp_server IS '智能体MCP服务关联表';
COMMENT ON COLUMN sai_agent_mcp_server.agent_id IS '智能体ID';
COMMENT ON COLUMN sai_agent_mcp_server.mcp_server_id IS 'MCP服务ID';

CREATE INDEX idx_agent_mcp_agent ON sai_agent_mcp_server (agent_id);
CREATE INDEX idx_agent_mcp_server ON sai_agent_mcp_server (mcp_server_id);

-- ============================================================
-- 六、Skill 技能包管理
-- ============================================================

-- 6.1 Skill 技能包表
CREATE TABLE IF NOT EXISTS sai_skill
(
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    file_name VARCHAR(255),
    file_path VARCHAR(1024),
    file_size BIGINT DEFAULT 0,
    skill_content TEXT,
    storage_path VARCHAR(500) DEFAULT NULL,
    version BIGINT DEFAULT 0,
    has_files BOOLEAN DEFAULT FALSE,
    creator_id BIGINT,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sai_skill IS 'Skill技能包表';
COMMENT ON COLUMN sai_skill.name IS 'Skill名称(从SKILL.md解析)';
COMMENT ON COLUMN sai_skill.description IS 'Skill描述(从SKILL.md解析)';
COMMENT ON COLUMN sai_skill.file_name IS '上传的zip文件名';
COMMENT ON COLUMN sai_skill.file_path IS '解压后存储路径';
COMMENT ON COLUMN sai_skill.file_size IS '文件大小(字节)';
COMMENT ON COLUMN sai_skill.skill_content IS 'SKILL.md正文内容(去除frontmatter)';
COMMENT ON COLUMN sai_skill.storage_path IS '对象存储相对路径前缀（如 skills/123/）';
COMMENT ON COLUMN sai_skill.version IS '版本号，文件变更时自增，用于缓存一致性校验';
COMMENT ON COLUMN sai_skill.has_files IS '是否包含支撑文件（0=仅SKILL.md，1=有scripts/references等）';
COMMENT ON COLUMN sai_skill.creator_id IS '创建者用户ID';

CREATE TRIGGER trigger_sai_skill_update
    BEFORE UPDATE ON sai_skill
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_skill_creator ON sai_skill (creator_id);

-- 6.2 Skill 支撑文件内容表
CREATE TABLE IF NOT EXISTS sai_skill_file
(
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL,
    file_path VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    file_size INT NOT NULL,
    encoding VARCHAR(50) DEFAULT 'utf-8',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_skill_path UNIQUE (skill_id, file_path)
);

COMMENT ON TABLE sai_skill_file IS 'Skill支撑文件内容表';
COMMENT ON COLUMN sai_skill_file.skill_id IS 'Skill ID';
COMMENT ON COLUMN sai_skill_file.file_path IS '文件相对路径';
COMMENT ON COLUMN sai_skill_file.content IS '文件内容';
COMMENT ON COLUMN sai_skill_file.file_size IS '文件大小(字节)';
COMMENT ON COLUMN sai_skill_file.encoding IS '编码方式';
COMMENT ON COLUMN sai_skill_file.created_at IS '创建时间';
COMMENT ON COLUMN sai_skill_file.updated_at IS '更新时间';

CREATE TRIGGER trigger_sai_skill_file_update
    BEFORE UPDATE ON sai_skill_file
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp_updated_at();

CREATE INDEX idx_skill_id ON sai_skill_file (skill_id);

-- 6.3 智能体与Skill关联表（多对多）
CREATE TABLE IF NOT EXISTS sai_agent_skill
(
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_skill UNIQUE (agent_id, skill_id)
);

COMMENT ON TABLE sai_agent_skill IS '智能体Skill关联表';
COMMENT ON COLUMN sai_agent_skill.agent_id IS '智能体ID';
COMMENT ON COLUMN sai_agent_skill.skill_id IS 'Skill ID';

CREATE INDEX idx_agent_skill_agent ON sai_agent_skill (agent_id);
CREATE INDEX idx_agent_skill_skill ON sai_agent_skill (skill_id);

-- ============================================================
-- 七、客户端应用与节点
-- ============================================================

-- 7.1 客户端应用
CREATE TABLE IF NOT EXISTS sai_app
(
    id BIGSERIAL PRIMARY KEY,
    app_id VARCHAR(128) NOT NULL,
    app_name VARCHAR(255) NOT NULL,
    description VARCHAR(512),
    token VARCHAR(128) NOT NULL,
    route_strategy VARCHAR(32) DEFAULT 'LEAST_LOAD',
    status SMALLINT DEFAULT 1,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_id UNIQUE (app_id)
);

COMMENT ON TABLE sai_app IS '客户端应用';
COMMENT ON COLUMN sai_app.app_id IS '应用唯一标识';
COMMENT ON COLUMN sai_app.app_name IS '应用名称';
COMMENT ON COLUMN sai_app.description IS '应用描述';
COMMENT ON COLUMN sai_app.token IS '通信认证令牌';
COMMENT ON COLUMN sai_app.route_strategy IS '路由策略';
COMMENT ON COLUMN sai_app.status IS '1=启用, 0=停用';

CREATE TRIGGER trigger_sai_app_update
    BEFORE UPDATE ON sai_app
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

-- 7.2 AI客户端实例节点
CREATE TABLE IF NOT EXISTS sai_client_node
(
    id BIGSERIAL PRIMARY KEY,
    app_id VARCHAR(128) NOT NULL,
    host_id VARCHAR(128) NOT NULL,
    host_ip VARCHAR(64) NOT NULL,
    grpc_port INT NOT NULL,
    max_concurrent INT DEFAULT 10,
    active_chats INT DEFAULT 0,
    supported_providers TEXT,
    labels TEXT,
    expire_dt TIMESTAMP NOT NULL,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_client_node UNIQUE (app_id, host_id)
);

COMMENT ON TABLE sai_client_node IS 'AI客户端实例节点';
COMMENT ON COLUMN sai_client_node.app_id IS '所属应用ID';
COMMENT ON COLUMN sai_client_node.host_id IS '客户端实例唯一标识';
COMMENT ON COLUMN sai_client_node.host_ip IS '客户端IP';
COMMENT ON COLUMN sai_client_node.grpc_port IS '客户端gRPC端口';
COMMENT ON COLUMN sai_client_node.max_concurrent IS '最大并发对话数';
COMMENT ON COLUMN sai_client_node.active_chats IS '当前活跃对话数';
COMMENT ON COLUMN sai_client_node.supported_providers IS '支持的模型提供商(JSON数组)';
COMMENT ON COLUMN sai_client_node.labels IS '路由标签';
COMMENT ON COLUMN sai_client_node.expire_dt IS '过期时间(心跳更新)';

CREATE TRIGGER trigger_sai_client_node_update
    BEFORE UPDATE ON sai_client_node
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_app_expire ON sai_client_node (app_id, expire_dt);

-- ============================================================
-- 八、存储与资源
-- ============================================================

-- 8.1 存储实例
CREATE TABLE IF NOT EXISTS sai_store_instance
(
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    category SMALLINT NOT NULL,
    type SMALLINT NOT NULL,
    config TEXT DEFAULT NULL,
    status SMALLINT DEFAULT 1,
    is_default BOOLEAN DEFAULT FALSE,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE sai_store_instance IS '存储实例';
COMMENT ON COLUMN sai_store_instance.name IS '实例名称';
COMMENT ON COLUMN sai_store_instance.category IS '分类: 1-向量库 2-搜索引擎';
COMMENT ON COLUMN sai_store_instance.type IS '类型: 1-PG_VECTOR 2-MILVUS 3-ELASTICSEARCH 4-PG_FULLTEXT';
COMMENT ON COLUMN sai_store_instance.config IS '连接参数 JSON';
COMMENT ON COLUMN sai_store_instance.status IS '状态: 0-停用 1-启用';
COMMENT ON COLUMN sai_store_instance.is_default IS '是否为该 category 下默认实例';

CREATE TRIGGER trigger_sai_store_instance_update
    BEFORE UPDATE ON sai_store_instance
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_store_instance_category ON sai_store_instance (category);
CREATE INDEX idx_store_instance_type ON sai_store_instance (type);

-- 8.2 通用资源存储
CREATE TABLE IF NOT EXISTS sai_resource
(
    id BIGSERIAL PRIMARY KEY,
    storage_key VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    file_size BIGINT DEFAULT 0,
    mime_type VARCHAR(128),
    storage_type VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    access_url VARCHAR(1024),
    biz_type VARCHAR(64) NOT NULL DEFAULT 'GENERAL',
    biz_id BIGINT,
    creator_id BIGINT,
    create_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_storage_key UNIQUE (storage_key)
);

COMMENT ON TABLE sai_resource IS '通用资源存储';
COMMENT ON COLUMN sai_resource.storage_key IS '存储键（相对路径或对象Key）';
COMMENT ON COLUMN sai_resource.original_name IS '原始文件名';
COMMENT ON COLUMN sai_resource.file_size IS '文件大小(bytes)';
COMMENT ON COLUMN sai_resource.mime_type IS 'MIME类型';
COMMENT ON COLUMN sai_resource.storage_type IS '存储类型: LOCAL/MINIO';
COMMENT ON COLUMN sai_resource.access_url IS '访问URL';
COMMENT ON COLUMN sai_resource.biz_type IS '业务类型: AVATAR/ATTACHMENT/DOCUMENT/GENERAL';
COMMENT ON COLUMN sai_resource.biz_id IS '关联业务ID';
COMMENT ON COLUMN sai_resource.creator_id IS '上传者ID';

CREATE TRIGGER trigger_sai_resource_update
    BEFORE UPDATE ON sai_resource
    FOR EACH ROW
    EXECUTE FUNCTION update_timestamp();

CREATE INDEX idx_biz ON sai_resource (biz_type, biz_id);
CREATE INDEX idx_creator ON sai_resource (creator_id);

-- ============================================================
-- 九、初始化数据
-- ============================================================

-- 默认管理员：admin / admin123
INSERT INTO sai_user VALUES (1, 2, NULL, 'admin', 'admin', '', 'pbkdf2$120000$c25haWwtYWktYWRtaW4tMQ==$kakglT/wYKOgv/77Ah1stie58d/JbY2nGgq5DwgUBw4=', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('sai_user', 'id'), COALESCE((SELECT MAX(id) FROM sai_user), 1), TRUE);

-- 插入常见的AI提供商
INSERT INTO sai_model_provider (provider_name, provider_key, description, is_enabled)
VALUES ('OpenAI', 'openai', 'OpenAI官方模型 (GPT-4, GPT-3.5等)', TRUE),
       ('Claude', 'claude', 'Anthropic Claude模型', TRUE),
       ('Ollama', 'ollama', '本地开源模型 (Llama, Mistral等)', TRUE),
       ('Google Gemini', 'gemini', 'Google Gemini模型', TRUE),
       ('阿里云百炼', 'qwen', '阿里云百炼 OpenAI 兼容模型 (Qwen等)', TRUE),
       ('DeepSeek', 'deepseek', 'DeepSeek OpenAI 兼容模型', TRUE),
       ('智谱AI', 'zhipu', '智谱AI OpenAI 兼容模型 (GLM等)', TRUE)
ON CONFLICT (provider_key) DO NOTHING;

INSERT INTO sai_model_config VALUES (1, 5, 'glm-5.1', 'glm-5.1', 'CHAT', 'openai-compatible', '', '', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '{"frequencyPenalty":0.0,"maxTokens":20000,"presencePenalty":0.0,"stopSequences":[],"stream":true,"temperature":0.7,"timeoutMs":300000,"topK":1,"topP":1.0}', NULL, 'GLOBAL', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('sai_model_config', 'id'), COALESCE((SELECT MAX(id) FROM sai_model_config), 1), TRUE);

INSERT INTO sai_agent VALUES (1, '智测先锋专家', '智测先锋专家是一款专注于软件测试与质量保障领域的智能助手。它能够高效生成覆盖全面的测试用例，深度分析Bug根因并提供修复建议，支持编写自动化测试脚本，以及解读复杂的测试报告。适用于软件开发周期的各个QA阶段，包括单元测试、接口测试、UI自动化及回归测试规划。其核心特点是逻辑严密、注重边界与异常场景，帮助团队大幅提升测试效率与软件质量。', NULL, '你是一位资深的软件测试与质量保障（QA）专家，名为“智测先锋专家”。\n\n【角色定位】你是开发团队的最后一道防线，致力于保障软件产品的卓越质量。\n\n【专业领域】精通黑盒与白盒测试、自动化测试框架（如Selenium、Pytest）、接口与性能测试、安全测试及CI/CD持续集成流程。\n\n【回答风格】逻辑严密、条理清晰、客观专业。善于使用结构化排版（如Markdown列表、代码块、表格）呈现测试用例和步骤，语言精炼，直击痛点。\n\n【行为指南】\n1. 生成测试用例：必须覆盖正常流、异常流、边界值和兼容性等方面，确保测试的全面性与无遗漏。\n2. 分析Bug根因：从代码逻辑、数据状态、环境配置等多维度推导，不仅给出修复建议，更要提供预防性的测试策略。\n3. 编写自动化脚本：确保代码规范、包含必要注释与断言（Assert），并明确说明运行依赖与环境配置。\n4. 需求澄清：若用户提问模糊，主动追问业务背景、技术栈等关键细节，拒绝给出宽泛且无实操价值的答案。\n5. 风险预警：始终秉持质量第一理念，在解答中适时提示潜在的测试盲区与质量风险。', '你好！我是智测先锋专家，你的专属软件测试与质量保障顾问。无论是编写用例还是排查Bug，我都能为你提供专业支持！', '["如何为一个用户登录接口设计全面的测试用例？","帮我分析这个空指针异常Bug的可能根因及修复建议。","请提供一段Python的Pytest接口自动化测试脚本示例。","怎样制定一个高效的回归测试策略？"]', 2, FALSE, FALSE, FALSE, FALSE, FALSE, NULL, 1, 20, 1, FALSE, 1, 1, NULL, '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('sai_agent', 'id'), COALESCE((SELECT MAX(id) FROM sai_agent), 1), TRUE);

INSERT INTO sai_app VALUES (1, '1', '测试', '', 'SAI_566a6bfbc26e4998b4841cc927d50c5d', 'LEAST_LOAD', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('sai_app', 'id'), COALESCE((SELECT MAX(id) FROM sai_app), 1), TRUE);

INSERT INTO sai_openapi_user VALUES (1, '1', '46ed53c6a20044c7bbd870848e80f92f', 1, '1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('sai_openapi_user', 'id'), COALESCE((SELECT MAX(id) FROM sai_openapi_user), 1), TRUE);
