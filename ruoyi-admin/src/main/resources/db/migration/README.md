# Flyway 迁移脚本版本号段位规范

> 维护日期：2026-09-11
> 本规范由「Flyway Migration 段位重整」项目确立。
> **编码格式**：V{2 位域码}{4 位序号}.sql（如 V110002 = panjia-people 域的第 2 个脚本）
> **扩展性**：2 位域码 = 90 个域 × 每域 10000 个序号 = 90 万号位，至少支撑未来 5 年。

---

## 一、段位分配总表（10 = 盘家业务；20-29 预留扩展；30-69 长期预留）

| 段位 | 业务域 | 归属模块 | 当前脚本 | 职责 |
|------|--------|----------|----------|------|
| `V1` | 基线 | `ruoyi-admin` | `V1__ruoyi_baseline.sql` | 官方 PostgreSQL 全量脚本（**不可变**） |
| `V10xxxx` | **盘家全业务种子** | `ruoyi-admin` | `V100001__panjia_menu_seed.sql` | 盘家业务菜单 + 角色 + 工作流定义 + 系统参数 |
| `V11xxxx` | **people 域** | `panjia-modules/panjia-people` | `V110002`-`V110005` | 员工主数据 / 算薪事实 / 字典 / 员工导入回迁 |
| `V12xxxx` | **import 域** | `panjia-modules/panjia-import` | `V120002`-`V120007` | 导入模板 / 批次表 / 模板管理菜单 |
| `V13xxxx` | **outbox 域** | `panjia-modules/panjia-outbox` | `V130001`-`V130002` | 事件 outbox + 幂等键 |
| `V14xxxx` | **performance 域** | `panjia-modules/panjia-performance` | `V140002`-`V140003` | 业绩事实表 + 业绩域菜单 |
| `V15xxxx` | customer 域 | `panjia-modules/panjia-customer` | （预留）| 客户账号 / 设备 / 在线状态 |
| `V16xxxx` | **commission 域** | `panjia-modules/panjia-commission` | （即将新建）| 结佣申请 / 审批 / 锁定 |
| `V17xxxx` | **payroll 域** | `panjia-modules/panjia-payroll` | （即将新建）| 薪酬结算：算薪引擎 / 工资批次 / 状态机 |
| `V18xxxx` | **ledger 域** | `panjia-modules/panjia-ledger` | （即将新建）| 经营结算：收入 / 支出 / 部门台账 / 利润 |
| `V19xxxx` | **rules 域** | `panjia-modules/panjia-rules` | （即将新建）| 规则引擎（条件 / 动作 / 评分 / 触发器）|
| `V20xxxx`-`V29xxxx` | 预留扩展 | TBD | — | 第 2 代盘家业务域（10 个域位备用） |
| `V30xxxx`-`V69xxxx` | 长期预留 | TBD | — | 第 3/4 代业务（40 个域位）|
| `V70xxxx`-`V89xxxx` | 系统 / 横切域 | TBD | — | license / backup / monitor / log 等基础设施 |
| `V90xxxx`-`V99xxxx` | auth / cross-cutting | TBD | — | 鉴权 / 跨切关注点 |

**段位纪律**：
- **新域申请流程**：在 PR 标题里写 `[seg-apply]` + 拟用段位 → 维护者确认后写入本表 + 同步 `scripts/migration-check.sh` 的 `SEGMENT_OWNER_FILE` → 自动纳入 CI 校验。
- **段位一旦分配不可回收**（即使域被砍，段位也要保留用于历史版本号解释）。

---

## 二、段位内部编号规则

每个域段位 `XX` 后跟 **4 位**顺序号（共 10000 个号位），从 `XX0001` 开始顺序递增，**禁止跳号**（如果某号位的脚本被废弃，必须在新提交中说明，并使用下一个号位）。

跨域引用检查：脚本中 `INSERT/UPDATE` 涉及跨域表（如 import 域脚本引用 people 域 `pj_people_employee`），需在文件顶部注释里明确依赖关系。

---

## 三、业务域职责边界

