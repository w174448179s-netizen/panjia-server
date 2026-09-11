# 盘家智管 · 薪酬与收支 · Phase 1 详细设计（员工域 panjia-people）

> **版本：** V1.1（评审修订版）
> **编制日期：** 2026-09-09
> **基线：** Phase 0 V3.0（底座 & 公共）完成后的第一个业务域
> **依据：** 架构设计 V1.6、产品化架构 V1.6、业务需求 V4.2、菜单设计生产上线版、Phase 0 V3.0、**《panjia-people 设计文档评审》（2026-09-09）**
> **读者：** 架构师、开发（含 AI 编码）、CI 负责人、测试
>
> **V1.1 变更说明：** 合入评审修订补丁——补齐 AI 总规约 P0 红线对齐（禁止 record / TypedId）、`EmployeeId` POJO 定义、`EmployeeSnapshot` 完整字段约束矩阵、`EmployeeSnapshotService` 取数逻辑契约、职级生效时序、sys_user 异常场景、人事变更类型枚举、事务/锁/幂等策略、CI 校验完整清单。架构决策（快照归属消费域、聚合根不外泄）保持不变。详见附录 A。

---



### 0.3 P0 红线对齐（评审修订 · 补丁一）

> 通用红线沿用 Phase 0 §0；本节列出 **people 域必须额外强化的 P0 约束**（对齐 AI 总规约）。

| 红线 | 本域落地方式 |
|------|-------------|
| 禁止 Java record / TypedId 强类型 ID | `EmployeeId` = 普通 POJO 包装 `Long`（见 §8.4）；`EmployeeSnapshot` = `@Data` POJO；**禁止 record** |
| 禁止数据库自增主键 | 所有表主键 = `BIGINT` 雪花 ID（应用层生成），禁止 `BIGSERIAL` / `AUTO_INCREMENT` |
| 禁止数据库触发器 | 乐观锁用 MP `@Version`；审计用 AOP；**无触发器** |
| 禁止裸 `@Scheduled` | 本域无定时任务；未来需要走 SnailJob |
| 禁止 Gson | 序列化统一 Jackson |
| 禁止自行新建线程 | 异步走 SnailJob 或底座 `@Async` |
| 状态/类型字段必须定义 Enum | 见 §3.5；数据库存 code 值，禁止魔法字符串 |

### 0.4 评审修订清单（V1.0 → V1.1）

| # | 评审意见 | 落地章节 |
|---|----------|----------|
| ① | 强类型 ID 约束（禁止 record） | §0.3、§8.4 |
| ② | EmployeeSnapshot 完整字段 + 包含/不包含矩阵 | §8.3（追加） |
| ③ | 职级生效时序 + 快照取数逻辑契约 | §3.2、§5（追加 §5.2） |
| ④ | sys_user 异常场景（删除/禁用/无账号） | §4（追加 §4.4） |
| ⑤ | 枚举缺失（人事变更类型） | §3.5（追加 EmployeeChangeTypeEnum） |
| ⑥ | 快照不持久化在 people 域 | §5.6 |
| ⑦ | 事务 / 幂等 / 锁策略 | §5.7 |
| 架构风险 A | 禁止快照下沉 people 域建表 | §5.6（红线重申） |
| 架构风险 B | 下游禁止 `new Employee()` / import 聚合根 | §3.6、§9 CI |

---

## §0 全局规约引用

> 本节为 Phase 1 特有补充；通用编码规约（P0 红线、数据库、后端编码、注释、Import、AI 执行约束）**全部沿用 Phase 0 §0**，此处不重复。仅列出 **Phase 1 新增或强化的规约**。

### 0.1 Phase 1 特有规约

| 项 | 规则 | 理由 |
|----|------|------|
| 员工聚合根不可离开 people 域 | `Employee` 类**不得**出现在 panjia-contracts 以外的任何 `panjia-*` 业务域 import 中 | 架构 ADR-011：跨域不共享聚合根 |
| Snapshot 出口唯一 | 外部域获取员工信息**只能通过** `EmployeeSnapshotService#takeSnapshot(employeeId, pointInTime)` | 保证快照冻结语义一致 |
| sys_user 单向引用 | `pj_people_employee.user_id` → `sys_user.id`（外键），**禁止反向**：sys_user 不加任何业务字段 | 主数据边界（架构 §10.3） |
| 职级生效日期 | 每次职级变更**新增一条** `pj_people_level` 记录（带 `effective_from`），**禁止 UPDATE 旧记录** | 支持按历史时点取值 |
| 变更日志 | 员工档案的任何写操作**必须**写 `pj_people_change_log`（谁、何时、改了什么、旧值→新值） | 审计闭环 |

### 0.2 people 域依赖声明

```
panjia-people
  ├── 依赖：panjia-contracts（仅接口/Snapshot/DTO/Id）
  ├── 依赖：ruoyi-common-core / ruoyi-common-redis（底座）
  └── 被依赖：panjia-import / panjia-performance / panjia-commission / panjia-payroll / panjia-ledger
              （均只能通过 EmployeeId / EmployeeSnapshot 访问）
```

> **CI 校验**：`check-domain-deps` 断言 people 域 pom 中**无** `panjia-import`/`panjia-performance`/`panjia-commission`/`panjia-payroll`/`panjia-ledger` 依赖。

---

## §1 员工域总览

### 1.1 职责（架构 §10.2 细化）

| 子域 | 核心职责 | 表 |
|------|---------|-----|
| 员工档案 | 工号、姓名、入职/离职、兼职标记、状态、与 sys_user 绑定 | `pj_people_employee` |
| 职级管理 | 当前职级 + 职级历史（按 effective_from 追溯） | `pj_people_level` |
| 社保档案 | 社保基数、个人比例、公积金自缴、商业保险、宿舍 | `pj_people_social_insurance` |
| 师徒关系 | 导师-学员绑定、推荐日期、有效性（≥2年行业经验） | `pj_people_mentor_relation` |
| 变更日志 | 全量审计（谁、何时、改了什么） | `pj_people_change_log` |
| **快照供给**（核心出口） | 按 EmployeeId + 时点 → EmployeeSnapshot | 消费域自有表（如 `pj_payroll_employee_snapshot`） |

### 1.2 包结构

```
panjia-modules/panjia-people/
├── domain/                          ← 领域层（聚合根、值对象、领域服务、状态枚举）
│   ├── Employee.java                 ← 聚合根（核心）
│   ├── EmployeeLevel.java            ← 职级值对象
│   ├── SocialInsuranceProfile.java   ← 社保档案值对象
│   ├── MentorRelation.java           ← 师徒关系实体
│   ├── EmployeeStatusEnum.java       ← 员工状态枚举
│   ├── EmployeeRoleEnum.java         ← 人员角色枚举（经纪人/店长/总监）
│   ├── LevelTypeEnum.java            ← 职级类型枚举（A0~A5/S1/S2/总监）
│   ├── PartTimeStatusEnum.java       ← 兼职状态枚举
│   └── service/
│       ├── EmployeeDomainService.java    ← 领域服务（职级变更/离职/转店等业务规则）
│       └── SnapshotFactory.java          ← Snapshot 构造（Employee → EmployeeSnapshot）
│
├── application/                      ← 应用层（用例编排、事务边界）
│   ├── EmployeeService.java           ← 员工 CRUD 应用服务
│   ├── EmployeeLevelService.java      ← 职级变更应用服务
│   ├── SocialInsuranceService.java    ← 社保档案维护
│   ├── MentorRelationService.java     ← 师徒关系维护
│   ├── EmployeeSnapshotService.java   ← ★ 快照供给（外部域唯一入口）
│   ├── EmployeeImportService.java     ← 批量导入（从 Excel/手工录入）
│   └── dto/
│       ├── EmployeeDTO.java
│       ├── EmployeeCreateDTO.java
│       ├── EmployeeUpdateDTO.java
│       ├── EmployeeLevelChangeDTO.java
│       └── EmployeeSnapshotDTO.java   ← Snapshot 对外 DTO
│
├── infrastructure/                    ← 基础设施层
│   ├── repository/
│   │   ├── EmployeeMapper.java        ← MyBatis-Plus Mapper
│   │   ├── EmployeeLevelMapper.java
│   │   ├── SocialInsuranceMapper.java
│   │   ├── MentorRelationMapper.java
│   │   └── ChangeLogMapper.java
│   ├── persistence/
│   │   ├── EmployeePO.java            ← 持久化对象（与 domain 分离）
│   │   ├── EmployeeLevelPO.java
│   │   ├── SocialInsurancePO.java
│   │   ├── MentorRelationPO.java
│   │   └── ChangeLogPO.java
│   └── adapter/
│       └── SysUserAdapter.java        ← sys_user 读取适配器（只读引用）
│
├── interface/                         ← 接口层（Controller、DTO 转换）
│   ├── EmployeeController.java
│   ├── EmployeeLevelController.java
│   ├── MentorRelationController.java
│   └── converter/
│       └── EmployeeConverter.java      ← Domain ↔ DTO 转换
│
└── pom.xml
```

### 1.3 模块依赖（pom.xml 关键声明）

```xml
<dependencies>
    <!-- 允许：契约层（叶子模块） -->
    <dependency>panjia-contracts</dependency>
    <!-- 允许：RuoYi 底座 -->
    <dependency>ruoyi-common-core</dependency>
    <dependency>ruoyi-common-redis</dependency>
    <dependency>ruoyi-system-domain</dependency>  <!-- sys_user 实体引用 -->
    <!-- 🚨 禁止：任何 panjia-* 业务域 -->
    <dependency>lombok</dependency>
</dependencies>
```

---

## §2 Task-1-1 数据库详细设计

### 2.1 表清单

| 表名 | 说明 | 归属 |
|------|------|------|
| `pj_people_employee` | 员工主表（聚合根） | people |
| `pj_people_level` | 职级历史表（每次变更新增记录） | people |
| `pj_people_social_insurance` | 社保档案表 | people |
| `pj_people_mentor_relation` | 师徒关系表 | people |
| `pj_people_change_log` | 变更日志表 | people |

### 2.2 表结构 DDL

#### 2.2.1 `pj_people_employee` — 员工主表

