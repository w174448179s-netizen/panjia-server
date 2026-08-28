# 盘家智管 License 客户端校验详细设计

> 版本：V1.1
> 状态：设计定稿（第一阶段）
> 开发工作量：约 11.5 人天
> 评审结论：通过（6 处修订全部落地，无架构级缺陷）
>
> V1.1 变更（2026-08-28）：
> - 新增 §2.9：RSA 非对称签名机制（防止客户伪造 token）
> - 新增 §2.10：开发/生产统一代码路径（dev-mode 由 Spring profile 推导）
> - 新增 §4.5：多点散布校验 + 硬依赖（LicenseCheckPoint + LicenseStartupHook）
> - 新增 §4.6：完整性校验实现细节（构建期生成 + 运行时校验 + 自校验）
> - 新增铁律 19–22

---

## 一、概述

### 1.1 目标

在私有化部署（客户购买云服务器或本地物理机，**统一由我方 Docker 部署**）场景下，通过纯软件防护，将"客户因觉得贵而请人破解"的成本推高到**明显高于授权费 + 维保费**，使破解在客户视角"不划算、搞不定、不敢用"。

### 1.2 威胁模型（核心前提）

**防护对象不是专业黑客，是"觉得贵了想省钱的店东请了个懂点技术的朋友 / 兼职 IT"。**

| 维度 | 专业黑客 | 本方案防护对象 |
|---|---|---|
| 动机 | 卖破解版赚钱，可投入数周 | 省几千块授权费，只想花一两天 |
| 技术能力 | 逆向 / 调试 / 改字节码 | 网上搜教程、用现成工具 |
| 时间投入 | 几周以上 | 一两天搞不定就放弃 |
| 攻击路径 | 深入代码逻辑 | 改时间、拷容器、换机器、搜配置文件 |
| **我们的目标** | 让他破解不了 | **让他试半小时处处碰壁，觉得"不如续费"** |

**结论：不追求"绝对攻不破"，只追求"成本明显高于授权费 + 试错挫败感强 + 破解后用着心里没底"。**

### 1.3 防护分层

| 层 | 名称 | 核心目标 | 第一阶段 |
|---|---|---|---|
| L1 | 在线鉴权 | 防断网永久使用、实时拦截黑名单 | ✅ |
| L2 | 机器指纹绑定 | 防授权 / 容器整体拷贝流转 | ✅ |
| L3 | 代码保护 | 防反编译、增加搜教程改字节码的难度 | ✅（ProGuard + ClassFinal） |
| **L4（核心威慑）** | **受限模式** | **绕过授权后算薪结果错误，客户不敢用** | ✅ |
| L5 | 完整性自检 | 防 class 静态替换后"绕过校验却照常算薪" | ✅（轻量，仅防静态替换） |
| ~~反调试 / 运行时防 patch~~ | ~~对抗专业逆向~~ | ~~对抗 JDWP / TracerPid / Frida~~ | ❌ 已移除（§1.5） |
| ~~水印溯源~~ | ~~破解版流转追责~~ | ~~对抗规模化传播~~ | ❌ 已移除（§1.5） |

> **本方案最高优先级安全特性：受限模式（L4）+ 完整性自检（L5）。**
> 受限模式直击"客户不敢用破解版"心理（算薪错得明显、可复现、手工核对即刻暴露）；完整性自检是受限模式的**前置触发保障**——只有它能让"锁死被绕过却照常算薪"这个本不该发生的场景真正闭环。

### 1.4 部署前提

- **全部由我方部署上线**：客户为中小中介（单店 / 小型连锁），无专职 IT 人员
- **唯一部署形态**：Docker（Docker Compose 单节点），客户服务器可以是阿里云 / 腾讯云 ECS，或本地物理机
- **运维归属**：指纹异常、换机、离线锁死恢复等**全部由我方运维远程处理**，客户只需"打电话给我们"
- **算薪逻辑全程本地运行**，不上传我方服务器

### 1.5 已移除 / 降级的设计

| 项 | 移除理由 |
|---|---|
| 反调试（JDWP / TracerPid / Frida 检测） | 对抗"用 IDE 远程调试分析逻辑"的专业行为；目标对象（兼职 IT）不会用 JDWP 调试 Spring Boot，投入产出低 |
| 运行时完整性自检 / 防内存 patch | 需注入 Java Agent 才能绕过，兼职 IT 做不到；且与受限模式功能重叠 |
| 水印溯源 | 溯源前提是"抓到人在传播"；本场景客户破解多为自用，不传播，溯源无意义 |
| 多时钟源单调时钟（uptime + nanoTime 组合） | 简化为 bootTime + 系统时间双源即可（见 §3.5） |
| 时间回拨多位置冗余检测 | 简化为单调时钟单文件（仅离线模式生效） |
| 公钥分片存储 | ProGuard 混淆已足够 |
| APM 白名单 | 客户无 IT，不装 APM |
| N-1 阈值匹配 | 改为双因子全量匹配，逻辑更简单 |

---

## 二、L1 在线鉴权

### 2.1 授权服务器接口

| 接口 | 方法 | 请求 | 响应 | 说明 |
|---|---|---|---|---|
| `/api/auth/activate` | POST | `{authCode, fingerprint, productVersion}` | `{license, token, offlineExpireAt, clientMode}` | 首次激活，绑定指纹，校验版本范围 |
| `/api/auth/heartbeat` | POST | `{token}` | `{status, offlineExpireAt, clientMode}` | 24h 心跳续期，可下发 `clientMode` 指令 |
| `/api/auth/check` | POST | `{token, operation}` | `{allowed: true/false, reason, code}` | 关键操作实时校验，按操作粒度返回 |

**服务端不信任客户端上报的 fingerprint**：token 为 JWT，payload 内含 `fingerprintHash` 与 `versionRange`，服务端解密 token 取指纹比对 + 版本校验（详见 §2.6、§7.4）。

### 2.2 客户端启动流程

```
Spring Boot 启动
  → ① MonotonicClock.verify()           （§3.5 离线时间防护）
  → ② IntegrityChecker.checkStartup()   （§4.3 完整性自检，启动期）
  → ③ LicenseStartupValidator.run()
       → 尝试在线激活 / 心跳
            → 成功 → 缓存 token（基于机器指纹派生的 AES 密钥加密）
            → 失败 → 离线模式
                 → 检查 License 文件 offlineExpireAt
                 → 未过期 → 离线宽限期（允许查看，禁止核心操作）
                 → 已过期 → 离线锁死（只读，提示"请联系服务商"）
  → ④ 校验通过 → 正常启动
  → ⑤ RestrictedMode 检查（§5）
```

### 2.3 心跳机制

| 项目 | 值 |
|---|---|
| 间隔 | 24h |
| 触发 | 后台线程 |
| 成功响应 | 更新本地 `offlineExpireAt`（= 服务器当前时间 + 7 天），返回最新 `clientMode` |
| 失败 | 记录失败但不立即停服；`offlineExpireAt` 到期则锁核心功能 |
| 刷新规则 | **仅在心跳成功时刷新**；客户须至少每 7 天成功联网一次 |

