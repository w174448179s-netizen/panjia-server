-- 清理无用的 OSS 配置种子：
--   qiniu / aliyun / qcloud 为框架占位数据（密钥全是 XXXXX，从未使用）
--   image 为历史遗留，endpoint 指向 host.docker.internal（Linux 容器无法解析，见 deploy/docker/OSS_SETUP.md 坑1）
-- 业务代码仅使用默认配置 minio（OssFactory.instance()）及按 sys_oss.service 的按需查找；
-- 以后需要对接其他存储时，在 系统管理→文件配置管理 界面按需新增即可。
-- 幂等：已清理的环境重复执行影响 0 行。
DELETE FROM sys_oss_config WHERE config_key IN ('qiniu', 'aliyun', 'qcloud', 'image');