```sql
CREATE TABLE pj_people_employee (
    id                  BIGINT       PRIMARY KEY,   -- 雪花 ID（= EmployeeId）
    user_id             BIGINT       NOT NULL UNIQUE, -- 关联 sys_user.id（单向外键）
    employee_code       VARCHAR(64)  NOT NULL UNIQUE, -- 工号（业务唯一标识，导入匹配键）
    name                VARCHAR(64)  NOT NULL,      -- 姓名
    phone               VARCHAR(20),                 -- 手机号
    id_card_no          VARCHAR(64),                 -- 身份证号（加密存储）
    dept_id             BIGINT       NOT NULL,      -- 所属门店/部门（= sys_dept.dept_id）
    post_id             BIGINT,                      -- 岗位（= sys_post.post_id，可空）
    employee_role       VARCHAR(16)  NOT NULL,      -- 人员角色：AGENT/STORE_MANAGER/DIRECTOR
    part_time_status    VARCHAR(16)  NOT NULL DEFAULT 'FULL_TIME', -- FULL_TIME/PART_TIME
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/RESIGNED/ON_LEAVE
    hire_date           DATE         NOT NULL,      -- 入职日期
    resign_date         DATE,                        -- 离职日期（status=RESIGNED 时必填）
    social_insurance_enabled BOOLEAN  NOT NULL DEFAULT TRUE, -- 是否缴纳社保（兼职=false）
    housing_fund_amount     DECIMAL(12,2) NOT NULL DEFAULT 0, -- 公积金自缴金额
    commercial_insurance    BOOLEAN  NOT NULL DEFAULT FALSE,   -- 是否购买商业保险
    dormitory_enabled       BOOLEAN  NOT NULL DEFAULT FALSE,   -- 是否住宿舍
    remark              VARCHAR(500),                -- 备注
    created_by          VARCHAR(64)  NOT NULL DEFAULT 'admin',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(64),
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    opt_lock_version    INT          NOT NULL DEFAULT 1  -- 乐观锁（MyBatis-Plus @Version）
);

CREATE INDEX idx_employee_dept      ON pj_people_employee(dept_id, status);
CREATE INDEX idx_employee_role      ON pj_people_employee(employee_role, status);
CREATE INDEX idx_employee_code_lower ON pj_people_employee(LOWER(employee_code)); -- 工号大小写不敏感匹配

-- CHECK 约束
CONSTRAINT chk_employee_status      CHECK (status IN ('ACTIVE','RESIGNED','ON_LEAVE')),
CONSTRAINT chk_employee_role        CHECK (employee_role IN ('AGENT','STORE_MANAGER','DIRECTOR')),
CONSTRAINT chk_part_time_status     CHECK (part_time_status IN ('FULL_TIME','PART_TIME')),
CONSTRAINT chk_resign_date          CHECK (resign_date IS NULL OR status = 'RESIGNED'),
CONSTRAINT chk_hire_resign_order    CHECK (resign_date IS NULL OR resign_date >= hire_date);
```

**关键字段说明：**

| 字段 | 设计决策 | 依据 |
|------|---------|------|
| `id` | 雪花 ID，= `EmployeeId`（Long），禁止自增 | Phase 0 §0.1 |
| `user_id` | 关联 `sys_user.id`，UNIQUE（一对一），单向引用 | 架构 §10.3 |
| `employee_code` | 工号，导入匹配键（贝壳数据按工号/姓名匹配），UNIQUE | 业务需求 §13.1 |
| `employee_role` | 经纪人/店长/总监 → 决定算薪策略路由 | 业务需求 四/五/六章 |
| `part_time_status` | **独立字段**，与底薪=0 逻辑无关 | 业务需求 §4.5 |
| `social_insurance_enabled` | 兼职=false → 跳过社保/公积金 | 业务需求 §14.2 |
| `housing_fund_amount` | 公积金自缴金额（公司不缴） | 业务需求 §8.2 |
| `opt_lock_version` | 乐观锁，防并发编辑 | 架构 ADR-014 |

#### 2.2.2 `pj_people_level` — 职级历史表

```sql
CREATE TABLE pj_people_level (
    id                  BIGINT       PRIMARY KEY,   -- 雪花 ID
    employee_id         BIGINT       NOT NULL,      -- 关联 pj_people_employee.id
    level_code          VARCHAR(16)  NOT NULL,      -- A0/A1/A2/A3/A4/A5/S1/S2/DIRECTOR
    level_name          VARCHAR(64)  NOT NULL,      -- 职级名称（冗余，便于展示）
    base_salary         DECIMAL(12,2) NOT NULL DEFAULT 0,  -- 底薪（A0=4500, S1=7000, S2=8000, 总监=6000）
    commission_rate     DECIMAL(5,4)  NOT NULL DEFAULT 0,   -- 基础提成比例（0.20/0.55/.../0.70）
    social_insurance_ratio DECIMAL(5,4) NOT NULL DEFAULT 0,  -- 社保个人承担比例（0.20/0.55/.../0.30）
    effective_from      DATE         NOT NULL,      -- 生效日期（职级变更日）
    effective_to        DATE,                        -- 失效日期（NULL=当前有效）
    change_reason       VARCHAR(255),                -- 变更原因（晋升/降级/初始化）
    created_by          VARCHAR(64)  NOT NULL DEFAULT 'admin',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_level_employee_effective UNIQUE (employee_id, effective_from),
    CONSTRAINT chk_level_code        CHECK (level_code IN ('A0','A1','A2','A3','A4','A5','S1','S2','DIRECTOR')),
    CONSTRAINT chk_level_date_range   CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE INDEX idx_level_employee_time ON pj_people_level(employee_id, effective_from DESC);

-- 🚨 应用层保证：同一 employee_id 同一时点只有一条 effective_to IS NULL 的记录
--   （CI 不建排除约束，靠应用层 + 单测保证，见 Backlog IMP-003）
```

**职级-薪酬映射（V1 固化数据，种子写入）：**

| level_code | base_salary | commission_rate | social_insurance_ratio | 说明 |
|------------|-------------|-----------------|------------------------|------|
| A0 | 4500.00 | 0.2000 | 0.2000 | 2500无责+2000绩效 |
| A1 | 0.00 | 0.5500 | 0.5500 | — |
| A2 | 0.00 | 0.6000 | 0.6000 | — |
| A3 | 0.00 | 0.6500 | 0.6500 | — |
| A4 | 0.00 | 0.6700 | 0.6700 | — |
| A5 | 0.00 | 0.7000 | 0.7000 | — |
| S1 | 7000.00 | 0.7000 | 0.3000 | 保底7000 |
| S2 | 8000.00 | 0.7000 | 0.3000 | 保底8000 |
| DIRECTOR | 6000.00 | 0.7000 | 0.3000 | 底薪6000 |

> **重要**：这些值是**职级模板**，`pj_people_level` 存储每个员工的实际职级记录。算薪时按快照取值，不实时查此表。

#### 2.2.3 `pj_people_social_insurance` — 社保档案表

```sql
CREATE TABLE pj_people_social_insurance (
    id                      BIGINT       PRIMARY KEY,   -- 雪花 ID
    employee_id             BIGINT       NOT NULL UNIQUE, -- 一对一
    social_base_amount      DECIMAL(12,2) NOT NULL DEFAULT 1637.15, -- 社保基数
    personal_ratio          DECIMAL(5,4)  NOT NULL DEFAULT 0.20,    -- 个人承担比例
    company_ratio           DECIMAL(5,4)  NOT NULL DEFAULT 0.80,    -- 公司承担比例（= 1 - personal_ratio）
    housing_fund_amount     DECIMAL(12,2) NOT NULL DEFAULT 0,       -- 公积金自缴金额
    commercial_insurance_amount DECIMAL(12,2) NOT NULL DEFAULT 21,   -- 商业保险费（月）
    dormitory_fee           DECIMAL(12,2) NOT NULL DEFAULT 0,       -- 宿舍管理费（月）
    effective_from          DATE         NOT NULL DEFAULT '2026-01-01',
    effective_to            DATE,
    created_by              VARCHAR(64)  NOT NULL DEFAULT 'admin',
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              VARCHAR(64),
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_social_ratio_sum CHECK (personal_ratio + company_ratio = 1.0)
);
```

> **社保双口径**（业务需求 §14.2）：
> - 个人承担 = `social_base_amount × personal_ratio` → 从工资扣除
> - 公司承担 = `social_base_amount × company_ratio` → 归集部门收支表
> - 校验：`personal_ratio + company_ratio = 1.0`（CHECK 约束）

#### 2.2.4 `pj_people_mentor_relation` — 师徒关系表

```sql
CREATE TABLE pj_people_mentor_relation (
    id                  BIGINT       PRIMARY KEY,   -- 雪花 ID
    mentor_id           BIGINT       NOT NULL,      -- 师傅 employee_id
    apprentice_id       BIGINT       NOT NULL,      -- 徒弟 employee_id
    apprentice_industry_years DECIMAL(4,1) NOT NULL DEFAULT 0, -- 徒弟行业经验年数（推荐时）
    recommend_date      DATE         NOT NULL,      -- 推荐日期
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,  -- 是否有效（徒弟离职→false）
    deactivated_at      TIMESTAMP,                    -- 失效时间（徒弟离职日）
    created_by          VARCHAR(64)  NOT NULL DEFAULT 'admin',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_mentor_apprentice UNIQUE (mentor_id, apprentice_id),
    CONSTRAINT chk_not_self_ref     CHECK (mentor_id != apprentice_id)
);

CREATE INDEX idx_mentor_relation_mentor ON pj_people_mentor_relation(mentor_id, is_active);
CREATE INDEX idx_mentor_relation_apprentice ON pj_people_mentor_relation(apprentice_id);

-- 业务规则（应用层校验）：
-- 1. apprentice_industry_years >= 2 → 才有招聘奖励资格
-- 2. 同一徒弟只能有一个有效师傅（is_active=true）
-- 3. 师傅最多 +10%（5个合格徒弟）
```

#### 2.2.5 `pj_people_change_log` — 变更日志表

```sql
CREATE TABLE pj_people_change_log (
    id              BIGINT       PRIMARY KEY,   -- 雪花 ID
    employee_id     BIGINT       NOT NULL,      -- 关联员工
    change_type     VARCHAR(32)  NOT NULL,      -- CREATE/UPDATE_LEVEL/UPDATE_SOCIAL/RESIGN/TRANSFER/MENTOR_CHANGE
    field_name      VARCHAR(64),                 -- 变更的字段名（UPDATE 时）
    old_value       TEXT,                        -- 旧值（JSON 序列化）
    new_value       TEXT,                        -- 新值（JSON 序列化）
    change_reason   VARCHAR(500),                -- 变更原因
    operator        VARCHAR(64)  NOT NULL,      -- 操作人（login_name）
    operated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_change_type CHECK (change_type IN ('CREATE','UPDATE_LEVEL','UPDATE_SOCIAL','UPDATE_BASE','RESIGN','TRANSFER','MENTOR_CREATE','MENTOR_DEACTIVATE'))
);

CREATE INDEX idx_change_log_employee_time ON pj_people_change_log(employee_id, operated_at DESC);
```

### 2.3 Flyway 脚本清单