**`clientMode` 指令**（心跳响应携带，客户端运行时读取）：
- `NORMAL`：正常模式
- `RESTRICT`：服务端下发受限指令，客户端进入受限模式（算薪偏移），写告警日志

### 2.4 网络状态判定（`networkReachable`）

**核心降级逻辑高度依赖"网络层不通"与"服务端故障"的区分**，由 `networkReachable` 标志位驱动。**`networkReachable` 的精确语义只有一个：判断"授权服务器网络路径能否建立 TCP 连接"，不是判断整个互联网是否断网。**

```java
// 判定 networkReachable：仅"TCP 连接无法建立"为 false，其余均为 true（含服务端应用层故障）
boolean networkReachable = true;

try {
    // 1. DNS 解析授权服务器域名
    InetAddress addr = InetAddress.getByName(LICENSE_SERVER_HOST);
    // 2. TCP 三次握手（超时 5s）—— 仅此步的失败 = 网络层不可达
    Socket sock = new Socket();
    sock.connect(new InetSocketAddress(addr, 443), 5000);
    sock.close();
} catch (UnknownHostException e) {
    // DNS 解析失败 → TCP 无法建立 → 真·断网
    networkReachable = false;
} catch (ConnectException e) {
    // TCP 连接被拒/超时（端口无响应）
    //   → 这是"服务器 IP 可达、端口不可达"，网络路径仍建立 → 仍算 reachable=true
    networkReachable = true;
} catch (SocketTimeoutException e) {
    // 同样出现在 connect() 超时 → 同上，服务端不可达 → reachable = true
    networkReachable = true;
}
// 注：以下异常不影响 networkReachable（属服务端应用层故障，非网络层）
//   → SocketException(其他) / IOException / 连接已建立但读取超时
//   → 均视为 networkReachable = true，走"在线但服务端不可达"降级分支

if (networkReachable) {
    // 走 /check 缓存降级（§2.5）；若 TCP 通但 HTTP 5xx，同样 reachable=true
} else {
    // 真·断网 → 立即走离线模式规则，完全禁用 /check 缓存（铁律）
}
```

**三条路径对照表：**

| 场景 | networkReachable | 走哪套规则 | `/check` 缓存 |
|---|---|---|---|
| DNS 失败（`UnknownHostException`） | `false` | 离线模式规则 1 | ❌ 禁用（铁律） |
| TCP 连接被拒 / 超时（`ConnectException`/`SocketTimeoutException`） | `true` | 在线模式 + 服务端不可达 | ✅ 30 分钟缓存可用 |
| TCP 通，HTTP 502/503/网关超时 | `true` | 在线模式 + 服务端不可达 | ✅ 30 分钟缓存可用 |
| TCP 通，HTTP 5xx | `true` | 在线模式 + 服务端不可达 | ✅ 30 分钟缓存可用 |
| 服务端返回 4xx/鉴权失败 | `true`（网络通） | 进入受限/锁死，不走缓存 | ❌ 不适用 |

> **反例锁死条件（开发必须遵守）**：任何把"服务端 5xx / TCP 端口不可达"判成 `networkReachable=false` 的实现，都等于"断网被误判为服务端故障 → 错误放行 `/check` 缓存 → 绕过离线禁止核心操作"。**出现即 Bug，过不了验收。**
>
> **测试重点**：验收对 `networkReachable` 的验证**只按上述定义执行**——构造"DNS 失败"（应 reachable=false）与"TCP 通但 HTTP 5xx"（应 reachable=true）两种用例，不验证"整个互联网是否断网"。

### 2.5 关键操作校验（含降级）

| 操作 | operation 枚举值 | 在线要求 |
|---|---|---|
| 导入结佣数据 | `IMPORT` | 调用 `/check`，在线时每次校验 |
| 生成算薪 | `CALCULATE` | 调用 `/check`，在线时每次校验 |
| 导出 Excel | `EXPORT` | 调用 `/check`，在线时每次校验 |

**降级优先级（关键，避免逻辑冲突）：**

1. **真·断网**（`networkReachable=false`）→ 离线模式规则 1，**无论缓存是否有效，均禁止核心操作**（铁律，见 §12 铁律 2、10）
2. 在线 + 服务端临时不可达（5xx/超时，但 TCP 通）+ 30 分钟内曾成功 `/check` → 允许操作（用缓存）
3. 在线 + 服务端不可达超过 30 分钟 → 锁定核心功能

**降级有效期**：`/check` 结果本地缓存 **30 分钟**，仅在线且服务端不可达时使用。

**验收用例 7 前置备注**：用例 7 的前提严格限定为 **`networkReachable=true`（网络通，仅服务端故障）**；真正网络层断网不执行该降级逻辑，直接走离线模式。**测试人员不得用物理断外网方式执行用例 7**，否则属测试设计错误，不产生 bug 误报。

### 2.6 服务端指纹 + 版本四步校验

服务端收到请求后依次执行：

1. 验证 token 签名有效性（JWT 签名）
2. 用请求中携带的 fingerprint 重新计算哈希
3. 与 token payload 中的 `fingerprintHash` 比对
4. **额外**：校验 `productVersion` 是否在 token payload 的 `versionRange` 许可范围内

四步均通过才放行；任一步失败 → 返回拒绝 + `code`（区分 `FP_MISMATCH` / `VERSION_OUT_OF_RANGE` / `SIGNATURE_INVALID` / `TOKEN_REVOKED`）。

### 2.7 多实例自动拉黑 + 旧实例降级行为

**同一 `authCode` 绑定多个 fingerprint 且均正常心跳** → 自动拉黑**全部相关 fingerprint** + 记录日志 + 运营后台通知我方运维 + **后台可手动解除拉黑**（兜底）。

> **换机与自动拉黑的边界（明确二义唯一解）：**
> 正常换机**必须**先执行 §7.2 的"旧 fingerprint 失效"流程（同步立即生效）；**自动拉黑仅处理"未经过换机流程"而出现的多 fingerprint 并发心跳**。
> 即：若旧 FP 已按 §7.2 标记失效，则旧实例下次心跳即被拒绝（走换机旧实例逻辑），**不会**再触发"自动拉黑"判定。
> 两条路径**不重叠**：§7.2 = 运营主动换机（旧 FP 先失效）；§2.7 = 未走换机、私自多开（并发心跳被捕获）。开发不要把正常换机误判成盗用。

> **自动拉黑的指纹，客户端行为必须复用换机旧实例逻辑，不可二义：**
> 客户端收到拉黑/拒绝响应后：
> 1. **清理本地 token**（删除 `.panjia_token`）
> 2. **进入受限模式**（`SERVER_REVOKED`），写告警日志（含 trigger=T2_SERVER_REVOKED）
> 3. **绝不**进入离线宽限期/锁死——拉黑是"授权被废"，不是"网络断了"
>
> 此行为与 §7.2 换机流程的旧实例逻辑**完全一致**，是二义的唯一解。开发照搬换机逻辑即可，不要另写一套。

> 兜底"快照克隆到新实例"场景：即使 `instanceId` 被一并拷贝，心跳并发也会被服务端捕获。

