-- ============================================================
-- V110026
-- 下线钉钉考勤定时同步（DingTalkSyncScheduleTask / DingTalkScheduleConfig 已删除），
-- 改为在「钉钉同步」页面手动触发；同步时间参数随之移除。
-- ============================================================

DELETE FROM sys_config WHERE config_key = 'panjia.dingtalk.sync_cron';