| 脚本 | 内容 |
|------|------|
| `V6__pj_people_employee.sql` | 员工主表 + 索引 + 约束 |
| `V7__pj_people_level.sql` | 职级历史表 + 索引 + 约束 |
| `V8__pj_people_social_insurance.sql` | 社保档案表 + CHECK |
| `V9__pj_people_mentor_relation.sql` | 师徒关系表 + 索引 + 约束 |
| `V10__pj_people_change_log.sql` | 变更日志表 + 索引 |
| `V11__pj_people_seed.sql` | 种子数据（职级模板 + 当前 6 家门店员工初始化） |

> **禁止触发器**：`updated_at` 自动填充用 MyBatis-Plus 自动填充插件；变更日志由应用层在 Service 中显式写入（不用触发器）。

### 2.4 种子数据（V11__pj_people_seed.sql）

#### 2.4.1 职级模板（sys_dict + level 初始化）

**sys_dict_type：`panjia_employee_level`**

| dict_value | dict_label | 备注 |
|------------|-----------|------|
| A0 | A0（新人） | base=4500, rate=20% |
| A1 | A1 | rate=55% |
| A2 | A2 | rate=60% |
| A3 | A3 | rate=65% |
| A4 | A4 | rate=67% |
| A5 | A5 | rate=70% |
| S1 | S1（店长） | base=7000, rate=70% |
| S2 | S2（店长） | base=8000, rate=70% |
| DIRECTOR | 总监 | base=6000, rate=70% |

**sys_dict_type：`panjia_employee_role`**

| dict_value | dict_label |
|------------|-----------|
| AGENT | 经纪人 |
| STORE_MANAGER | 店长 |
| DIRECTOR | 总监 |

**sys_dict_type：`panjia_part_time_status`**

| dict_value | dict_label |
|------------|-----------|
| FULL_TIME | 全职 |
| PART_TIME | 兼职 |

#### 2.4.2 当前客户初始化数据（示例，实际值待实施确认）

```sql
-- 6 家门店店长
INSERT INTO pj_people_employee (id, user_id, employee_code, name, dept_id, employee_role, part_time_status, status, hire_date, social_insurance_enabled) VALUES
(1001, 1001, 'FZ001', '王青松', 101, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE),  -- 龙湖店 S2
(1002, 1002, 'FZ002', '吴志龙', 102, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE),  -- 云庭店 S2
(1003, 1003, 'FZ003', '王学正', 103, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE),  -- 锦城名都店 S2
(1004, 1004, 'FZ004', '李润梅', 104, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE),  -- 长庆店 S2
(1005, 1005, 'FZ005', '牟真琴', 105, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE),  -- 西派少城店 S1
(1006, 1006, 'FZ006', '周治江', 106, 'STORE_MANAGER', 'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE),  -- 租赁部 S1
(1007, 1007, 'FZ007', '廖明',   100, 'DIRECTOR',     'FULL_TIME', 'ACTIVE', '2024-01-01', TRUE);  -- 总监

-- 对应职级记录（effective_from = 入职日）
INSERT INTO pj_people_level (id, employee_id, level_code, level_name, base_salary, commission_rate, social_insurance_ratio, effective_from, change_reason) VALUES
(2001, 1001, 'S2', '高级店长', 8000, 0.70, 0.30, '2024-01-01', '初始化'),
(2002, 1002, 'S2', '高级店长', 8000, 0.70, 0.30, '2024-01-01', '初始化'),
(2003, 1003, 'S2', '高级店长', 8000, 0.70, 0.30, '2024-01-01', '初始化'),
(2004, 1004, 'S2', '高级店长', 8000, 0.70, 0.30, '2024-01-01', '初始化'),
(2005, 1005, 'S1', '初级店长', 7000, 0.70, 0.30, '2024-01-01', '初始化'),
(2006, 1006, 'S1', '初级店长', 7000, 0.70, 0.30, '2024-01-01', '初始化'),
(2007, 1007, 'DIRECTOR', '总监', 6000, 0.70, 0.30, '2024-01-01', '初始化');

-- 社保档案
INSERT INTO pj_people_social_insurance (id, employee_id, social_base_amount, personal_ratio, company_ratio, housing_fund_amount) VALUES
(3001, 1001, 1637.15, 0.30, 0.70, 0),
(3002, 1002, 1637.15, 0.30, 0.70, 0),
(3003, 1003, 1637.15, 0.30, 0.70, 0),
(3004, 1004, 1637.15, 0.30, 0.70, 0),
(3005, 1005, 1637.15, 0.30, 0.70, 0),
(3006, 1006, 1637.15, 0.30, 0.70, 0),
(3007, 1007, 1637.15, 0.30, 0.70, 0);
```

> **注意**：经纪人员工（A序列）由导入或手工录入批量添加，种子数据仅初始化管理层。

---

## §3 Task-1-2 领域对象详细设计

### 3.1 聚合根 `Employee`

```java
package com.panjia.people.domain;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import lombok.Data;

/**
 * 员工聚合根 —— people 域的核心领域对象。
 *
 * 聚合边界：Employee 是聚合根，EmployeeLevel / SocialInsuranceProfile
 *          是它的组成部分（同生命周期），MentorRelation 是独立实体（有自己生命周期）。
 *
 * 🚨 铁律：Employee 聚合根不得离开 people 域。
 *   外部域只能通过 EmployeeSnapshot（快照）或 EmployeeId（Long）引用。
 */
@Data
public class Employee {

    /** 员工 ID（= 雪花 ID = EmployeeId 的 Long 值） */
    private Long id;

    /** 关联的 sys_user ID（单向引用） */
    private Long userId;

    /** 工号（业务唯一标识，导入匹配键） */
    private String employeeCode;

    /** 姓名 */
    private String name;

    /** 所属部门（门店）ID = sys_dept.dept_id */
    private Long deptId;

    /** 人员角色：AGENT / STORE_MANAGER / DIRECTOR */
    private EmployeeRoleEnum role;

    /** 兼职状态 */
    private PartTimeStatusEnum partTimeStatus;

    /** 员工状态 */
    private EmployeeStatusEnum status;

    /** 入职日期 */
    private Date hireDate;

    /** 离职日期（status=RESIGNED 时有值） */
    private Date resignDate;

    /** 是否缴纳社保（兼职=false） */
    private boolean socialInsuranceEnabled;

    /** 公积金自缴金额 */
    private BigDecimal housingFundAmount;

    /** 是否购买商业保险 */
    private boolean commercialInsurance;

    /** 是否住宿舍 */
    private boolean dormitoryEnabled;

    /** 职级历史（按 effectiveFrom 倒序） */
    private List<EmployeeLevel> levelHistory;

    /** 当前社保档案 */
    private SocialInsuranceProfile socialInsurance;

    // ==================== 领域行为（业务规则内聚） ====================

    /**
     * 变更职级 —— 新增一条 Level 记录，旧记录设置 effectiveTo。
     *
     * @param newLevelCode  新职级编码
     * @param effectiveDate 生效日期（必须 >= hireDate，且 > 当前有效记录的 effectiveFrom）
     * @param reason        变更原因
     * @throws IllegalStateException 如果日期不合法或状态不允许变更
     */
    public void changeLevel(String newLevelCode, Date effectiveDate, String reason) {
        // 校验：员工必须在职
        if (status != EmployeeStatusEnum.ACTIVE) {
            throw new IllegalStateException("离职员工不可变更职级");
        }
        // 校验：生效日期不能早于入职日
        if (effectiveDate.before(hireDate)) {
            throw new IllegalStateException("职级生效日期不能早于入职日期");
        }
        // 关闭当前有效记录
        EmployeeLevel current = getCurrentLevel();
        if (current != null && current.getEffectiveTo() == null) {
            current.setEffectiveTo(effectiveDate); // 旧记录的失效日 = 新记录生效日
        }
        // 新增记录
        EmployeeLevel newLevel = EmployeeLevel.create(id, newLevelCode, effectiveDate, reason);
        levelHistory.add(newLevel);
    }

    /**
     * 获取指定时点的有效职级。
     *
     * @param pointInTime 历史时点（如算薪月份的最后一天）
     * @return 该时点有效的职级，找不到抛异常
     */
    public EmployeeLevel getLevelAt(Date pointInTime) {
        return levelHistory.stream()
                .filter(l -> !l.getEffectiveFrom().after(pointInTime))
                .filter(l -> l.getEffectiveTo() == null || !l.getEffectiveTo().before(pointInTime))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "员工 " + employeeCode + " 在 " + pointInTime + " 无有效职级"));
    }

    /**
     * 离职处理 —— 设置状态 + 离职日 + 关闭当前职级记录。
     *
     * @param resignDate 离职日期
     */
    public void resign(Date resignDate) {
        if (status == EmployeeStatusEnum.RESIGNED) {
            throw new IllegalStateException("员工已离职，不可重复操作");
        }
        if (resignDate.before(hireDate)) {
            throw new IllegalStateException("离职日期不能早于入职日期");
        }
        this.status = EmployeeStatusEnum.RESIGNED;
        this.resignDate = resignDate;
        // 关闭当前职级
        EmployeeLevel current = getCurrentLevel();
        if (current != null && current.getEffectiveTo() == null) {
            current.setEffectiveTo(resignDate);
        }
    }

    /**
     * 是否兼职。
     */
    public boolean isPartTime() {
        return partTimeStatus == PartTimeStatusEnum.PART_TIME;
    }

    /**
     * 是否参与社保扣款。
     * 规则：全职 + socialInsuranceEnabled = true → 参与。
     */
    public boolean shouldDeductSocial() {
        return !isPartTime() && socialInsuranceEnabled;
    }

    // ==================== 私有辅助 ====================

    private EmployeeLevel getCurrentLevel() {
        return levelHistory.stream()
                .filter(l -> l.getEffectiveTo() == null)
                .findFirst()
                .orElse(null);
    }
}
```

### 3.2 值对象 `EmployeeLevel`