### 2.8 离线模式状态定义

| 状态 | 触发 | 可用功能 | 恢复方式 |
|---|---|---|---|
| **离线宽限期** | 真·断网且 `offlineExpireAt` 未过期 | 查看历史、基础设置 | 恢复联网心跳 |
| **离线锁死** | `offlineExpireAt` 已过期 / 单调时钟回拨 / 单调文件缺失 | 仅只读浏览 | 联系我方运维远程恢复 |
| **受限模式** | 校验/完整性异常 / 服务端 REVOKED 指令 | **功能可用但算薪错误** | 联网 + 校验通过 / 服务端解除 |

### 2.9 RSA 非对称签名机制（V1.1 新增）

> **安全核心**：客户拿不到私钥，无法伪造合法 token。这是纯本地部署场景下不可绕过的密码学保障。

**架构**：

```
授权服务器（我方）：
  RSA 私钥 → 签发 token → 私钥永远不离开服务器

客户端 JAR（客户拿到）：
  RSA 公钥 → 只能验签，不能签名
  公钥嵌入 JAR 内部（classpath 资源 license-public-key.pem）
  → 不在 application.yml 配置文件中
  → 客户看得见公钥，但拿公钥签不出合法 token
```

**密钥管理**：

| 密钥 | 存放位置 | 客户可见 | 用途 |
|---|---|---|---|
| RSA 私钥 | 授权服务器 `script/keys/panjia-license.jks`（不打包进 JAR，不入 Git） | ❌ 不可见 | 签发 token |
| RSA 公钥 | 客户端 JAR 内 `license-public-key.pem`（classpath 资源） | ✅ 可见但无用 | 验证 token 签名 |

**密钥生成**：

```bash
# 生成 RSA 密钥对（仅授权服务器执行）
script/keys/generate-keys.sh
  → 输出 panjia-license.jks（私钥库，不打包）
  → 输出 license-public-key.pem（公钥，打包进客户端 JAR）
```

**为何从 HMAC 改为 RSA**：

| | HMAC 对称签名（V1.0 隐含） | RSA 非对称签名（V1.1） |
|---|---|---|
| 密钥位置 | `application.yml` 配置 | 私钥在服务器 / 公钥在 JAR 内 |
| 客户能看到密钥 | **是** → 能自己签 token | 只能看到公钥 → 签不了 |
| 客户能伪造 token | **是** → 所有校验形同虚设 | ❌ 不可伪造 |
| 安全性 | 配置层面即可绕过 | 密码学层面保障，不可绕过 |

> **铁律 19**：License token 必须使用 RSA 非对称签名。私钥仅在授权服务器，公钥嵌入客户端 JAR。禁止使用对称签名（HMAC），禁止将签名密钥放在配置文件中。

### 2.10 开发/生产统一代码路径（V1.1 新增）

> **问题背景**：V1.0 设计中 dev 模式完全跳过 License 校验（`enabled=false`），导致 License 代码在开发时从未执行，到生产才第一次运行 → 潜在 bug 不可预见。且 `enabled` / `dev-mode` 作为配置项，客户在生产改成 `true`/`false` 即可绕过。

**方案：dev/prod 走同一套代码，差异仅在数据来源**

| 维度 | dev 模式 | 生产模式 |
|---|---|---|
| **LicenseGuard** | 始终 `true`（不跳过） | 始终 `true` |
| **LicenseVerifier** | 验签 dev token（RSA 验签正常执行） | 验签真实 token |
| **HeartbeatScheduler** | 跳过远程调用（避免开发时依赖授权服务器） | 定期远程心跳 |
| **LicenseService.check()** | 直接放行（无需授权服务器在线） | 远程 `/check` 校验 |
| **LicenseServiceImpl.init()** | 加载预置 dev token | 等待真实激活流程 |
| **IntegrityChecker** | 正常执行 | 正常执行 |

→ 所有环境走**同一套代码**，License 代码在开发时就被验证，不存在"到生产才暴露 bug"的风险。

**dev 模式判定方式（关键安全设计）**：

```
❌ 旧方案（V1.0 隐含）：
  LicenseProperties.devMode = true  ← 配置项，客户可改

✅ 新方案（V1.1）：
  LicenseGuard.isDevMode()
    → environment.acceptsProfiles(Profiles.of("dev", "local"))
    → 由 spring.profiles.active 决定，不是配置项
```

**为什么 dev-mode 不能是配置项**：

| 攻击方式 | 成本 | dev-mode 配置项 | Spring profile 推导 |
|---|---|---|---|
| 改 yml `dev-mode: true` | 极低 | ❌ 可绕过 | ✅ 不存在此配置项 |
| 改环境变量 | 极低 | ❌ 可绕过 | ✅ 无此变量 |
| 改 `spring.profiles.active=dev` | 高 | 不适用 | ⚠️ 可绕过但数据库、日志、Redis 全部变 dev 配置，应用基本不可用 |

**dev token 安全性**：

```
客户拿到 dev token 后想在生产用？
  1. 公钥验签 → 通过（token 确实是私钥签的）
  2. 指纹校验 → dev token 含 fingerprintHash="DEV-FINGERPRINT"
     → 生产环境采集真实硬件指纹 ≠ "DEV-FINGERPRINT" → 拒绝

客户想自己造 dev token？
  → 没有私钥 → 签不出来 → 公钥验签失败
```

> **铁律 20**：开发模式由 `spring.profiles.active` 推导（dev/local → dev 模式），不允许通过配置项 `dev-mode` 控制。`application.yml` 中不存在的配置项无法被修改。

> **铁律 21**：所有环境（含 dev）必须走完整 License 校验代码路径。禁止通过 `enabled=false` 跳过校验。dev 与 prod 的差异仅在数据来源（dev token vs 真实 token），不在代码路径。

---

## 三、L2 机器指纹

### 3.1 采集因子（Docker 专用，仅双因子）

| 因子 | 来源 | 稳定性 | 说明 |
|---|---|---|---|
| **hostMachineId** | 挂载宿主机 `/etc/machine-id`（只读） | ✅ 极稳定 | 主因子 |
| **instanceId** | 数据卷 `/data/.panjia_instance_id` | ✅ 稳定 | 辅因子（首次启动生成 UUID，重建容器不丢） |

**仅此两个因子。** 不采集 diskSerial / cpuId / MAC / memorySize（云上或升配即变，详见 §3.3）。

### 3.2 匹配策略

**全量匹配**：两个因子必须完全一致；任一不一致 → 需重新激活。由于双因子分别绑定"宿主机"与"持久化数据卷"，正常情况下永不变化，不一致仅出现在"换机器 / 删数据卷"等明确操作。

### 3.3 场景与授权影响

