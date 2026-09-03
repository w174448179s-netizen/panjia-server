# 盘家智管（panjia-server）

> 房产中介行业一体化智能管理平台 —— 以「薪酬与收支模块」为首个业务模块
>
> - **架构设计文档**：盘家智管 架构设计 V2.11（2026-08 封版）
> - **业务需求说明书**：薪酬与收支模块 V4.2（开发与验收唯一依据）
> - **技术底座**：Dromara RuoYi-Vue-Plus v6.0.0（单租户版），Spring Boot 4.1 / JDK 21

---

## 一、产品简介

**盘家智管**是面向房产中介（贝壳合作商）的一体化智能管理平台。当前版本实现平台首个业务模块——**薪酬与收支模块**，覆盖：

- **三类人员算薪**：经纪人（A0~A5）、店长（S1/S2）、总监的完整收入/支出计算
- **收入项 6 项**：业绩提成、团队提成、底薪/保底、招聘奖励、奖金、其他收入
- **支出项 9 项**：社保、公积金、考勤、积分、商业保险、宿舍管理费、往月负工资结转、其他支出、个税
- **业绩与结佣管理**：贝壳理房通数据 Excel 导入 → 结佣确认 → 结佣审批 → 计薪
- **公司视角**：社保双口径（个人/公司承担）、部门人工成本归集
- **部门收支分析**：营业收入 / 人工成本 / 门店成本 / 营业利润，可从员工工资明细自动汇总，实现"汇总数 ← 逐人明细"双向追溯

平台后续将逐步纳入房源管理、客源维护、人事管理、AI 智能分析等模块，形成完整业务闭环。

### 核心业务链路

```
贝壳 Excel 导入 → 结佣确认 → 结佣审批（Warm-Flow）
  → 工资计算（规则快照）→ 工资批次（草稿→锁定→发放）
  → 部门收支归集（事件驱动，补发/调整后自动重算）
  → 工资发放 + 个税 + 负工资结转 + 报表导出
```

### 关键业务口径（摘要）

| 人员 | 提成基数 | 提成方式 |
|------|----------|----------|
| 经纪人（A0~A5） | 本人**结佣**业绩 | 结佣计薪业绩 × 当月最终提点（基础提点 + 绩效扣点 + 招聘奖励加点，上限 +10%） |
| 店长-团队 | 门店**新签**业绩（贝壳应收） | 团队新签计薪业绩 × 10%（保底判断同口径，S1 保底 7000 / S2 保底 8000） |
| 店长-个人 | 个人新签 × 70%；当月有实际结佣另按结佣 × 70% | 与员工算法一致，个人提点统一 70% |
| 总监 | 门店**新签**业绩（跳点 6%/7%/8%）+ 本人**结佣**业绩 | 逐店计算后汇总 + 个人提成 |

> 员工提成以**结佣业绩**（贝壳实收确认）为基数；店长团队提成/保底与总监门店提成以**新签业绩**（贝壳应收）为基数。考勤与积分由人事整理为标准 Excel 模板导入，系统不对接钉钉、不做自动采集。

---

## 二、技术栈（对齐底座 v6.0.0）

| 层 | 组件 | 版本 | 用途 |
|----|------|------|------|
| 运行 | Spring Boot | 4.1.0 | 核心框架（JDK 21，兼容 25） |
| 容器 | Jetty | 随 Boot 4.1 | Web 容器 |
| 认证 | Sa-Token + JWT | 1.45.0 | 认证授权、License 校验嵌入点 |
| 三方登录 | JustAuth | 2.0.0 | 钉钉/企微等 OAuth（增值） |
| ORM | MyBatis-Plus（+ Join） | 3.5.17 / 1.5.9 | ORM、分页、连表、乐观锁 |
| 数据库 | **PostgreSQL 16** | — | 业务主库（需求方选定） |
| 缓存 | Redis + Redisson | ≥7.x / 4.6.1 | 缓存、分布式锁、幂等 |
| 工作流 | Warm-Flow | 1.8.9 | 结佣/工资/奖金审批 |
| 任务 | SnailJob | 2.0.2 | 分布式定时任务（备份/异步算薪/心跳） |
| Excel | Apache Fesod | 2.0.2 | 贝壳/考勤/积分导入导出 |
| 接口文档 | SpringDoc + javadoc | 3.0.3 | 接口文档 |
| 监控 | Spring Boot Admin | 4.1.2 | 实例监控 |
| 加密/签名 | BouncyCastle | 1.85 | RSA 签名验签、SSL 证书指纹 |
| 对象存储 | AWS S3 SDK（兼容 MinIO） | 2.48.1 | 附件、备份归档 |
| 工具 | Hutool / Lombok / MapStruct-Plus | — | 工具、编译增强、对象映射 |
| 推送 | ruoyi-common-push（SSE + WebSocket） | 底座内置 | 站内通知/待办（IM 关闭时降级通道） |
| AI 底座 | snail-ai + Spring AI | 1.1.1 / 2.0.0 | 盘家 AI 增值模块的能力底座 |
| 前端 | plus-ui（Vue3 + TS + Element Plus） | 6.x 配套 | PC 管理后台 |
| 代码保护 | ProGuard + ClassFinal | 随构建集成 | L3 代码保护（混淆 + 加密） |