```java
package com.panjia.people.domain;

import java.util.Date;

import lombok.Data;

/**
 * 职级记录 —— 值对象（不可变语义，追加式写入）。
 *
 * 每次职级变更新增一条记录（带 effectiveFrom），禁止 UPDATE 旧记录。
 */
@Data
public class EmployeeLevel {

    private Long id;
    private Long employeeId;
    private String levelCode;       // A0~A5 / S1 / S2 / DIRECTOR
    private String levelName;       // 冗余展示名
    private BigDecimal baseSalary;  // 底薪
    private BigDecimal commissionRate;  // 基础提成比例
    private BigDecimal socialInsuranceRatio; // 社保个人比例
    private Date effectiveFrom;     // 生效日期
    private Date effectiveTo;       // 失效日期（NULL = 当前有效）
    private String changeReason;    // 变更原因

    /**
     * 工厂方法：创建新职级记录。
     */
    public static EmployeeLevel create(Long employeeId, String levelCode, Date effectiveFrom, String reason) {
        EmployeeLevel level = new EmployeeLevel();
        level.setEmployeeId(employeeId);
        level.setLevelCode(levelCode);
        level.setEffectiveFrom(effectiveFrom);
        level.setChangeReason(reason);
        // levelName / baseSalary / commissionRate / socialInsuranceRatio
        // 由 LevelTemplateService 填充（查职级模板）
        return level;
    }

    /**
     * 判断在指定时点是否有效。
     */
    public boolean isEffectiveAt(Date pointInTime) {
        boolean afterFrom = !effectiveFrom.after(pointInTime);
        boolean beforeTo = effectiveTo == null || !effectiveTo.before(pointInTime);
        return afterFrom && beforeTo;
    }
}
```

### 3.3 值对象 `SocialInsuranceProfile`

```java
package com.panjia.people.domain;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 社保档案 —— 值对象。
 *
 * 社保双口径（业务需求 §14.2）：
 *   个人承担 = baseAmount × personalRatio → 从工资扣除
 *   公司承担 = baseAmount × companyRatio  → 归集部门收支表
 */
@Data
public class SocialInsuranceProfile {

    private Long employeeId;
    private BigDecimal socialBaseAmount;   // 社保基数（默认 1637.15）
    private BigDecimal personalRatio;      // 个人比例
    private BigDecimal companyRatio;       // 公司比例（= 1 - personalRatio）

    /**
     * 计算个人承担社保金额。
     */
    public BigDecimal calculatePersonalAmount() {
        return socialBaseAmount.multiply(personalRatio).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算公司承担社保金额（归集部门收支）。
     */
    public BigDecimal calculateCompanyAmount() {
        return socialBaseAmount.multiply(companyRatio).setScale(2, RoundingMode.HALF_UP);
    }
}
```

### 3.4 实体 `MentorRelation`

```java
package com.panjia.people.domain;

import java.util.Date;

import lombok.Data;

/**
 * 师徒关系 —— 独立实体（有独立生命周期，不属于 Employee 聚合）。
 *
 * 业务规则（业务需求 §4.2 收入3）：
 *   1. 徒弟行业经验 >= 2 年 → 师傅获得招聘奖励资格
 *   2. 师傅每推荐 1 人 +2%，上限 +10%（最多 5 人）
 *   3. 徒弟离职 → 关系失效（isActive = false），已发奖励不追回
 */
@Data
public class MentorRelation {

    private Long id;
    private Long mentorId;          // 师傅 EmployeeId
    private Long apprenticeId;      // 徒弟 EmployeeId
    private BigDecimal apprenticeIndustryYears; // 推荐时行业经验年数
    private Date recommendDate;     // 推荐日期
    private boolean active;         // 是否有效

    /**
     * 判断是否满足招聘奖励条件（>= 2 年）。
     */
    public boolean isQualified() {
        return apprenticeIndustryYears != null
                && apprenticeIndustryYears.compareTo(new BigDecimal("2.0")) >= 0;
    }

    /**
     * 失效（徒弟离职时调用）。
     */
    public void deactivate(Date resignDate) {
        this.active = false;
    }
}
```

### 3.5 枚举定义

```java
package com.panjia.people.domain;

/**
 * 员工状态枚举（P0：含完整状态机方法）。
 */
public enum EmployeeStatusEnum {
    ACTIVE("在职"),
    RESIGNED("离职"),
    ON_LEAVE("停薪留职");

    private final String desc;

    EmployeeStatusEnum(String desc) { this.desc = desc; }

    public String getDesc() { return desc; }

    /**
     * 是否可 transitions 到目标状态。
     */
    public boolean canTransitTo(EmployeeStatusEnum target) {
        if (this == target) return false;
        switch (this) {
            case ACTIVE:   return target == RESIGNED || target == ON_LEAVE;
            case ON_LEAVE: return target == ACTIVE || target == RESIGNED;
            case RESIGNED: return false; // 终态
            default: return false;
        }
    }

    public boolean isTerminal() { return this == RESIGNED; }
    public boolean isActive() { return this == ACTIVE; }
}

/**
 * 人员角色枚举 —— 决定算薪策略路由。
 */
public enum EmployeeRoleEnum {
    AGENT("经纪人"),
    STORE_MANAGER("店长"),
    DIRECTOR("总监");

    private final String desc;
    EmployeeRoleEnum(String desc) { this.desc = desc; }
    public String getDesc() { return desc; }
}

/**
 * 兼职状态枚举。
 *
 * 🚨 与底薪无关：partTimeStatus=FULL_TIME 但 baseSalary=0 是合法的（如 A1 经纪人）。
 */
public enum PartTimeStatusEnum {
    FULL_TIME("全职"),
    PART_TIME("兼职");

    private final String desc;
    PartTimeStatusEnum(String desc) { this.desc = desc; }
    public String getDesc() { return desc; }
}
```

### 3.6 Snapshot 构造（`SnapshotFactory`）

### 3.5.1 补充：`EmployeeChangeTypeEnum`（评审 ⑤）

```java
package com.panjia.people.domain;

/**
 * 人事变更类型枚举 —— 用于 pj_people_change_log.change_type 编码。
 * （评审补充：原 §3.5 缺失此类，导致变更日志类型无统一口径）
 */
public enum EmployeeChangeTypeEnum {
    ENTRY(10, "入职"),
    RESIGN(20, "离职"),
    LEVEL_CHANGE(30, "职级变更"),
    SOCIAL_CHANGE(40, "社保基数/比例变更"),
    MENTOR_BIND(50, "师徒关系绑定"),
    MENTOR_UNBIND(60, "师徒关系解除"),
    PART_TIME_CHANGE(70, "兼职标记变更");

    private final int code;
    private final String desc;
    EmployeeChangeTypeEnum(int code, String desc) { this.code = code; this.desc = desc; }
    public int getCode() { return code; }
    public String getDesc() { return desc; }
}
```

> **规约**：`pj_people_change_log.change_type` **只存 code 值**（如 `30`），禁止存中文或魔法字符串。

### 3.6 Snapshot 构造（`SnapshotFactory`）

```java
package com.panjia.people.domain.service;

import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeLevel;

import java.util.Date;

/**
 * Snapshot 工厂 —— 将 Employee 聚合根在指定时点的状态冻结为 Snapshot。
 *
 * 🚨 这是 Employee 离开 people 域的唯一合法形式。
 *    外部域（payroll/commission）只能接收 EmployeeSnapshot，不能直接持有 Employee。
 */
public class SnapshotFactory {

    /**
     * 按指定时点构造员工快照。
     *
     * @param employee    员工聚合根（含完整职级历史）
     * @param pointInTime 快照时点（如算薪月份的任意一天）
     * @return EmployeeSnapshot（不可变 POJO，可序列化）
     */
    public static EmployeeSnapshot create(Employee employee, Date pointInTime) {
        EmployeeSnapshot snapshot = new EmployeeSnapshot();
        snapshot.setEmployeeId(employee.getId());
        snapshot.setEmployeeCode(employee.getEmployeeCode());
        snapshot.setName(employee.getName());
        snapshot.setDeptId(employee.getDeptId());
        snapshot.setRole(employee.getRole().name());
        snapshot.setPartTime(employee.isPartTime());

        // 职级快照（按时点取值）
        EmployeeLevel levelAt = employee.getLevelAt(pointInTime);
        snapshot.setLevelCode(levelAt.getLevelCode());
        snapshot.setBaseSalary(levelAt.getBaseSalary());
        snapshot.setCommissionRate(levelAt.getCommissionRate());
        snapshot.setSocialInsuranceRatio(levelAt.getSocialInsuranceRatio());

        // 社保快照
        if (employee.getSocialInsurance() != null) {
            snapshot.setSocialBaseAmount(employee.getSocialInsurance().getSocialBaseAmount());
            snapshot.setPersonalSocialRatio(employee.getSocialInsurance().getPersonalRatio());
            snapshot.setCompanySocialRatio(employee.getSocialInsurance().getCompanyRatio());
            snapshot.setHousingFundAmount(employee.getSocialInsurance().getHousingFundAmount());
        }

        snapshot.setSnapshotAt(new Date()); // 快照生成时间
        return snapshot;
    }
}
```

> **Snapshot 字段说明**：只包含算薪/结佣需要的字段，不包含敏感信息（身份证号等）。快照一旦生成即不可变。

---

## §4 Task-1-3 sys_user 绑定 & 主数据边界

### 4.1 绑定规约

| 职责 | 归属 | 表/字段 |
|------|------|---------|
| 登录账号、密码、角色、部门树、菜单权限、岗位 | **RuoYi 底座** | `sys_user` / `sys_dept` / `sys_role` / `sys_post` / `sys_menu` |
| 工号、入职/离职、职级、薪酬档案、社保、兼职 | **panjia-people** | `pj_people_employee` + 扩展表 |

**关系图：**

```
sys_user                    pj_people_employee
┌──────────────┐            ┌──────────────────┐
│ id (PK)      │◀─user_id──│ id (PK)          │
│ username     │  唯一外键  │ employee_code    │
│ password     │            │ hire_date        │
│ dept_id      │            │ employee_role    │
│ role_ids     │            │ part_time_status │
│ ...          │            │ social_insurance │
└──────────────┘            └────────┬─────────┘
                                     │
                      ┌──────────────┴──────────────┐
                      ▼                             ▼
              pj_people_level              pj_people_social_insurance
              （职级+生效日期+历史）            （社保/公积金/保险/宿舍）
```

### 4.2 SysUserAdapter（只读引用）

```java
package com.panjia.people.infrastructure.adapter;

import org.dromara.system.api.domain.SysUser;
import org.dromara.system.mapper.SysUserMapper;
import org.springframework.stereotype.Component;

/**
 * sys_user 读取适配器 —— 只读引用，禁止写入。
 *
 * 🚨 铁律：
 *   1. 只允许 SELECT sys_user，禁止 INSERT/UPDATE/DELETE
 *   2. people 域不持有 SysUser 实体，只取需要的字段（username/nick_name/dept_id）
 *   3. sys_user 不添加任何业务字段（base_salary/rank_id 等）
 */
@Component
public class SysUserAdapter {

    @Autowired
    private SysUserMapper sysUserMapper;

    /**
     * 根据 login_name 查询 sys_user（用于员工创建时绑定）。
     */
    public SysUser findByLoginName(String loginName) {
        return sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUserName, loginName)
        );
    }

    /**
     * 根据 dept_id 查询部门下所有 sys_user（用于批量绑定校验）。
     */
    public List<SysUser> findByDeptId(Long deptId) {
        return sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getDeptId, deptId)
        );
    }
}
```

