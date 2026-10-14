-- ============================================================================
-- V200003 OSS 业务桶改名：ruoyi → panjia-file（与 panjia-archive 命名风格统一）
-- 背景：
--   · sys_oss_config 种子沿用 RuoYi 框架 baseline 的 bucket_name='ruoyi'，
--     与项目 panjia 命名体系不一致；框架代码从配置读桶名，改名无代码影响
--   · MinIO 侧的建桶/迁移由部署脚本（deploy.sh）完成，本迁移只改库内元数据
-- 内容：
--   1) sys_oss_config minio/image 两条配置的 bucket_name 改名
--   2) sys_oss 已上传文件的 url 字段批量替换（本地上传过的测试截图；
--      服务器无历史上传则命中 0 行）
-- 幂等：WHERE 条件限定旧值，重复执行无副作用
-- ============================================================================

UPDATE sys_oss_config SET bucket_name = 'panjia-file', update_time = now()
WHERE config_key IN ('minio', 'image') AND bucket_name = 'ruoyi';

UPDATE sys_oss SET url = replace(url, '/ruoyi/', '/panjia-file/')
WHERE url LIKE '%/ruoyi/%';