| 客户操作（均由我方执行） | hostMachineId | instanceId | 授权是否失效 |
|---|---|---|---|
| 重启容器 | 不变 | 不变 | ❌ 不影响 |
| `docker rm` + `docker run` 重建（数据卷保留） | 不变 | 不变 | ❌ **不影响** |
| 升配 / 降配（CPU / 内存） | 不变 | 不变 | ❌ 不影响 |
| 调整带宽 / 重启 ECS | 不变 | 不变 | ❌ 不影响 |
| **同一宿主机上销毁重建（保留数据卷）** | 不变 | 不变 | ❌ **不影响** |
| **迁移至新宿主机 / 新服务器** | ❌ 变 | 视数据卷 | ✅ 需重新激活 |
| **销毁重建（同镜像，新数据卷）** | ❌ 变 | ❌ 变 | ✅ 需重新激活 |

> 客户 99% 的运维 = 重启 / 重建容器，只要数据卷挂载正确即无感。

### 3.4 采集逻辑（伪代码）

```java
public Map<String, String> collectFactors() {
    Map<String, String> factors = new LinkedHashMap<>();
    String hostId = readFirstLine("/etc/machine-id");
    if (isBlank(hostId)) throw new LicenseException("machine-id 未挂载，请联系部署人员");
    factors.put("hostMachineId", hostId.trim());
    Path f = Paths.get("/data/.panjia_instance_id");
    String instanceId = Files.exists(f) ? readFirstLine(f) : UUID.randomUUID().toString();
    Files.writeString(f, instanceId); // 持久化到数据卷
    factors.put("instanceId", instanceId.trim());
    return factors;
}
```

### 3.5 离线时间防护（单调时钟，简化版 + 防删强化）

**漏洞**：离线模式下客户端靠本地时间判断 `offlineExpireAt` 是否过期，客户可把系统时间往前拨以无限延长宽限期。在线模式由服务器校验时间，不受影响；**此机制仅作用于离线模式。**

**机制**：单调时钟单文件 `.panjia_monotonic`，记录"上次看到的可信时间"，任何回拨即拒绝。

**时钟源（双源取 max）：**

| 时间源 | 说明 | 抗篡改 |
|---|---|---|
| 系统时间 | `System.currentTimeMillis()` | ❌ 可被改 |
| 系统启动时刻 | `/proc/stat` 的 `btime`（跨重启单调锚点） | ✅ 改系统时间不影响 |

```
trustedNow = max(系统时间, bootTime + uptime)
if trustedNow < lastTrusted - 5分钟:
    → 时间回拨 → 离线锁死
else:
    → 更新文件
```

**容忍度**：5 分钟，允许 NTP 微调，避免误杀。**5 分钟须定义为单一常量 `MONOTONIC_TOLERANCE_MS`，全代码一处配置（见 §11 工具类 `MonotonicTolerance`），禁止多处硬编码。**

**文件被删 = 严重异常，不可自动重建：**

> `.panjia_monotonic` **缺失或被删 → 立即进入 OFFLINE_LOCK（离线锁死），禁止自填基准、禁止自动重建。**
> 仅当**心跳成功**（服务端在线确认时间可信）后才允许重建基准。
> 原因：若允许自动重建，客户删文件 + 拨时间 → 历史回拨记录丢失 → 无限延长宽限期，**时间防护被绕过**。

**异常兜底：**

| 场景 | 处理 |
|---|---|
| 文件缺失且网络通 | 心跳成功 → 重建基准，恢复 NORMAL |
| 文件缺失且网络不通 | 永久锁死，**只能联系我方运维** |
| 运维远程恢复 | 见 §3.6 `LicenseDiagnosticCli` 重建 SOP |

**拨时间行为精确定义：**

| 操作 | 是否触发 | 说明 |
|---|---|---|
| 往**未来**拨（系统时间被拉高） | ❌ 不延长宽限期，但**可能提前触发锁死** | `trustedNow = max(系统时间, bootTime+uptime)` 被拉高 → 可能 `> offlineExpireAt` → **提前 OFFLINE_LOCK**。属预期，非"绕过" |
| NTP 微调 < 5 分钟 | ❌ 不误杀 | 跳变幅度 < `MONOTONIC_TOLERANCE_MS` |
| 往**过去**拨（回拨） | ✅ 触发离线锁死 | 跳变幅度 > 5 分钟容忍 |
| 先拨未来再拨回正确时间（跳变 > 5 分钟） | ✅ 触发 | 相对上次可信时间跳变超容忍 |

> 判定基于"**相对上次可信时间的跳变幅度**"，非绝对方向。**往未来拨不会延长离线宽限期**（不会绕过授权），但会因 `max()` 把 `trustedNow` 拉高而**可能提前进入离线锁死**——这是 `max()` 公式的固有副作用，非漏洞，属预期行为。

### 3.6 远程运维通道 + 诊断 CLI（防暴露约束）

系统内置远程运维通道（SSH 隧道 / 我方运维平台接入），授权有效期内我方可直接远程诊断与修复（含离线锁死恢复、指纹重置、单调时钟重建等）。

**运维工具约束（防暴露）：**

| 约束 | 说明 |
|---|---|
| 通道认证 | 仅允许我方**授权人员**访问，mTLS 双向认证 |
| CLI 禁止 HTTP/API 入口 | `LicenseDiagnosticCli` **禁止**暴露给业务 HTTP 接口 / REST 端点 / 管理端点；仅可在**容器本地控制台**执行 |
| 原因 | 防止后续有人把 CLI 封装成接口埋下"本地可绕过校验"的风险入口 |
| 监控 | 所有运维操作记录审计日志（操作人、时间、目标机、动作、结果） |

**单调时钟人工重建 SOP（文件缺失 + 网络不通时）：**

1. 运维通过 mTLS 通道进入容器本地控制台
2. 执行 `LicenseDiagnosticCli --rebuild-monotonic`
3. 该 CLI **必须三检合一**：① 指纹匹配 ② 授权有效 ③ **服务端在线确认**（不能本地自填）
4. 三检通过 → 重建 `.panjia_monotonic` 基准 → 恢复 NORMAL
5. 任一不通过 → 拒绝执行，返回具体原因

> 关键闸门：第 3 步"服务端在线确认"。如果只写"运维手动重建"，等于给"删文件绕过"留复活通道。必须是服务端在线点头，客户端绝不自填。

---

## 四、L5 完整性自检

### 4.1 目的与边界

**目的**：防"class 文件被静态替换（绕过校验入口），系统未崩却照常算薪"——这是锁死/拒绝逻辑拦不住的场景，是受限模式的**前置触发保障**。

**攻击边界（明确声明）：**
- ✅ 防：静态替换 class 字节码（ProGuard/JDGUI 改 `.class` → 重新打包）
- ❌ **不防**：整体替换 jar 包 + 伪造 `panjia-checksums`（需逆向 ClassFinal，属威胁模型外）
- 不防运行时内存 patch / 注入 Agent（需 Java Agent，兼职 IT 做不到，已移除）

### 4.2 构建流程（顺序钉死）

> ⚠️ **实现陷阱**：checksum **必须**在 ClassFinal 加密**之前**生成。先加密再算 hash → 运行时解密后字节码变化 → 校验和全部不匹配 → 启动即全受限。顺序写反是会让整个特性报废的硬 bug。

```
mvn package
  → ProGuard          （混淆，输出混淆后 class）
  → checksum-gen       （⚠️ 对**此时**的 class 算 SHA256，生成 panjia-checksums）  ← 顺序关键
  → ClassFinal         （加密 class 字节码，运行时解密）
```