### 4.3 主数据边界 CI 校验

| 校验 | 实现 | 违反 |
|------|------|------|
| `pj_people_*` 表不得出现 `sys_` 前缀字段（除 `user_id`） | ArchUnit | 编译报错 |
| `sys_user` 不得出现业务字段（base_salary / rank_id / social_*） | ArchUnit + 集成测试 | 编译报错 |
| `pj_people_employee.user_id` 是唯一跨域引用键 | ArchUnit | 编译报错 |
| people 域不得 import 其他业务域 | `check-domain-deps` | CI 拦截 |

---

## §5 EmployeeSnapshotService（核心出口）

### 4.4 sys_user 异常场景与主数据边界（评审修订 · 补丁三 ④）

**主数据边界（架构 §10.3）补充规则：**

| # | 规则 | 校验 |
|---|------|------|
| 1 | people 域**只读**引用 `sys_user`；禁止 INSERT/UPDATE/DELETE sys_user | ArchUnit：`SysUserAdapter` 不依赖 `SysUserMapper` 写方法 |
| 2 | `pj_people_employee.user_id` 允许为 `NULL`（支持无登录账号员工） | DDL：字段可空 |
| 3 | sys_user 删除时 `user_id` **保留**，不级联删除 | DDL：无 ON DELETE CASCADE |
| 4 | 创建员工时 sys_user 可后补；先有员工档案再绑定账号为合法流程 | 应用层允许 user_id=null 创建 |

> 上述规则与 §5.3 快照服务异常场景处理保持一致。

---

## §5 EmployeeSnapshotService（核心出口）

```java
package com.panjia.people.application;

import com.panjia.contracts.snapshot.EmployeeSnapshot;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.service.SnapshotFactory;
import com.panjia.people.infrastructure.repository.EmployeeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 员工快照供给服务 —— 外部域获取员工信息的唯一入口。
 *
 * 🚨 这是 people 域对外暴露的核心 Port 实现。
 *    payroll/commission 等域**只**通过此服务获取员工历史状态。
 *
 * 使用场景：
 *   - 算薪时：payroll 调用 takeSnapshot(employeeId, payrollMonthEnd)
 *   - 结佣时：commission 调用 takeSnapshot(employeeId, commissionDate)
 */
@Slf4j
@Service
@Transactional(readOnly = true)  // 只读服务
public class EmployeeSnapshotService {

    @Autowired
    private EmployeeMapper employeeMapper;

    /**
     * 为单个员工在指定时点创建快照。
     *
     * @param employeeId  员工 ID
     * @param pointInTime 快照时点（通常为算薪月份最后一天）
     * @return EmployeeSnapshot（不可变）
     * @throws ServiceException 员工不存在或时点无有效职级
     */
    public EmployeeSnapshot takeSnapshot(Long employeeId, Date pointInTime) {
        Employee employee = employeeMapper.selectFullById(employeeId);
        if (employee == null) {
            throw new ServiceException("员工不存在: " + employeeId, BizCode.EMPLOYEE_NOT_FOUND);
        }
        return SnapshotFactory.create(employee, pointInTime);
    }

    /**
     * 批量创建快照（算薪时一次性冻结全部门店员工）。
     *
     * @param employeeIds 员工 ID 列表
     * @param pointInTime 统一快照时点
     * @return Map<employeeId, EmployeeSnapshot>
     */
    public Map<Long, EmployeeSnapshot> takeSnapshots(List<Long> employeeIds, Date pointInTime) {
        return employeeIds.stream()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> takeSnapshot(id, pointInTime)
                ));
    }

    /**
     * 查询指定部门在某时点所有在职员工的快照（门店维度算薪）。
     *
     * @param deptId      部门（门店）ID
     * @param pointInTime 快照时点
     * @return 快照列表
     */
    public List<EmployeeSnapshot> takeSnapshotsByDept(Long deptId, Date pointInTime) {
        List<Long> employeeIds = employeeMapper.selectActiveIdsByDept(deptId, pointInTime);
        return takeSnapshots(employeeIds, pointInTime).values().stream().toList();
    }
}
```

### 5.1 快照时点约定

### 5.2 取数逻辑契约（评审修订 · 补丁三 ③ ⑤）

> 评审要求：`takeSnapshot(employeeId, pointInTime)` 必须**严格按业务时点挑选唯一生效职级**，逻辑文档化、可测试。

```
1. 校验 employeeId 对应员工存在；否则抛 ServiceException(BizCode.EMPLOYEE_NOT_FOUND)
2. 查询 pj_people_level（按时点取唯一生效记录）：
     SELECT * FROM pj_people_level
     WHERE employee_id = #{id}
       AND effective_from <= #{pointInTime}
       AND (effective_to IS NULL OR effective_to >= #{pointInTime})
     ORDER BY effective_from DESC
     LIMIT 1
3. 若无记录 → 抛 ServiceException(BizCode.NO_VALID_LEVEL, "该时点无有效职级")  // 评审：不降级、不返回 null
4. 社保快照（pj_people_social_insurance）同理取生效记录
5. 师徒快照（pj_people_mentor_relation）取有效关系
6. 组装 EmployeeSnapshot（纯内存 DTO，people 域不落表）
```

**职级生效时序规则（评审 ③）：**

| 场景 | 处理 |
|------|------|
| 同一员工多条历史职级 | 按 `effective_from` 倒序取**第一条**覆盖时点的记录 |
| 时点无生效职级 | 抛业务异常（**不降级**，避免算薪口径错误） |
| 未来生效职级 | 时点未到 → 不生效；生效记录以 `effective_to` 为界 |
| 重叠记录 | 以 `effective_from` 最新者为准（应用层校验禁止重叠） |

### 5.3 sys_user 异常场景处理（评审修订 · 补丁三 ④）

| 场景 | 处理方式 |
|------|----------|
| sys_user 账号被**删除** | people 域 `user_id` **保留**（历史关联不破坏），标记员工状态=离职；**禁止置空** |
| sys_user 账号被**禁用** | people 域不感知，快照照常生成（历史工资不受影响） |
| **无 sys_user 账号**的员工 | **允许存在**；`user_id = null`；只参与薪酬结算，无登录权限 |
| people 域写 sys_user | **禁止**；`SysUserAdapter` 只做只读查询 |

> 详见 §4.4。

### 5.4 快照字段约束（评审修订 · 补丁三 ②）

在 §8.3 `EmployeeSnapshot` 字段基础上，明确**包含 / 不包含**矩阵：

| 分类 | ✅ 包含 | ❌ 不包含 |
|------|---------|-----------|
| 身份 | employeeId, code, name, deptId, isPartTime | 数据库主键 id、内部版本号 |
| 职级 | levelCode, baseSalary, personalRatio, companyRatio | 职级历史全部记录 |
| 社保 | socialBase, housingFund | 变更历史 |
| 师徒 | mentorId | 全部师徒关系 |
| 审计 | snapshotDate, snapshottedAt | created_by, updated_by |

> **禁止事项**：快照是只读 DTO，**禁止回写 people 域任何数据**。

### 5.5 EmployeeId 定义（评审修订 · 补丁三 ①）

```java
package com.panjia.contracts.id;

import lombok.Data;
import java.io.Serializable;

/**
 * 员工强类型标识 —— 普通 POJO 包装 Long。
 *
 * 🚨 禁止 Java Record（AI 总规约 P0 红线）；禁止自定义 TypedId 体系。
 *   数据库存储原始 bigint 雪花 ID。
 */
@Data
public class EmployeeId implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long value;

    public EmployeeId() { /* 无参构造：MP / Jackson 需要 */ }
    public EmployeeId(Long value) { this.value = value; }
    public Long getValue() { return value; }

    /** 便捷方法：避免裸 Long 比较 */
    public boolean isPresent() { return value != null; }
}
```

### 5.6 下游消费 & 持久化归属（评审修订 · 补丁三 ⑥ + 架构风险 A）

| 消费域 | 消费方式 | 快照持久化表（归消费域） |
|--------|----------|------------------------|
| payroll | 算薪前调用 `takeSnapshot` | `pj_payroll_employee_snapshot` |
| commission | 结佣前调用 `takeSnapshot` | `pj_commission_employee_snapshot` |

> **🚨 people 域绝不建 `pj_people_employee_snapshot` 表**（ADR-012）。
> people 输出的只是内存 DTO；下游快照表数据正确性由**下游自身负责**，people 域不校验。

### 5.7 事务 / 幂等 / 锁策略（评审修订 · 补丁三 ⑦）

| 操作 | 事务 | 锁 / 幂等 |
|------|------|-----------|
| `takeSnapshot`（读） | **无写事务**（`readOnly=true`） | 无锁 |
| 员工档案更新（入职/离职/职级变更） | `@Transactional(rollbackFor=Exception.class)` | MP `@Version` 乐观锁防并发 |
| 职级变更 | 同事务内写 `pj_people_level` + `pj_people_change_log` | 乐观锁；幂等键 = (employeeId, levelCode, effectiveFrom) |
| 快照生成 | people 域**不持久化**，无事务 | — |

---



| 消费域 | 快照时点 | 说明 |
|--------|---------|------|
| payroll（算薪） | 算薪月份**最后一天** 23:59:59 | 如 2026-07-31，取当月最后一天的有效职级 |
| commission（结佣） | 结佣确认日期 | 取结佣当天的员工状态 |

> **规则**：快照时点取当月最后一天，确保整月算薪使用同一套职级/社保参数，即使月中升职也不影响当月。

---

## §6 Application Service 设计

### 6.1 EmployeeService（员工 CRUD）