---

## 三、仓库结构（ruoyi 内核 + panjia 业务域）

**命名域铁律**：底座内核保留 `ruoyi-*` 原名（`org.dromara`，原则上零 diff）；盘家业务域全部使用 `panjia-*` 前缀（`com.panjia`），在 `panjia-modules/` 顶层聚合，与 `ruoyi-modules/` 平级物理隔离。

```
panjia-server/
├── ruoyi-admin/          # 底座启动工程（upstream）
├── ruoyi-api/            # 底座通用接口契约（6.x 新增）
├── ruoyi-common/         # 底座通用模块（25 子模块）
├── ruoyi-modules/        # 底座业务（system / job / workflow / gen / ai / demo）
├── ruoyi-extend/         # 扩展（monitor-admin / snailjob-server）
├── panjia-modules/       # ★ 盘家业务域（我方全部业务代码）
│   ├── panjia-common/    #   业务公共（含契约包 panjia-common-api）
│   ├── panjia-salary/    #   薪酬与收支（核心业务，内部按域拆分见下）
│   ├── panjia-license/   #   License 授权客户端
│   ├── panjia-backup/    #   备份容灾
│   ├── panjia-im-core/   #   IM 平台 SPI 抽象层
├── script/               # 部署/数据库脚本
├── docs/                 # 详细设计文档（底座治理、License 详设、前端基础设施等）
└── pom.xml               # 聚合 POM（版本对齐 + CI 目录守卫）
```

> 授权服务器（panjia-console）与内控运营端（console-customer）为**独立工程、独立仓库**，不打入客户交付包，详见架构文档 §2.4.1。

### panjia-salary 内部域拆分（架构级）

按业务事实链路单向依赖、无环：`people → import → performance → commission → payroll → ledger`，另加协作契约层 `contracts`（叶子，只含事件契约不含实现）。

| 域 | 职责 |
|----|------|
| panjia-people | 员工域·公共根（员工聚合根不离开本域） |
| panjia-import | 导入域（贝壳/考勤/积分 Excel 归一化） |
| panjia-performance | 业绩域（口径/折算/确认） |
| panjia-commission | 结佣域（申请→审批→锁定快照） |
| panjia-payroll | 薪酬结算域·核心（算薪→工资批次→锁定/发放） |
| panjia-ledger | 经营结算域·核心（收入/支出/部门台账/利润，事件驱动） |
| panjia-contracts | 协作契约层（跨域事件/DTO/快照契约） |

表前缀规约：`pj_{domain}_*`；快照归消费域（如 `pj_payroll_employee_snapshot` 归 payroll）。

### 模块依赖铁律（CI 强制校验）

- ✅ `panjia-salary → panjia-im-core`（接口）、`panjia-salary → panjia-license`（授权校验）
- ❌ `panjia-salary → panjia-im-dingtalk`（禁止直接依赖任何 IM SDK）
- ❌ `panjia-license / panjia-backup → 具体业务模块`（平台能力必须保持通用）
- ❌ 域间循环依赖（check-domain-deps 门禁）

---

## 四、产品化架构能力

### License 授权体系（L1~L4 防护分层）

| 层 | 名称 | 目标 |
|----|------|------|
| L1 | 在线鉴权 | 防断网永久使用、实时拦截黑名单（激活/心跳 24h/关键操作 /check，30 分钟缓存降级，离线 7 天宽限兜底） |
| L2 | 机器指纹绑定 | Docker 双因子（宿主机 machine-id + 数据卷 instanceId）全量匹配，防容器整体拷贝 |
| L3 | 代码保护 | ProGuard 混淆 + ClassFinal 加密（panjia-license + salary-core） |
| L4 | **受限模式（核心威慑）** | 绕过授权后算薪结果确定性错误，绝不篡改业务数据 |

其他要点：RSA2048 签名（私钥仅存授权服务器）、SSL Pinning 强制（证书双指纹、无关闭入口）、单调时钟防时间回拨、多实例自动拉黑、订阅到期只读保护（历史财务数据允许导出并强制审计）。

### 其他平台能力