**铁律 11**：`panjia-checksums` 必须在 ClassFinal **之前**执行；构建脚本改顺序前须对照此铁律。

### 4.3 运行时校验

| 时机 | 校验范围 | 说明 |
|---|---|---|
| 启动期 | 全量关键 class | `IntegrityChecker.checkStartup()`，`LicenseStartupValidator` 第一步调用（见 §2.2） |
| 算薪前 | 抽样 + 关键算薪 class 全量 | `IntegrityChecker.checkBeforeSalary()`，算薪入口前调用 |

### 4.4 偏移规则要求

- 偏移逻辑与 fingerprint + 授权状态绑定，**非固定算法**，破解者无法通过"看几次结果反推并自行修正"
- 客户对比正版 / 手工算薪 → 即刻发现系统性偏差 → 不敢用于正式发薪

### 4.5 多点散布校验 + 硬依赖（V1.1 新增）

> **问题背景**：V1.0 设计中完整性校验仅在拦截器和启动校验两处，如果客户删除 `panjia-license` 整个 JAR 模块，拦截器不注册、注解被忽略、应用仍可启动 → License 全部失效。

**方案：业务代码硬依赖 LicenseCheckPoint，删模块 = 应用崩溃**

```
LicenseCheckPoint.requireLicense(OperationEnum operation)

  四步校验链路（任何一步失败即阻断）：
  ┌──────────────────────────────────────────────────────┐
  │ 1. LicenseGuard.shouldEnforce()    → 是否需要校验     │
  │ 2. IntegrityChecker.quickCheck()    → 代码完整性快检   │
  │ 3. LicenseContext.isRestricted()    → 受限模式检查     │
  │ 4. LicenseService.isOperationAllowed() → 操作授权校验  │
  └──────────────────────────────────────────────────────┘
```

**硬依赖部署点**：

| 部署点 | 类 | 作用 |
|---|---|---|
| admin 启动钩子 | `LicenseStartupHook` | `@PostConstruct` 调用 `requireIntegrity()`，移除 license 模块 → Spring 找不到 bean → 启动失败 |
| 拦截器注册 | `LicenseWebConfig` | 构造注入 `LicenseInterceptor` → 移除模块 → bean 不存在 → 启动失败 |
| 算薪入口（待补） | 业务 Service | `licenseCheckPoint.requireLicense(OperationEnum.CALCULATE)` |
| 导出入口（待补） | 业务 Service | `licenseCheckPoint.requireLicense(OperationEnum.EXPORT)` |

**攻击成本对比**：

| 攻击方式 | 效果 |
|---|---|
| 改 yml 配置 | 不受影响（LicenseGuard 始终 true） |
| 反编译改 class | IntegrityChecker 检出（校验和不匹配） |
| 删除 license 模块 | **应用无法启动**（LicenseStartupHook 找不到 bean） |
| 整体替换 JAR | 需重新生成校验和 + 找到所有散布点 |

> **铁律 22**：`panjia-license` 模块是应用启动的硬依赖。admin 模块通过 `LicenseStartupHook` 在 `@PostConstruct` 阶段注入 `LicenseCheckPoint`，移除 license 模块必须导致应用启动失败。后续开发算薪、导出等业务模块时，必须在关键方法入口直接调用 `LicenseCheckPoint.requireLicense()`，形成更多硬依赖点。

### 4.6 完整性校验实现细节（V1.1 新增）

**构建期校验和生成**：

```
mvn process-classes
  → exec-maven-plugin 执行 ChecksumGenerator
  → 扫描 target/classes 下所有 .class 文件
  → 计算 SHA-256
  → 写入 META-INF/panjia-checksums.txt
  → 打包进 JAR（运行时只读）
```

**运行时校验策略**：

| 方法 | 校验范围 | 调用时机 | 性能 |
|---|---|---|---|
| `checkStartup()` | 全量 16 个关键 class | Spring 启动阶段 | 启动期，可接受 |
| `checkBeforeSalary()` | 全量 16 个关键 class | 算薪前 | 低频操作，可接受 |
| `quickCheck()` | 3 个核心 class（LicenseGuard + IntegrityChecker + LicenseVerifier） | 拦截器每次请求 | 高频，<1ms |

**自校验机制**：

```
IntegrityChecker.class 自身也在校验列表中
  → 篡改 IntegrityChecker 本身 → 校验和不匹配 → 被检出
  → 篡改校验和文件 → JAR 内嵌资源不可外部修改 → 被检出
```

**校验和文件加载优先级**：

1. 优先读 JAR 内嵌资源 `META-INF/panjia-checksums.txt`（不可被外部修改）
2. 降级读外部文件 `dataDir/panjia-checksums`（兼容运维场景）

---

## 五、L4 受限模式（核心威慑）

> **本方案最高优先级安全特性。** 直接作用于"客户不敢用破解版"的心理。

### 5.1 设计原则

**绕过授权校验后，算薪结果必然是错的——且错得明显、可复现、与客户手工核对即刻暴露。** 客户（店东）的核心诉求是"工资算对，别出纠纷"。破解版若能跑但算不准，客户**不敢用来发工资**，破解即失去意义。

### 5.2 五路触发信号（T1–T5）

| 信号 | 触发条件 | 触发方 | 进入模式 |
|---|---|---|---|
| **T1 授权校验失败** | 指纹不匹配 / 版本越界 / token 签名无效 | 启动校验 | RESTRICTED |
| **T2 服务端 REVOKED** | 心跳/激活返回 `clientMode=RESTRICT` 或 `code=TOKEN_REVOKED` | 服务端指令 | RESTRICTED |
| **T3 完整性自检失败** | 启动期或算薪前 class 校验不匹配 | 自检 | RESTRICTED |
| **T4 单调时钟回拨** | `trustedNow` 回拨超容忍 | 时钟 | **OFFLINE_LOCK**（非受限，见下） |
| **T5 离线锁死** | `offlineExpireAt` 过期 / 单调文件缺失 | 离线 | **OFFLINE_LOCK** |

> **T3 注释（防误用）**：T3 是**兜底信号**，仅在"锁死逻辑本身被绕过"的前提下才走到算薪。
> ❌ **禁止**把正常的"指纹异常 / 时钟回拨"直接触发受限模式——那些**正常流程优先走 OFFLINE_LOCK**。
> ✅ T3 仅在"完整性自检发现 class 被静态替换"这一**入侵异常**时触发。
> 开发误用（把时钟回拨直接挂到 T3）会破坏离线锁死主流程，属 Bug。

### 5.3 行为（绝不篡改业务数据）

| 项目 | 说明 |
|---|---|
| 表现 | 算薪核心金额按**确定性偏移规则**计算（偏移量由指纹 + 授权状态派生，非固定值） |
| 错误特征 | 每次都错、错得明显（与手工核对系统性偏差） |
| 不崩溃、不弹窗 | 系统正常运行，客户自行对比后发现异常 |
| 日志 | 写本地告警日志，**约定字段见 §5.4**（含 trigger 原因码 + 偏移量 + 时间戳 + 机器指纹哈希） |
| **绝不篡改** | 不修改数据库、不破坏业务数据、不阻止客户导出核对 |