```java
package com.panjia.people.application;

import com.panjia.people.application.dto.EmployeeCreateDTO;
import com.panjia.people.application.dto.EmployeeUpdateDTO;
import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeStatusEnum;
import com.panjia.people.infrastructure.repository.EmployeeMapper;
import com.panjia.people.infrastructure.repository.ChangeLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 员工应用服务 —— 员工档案的 CRUD 用例编排。
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired
    private ChangeLogMapper changeLogMapper;
    @Autowired
    private EmployeeDomainService domainService;

    /**
     * 创建员工。
     *
     * 步骤：1) 校验工号唯一  2) 创建 Employee + 初始职级 + 社保档案  3) 写变更日志
     */
    public Long createEmployee(EmployeeCreateDTO dto) {
        // 校验工号唯一
        if (employeeMapper.existsByEmployeeCode(dto.getEmployeeCode())) {
            throw new ServiceException("工号已存在: " + dto.getEmployeeCode(), BizCode.EMPLOYEE_CODE_DUPLICATE);
        }
        Employee employee = EmployeeConverter.toDomain(dto);
        employeeMapper.insert(employee);
        // 初始职级
        domainService.initLevel(employee, dto.getLevelCode());
        // 初始社保档案
        domainService.initSocialInsurance(employee, dto.getSocialInsuranceRatio());
        // 变更日志
        changeLogMapper.insert(ChangeLog.create(employee.getId(), "CREATE", null, null, dto.toString(), "初始化"));
        return employee.getId();
    }

    /**
     * 更新员工（不含职级变更 —— 职级走单独的 LevelService）。
     */
    public void updateEmployee(Long id, EmployeeUpdateDTO dto) {
        Employee existing = employeeMapper.selectFullById(id);
        if (existing == null) {
            throw new ServiceException("员工不存在: " + id, BizCode.EMPLOYEE_NOT_FOUND);
        }
        // 记录变更前后值
        String oldValue = serializeForLog(existing);
        EmployeeConverter.applyUpdate(existing, dto);
        employeeMapper.updateById(existing);
        changeLogMapper.insert(ChangeLog.create(id, "UPDATE", fieldName, oldValue, serializeForLog(existing), dto.getReason()));
    }

    /**
     * 员工离职。
     */
    public void resign(Long id, Date resignDate, String operator) {
        Employee employee = employeeMapper.selectFullById(id);
        employee.resign(resignDate);  // 领域行为：设置状态 + 关闭职级
        employeeMapper.updateById(employee);
        changeLogMapper.insert(ChangeLog.create(id, "RESIGN", "status", "ACTIVE", "RESIGNED", "离职"));
    }

    /**
     * 按工号查询（导入匹配用）。
     */
    @Transactional(readOnly = true)
    public Employee findByEmployeeCode(String employeeCode) {
        return employeeMapper.selectByEmployeeCode(employeeCode);
    }
}
```

### 6.2 EmployeeLevelService（职级变更）

```java
package com.panjia.people.application;

import com.panjia.people.domain.Employee;
import com.panjia.people.domain.EmployeeLevel;
import com.panjia.people.infrastructure.repository.EmployeeMapper;
import com.panjia.people.infrastructure.repository.EmployeeLevelMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 职级变更应用服务。
 *
 * 核心规则：职级变更 = 新增记录（追加式），禁止 UPDATE 旧记录。
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class EmployeeLevelService {

    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired
    private EmployeeLevelMapper levelMapper;
    @Autowired
    private ChangeLogMapper changeLogMapper;

    /**
     * 变更职级（晋升/降级）。
     *
     * @param employeeId    员工 ID
     * @param newLevelCode  新职级
     * @param effectiveDate 生效日期
     * @param reason        变更原因
     */
    public void changeLevel(Long employeeId, String newLevelCode, Date effectiveDate, String reason) {
        Employee employee = employeeMapper.selectFullById(employeeId);
        if (employee == null) {
            throw new ServiceException("员工不存在: " + employeeId, BizCode.EMPLOYEE_NOT_FOUND);
        }

        String oldLevel = employee.getCurrentLevel().getLevelCode();
        // 领域行为：校验 + 追加新记录
        employee.changeLevel(newLevelCode, effectiveDate, reason);

        // 持久化：更新旧记录的 effectiveTo + 插入新记录
        EmployeeLevel current = employee.getCurrentLevel();
        levelMapper.updateById(current);  // 设置 effectiveTo
        // 注意：changeLevel 内部已 add 新记录到 levelHistory
        EmployeeLevel newLevel = employee.getLevelHistory().get(employee.getLevelHistory().size() - 1);
        levelMapper.insert(newLevel);

        // 变更日志
        changeLogMapper.insert(ChangeLog.create(employeeId, "UPDATE_LEVEL",
                "level", oldLevel, newLevelCode, reason));

        log.info("员工 {} 职级变更: {} → {}，生效日 {}", employee.getEmployeeCode(), oldLevel, newLevelCode, effectiveDate);
    }

    /**
     * 查询职级历史。
     */
    @Transactional(readOnly = true)
    public List<EmployeeLevel> getLevelHistory(Long employeeId) {
        return levelMapper.selectByEmployeeIdOrderByEffectiveFromDesc(employeeId);
    }
}
```

### 6.3 MentorRelationService（师徒关系）

```java
package com.panjia.people.application;

import com.panjia.people.domain.MentorRelation;
import com.panjia.people.infrastructure.repository.MentorRelationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 师徒关系服务。
 *
 * 业务规则：
 *   1. 徒弟行业经验 >= 2 年 → 有招聘奖励资格
 *   2. 师傅最多 +10%（5 个合格徒弟）
 *   3. 徒弟离职 → 关系失效，已发奖励不追回
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class MentorRelationService {

    @Autowired
    private MentorRelationMapper mentorMapper;

    /**
     * 建立师徒关系。
     *
     * @param mentorId        师傅 ID
     * @param apprenticeId    徒弟 ID
     * @param industryYears   徒弟行业经验年数
     * @param recommendDate   推荐日期
     */
    public void createRelation(Long mentorId, Long apprenticeId, BigDecimal industryYears, Date recommendDate) {
        // 校验：不能自己推荐自己
        if (mentorId.equals(apprenticeId)) {
            throw new ServiceException("不能自推荐", BizCode.MENTOR_SELF_REFERENCE);
        }
        // 校验：徒弟只能有一个有效师傅
        if (mentorMapper.existsActiveByApprentice(apprenticeId)) {
            throw new ServiceException("该员工已有有效师傅", BizCode.MENTOR_ALREADY_EXISTS);
        }
        // 校验：师傅是否已达上限（5 人）
        long activeCount = mentorMapper.countActiveByMentor(mentorId);
        if (activeCount >= 5) {
            throw new ServiceException("师傅推荐人数已达上限(5)", BizCode.MENTOR_LIMIT_REACHED);
        }

        MentorRelation relation = new MentorRelation();
        relation.setMentorId(mentorId);
        relation.setApprenticeId(apprenticeId);
        relation.setApprenticeIndustryYears(industryYears);
        relation.setRecommendDate(recommendDate);
        relation.setActive(true);
        mentorMapper.insert(relation);
    }

    /**
     * 失效师徒关系（徒弟离职时调用）。
     */
    public void deactivateByApprenticeResign(Long apprenticeId, Date resignDate) {
        List<MentorRelation> active = mentorMapper.selectActiveByApprentice(apprenticeId);
        for (MentorRelation rel : active) {
            rel.deactivate(resignDate);
            mentorMapper.updateById(rel);
        }
    }

    /**
     * 查询师傅的合格徒弟数量（用于招聘奖励 +N% 计算）。
     *
     * @param mentorId 师傅 ID
     * @return 合格徒弟数（行业经验 >= 2 年且在有效期内）
     */
    @Transactional(readOnly = true)
    public int countQualifiedApprentices(Long mentorId) {
        return mentorMapper.countQualifiedByMentor(mentorId); // industry_years >= 2 AND is_active
    }
}
```

### 6.4 EmployeeImportService（批量导入）

```java
package com.panjia.people.application;

import com.panjia.people.domain.Employee;
import com.panjia.people.infrastructure.repository.EmployeeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 员工批量导入服务。
 *
 * 数据源：Excel（标准模板）或手工录入。
 * 匹配规则：优先按 employee_code 匹配（存在则更新，不存在则创建）。
 *
 * 🚨 导入只创建/更新员工档案，不修改历史职级记录。
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class EmployeeImportService {

    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired
    private EmployeeService employeeService;

    /**
     * 批量导入员工。
     *
     * @param dtos 员工 DTO 列表（来自 Excel 解析）
     * @return 导入结果（成功数/失败列表）
     */
    public ImportResult importEmployees(List<EmployeeCreateDTO> dtos) {
        ImportResult result = new ImportResult();
        for (EmployeeCreateDTO dto : dtos) {
            try {
                Employee existing = employeeMapper.selectByEmployeeCode(dto.getEmployeeCode());
                if (existing != null) {
                    // 存在 → 更新（仅基本档案，职级走单独流程）
                    EmployeeUpdateDTO update = convertToUpdate(dto);
                    employeeService.updateEmployee(existing.getId(), update);
                } else {
                    // 不存在 → 创建
                    employeeService.createEmployee(dto);
                }
                result.incrementSuccess();
            } catch (Exception e) {
                result.addFailure(dto.getEmployeeCode(), e.getMessage());
            }
        }
        return result;
    }
}
```

---

## §7 Controller & API 设计

### 7.1 EmployeeController

```java
package com.panjia.people.interface_;

import com.panjia.common.core.domain.R;
import com.panjia.people.application.EmployeeService;
import com.panjia.people.application.EmployeeSnapshotService;
import com.panjia.people.application.dto.EmployeeDTO;
import com.panjia.people.application.dto.EmployeeCreateDTO;
import com.panjia.people.application.dto.EmployeeUpdateDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

/**
 * 员工档案 Controller。
 *
 * 权限：总监/算薪人员 可 CRUD；店长仅查看本店；经纪人仅查看本人。
 */
@Slf4j
@RestController
@RequestMapping("/people/employee")
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;
    @Autowired
    private EmployeeSnapshotService snapshotService;

    /**
     * 创建员工。
     * 权限：people:employee:add
     */
    @PreAuthorize("@ss.hasPermi('people:employee:add')")
    @PostMapping
    public R<Long> create(@RequestBody EmployeeCreateDTO dto) {
        return R.ok(employeeService.createEmployee(dto));
    }

    /**
     * 更新员工。
     * 权限：people:employee:edit
     */
    @PreAuthorize("@ss.hasPermi('people:employee:edit')")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody EmployeeUpdateDTO dto) {
        employeeService.updateEmployee(id, dto);
        return R.ok();
    }

    /**
     * 员工离职。
     * 权限：people:employee:resign
     */
    @PreAuthorize("@ss.hasPermi('people:employee:resign')")
    @PostMapping("/{id}/resign")
    public R<Void> resign(@PathVariable Long id, @RequestParam Date resignDate) {
        employeeService.resign(id, resignDate, SecurityUtils.getLoginUser().getUsername());
        return R.ok();
    }

    /**
     * 分页查询。
     * 权限：people:employee:list
     * 数据权限：@DataPermission 按门店拦截
     */
    @PreAuthorize("@ss.hasPermi('people:employee:list')")
    @GetMapping("/page")
    public R<IPage<EmployeeDTO>> page(@RequestParam int pageNum, @RequestParam int pageSize,
                                       @RequestParam(required = false) Long deptId,
                                       @RequestParam(required = false) String role) {
        return R.ok(employeeService.page(pageNum, pageSize, deptId, role));
    }

    /**
     * 按工号查询（导入匹配用）。
     */
    @GetMapping("/by-code/{employeeCode}")
    public R<EmployeeDTO> findByCode(@PathVariable String employeeCode) {
        return R.ok(employeeService.findByEmployeeCode(employeeCode));
    }

    /**
     * ★ 快照供给接口（内部域间调用，不暴露给前端）。
     * 仅供 payroll/commission 等域通过 Feign/直接调用。
     */
    @GetMapping("/snapshot/{employeeId}")
    public R<EmployeeSnapshot> takeSnapshot(@PathVariable Long employeeId,
                                             @RequestParam Date pointInTime) {
        return R.ok(snapshotService.takeSnapshot(employeeId, pointInTime));
    }
}
```