- **客户与版本管理**（内控端）：客户档案 → 签发授权 → 版本/升级闭环 → 心跳监控
- **备份与容灾**：数据库/文件/配置多级策略备份，加密归档，恢复双人审批，RPO ≤ 24h / RTO ≤ 2h，季度演练
- **可插拔增值**：IM（钉钉/企微/飞书适配器）、AI、BI 按需选装；审批双轨（Warm-Flow 默认 / IM 选装）；IM 关闭时降级到站内信 + PC 审批，核心完整可用
- **底座升级治理**：RuoYi 为 upstream、盘家仓库为主线；Flyway 统一库变更（00xxxx 底座域 / 10xxxx 薪酬域 / 20xxxx 平台域）；升级分支先行；产品版本与底座版本绑定
- **多租户演进**：当前单租户、一客户一实例（数据物理隔离，无需 ICP）；**不预留 tenant_id 字段**，以架构纪律护栏（雪花 ID / 唯一键集中管控 / 无单客户硬编码）保障未来演进

### 数据架构原则

原始导入数据只读（触发器 + 拦截器双保险）· 规则快照锁定（结佣时冻结薪酬规则）· 工资锁定后不可变（调整/补发走新增单据）· 双向可追溯 · 全局雪花 ID · 敏感字段透明加密。

---

## 五、部署形态

| 形态 | 适用 | 方式 | 资源基线 |
|------|------|------|----------|
| 私有化（客户本地物理机） | 单店/小型连锁 · **主力** | Docker Compose 单节点 | 4C8G/100G（单店 ≤30 人） |
| 代部署（客户云账号 ECS） | 小客户不自建 · **主力** | Docker Compose 单节点 | 2C4G/50G（≤20 人） |
| 托管 SaaS | 未来小客户集群 · 延后 | —（需 ICP 证） | — |

- 统一由 `panjia-deploy` 一键部署（Docker 安装/镜像拉取/数据卷/machine-id 挂载/首次激活），我方全权运维
- 授权服务器（我方部署）：单 ECS 极简商用版（Nginx + Spring Boot + PostgreSQL 16 本地），443 鉴权 / 8443 外网转发端口隔离
- 客户外网访问：公网 IP + HTTPS（首选）＞ 专线/VPN ＞ FRP 隧道（备选，纯转发不落地，合规准入红线见架构文档 §10.4）

---

## 六、开发指南

### 环境要求

JDK 21（兼容 25）· Maven 3.8+ · PostgreSQL 16 · Redis ≥7 · MinIO（可选）

### 快速启动

```bash
# 1. 初始化数据库（执行 script/sql 下脚本）
# 2. 修改 ruoyi-admin/src/main/resources/application-*.yml 数据源/Redis 配置
# 3. 构建 & 启动
./mvnw clean package -DskipTests
java -jar ruoyi-admin/target/ruoyi-admin.jar
# 4. 前端使用 6.x 配套 plus-ui（Vue3 + TS + Element Plus）
```

### 开发纪律（CI 门禁）

1. `ruoyi-*` 目录不得出现 `com.panjia` 包；业务代码一律进 `panjia-modules`
2. 客户交付包构件清单不得含 `panjia-customer / ruoyi-gen / ruoyi-demo`
3. PR diff 不得触及底座目录（白名单 =《底座改动清单》，见 `docs/底座改动清单.md`）
4. 库变更统一走 Flyway 脚本，表前缀须在域白名单内
5. 大批量算薪（>200 人）走 SnailJob 异步任务，按门店分片、支持定向重算

### 文档索引

| 文档 | 位置 |
|------|------|
| 架构设计文档 V2.11 | 项目外部（客户提供） |
| 薪酬与收支模块 业务需求说明书 V4.2 | 项目外部（客户提供） |
| License 客户端校验 详细设计 | `docs/盘家智管_License客户端校验_详细设计_V1.3.md` |
| 底座治理 详细设计 | `docs/详细设计_底座治理_T-INF-01_02_07.md` |
| 前端基础设施 详细设计 | `docs/详细设计_前端基础设施_T-FE-INF-01_02_03.md` |
| 底座改动清单 | `docs/底座改动清单.md` |

---

## 七、致谢与开源声明

本项目基于开源框架 **[RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus)**（Dromara，v6.0.0）构建：

- 上游仓库：[gitee](https://gitee.com/dromara/RuoYi-Vue-Plus) · [github](https://github.com/dromara/RuoYi-Vue-Plus) · [gitcode](https://gitcode.com/dromara/RuoYi-Vue-Plus)
- 官方文档：[plus-doc](https://plus-doc.dromara.org)
- 遵循 MIT 开源协议，项目中保留开源协议文件（见 `LICENSE`）

底座内核（`ruoyi-*` 目录）保持与 upstream 低冲突合并能力，盘家业务全部收敛于 `panjia-modules` 自建模块，详见《底座治理详细设计》。

---

© 盘家智管 · panjia-server · 架构 V2.11 / 需求 V4.2 · 2026-08