### 5.4 受限模式日志字段约定

告警日志必须包含以下字段，便于我方后台排查客户报障：

| 字段 | 说明 |
|---|---|
| `trigger` | 触发原因码：`T1_AUTH_FAIL` / `T2_SERVER_REVOKED` / `T3_INTEGRITY` / `SERVER_DECISION` |
| `offset` | 本次应用的偏移量（确定性派生值） |
| `ts` | 触发时间戳 |
| `fpHash` | 机器指纹哈希（脱敏） |
| `licenseId` | 授权码后四位 |
| `productVersion` | 当前版本 |

### 5.5 与离线锁死的区别与优先级

| 维度 | 离线锁死 | 受限模式 |
|---|---|---|
| 触发 | 离线超时 / 时钟回拨 / 单调文件缺失（T4/T5） | 校验/完整性异常 / 服务端指令（T1/T2/T3） |
| 表现 | 功能锁定（无法操作） | 功能可用但算薪错误 |
| 恢复 | 联网 + 心跳成功 | 联网 + 校验通过 / 服务端解除 |

**优先级**：RESTRICTED > OFFLINE_LOCK。两套恢复路径**独立**：
- RESTRICTED → 联网校验通过 或 服务端 `clientMode=NORMAL`
- OFFLINE_LOCK → 心跳成功（仅此一条，单调文件缺失还需服务端在线确认）

> 两者几乎不会真实并存（锁死禁止算薪入口）。若同时触发，**以受限模式为准**（兜底原则），即"能用但错"优先于"不能用"。

---

## 六、部署规范（我方统一执行）

> 以下规范由**我方部署工具自动执行**，客户无需手动操作，也无需理解。

### 6.1 部署形态

- 单节点 Docker Compose，运行于客户云服务器（阿里云 / 腾讯云 ECS）或本地物理机
- 不支 K8s / ACK / TKE 等编排（中小中介场景不需要，且增加指纹复杂度）；如未来需要，单独评估

### 6.2 docker-compose 规范

```yaml
version: '3.8'
services:
  panjia:
    image: registry.panjia.com/panjia-app:latest
    container_name: panjia-app
    restart: unless-stopped
    volumes:
      - /etc/machine-id:/etc/machine-id:ro      # 指纹主因子（只读）
      - panjia-data:/data                        # 持久化数据卷（instanceId + 业务数据 + 状态文件）
    environment:
      - LICENSE_SERVER=https://license.panjia.com

volumes:
  panjia-data:
    driver: local
    # 生产建议映射到云盘挂载点，如 /mnt/cloud-disk/panjia-data
```

### 6.3 容器内目录结构

```
/data/
  ├── .panjia_instance_id     ← 指纹辅因子（自动生成）
  ├── license.lic             ← 授权文件
  ├── .panjia_token           ← 在线鉴权 token（机器指纹派生密钥加密）
  ├── .panjia_monotonic       ← 单调时钟（离线时间防护，**缺失即锁死，禁止重建**）
  ├── panjia-checksums        ← 完整性校验和（META-INF，构建期生成，ClassFinal 加密前算）
  └── business/               ← 业务数据
```

> 所有状态文件必须位于**持久化数据卷**，不得放系统盘临时目录。

### 6.4 部署工具（我方提供）

我方提供标准化部署工具 `panjia-deploy`，自动完成：Docker 安装、镜像拉取、数据卷创建、`/etc/machine-id` 挂载校验、网络配置、首次激活。

> **`/etc/machine-id` 缺失处理**：部署工具启动前先检查宿主机 `machine-id` 是否存在。缺失 → 明确报错提示部署人员挂载，**不静默降级**。宿主机为 Windows（非生产）→ 提示不支持，走标准 Docker 部署流程。

### 6.5 远程运维通道（mTLS）

- 认证：仅我方**授权人员**，mTLS 双向证书认证
- 通道：SSH 隧道 / 我方运维平台接入
- 操作范围：离线锁死恢复、单调时钟重建、指纹重置、换机部署、拉黑解除
- **CLI 禁止 HTTP/API 入口**（见 §3.6）

---

## 七、授权模型与运维流程

### 7.1 授权模型

- **一码一机**：一个授权码绑定一组指纹（hostMachineId + instanceId）
- **版本范围**：token payload 含 `versionRange`（`[minVersion, maxVersion]`），服务端激活/心跳时校验（§7.4）
- 测试环境：单独签发测试授权码，支持**到期自动失效** + **手动立即失效**两种方式（手动失效下次心跳即拒绝）
- 客户更换服务器：走 §7.2 换机流程

### 7.2 更换服务器 / 换机流程（我方运维操作）

1. 客户联系我方客服
2. 我方后台将该授权码旧 fingerprint **标记失效并同步停用旧 token**（加入"下次心跳拒绝名单"，**同步立即生效**，非异步）
3. 我方远程登录新服务器，运行 `panjia-deploy` 部署
4. 挂载数据卷（保留 instanceId）或全新部署
5. 启动系统，使用原授权码自动重新激活
6. 验证指纹匹配 + 在线鉴权连通 + 旧实例心跳被拒行为正确

> **旧实例行为（第 2 步后）**：旧实例下次心跳即被拒绝 → **清理本地 token → 进入受限模式**（`SERVER_REVOKED`，写告警日志）→ 与自动拉黑场景（§2.7）**完全一致**，不复用即二义。
>
> **客户视角：换机器了打电话给我们，剩下的我们来。**

### 7.3 异常场景总表

| 场景 | 行为 | 归属 |
|---|---|---|
| 断网 ≤ 7 天 | 离线宽限期，允许查看 | 自动 |
| 断网 > 7 天 | 离线锁死，只读 | 联系我方恢复 |
| 授权服务器宕机 | 30 分钟缓存内可用，超期锁核心功能 | 我方保障 SLA |
| 更换核心硬件 / 换机 | 指纹不匹配 → 需重新激活 | §7.2 我方处理 |
| 升配 / 降配 / 重启 | 无影响 | 自动 |
| 检测到破解行为（静态替换 class） | 完整性自检失败 → 受限模式（算薪错误）+ 日志 | 自动 + 我方跟进 |
| 快照克隆多开 | 服务端多实例检测 → 自动拉黑 + 旧实例进受限模式 | 我方跟进 |
| SSL 中间人代理拦截 | 握手失败（须加解密白名单） | 我方部署时配置 |
| 单调时钟文件被删 + 断网 | 永久锁死，仅运维可恢复 | 联系我方运维（§3.6 SOP） |
| 单调时钟文件被删 + 联网 | 心跳成功 → 重建基准，恢复 NORMAL | 自动 |

### 7.4 版本管理

- 激活/心跳时服务端校验 `productVersion ∈ versionRange`
- 低版本授权跑高版本 → 版本越界 → `/check` 拒绝（非 30 分钟缓存，铁律优先）
- 防止客户"用旧授权绕过高版本安全修复"

---

## 八、SSL Pinning（强制永久开启）

