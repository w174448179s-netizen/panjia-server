# Flyway 迁移脚本版本号段位

| 段位 | 范围 | 用途 | 示例 |
|------|------|------|------|
| V1 | 基线 | 官方 PostgreSQL 全量脚本（不可变） | `V1__ruoyi_baseline.sql` |
| `00xxxx` | 底座域 | 底座升级增量 SQL（PG 方言转译） | `V000001__ruoyi_upgrade_6.0.1.sql` |
| `10xxxx` | 薪酬业务域 | `panjia-salary` 业务表 | `V100001__salary_employee.sql` |
| `20xxxx` | 平台域 | license / backup / customer / im | `V200001__license_key.sql` |
| `30xxxx` | 预留 | 未来扩展 | — |

## 升级纪律

1. 所有库变更走 Flyway，禁止手工执行 SQL
2. 基线不可变：V1 及已合入 main 的 migration 不可修改
3. 缺陷修复用新增脚本，不修改旧 migration
4. 底座增量 SQL 须 PG 方言转译（官方脚本以 MySQL 为主）
5. 升级前自动全量备份（由 panjia-backup 模块触发）
6. 升级分支先行：`upgrade/ruoyi-{ver}` 验证通过后才合入 main