### 7.2 EmployeeLevelController

```java
package com.panjia.people.interface_;

import com.panjia.common.core.domain.R;
import com.panjia.people.application.EmployeeLevelService;
import com.panjia.people.domain.EmployeeLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

/**
 * 职级管理 Controller。
 *
 * 菜单：「基础档案 → 职级与社保模板」
 * 权限：总监可 CRUD；其他人只读
 */
@Slf4j
@RestController
@RequestMapping("/people/level")
public class EmployeeLevelController {

    @Autowired
    private EmployeeLevelService levelService;

    /**
     * 变更职级（晋升/降级）。
     * 权限：people:level:change
     */
    @PreAuthorize("@ss.hasPermi('people:level:change')")
    @PostMapping("/change")
    public R<Void> changeLevel(@RequestBody EmployeeLevelChangeDTO dto) {
        levelService.changeLevel(dto.getEmployeeId(), dto.getNewLevelCode(),
                                  dto.getEffectiveDate(), dto.getReason());
        return R.ok();
    }

    /**
     * 查询职级历史。
     */
    @PreAuthorize("@ss.hasPermi('people:level:list')")
    @GetMapping("/history/{employeeId}")
    public R<List<EmployeeLevel>> getHistory(@PathVariable Long employeeId) {
        return R.ok(levelService.getLevelHistory(employeeId));
    }
}
```

### 7.3 MentorRelationController

```java
package com.panjia.people.interface_;

import com.panjia.common.core.domain.R;
import com.panjia.people.application.MentorRelationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 师徒关系 Controller。
 *
 * 菜单：「基础档案 → 师徒关系」
 */
@Slf4j
@RestController
@RequestMapping("/people/mentor")
public class MentorRelationController {

    @Autowired
    private MentorRelationService mentorService;

    /**
     * 建立师徒关系。
     * 权限：people:mentor:add
     */
    @PreAuthorize("@ss.hasPermi('people:mentor:add')")
    @PostMapping
    public R<Void> create(@RequestParam Long mentorId, @RequestParam Long apprenticeId,
                           @RequestParam BigDecimal industryYears, @RequestParam Date recommendDate) {
        mentorService.createRelation(mentorId, apprenticeId, industryYears, recommendDate);
        return R.ok();
    }

    /**
     * 查询师傅的合格徒弟数（招聘奖励计算用）。
     */
    @GetMapping("/qualified-count/{mentorId}")
    public R<Integer> countQualified(@PathVariable Long mentorId) {
        return R.ok(mentorService.countQualifiedApprentices(mentorId));
    }
}
```

### 7.4 API 汇总表

| Method | Path | 权限标识 | 说明 |
|--------|------|----------|------|
| POST | `/people/employee` | `people:employee:add` | 创建员工 |
| PUT | `/people/employee/{id}` | `people:employee:edit` | 更新员工 |
| POST | `/people/employee/{id}/resign` | `people:employee:resign` | 员工离职 |
| GET | `/people/employee/page` | `people:employee:list` | 分页查询（数据权限拦截） |
| GET | `/people/employee/by-code/{code}` | `people:employee:list` | 按工号查询 |
| GET | `/people/employee/snapshot/{id}` | 内部调用 | ★ 快照供给 |
| POST | `/people/level/change` | `people:level:change` | 职级变更 |
| GET | `/people/level/history/{id}` | `people:level:list` | 职级历史 |
| POST | `/people/mentor` | `people:mentor:add` | 建立师徒关系 |
| GET | `/people/mentor/qualified-count/{id}` | `people:mentor:list` | 合格徒弟数 |

---

## §8 与下游域的契约

### 8.1 下游域如何消费 people 域

| 下游域 | 消费方式 | 用到的方法 |
|--------|---------|-----------|
| `panjia-import` | 按 `employee_code` 匹配员工 | `EmployeeService#findByEmployeeCode` |
| `panjia-performance` | 按 EmployeeId 列表查询 | `EmployeeService#findByIds` |
| `panjia-commission` | ★ 快照冻结（结佣时点） | `EmployeeSnapshotService#takeSnapshot` |
| `panjia-payroll` | ★ 快照冻结（算薪月末时点） | `EmployeeSnapshotService#takeSnapshotsByDept` |
| `panjia-ledger` | 不直接依赖 people（经 payroll 事件） | — |

### 8.2 契约接口（定义在 panjia-contracts）

> people 域**实现**以下接口，下游域**依赖**接口（不依赖 people 实现类）。

```java
// panjia-contracts/port/EmployeeQueryPort.java
package com.panjia.contracts.port;

import com.panjia.contracts.snapshot.EmployeeSnapshot;

/**
 * 员工查询 Port —— 下游域通过此接口获取员工快照。
 *
 * 实现方：panjia-people（EmployeeSnapshotService）
 * 消费方：panjia-commission / panjia-payroll
 */
public interface EmployeeQueryPort {

    /**
     * 为单个员工在指定时点创建快照。
     */
    EmployeeSnapshot takeSnapshot(Long employeeId, Date pointInTime);

    /**
     * 批量创建快照。
     */
    Map<Long, EmployeeSnapshot> takeSnapshots(List<Long> employeeIds, Date pointInTime);

    /**
     * 查询指定部门在某时点所有在职员工的快照。
     */
    List<EmployeeSnapshot> takeSnapshotsByDept(Long deptId, Date pointInTime);
}
```

> **注意**：此 Port 接口定义在 `panjia-contracts`（叶子模块），people 域实现它，下游域依赖它。这是**唯一允许**的跨域交互方式。

### 8.3 EmployeeSnapshot 完整字段（contracts 定义）

```java
// panjia-contracts/snapshot/EmployeeSnapshot.java
package com.panjia.contracts.snapshot;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

import lombok.Data;

/**
 * 员工快照 —— 跨域只读数据传输对象。
 *
 * 🚨 规则：
 *   1. 不可变（只生成一次，不修改）
 *   2. 不含敏感信息（无身份证号）
 *   3. 不含其他域的 @Entity
 *   4. 由消费域持久化为自己的快照表
 */
@Data
public class EmployeeSnapshot implements Snapshot, Serializable {

    private static final long serialVersionUID = 1L;

    // === 基本信息 ===
    private Long employeeId;
    private String employeeCode;
    private String name;
    private Long deptId;
    private String role;               // AGENT / STORE_MANAGER / DIRECTOR
    private boolean partTime;

    // === 职级快照（算薪时点冻结） ===
    private String levelCode;          // A0~A5 / S1 / S2 / DIRECTOR
    private BigDecimal baseSalary;     // 底薪
    private BigDecimal commissionRate;  // 基础提成比例
    private BigDecimal socialInsuranceRatio; // 社保个人比例

    // === 社保快照 ===
    private BigDecimal socialBaseAmount;    // 社保基数
    private BigDecimal personalSocialRatio; // 个人比例
    private BigDecimal companySocialRatio;  // 公司比例
    private BigDecimal housingFundAmount;   // 公积金自缴

    // === 元数据 ===
    private Date snapshotAt;  // 快照生成时间
}
```

---

## §9 编码任务拆分（Task-1-1 ~ Task-1-3 子任务）

### 9.1 任务清单

| Task | 名称 | 产出文件 | 优先级 |
|------|------|---------|:------:|
| Task-1-1-1 | Flyway DDL（5 张表） | `V6~V10__pj_people_*.sql` | P0 |
| Task-1-1-2 | 种子数据（职级模板 + 管理层初始化） | `V11__pj_people_seed.sql` | P0 |
| Task-1-2-1 | 枚举定义 | `EmployeeStatusEnum` / `EmployeeRoleEnum` / `PartTimeStatusEnum` | P0 |
| Task-1-2-2 | 领域对象（Employee 聚合根 + 值对象） | `Employee` / `EmployeeLevel` / `SocialInsuranceProfile` / `MentorRelation` | P0 |
| Task-1-2-3 | SnapshotFactory + EmployeeSnapshot（contracts） | `SnapshotFactory` / `EmployeeSnapshot` | P0 |
| Task-1-2-4 | EmployeeQueryPort（contracts） | `EmployeeQueryPort` | P0 |
| Task-1-2-5 | 应用服务 | `EmployeeService` / `EmployeeLevelService` / `MentorRelationService` / `EmployeeSnapshotService` / `EmployeeImportService` | P0 |
| Task-1-2-6 | Repository（Mapper + PO） | `EmployeeMapper` / `EmployeeLevelMapper` / `SocialInsuranceMapper` / `MentorRelationMapper` / `ChangeLogMapper` + PO 类 | P0 |
| Task-1-2-7 | SysUserAdapter | `SysUserAdapter` | P1 |
| Task-1-3-1 | Controller + DTO | `EmployeeController` / `EmployeeLevelController` / `MentorRelationController` + DTO 类 | P0 |
| Task-1-3-2 | 主数据边界 CI 校验补充 | ArchUnit 规则追加 | P1 |
| Task-1-3-3 | 集成测试 | `EmployeeServiceTest` / `SnapshotTest` / `LevelChangeTest` | P0 |

### 9.2 任务依赖关系

```
Task-1-1-1 (DDL) ──▶ Task-1-1-2 (Seed)
     │
Task-1-2-1 (枚举) ──▶ Task-1-2-2 (领域对象) ──▶ Task-1-2-3 (Snapshot)
     │                                              │
     └──▶ Task-1-2-4 (Port 接口)                    │
                                                     ▼
Task-1-2-6 (Repository) ◀── Task-1-2-5 (应用服务)
                                                     │
                                                     ▼
Task-1-3-1 (Controller/DTO) ──▶ Task-1-3-2 (CI) ──▶ Task-1-3-3 (测试)
```

> **Phase 0 前置**：所有 Task 依赖 Phase 0 完成（contracts 模块、Port 接口、Outbox、CI 框架）。

---

## §10 测试计划


### 9.3 CI 校验完整清单（评审修订 · 补丁五）

> people 域相关 CI 校验汇总（含架构文档 §10.3 + 评审新增项），由 `check-domain-deps` / ArchUnit 执行。