**规则**：客户端永远校验证书双指纹，不提供任何关闭入口（无配置项 / 环境变量 / JVM 参数 / 管理端点 / 降级分支）。

**三种环境表现：**

| 环境 | 表现 |
|---|---|
| 直连（默认） | ✅ 正常握手 |
| 普通 HTTP 正向代理（`HTTPS_PROXY`，不解密 HTTPS） | ✅ 正常，HTTPS 隧道透明转发，证书链完整校验 |
| SSL 中间人代理（网关替换证书） | ❌ 证书指纹不命中 → SSL 握手失败 → 鉴权不通 |

**写死约束**：授权服务通信**不支持 SSL 中间人解密代理**。客户出网网关（若有）不得将 `license.panjia.com` 加入 SSL 解密拦截，须加**解密白名单（放行不解密）**。

**证书轮换（双指纹过渡）：**

| 阶段 | 客户端 | 服务端 | 说明 |
|---|---|---|---|
| 阶段 1 | 信任 A、B 双指纹 | 仅 A | 备指纹先行，30 天窗口 |
| 阶段 2 | 信任 A、B 双指纹 | A、B 都信任 | 主切后验证 |
| 阶段 3 | 仅信任 B | 仅 B | 主指纹切换完成 |
| 阶段 4 | 仅信任 B | 仅 B | 备指纹下线，90 天后阶段 1 失效 |

> **⚠️ 不可回退警告**：一旦客户端发布到**阶段 3/4（仅信任 B）**，**绝不能**把服务端证书回退到 A——此时所有对应版本客户端全部鉴权失败，只能重新发布修复，业务中断。这是版本升级失误的不可逆状态，运维须谨慎。
>
> **双指纹存储**：客户端**硬编码**主 + 备指纹列表。**备指纹不可通过服务端下发**（防"假服务器诱导信任新证书"投毒），仅通过版本升级过渡。

---

## 九、开发工作量

| 模块 | 工作量 | 说明 |
|---|---|---|
| 授权服务器后端（3 接口 + 多实例自动拉黑 + 版本校验 + clientMode 指令） | 2.2 天 | 较初始 +0.2（versionRange + clientMode） |
| 最小运营后台（授权码生成 + 黑名单 + 告警 + 手动解除拉黑 + 测试码回收） | 2 天 | 不变 |
| 客户端在线校验 + 心跳 + networkReachable 判定 + 30 分钟降级 | 2 天 | 较初始 +0.5（networkReachable 伪代码 + 三态路径） |
| DockerCollector（hostMachineId + instanceId） | 0.5 天 | 不变 |
| 单调时钟（bootTime + 系统时间 + 防删锁死 + 异常处理降级） | 0.3 天 | 较初始 +0.1 |
| 完整性自检（启动期 + 算薪前 + 构建顺序铁律） | 0.8 天 | 新增 L5，checksum-gen 顺序 |
| ProGuard + ClassFinal 加密 | 1.5 天 | 不变（顺序已钉死） |
| 受限模式（确定性偏移 + 五路触发 T1–T5 收敛 + 日志字段） | 1.2 天 | 较初始 +0.2（触发收敛 + 日志） |
| 部署工具 + 远程运维通道（mTLS + CLI 防 HTTP 暴露） | 0.5 天 | 不变（+CLI 约束） |
| 多实例自动拉黑 + 旧实例降级行为 | 0.3 天 | 新增，复用换机逻辑 |
| 证书轮换四阶段 + 回退警告 | 0.2 天 | 新增，运维文档为主 |
| **合计** | **约 11.5 人天** | 较初始 V2.5 +1.5 |

---

## 十、验收标准

### 功能正确性（1–18）

1. 首次激活：合法授权码 + 正确指纹 + 版本在范围内 → 签发 License + token
2. 指纹不匹配 → 拒绝授权，需重新激活
3. 版本越界 → `/check` 拒绝，不高版本绕过
4. 心跳成功 → `offlineExpireAt` 刷新为当前 + 7 天，返回最新 `clientMode`
5. 离线宽限期内 → 允许查看，禁止导入 / 算薪 / 导出
6. 离线锁死 → 只读，提示联系服务商
7. **【前置：networkReachable=true，仅服务端故障】** 服务器不可达 30 分钟内 → 核心操作可用（用缓存）；超 30 分钟 → 锁定
8. 真·断网（DNS/TCP 失败）→ `networkReachable=false` → **离线模式禁用 `/check` 缓存**，无论缓存是否有效
9. 服务端 5xx/TCP 通但 HTTP 5xx → `networkReachable=true` → 走缓存降级，**不误判为断网**
10. 关键操作（导入 / 算薪 / 导出）每次调用 `/check`，按 operation 枚举区分
11. JWT token 服务端四步校验（签名 + 重算哈希 + 比对 + 版本范围）
12. 无 SSL Pinning 关闭入口（配置项 / 环境变量 / JVM 参数 / 端点均无效）
13. 普通正向代理 → 鉴权正常；SSL 中间人代理 → 握手失败
14. 时间往未来拨 → **不延长宽限期，但可能因 max() 拉高 trustedNow 而提前锁死**（属预期）；NTP 微调 < 5 分钟 → 不误杀
15. 时间往过去拨（回拨 > 5 分钟）→ 触发离线锁死
16. 先拨未来再拨回正确时间（跳变 > 5 分钟）→ 触发（相对上次可信时间判据）
17. `.panjia_monotonic` 被删 + 联网 → 心跳成功 → 重建基准，恢复 NORMAL
18. `.panjia_monotonic` 被删 + 断网 → 永久锁死，仅运维可恢复（§3.6 SOP）

### 完整性自检与受限模式（19–24）

19. 构建顺序正确：`ProGuard → checksum-gen → ClassFinal`，先加密后算 hash → **启动即全受限，不合格**
20. 启动期完整性全量校验通过 → 正常启动；失败 → 进入受限模式（T3）
21. 算薪前完整性抽样 + 关键 class 全量校验；失败 → 进入受限模式（T3）
22. 受限模式 → 算薪结果确定性错误，不崩溃、不改业务数据，写告警日志（含 trigger 字段）
23. 受限模式恢复 → 联网校验通过 / 服务端 `clientMode=NORMAL` → 偏移自动消失
24. 受限模式优先级 > 离线锁死：同时触发 → 以受限模式为准（兜底）

### 多实例 / 换机（25–27）

25. 同一授权码两 fingerprint **并发心跳** → 自动拉黑全部 + 通知运维 + 后台可手动解除
26. 自动拉黑后旧实例下次心跳被拒 → **清理 token → 进入受限模式（SERVER_REVOKED）→ 写日志**，**不复用离线宽限**
27. 换机流程旧实例行为 = 自动拉黑场景行为（完全一致，无二义）

### 运维与故障（28–31）

28. `LicenseDiagnosticCli` 禁止 HTTP/API 入口，仅容器本地控制台执行
29. 单调时钟人工重建 SOP：CLI 三检（指纹 + 授权 + **服务端在线确认**）合一
30. 证书轮换四阶段过渡正确；**阶段 3/4 后不可回退服务端到旧证书**
31. 测试授权码：到期自动失效 + 手动立即失效（下次心跳即拒绝）

