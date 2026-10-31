-- ============================================================
-- 盘家智管 · 钉钉集成域初始化（V18 段位）
-- 归属域：panjia-im-dingtalk
-- 内容：同步日志表 + 基础档案 → 钉钉同步（考勤）页面与按钮权限
-- ============================================================

-- ----------------------------
-- 1. 钉钉同步日志表
-- 一次同步一行：先落 RUNNING，结束后回写结果与字段缺口
-- ----------------------------
CREATE TABLE pj_dingtalk_sync_log (
    id              BIGINT       PRIMARY KEY,                   -- 雪花 ID（应用层 ASSIGN_ID 生成）
    sync_type       VARCHAR(32)  NOT NULL,                      -- EMPLOYEE / ATTENDANCE
    period          VARCHAR(7),                                 -- 考勤期间 YYYY-MM；人员同步为空
    trigger_type    VARCHAR(16)  NOT NULL,                      -- MANUAL / SCHEDULE
    status          VARCHAR(16)  NOT NULL,                      -- RUNNING / SUCCESS / PARTIAL / FAILED
    total_count     INT          NOT NULL DEFAULT 0,            -- 钉钉侧记录数
    success_count   INT          NOT NULL DEFAULT 0,            -- 成功条数
    skipped_count   INT          NOT NULL DEFAULT 0,            -- 跳过条数（工号未匹配等）
    failed_count    INT          NOT NULL DEFAULT 0,            -- 失败条数
    message         TEXT,                                       -- 结果说明 / 字段缺口 / 错误摘要
    operator_id     BIGINT,                                     -- 手动触发人 ID；定时任务为空
    start_time      TIMESTAMP    NOT NULL,                      -- 开始时间
    end_time        TIMESTAMP                                   -- 结束时间
);

CREATE INDEX idx_dingtalk_sync_log_time ON pj_dingtalk_sync_log(start_time DESC);
CREATE INDEX idx_dingtalk_sync_log_type_period ON pj_dingtalk_sync_log(sync_type, period);

COMMENT ON TABLE  pj_dingtalk_sync_log IS '钉钉同步日志（人员/考勤同步结果与字段缺口留痕）';
COMMENT ON COLUMN pj_dingtalk_sync_log.sync_type IS '同步类型：EMPLOYEE(人员) / ATTENDANCE(考勤)';
COMMENT ON COLUMN pj_dingtalk_sync_log.period IS '考勤期间 YYYY-MM；人员同步为空';
COMMENT ON COLUMN pj_dingtalk_sync_log.trigger_type IS '触发方式：MANUAL(手动) / SCHEDULE(定时)';
COMMENT ON COLUMN pj_dingtalk_sync_log.status IS '状态：RUNNING / SUCCESS / PARTIAL / FAILED';
COMMENT ON COLUMN pj_dingtalk_sync_log.message IS '结果说明，含字段缺口（如考勤报表权限未取到）';

-- ----------------------------
-- 2. 菜单：基础档案 → 钉钉同步（页面，内含两个同步按钮）
-- icon 必须使用 src/assets/icons/svg 中真实存在的 svg 名，否则前端渲染空白
-- ----------------------------
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002720, '钉钉同步', 1761400000000002000, 90, 'dingtalk', 'people/dingtalk/index', NULL, 'N', 'Y', 'C', '0', '0', NULL, 'peoples', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '钉钉人员档案与考勤汇总同步入口');

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES (1761400000000002723, '同步考勤', 1761400000000002720, 1, NULL, NULL, NULL, 'N', 'Y', 'F', '0', '0', 'dingtalk:sync:attendance', '#', '', '', 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '手动触发钉钉月度考勤汇总同步');

-- 默认授权：超级管理员（1761300000000000001）、总监（1761300000000000010）、人事（1761300000000000013）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002720),
(1761300000000000001, 1761400000000002723),
(1761300000000000010, 1761400000000002720),
(1761300000000000010, 1761400000000002723),
(1761300000000000013, 1761400000000002720),
(1761300000000000013, 1761400000000002723)
ON CONFLICT DO NOTHING;
