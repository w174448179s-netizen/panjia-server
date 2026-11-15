-- ============================================================
-- V110027
-- IM 账号映射表：本地员工/账号 ↔ 第三方 IM 账号
--
-- 背景：钉钉工作通知、待办推送需要「本地用户 → 钉钉 userid」的映射；
--       未来接入企业微信/飞书时复用同一张表，用 channel + corp_id 区分渠道与企业，
--       渠道差异字段统一放 ext_json，不新增列。
--
-- 匹配口径：钉钉 job_number（工号）→ pj_people_employee.employee_code，
--           匹配成功后回填 employee_id 与 sys_user.user_id。
-- 依赖：pj_people_employee（V110001）、sys_user（V1 基线）
-- ============================================================

CREATE TABLE pj_people_im_mapping (
    mapping_id        BIGINT       PRIMARY KEY,                    -- 雪花 ID（应用层 ASSIGN_ID）
    channel           VARCHAR(16)  NOT NULL,                       -- 渠道：DINGTALK / WECOM / FEISHU
    corp_id           VARCHAR(64)  NOT NULL DEFAULT '',             -- 企业标识（钉钉 corpId / 企微 corpid；单企业留空串）
    external_user_id  VARCHAR(128) NOT NULL,                       -- 渠道内用户唯一标识（钉钉 userid / 企微 userid）
    external_union_id VARCHAR(128),                                -- 开放平台 unionid（跨应用稳定标识，可空）
    external_name     VARCHAR(64),                                 -- 渠道侧姓名（同步快照）
    external_mobile   VARCHAR(32),                                 -- 渠道侧手机号（同步快照）
    external_code     VARCHAR(64),                                 -- 渠道侧工号（钉钉 job_number）
    external_active   BOOLEAN      NOT NULL DEFAULT TRUE,          -- 渠道侧是否在职
    employee_id       BIGINT,                                      -- 本地员工 ID（未匹配为空）
    user_id           BIGINT,                                      -- 本地登录账号 ID（员工未开户为空）
    match_type        VARCHAR(16)  NOT NULL DEFAULT 'NONE',        -- CODE=工号匹配 MOBILE=手机号匹配 MANUAL=人工 NONE=未匹配
    ext_json          JSONB,                                       -- 渠道差异扩展字段（预留，不入常规查询）
    last_sync_time    TIMESTAMP    NOT NULL DEFAULT NOW(),         -- 最近一次同步时间
    create_time       TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 渠道内一个账号只允许一行
CREATE UNIQUE INDEX uk_im_mapping_ext ON pj_people_im_mapping(channel, corp_id, external_user_id);
-- 同一企业内 unionid 唯一（为空不约束）
CREATE UNIQUE INDEX uk_im_mapping_union ON pj_people_im_mapping(channel, corp_id, external_union_id)
    WHERE external_union_id IS NOT NULL;
-- 按员工/账号反查渠道账号（发消息、查待办用）
CREATE INDEX idx_im_mapping_employee ON pj_people_im_mapping(channel, corp_id, employee_id);
CREATE INDEX idx_im_mapping_user ON pj_people_im_mapping(channel, corp_id, user_id);

COMMENT ON TABLE  pj_people_im_mapping IS 'IM 账号映射（本地员工/账号 ↔ 钉钉、企微、飞书账号，渠道无关）';
COMMENT ON COLUMN pj_people_im_mapping.channel IS '渠道：DINGTALK 钉钉 / WECOM 企业微信 / FEISHU 飞书';
COMMENT ON COLUMN pj_people_im_mapping.corp_id IS '企业标识，用于同一渠道多企业隔离；单企业可留空串';
COMMENT ON COLUMN pj_people_im_mapping.external_user_id IS '渠道内用户唯一标识：钉钉 userid、企微 userid 等';
COMMENT ON COLUMN pj_people_im_mapping.external_union_id IS '开放平台 unionid，跨应用稳定标识，可空';
COMMENT ON COLUMN pj_people_im_mapping.external_code IS '渠道侧工号（钉钉 job_number），匹配本地员工用';
COMMENT ON COLUMN pj_people_im_mapping.employee_id IS '本地员工 ID（pj_people_employee.employee_id），未匹配为空';
COMMENT ON COLUMN pj_people_im_mapping.user_id IS '本地登录账号 ID（sys_user.user_id），员工未开户为空';
COMMENT ON COLUMN pj_people_im_mapping.match_type IS '匹配方式：CODE=工号匹配 MOBILE=手机号匹配 MANUAL=人工指定 NONE=未匹配';
COMMENT ON COLUMN pj_people_im_mapping.ext_json IS '渠道差异扩展字段（JSONB，预留，如部门路径、角色、union 扩展信息）';

-- ----------------------------
-- 钉钉企业标识参数（可选；映射按企业隔离时填写）
-- ----------------------------
INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, remark)
VALUES
    (1761700000000000034, '钉钉-企业 CorpId', 'panjia.dingtalk.corp_id', '', 'Y',
     1761000000000000100, 1761100000000000001, now(),
     '企业 CorpId，用于 IM 账号映射按企业隔离；单企业可留空，留空时映射表 corp_id 记为空串')
ON CONFLICT (config_id) DO NOTHING;