| # | 校验项 | 工具 | 规则 |
|---|--------|------|------|
| 1 | 禁止 record / TypedId | ArchUnit | `EmployeeId` 类不得为 record；contracts 下 Id 类不得为 record |
| 2 | 禁止自增主键 | DDL 扫描 | 所有 `pj_people_*` 表主键为 `BIGINT`，无 `BIGSERIAL` / `AUTO_INCREMENT` |
| 3 | 禁止数据库触发器 | DDL 扫描 | 无 `CREATE TRIGGER` |
| 4 | 禁止 import `people.domain.Employee` | ArchUnit | 非 people 域类不得 import `com.panjia.people.domain.Employee` |
| 5 | 禁止下游 `new Employee()` | ArchUnit | `com.panjia.payroll/commission/performance/import` 不得引用 Employee 构造 |
| 6 | people 域只依赖 contracts + 底座 | `check-domain-deps` | pom 无其它 `panjia-*` 业务域依赖 |
| 7 | sys_user 只读 | ArchUnit | people 域不得依赖 `SysUserMapper` 的写方法 |
| 8 | 枚举禁止魔法字符串 | ArchUnit | `change_type` / `status` 等字段只赋枚举 code |
| 9 | 快照表归属消费域 | ArchUnit | people 域无 `EmployeeSnapshot` 持久化 Entity / Mapper |
| 10 | 表前缀白名单 | `check-table-prefix` | `pj_people_*` ∈ 已知顶层域集合 |

---



| # | 验证点 | 优先级 | 测试类型 |
|---|--------|:------:|---------|
| 1 | 创建员工 → 自动创建初始职级 + 社保档案 + 变更日志 | P0 | 单元 |
| 2 | 职级变更 = 新增记录，旧记录 effectiveTo 被正确设置 | P0 | 单元 |
| 3 | `getLevelAt(pointInTime)` → 返回正确历史时点职级 | P0 | 单元 |
| 4 | 快照冻结：员工月中升职，月末快照取升职后职级 | P0 | 单元 |
| 5 | 快照冻结：员工已离职，历史月份快照仍可生成 | P0 | 单元 |
| 6 | `EmployeeSnapshotService#takeSnapshotsByDept` → 只返回在职员工 | P0 | 单元 |
| 7 | 社保双口径：个人 + 公司 = 基数（CHECK 约束） | P0 | 单元 |
| 8 | 兼职员工：shouldDeductSocial()=false | P0 | 单元 |
| 9 | 师徒关系：徒弟 <2 年 → isQualified=false | P0 | 单元 |
| 10 | 师徒关系：师傅达 5 人上限 → 拒绝新增 | P0 | 单元 |
| 11 | 离职处理：关闭当前职级 + 失效师徒关系 | P0 | 单元 |
| 12 | 变更日志：每次写操作都有对应日志记录 | P0 | 单元 |
| 13 | 工号唯一约束：重复工号 → 抛异常 | P1 | 单元 |
| 14 | 乐观锁：并发更新同一员工 → 后提交者失败 | P1 | 单元 |
| 15 | ArchUnit：people 域不依赖其他业务域 | P0 | 架构测试 |
| 16 | ArchUnit：Employee 类不出现在非 people 域 import 中 | P0 | 架构测试 |
| 17 | ArchUnit：pj_people 表无 sys_ 前缀字段（除 user_id） | P0 | 架构测试 |
| 18 | 集成测试：Excel 导入 → 匹配工号 → 创建/更新员工 | P1 | 集成 |
| 19 | 集成测试：payroll 调用 snapshotService → 正确冻结 | P0 | 集成 |
| 20 | 种子数据校验：6 家门店管理层初始化正确 | P0 | 集成 |

---

## §11 风险清单

| ID | 风险 | 等级 | 应对 | 验证 |
|----|------|:----:|------|------|
| P1-R-01 | 职级变更并发：同一员工同时变更两次 | P1 | `@Version` 乐观锁 + 应用层校验 | 并发单测 |
| P1-R-02 | 快照时点取值边界：月末 23:59:59 vs 月初 | P1 | 统一取月末最后一天，文档化约定 | 单测 |
| P1-R-03 | 师徒关系循环引用（A 推荐 B，B 推荐 A） | P2 | 应用层校验 mentor_id != apprentice_id | 单测 |
| P1-R-04 | sys_user 与 pj_people_employee 数据不一致 | P1 | 创建员工时必须先有 sys_user；CI 校验 | 集成测试 |
| P1-R-05 | 历史职级查询性能（全量加载 levelHistory） | P2 | 索引 `idx_level_employee_time` + 按需加载 | 性能测试 |
| P1-R-06 | 社保基数变更历史缺失（当前只存当前值） | P1 | `pj_people_social_insurance` 带 effective_from/to，变更新增记录 | 单测 |
| P1-R-07 | 工号匹配大小写不一致（贝壳 vs 系统） | P1 | `employee_code` 统一转小写存储 + 查询 | 集成测试 |

---

## §12 V1 限制 & Backlog

| ID | 内容 |
|----|------|
| IMP-003 | 职级唯一有效约束 → PG 排除约束（V1 应用层 + 单测） |
| IMP-006 | 员工档案导入模板版本管理（复用 pj_import_template） |
| IMP-007 | 批量职级变更（Excel 导入 + 审批） |
| IMP-008 | 员工照片/附件存储（V1 不做，后续接入 MinIO） |
| IMP-009 | 跨门店转店（transfer）的领域事件发布 |
| BK-006 | 变更日志归档（>1 年迁归档表） |

---

## §13 下游同步通知

1. **Task-0-2（contracts）**：补充 `EmployeeSnapshot` 完整字段 + `EmployeeQueryPort` 接口
2. **Task-2-x（import）**：按 `employee_code` 匹配 → `EmployeeService#findByEmployeeCode`；匹配失败进"待对齐"列表
3. **Task-4-x（commission）**：结佣确认时调用 `EmployeeSnapshotService#takeSnapshot(employeeId, commissionDate)`
4. **Task-5-x（payroll）**：算薪时调用 `EmployeeSnapshotService#takeSnapshotsByDept(deptId, monthEnd)` → 持久化为 `pj_payroll_employee_snapshot`
5. **CI 负责人**：更新 `check-domain-deps`（people = contracts + ruoyi-common）；新增 ArchUnit 规则（Employee 不出 people 域）
6. **菜单设计**：「基础档案」菜单对应的 API 权限标识已全部定义（§7.4）

---

## §14 总结：Phase 1 交付清单

| 类别 | 文件 |
|------|------|
| Flyway DDL | `V6__pj_people_employee.sql` ~ `V11__pj_people_seed.sql`（6 个脚本） |
| 领域对象 | `Employee` / `EmployeeLevel` / `SocialInsuranceProfile` / `MentorRelation` + 3 个枚举 |
| 应用服务 | `EmployeeService` / `EmployeeLevelService` / `SocialInsuranceService` / `MentorRelationService` / `EmployeeSnapshotService` / `EmployeeImportService` |
| Repository | 5 个 Mapper + 5 个 PO |
| Controller | `EmployeeController` / `EmployeeLevelController` / `MentorRelationController` |
| DTO | `EmployeeDTO` / `EmployeeCreateDTO` / `EmployeeUpdateDTO` / `EmployeeLevelChangeDTO` / `EmployeeSnapshotDTO` / `ImportResult` |
| contracts（新增/更新） | `EmployeeSnapshot`（完整字段） / `EmployeeQueryPort` |
| 测试 | 20 条测试（单元 + 架构 + 集成） |
| CI 规则 | 3 条 ArchUnit 新增 |

---

**— Phase 1 详细设计 V1.0 结束 —**

> **交付说明：** 本文档是「产品架构 → 顶层域 → 领域架构 → 模块边界 → 核心业务状态机 → 领域对象 → 数据库详细设计 → API/Service → 编码任务」链路中 **Phase 1（员工域）** 的完整输入文档。下游 Phase 2~6 依赖本文档定义的 `EmployeeSnapshot` / `EmployeeQueryPort` / `EmployeeService` 接口。
>
> **下一阶段：** Phase 2（导入域 panjia-import），依赖本文档定义的员工匹配接口。


---

## 附录 A：评审修订记录（V1.0 → V1.1）

> 本附录记录本次评审的全部修订，便于追溯。架构决策（快照归属消费域、聚合根不外泄、sys_user 单向引用）**保持不变**。

### A.1 修订来源

- 《盘jia‑people 设计文档评审》（2026-09-09）
- 《盘家智管 AI 总规约 V3.0》P0 红线
- 《盘家智管 · 薪酬与经营结算 · 产品化架构设计 V1.6》ADR-011 / ADR-012

### A.2 逐条修订对照

| 评审意见 | 修订动作 | 落地位置 | 状态 |
|----------|----------|----------|:----:|
| ① 强类型 ID 约束（禁止 record / TypedId） | 新增 §0.3 P0 红线 + §5.5 `EmployeeId` POJO | §0.3、§5.5 | ✅ |
| ② EmployeeSnapshot 完整字段 + 包含/不包含矩阵 | 新增 §5.4 字段约束矩阵 | §5.4 | ✅ |
| ③ 职级生效时序 + 快照取数逻辑契约 | 新增 §5.2 取数逻辑 + §3.2 时序规则 | §3.2、§5.2 | ✅ |
| ④ sys_user 异常场景（删除/禁用/无账号） | 新增 §4.4 + §5.3 | §4.4、§5.3 | ✅ |
| ⑤ 枚举缺失（人事变更类型） | 新增 §3.5.1 `EmployeeChangeTypeEnum` | §3.5.1 | ✅ |
| ⑥ 快照不持久化在 people 域 | 新增 §5.6 下游消费 & 持久化归属 | §5.6 | ✅ |
| ⑦ 事务 / 幂等 / 锁策略 | 新增 §5.7 事务锁表 | §5.7 | ✅ |
| 架构风险 A：快照下沉 people 建表 | §5.6 红线重申（ADR-012） | §5.6 | ✅ |
| 架构风险 B：下游跨域 import 聚合根 | §9.3 CI 校验 #4/#5 拦截 | §9.3 | ✅ |

### A.3 未变更项（确认继续沿用）

- 快照物理表归消费域（`pj_payroll_employee_snapshot` / `pj_commission_employee_snapshot`），people 域不建快照表 ✅
- `Employee` 聚合根只存在于 people 域，外部通过 `EmployeeSnapshotService` 获取快照 ✅
- `sys_user` 单向引用，`pj_people_employee.user_id` 外键，底座零业务侵入 ✅
- 职级变更追加式（新增记录 + `effective_from/to`），禁止 UPDATE 旧记录 ✅

---

**— 文档结束 · V1.1 —**
