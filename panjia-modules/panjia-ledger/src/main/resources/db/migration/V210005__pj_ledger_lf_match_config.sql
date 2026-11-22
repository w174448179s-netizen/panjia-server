-- ============================================================
-- V210005
-- 理房通流水：每日自动匹配参数（系统管理 → 参数设置）
--
-- 说明：
--   1) 调度开关与 cron 不再写死 application.yml，改由参数管理维护；
--   2) 修改后无需重启，下一次调度按新值计算；非法 cron 回退默认 0 0 0 * * ?；
--   3) 幂等：按 config_key 判重，重复执行不报错。
-- ============================================================

BEGIN;

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type,
                        create_dept, create_by, create_time, remark)
SELECT 1761500000000000010,
       '理房通-每日自动匹配开关',
       'panjia.lf.match.schedule.enabled',
       'true',
       'Y',
       1761000000000000100,
       1761100000000000001,
       now(),
       'true=每天按 cron 自动重跑理房通流水匹配；false=关闭定时匹配（导入后仍自动匹配）'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_config WHERE config_key = 'panjia.lf.match.schedule.enabled'
);

INSERT INTO sys_config (config_id, config_name, config_key, config_value, config_type,
                        create_dept, create_by, create_time, remark)
SELECT 1761500000000000011,
       '理房通-每日自动匹配CRON',
       'panjia.lf.match.schedule.cron',
       '0 0 0 * * ?',
       'Y',
       1761000000000000100,
       1761100000000000001,
       now(),
       '每日自动匹配 cron，按 Asia/Shanghai 执行；默认每天 00:00，非法表达式回退默认值'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_config WHERE config_key = 'panjia.lf.match.schedule.cron'
);

COMMIT;