### 非功能性（32–34）

32. `/check` 接口 P99 < 200ms
33. 授权服务器支持 500 并发心跳
34. OS 兼容：CentOS 7+、Ubuntu 18.04+、银河麒麟 V10；JDK 8/11/17；Docker 20.10+

### 安全防护（35–40，V1.1 新增）

35. License token 使用 RSA 非对称签名；私钥不在客户端 JAR / 配置文件中 → 客户无法伪造 token
36. 公钥嵌入 JAR classpath 资源（`license-public-key.pem`），不在 `application.yml` 中
37. dev 模式由 `spring.profiles.active` 推导，yml 中不存在 `dev-mode` 配置项 → 改 yml 无法进入 dev 模式
38. 所有环境（含 dev）走完整 License 校验代码路径，`enabled` 配置项不存在 → 无法跳过
39. 移除 `panjia-license` 模块 → 应用启动失败（`LicenseStartupHook` 找不到 `LicenseCheckPoint` bean）
40. 完整性校验自校验：篡改 `IntegrityChecker.class` 本身 → 校验和不匹配 → 被检出

---

## 十一、包结构

```
com.panjia.license
├── config/           # LicenseProperties、Web 配置
├── domain/           # LicenseContent、HardwareFingerprint、FingerprintFactor、VersionRange
├── enums/            # LicenseStatusEnum、FingerprintStatusEnum、ClientModeEnum、OperationEnum、TriggerCodeEnum
├── exception/        # LicenseException 及子类
├── crypto/verify/    # LicenseVerifier（RSA 验签）、KeyStore（双指纹）
├── fingerprint/      # FingerprintCollector、DockerCollector
├── starter/          # LicenseStartupValidator、MonotonicClock、HeartbeatScheduler
├── security/         # IntegrityChecker（L5）、RestrictedMode（L4）、LicenseGuard（强制守卫）、LicenseCheckPoint（多点散布校验）
├── interceptor/      # LicenseInterceptor（关键操作 /check，按 operation 枚举）
├── service/          # LicenseContext、LicenseService、LicenseServiceImpl
├── diagnose/         # LicenseDiagnosticCli（仅本地控制台，禁止 HTTP 入口）
└── util/             # LicenseFileUtils、MonotonicTolerance（⚠️ 单一常量，全代码一处引用）

# admin 模块中：
com.panjia.admin
└── license/          # LicenseStartupHook（@PostConstruct 硬依赖 LicenseCheckPoint）
```

**依赖铁律**：禁止引入 `org.dromara`、`com.baomidou`（MyBatis-Plus）等外部框架直接依赖；所有外部调用通过 `panjia-common` 适配。

---

## 十二、安全设计铁律（开发 Code Review Checklist）

| # | 铁律 | 关联 |
|---|---|---|
| 1 | 授权校验失败 → 受限模式（算薪错误），**绝不篡改业务数据** | 全局 |
| 2 | 离线模式优先级 > 30 分钟 `/check` 缓存；真·断网禁用缓存 | 离线模式 |
| 3 | SSL Pinning 强制开启，**无任何关闭入口** | SSL |
| 4 | 受限模式恢复需"联网 + 校验通过"，离线锁死需"联网 + 心跳成功"，路径分明 | 状态机 |
| 5 | `offlineExpireAt` 仅在心跳成功时刷新 | 心跳 |
| 6 | **更换服务器必须一并失效 `.panjia_monotonic` 基准** | 换机 |
| 7 | 禁止新增指纹因子（cpuId / MAC / memorySize / diskSerial）——云上升配即变 | 指纹 |
| 8 | 单调时钟禁止使用单一 `System.currentTimeMillis()`，须含 `btime` 跨重启锚点 | 时钟 |
| 9 | **`.panjia_monotonic` 缺失 = 严重异常，禁止自动重建，仅心跳/运维可恢复** | — |
| 10 | `networkReachable` 严格按 §2.4 伪代码定义；5xx ≠ 断网 | — |
| 11 | **`panjia-checksums` 必须在 ClassFinal 加密之前生成** | 构建 |
| 12 | 完整性自检只防静态替换，声明"整体换 jar"为威胁模型外 | §4.1 |
| 13 | T3 仅兜底"锁死被绕过"的入侵异常；**禁止**把正常时钟回拨/指纹异常挂到 T3 | — |
| 14 | 状态文件与 instanceId 同等重要，须位于持久化数据卷 | 部署 |
| 15 | 禁止将运维操作（换机 / 恢复 / 重建 / 拉黑解除）暴露给客户；全部由我方运维处理 | 运维 |
| 16 | `LicenseDiagnosticCli` 禁止 HTTP/API 入口，仅本地控制台 + mTLS 通道 | — |
| 17 | 证书轮换备指纹不可服务端下发；**阶段 3/4 后不可回退旧证书** | — |
| 18 | 版本越界一律拒绝，不高版本绕过安全修复 | §7.4 |
| 19 | License token 必须使用 RSA 非对称签名；私钥仅在授权服务器，公钥嵌入 JAR；禁止对称签名，禁止密钥放配置文件 | §2.9 |
| 20 | 开发模式由 `spring.profiles.active` 推导，不允许通过配置项 `dev-mode` 控制 | §2.10 |
| 21 | 所有环境（含 dev）必须走完整 License 校验代码路径，禁止通过 `enabled=false` 跳过 | §2.10 |
| 22 | `panjia-license` 是应用启动硬依赖，移除模块必须导致启动失败；业务关键方法必须调用 `LicenseCheckPoint.requireLicense()` | §4.5 |

---

## 十三、设计原则备忘

- **威胁模型是"找人破解的店东"，不是专业黑客**：追求"成本高 + 挫败感强 + 不敢用"，不追求"绝对不可破"
- **受限模式 + 完整性自检是核心威慑**：让破解版"能用但算不准"，直击客户"工资算对"的底线诉求
- **Docker 统一部署**：简化指纹逻辑、统一采集路径、标准化部署
- **我方全权运维**：客户无 IT 人员，所有异常流程由我方处理，文档不面向客户操作
- **够用就好**：已移除反调试、内存 patch、水印溯源等对抗专业逆向的过度设计，把投入集中在真正起效的防线
- **增量修补原则**：发现的问题优先"打补丁"，不推倒重构；架构稳定 > 完美

---

## 十四、交付物清单

1. `panjia-license` 模块源码（约 35 个 Java 文件，含 L5 完整性自检）
2. 授权服务器（Spring Boot，4 接口 + 版本校验 + clientMode 指令 + 最小运营后台）
3. `panjia-deploy` 部署工具（Docker Compose 一键部署 + machine-id 校验）
4. ProGuard + ClassFinal 构建脚本（**含 checksum-gen 顺序铁律注释**）
5. 运营手册（证书轮换四阶段 + 回退警告、换机流程、告警处理、单调时钟人工重建 SOP）
6. 本文档（设计与验收唯一依据）
7. 开发评审 Checklist（三档打勾版）
</content>
</invoke>