| 域 | 段位 | 拥有表前缀 | 职责 |
|----|------|------------|------|
| people | `V11xxxx` | `pj_people_*` | 员工主数据、算薪事实、变更审计、员工导入回迁 |
| import | `V12xxxx` | `pj_import_*` | 导入模板、批次表、原始记录、标准化记录、模板管理菜单 |
| outbox | `V13xxxx` | `pj_event_*` | 事件 outbox + 幂等键 |
| performance | `V14xxxx` | `pj_perf_*` | 业绩事实表、调整单、期间封账 |
| customer | `V15xxxx` | `pj_customer_*` | 客户域表 |
| commission | `V16xxxx` | `pj_commission_*` | 结佣申请 / 审批 / 锁定 |
| payroll | `V17xxxx` | `pj_payroll_*` | 算薪引擎 / 工资批次 / 状态机 |
| ledger | `V18xxxx` | `pj_ledger_*` | 收入 / 支出 / 部门台账 / 利润 |
| rules | `V19xxxx` | `pj_rule_*` | 规则定义 / 触发器 / 评分卡 |

**注意事项**：
- people 域的"员工导入"功能使用独立的 `pj_people_import_*` 批次表（与 import 域的 `pj_import_*` 并行），这是**设计决定**——员工导入的业务语义与单据导入不同，需要独立的标准化流程。
- `pj_people_import_*` 内部仍引用 `pj_import_template` 模板定义（人员模板走 import 域模板管理菜单），但实际批次/原始/标准化数据归 people 域。
- commission → payroll 是**强依赖流水线**（commission 锁定后才能跑算薪），所以 commission 用 `V16`、payroll 用 `V17`，相邻段位便于查阅依赖链路。

---

## 四、菜单 ID 段位与 parent_id 约束

- 顶级菜单（`parent_id=0`）：`1761400000000002000`-`1761400000000002999`
- 业务子菜单：`1761400000000002100`-`1761400000000002599`
- 运维/超管菜单：`1761400000000002600`-`1761400000000002999`

**parent_id 必须指向真实存在的菜单**：禁止 INSERT 菜单时 `parent_id` 引用未 INSERT 的菜单（孤儿引用）。CI 检查脚本会扫所有 SQL 文件中的 `INSERT INTO sys_menu`，提取 `parent_id` 并验证是否在同文件或更早版本的 SQL 中被 INSERT。

---

## 五、升级纪律

1. **所有库变更走 Flyway**，禁止手工执行 SQL。
2. **基线不可变**：`V1` 及已合入 main 的 migration 不可修改。
3. **缺陷修复用新增脚本**（如 `V110006`），不修改旧 migration。
4. **跨模块序号必须归属对应域**：
   - `V10xxxx` 只能在 `ruoyi-admin` 模块下（盘家全业务种子）
   - `V11xxxx` 只能在 `panjia-modules/panjia-people` 模块下
   - `V12xxxx` 只能在 `panjia-modules/panjia-import` 模块下
   - `V13xxxx` 只能在 `panjia-modules/panjia-outbox` 模块下
   - `V14xxxx` 只能在 `panjia-modules/panjia-performance` 模块下
   - `V15-V19xxxx` 各自归属新模块（按段位分配表）
5. **升级前自动全量备份**（由 `panjia-backup` 模块触发）。
6. **升级分支先行**：`upgrade/ruoyi-{ver}` 验证通过后才合入 main。
7. **Maven multi-module 资源冲突规避**：每个 panjia-* 模块只放自己域的 SQL，**禁止跨域 SQL 散布**——避免 jar + target/classes 双份扫描导致 `Found more than one migration with version xxx` 启动失败。

---

## 六、CI 检查脚本

`scripts/migration-check.sh`（CI 阶段执行，**默认走 strict 模式**）：

1. 扫描所有 `**/db/migration/V*.sql` 文件
2. 校验段位与模块归属是否匹配（按本节第 4 条映射）
3. 校验序号是否跳号（同一域内连续）
4. 校验跨模块文件无重名（避免 Maven classpath 冲突）
5. 校验孤儿 `parent_id` 引用（python3 解析 SQL，处理多行 INSERT）
6. 校验 `sys_role_menu` 中 `role_id=1` 仅用于超管专属资源

**新域接入 CI**：在 `scripts/migration-check.sh` 的 `SEGMENT_OWNER_FILE` 加一行 `XX <module-name>`，PR 通过 CI 即视为接入完成。