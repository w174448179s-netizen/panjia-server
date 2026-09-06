# 盘家智管 - Windows 一键安装器

面向终端客户的 Windows 一键安装 EXE。客户双击运行 → 输入授权码 → 自动完成 Docker 部署 + 授权激活，全程无需技术背景。

**默认完全离线安装**：Docker Desktop 安装包 + 所有 Docker 镜像全部打进 EXE，客户机无需外网，开箱即用。

> 本文档为 Windows 安装器说明。Linux 安装器位于 `deploy/linux/`（待开发）。

## 目录结构

```
deploy/windows/
├── panjia-setup.nsi       全量安装器 NSIS 脚本（编译 panjia-setup.exe）
├── panjia-upgrade.nsi     升级包 NSIS 脚本（编译 panjia-upgrade.exe）
├── build-installer.sh     Mac / Linux 全量包构建脚本（用 Docker 编译 NSIS）
├── build-installer.bat    Windows 全量包构建脚本
├── build-upgrade.sh       Mac / Linux 升级包构建脚本
├── license.txt            最终用户许可协议
├── scripts/               PowerShell 脚本（核心逻辑）
│   ├── install.ps1        主安装逻辑（Docker检查/安装+部署+启动验证）
│   ├── upgrade.ps1        升级逻辑（换业务镜像+前端，保留数据/授权）
│   ├── uninstall.ps1      卸载辅助脚本
│   ├── reauth-app.ps1     重新激活授权（授权码输错/换码后的补救工具）
│   ├── start-app.ps1      启动应用
│   └── stop-app.ps1       停止应用
├── config/                部署配置（安装时释放到目标机）
│   ├── docker-compose.yml 四容器编排（Postgres+Redis+Server+Nginx）
│   └── nginx.conf         Nginx 配置
├── web/dist/              前端静态资源（构建时生成）
├── docker/                Docker Desktop 安装包（构建时下载）
│   ├── Docker Desktop Installer.exe
│   └── wsl.msi            WSL 内核离线更新包（构建时自动下载，离线客户机必需）
├── images/                Docker 镜像 tar（构建时导出）
│   ├── panjia-server.tar  业务镜像
│   ├── postgres.tar       PostgreSQL
│   ├── redis.tar          Redis
│   └── nginx.tar          Nginx
└── README.md              本文档
```

> `panjia-setup.exe`、`panjia-upgrade.exe`、`build_nsi*/` 等构建产物已在 `.gitignore` 中忽略，不要提交到 git。

## 构建安装器 EXE

### 方式一：Mac / Linux 构建（推荐，无需 Windows 电脑）

**前置条件**：Docker Desktop 已启动

```bash
cd deploy/windows

# 完整离线安装包（默认，约 1GB+）
sh build-installer.sh

# 在线安装包（EXE 较小，约 50MB，客户机需联网）
sh build-installer.sh --online

# 跳过前后端构建（已有 dist 和 JAR）
sh build-installer.sh --skip-build

# 仅编译 NSIS EXE（所有资源已就绪，最快）
sh build-installer.sh --nsis-only
```

### 方式二：Windows 构建

**前置条件**：JDK 21+、Maven、Node.js 20+、pnpm、Docker Desktop、NSIS 3.0+

```cmd
cd deploy\windows
build-installer.bat
```

### 构建参数说明

| 参数 | 说明 |
|------|------|
| （无参数） | 完整离线安装包（默认） |
| `--online` | 在线安装包（EXE 小，客户机需联网拉镜像） |
| `--skip-build` | 跳过前后端代码构建（已有 dist 和 JAR 时用） |
| `--nsis-only` | 仅编译 NSIS EXE（所有资源已就绪） |

### 在线安装 vs 离线安装

| 模式 | 安装包大小 | 客户网络要求 | 适用场景 |
|------|----------|-------------|---------|
| **完全离线**（默认） | ~1.8GB | **完全不需要** | 内网/涉密环境、网络不好的客户 |
| 在线安装 | ~50MB | 需要能访问 Docker Hub | 网络好的大多数客户 |

**完全离线原理**：构建时把 Docker Desktop 安装包（~600MB）和所有 Docker 镜像（~1GB+）全部导出并打进 EXE。客户安装时全程从本地加载，不需要任何网络。

**在线安装原理**：EXE 不含 Docker 安装包和镜像，安装时实时从网上下载。

## 构建升级包（发给老客户）

老客户（已装过任意版本）发升级包即可，无需重新下 1GB+ 全量包：

```bash
cd deploy/windows
sh build-upgrade.sh
```

### 全量包 vs 升级包

| 包 | 产物 | 大小 | 适用客户 | 说明 |
|------|------|------|---------|------|
| 全量安装包 | `panjia-setup.exe` | ~1.8GB | 新客户（首次安装） | 含 Docker Desktop + 基础镜像 + 业务镜像 + 前端 |
| 升级包 | `panjia-upgrade.exe` | ~300MB | 老客户（已装过） | 只含业务镜像 + 前端 + 配置，自动保留数据/密码/授权 |

**升级包前提**：客户机已安装 Docker Desktop 且已有安装目录（`C:\Program Files\Panjia`）。升级过程自动保留 `data/`、`.env`、授权 token、machine-id，升级后无需重新激活。

**拿不准就发全量包**：`panjia-setup.exe` 天然支持覆盖式升级，安装逻辑与首次安装相同，所有数据自动保留，代价只是客户多下载约 1GB。

## 客户安装流程

### 安装前准备

1. **生成授权码**（运营侧操作）
   - 访问：https://panjia.icu 运营后台
   - 客户管理 → 新增客户 → 生成授权码
   - 将授权码和安装包一起发给客户

2. **客户电脑要求**
   - Windows 10 2004 / Windows 11 或更高版本
   - 4GB 以上内存（建议 8GB+）
   - 20GB 以上磁盘空间（镜像 + 数据）
   - 管理员权限

### 安装步骤（客户操作）

1. **双击 `panjia-setup.exe`**
2. **欢迎页** → 下一步
3. **许可协议** → 我接受 → 下一步
4. **安装路径** → 选择目录（默认 `C:\Program Files\Panjia`）→ 下一步
5. **输入授权码** → 粘贴授权码 → 下一步
6. **等待安装**（5-15 分钟）：
   - 检查/安装 Docker Desktop
   - 加载 Docker 镜像（离线模式从本地加载，无需联网）
   - 启动所有服务并验证启动成功
   - 授权码在后端启动时自动激活（激活失败安装器会立即报错并提示处理方式）
7. **完成** → 勾选"立即启动盘家智管"→ 完成

安装完成后浏览器自动打开 http://localhost

### 安装后使用

- **开始菜单** → 盘家智管 → 启动盘家智管
- **桌面快捷方式** → 双击打开
- **浏览器直接访问** → http://localhost

### 授权码输错 / 更换授权码

安装时授权码输错、或后续需要换码，**无需重装**：

开始菜单 → 盘家智管 → **重新激活授权**

会弹出输入框（默认填当前授权码，方便微调），确认后自动：备份 `.env` → 写入新授权码 → 重建 server 容器 → 等待激活成功并弹窗反馈结果。

## 运维管理

### 查看状态

```cmd
cd "C:\Program Files\Panjia\config"
docker compose ps
```

### 查看日志

```cmd
# 后端日志
docker compose logs -f server

# 前端日志
docker compose logs -f web

# 数据库日志
docker compose logs -f postgres
```

### 停止/启动

- 开始菜单 → 盘家智管 → 启动/停止
- 或命令行：`docker compose up -d` / `docker compose down`

### 数据位置

| 内容 | 路径 |
|------|------|
| 数据库 | `C:\Program Files\Panjia\data\postgres\` |
| Redis | `C:\Program Files\Panjia\data\redis\` |
| 授权信息 | `C:\Program Files\Panjia\data\panjia-license\` |
| 应用日志 | `C:\Program Files\Panjia\logs\` |
| 安装日志 | `C:\Program Files\Panjia\logs\install.log` |

### 卸载

- 开始菜单 → 盘家智管 → 卸载
- 或：控制面板 → 程序和功能 → 盘家智管 → 卸载

卸载时会依次询问：

1. **是否同时卸载 Docker Desktop？**——仅本次安装器装的 Docker 才建议卸载；机器原本就有 Docker、或以后还要重装盘家智管的，选"否"（卸载 Docker 需要 1-3 分钟，期间卸载窗口无响应属正常）。
2. **是否保留数据？**——选"是"保留数据库和授权信息（方便重装后接着用），选"否"删除全部数据。

## 授权服务器配置

默认授权服务器地址：`https://panjia.icu`

如需修改：
- 安装时在授权码页面的"授权服务器地址"字段修改
- 或安装后修改 `config\.env` 中的 `LICENSE_SERVER_URL` 配置，然后重启服务

## 常见问题

### Q: 安装卡在 Docker Desktop 安装？
A: 离线模式下 Docker Desktop 安装包已内置，不会卡下载。安装本身需要 2-5 分钟，还可能需要重启电脑。

### Q: 完全离线真的一点网都不需要吗？
A: 是的。离线安装包包含了 Docker Desktop 安装程序和所有 Docker 镜像。客户机从下载完 EXE 之后，整个安装过程都不需要联网。

### Q: 安装完成但打不开页面？
A: 可能 Docker 服务还在启动中，等待 2-3 分钟后刷新页面。如仍不行，查看日志排查。

### Q: 授权码输入错误，能改吗？
A: 可以，用开始菜单 → 盘家智管 → **重新激活授权**，输入新授权码自动完成激活（详见上文"授权码输错 / 更换授权码"）。

### Q: 授权激活失败会怎样？
A: 后端是"硬失败"设计：授权码无效/过期/服务器不可达时后端拒绝启动，安装器会立即检测到并弹窗报错。弹窗里可点「**重试**」当场重新输入授权码继续安装；点「取消」中止后也不会失去入口——开始菜单 → 盘家智管 → **重新激活授权** 随时可用。也可查看 `docker logs panjia-server --tail 100` 排查。

### Q: 数据会丢失吗？
A: 数据存储在 `data/` 目录，重启服务不影响。卸载时选择"保留数据"也不会删除。

### Q: 如何更新版本？
A: 老客户发 `panjia-upgrade.exe` 升级包（~300MB，自动保留数据/密码/授权）；新客户或拿不准状态就发全量 `panjia-setup.exe`（覆盖式升级，数据同样保留）。

### Q: Mac 上能编译 Windows EXE 吗？
A: 可以。`build-installer.sh` 使用 Docker 运行 NSIS 编译器，在 Mac 和 Linux 上都能生成 Windows EXE。

## 技术架构

```
panjia-setup.exe (NSIS)
    │
    ├── 欢迎/协议/路径/授权码页面
    │
    └── 调用 install.ps1（6 步，支持断点续装）
         │
         ├── 1. 系统检查（OS/内存/磁盘）
         ├── 2. Docker 检查/安装（WOW64 兼容 + 拉不起来时弹窗引导手工启动）
         ├── 3. 创建目录结构
         ├── 4. 部署配置文件（写 .env：授权码/授权服务器地址等）
         ├── 5. 加载 Docker 镜像（优先本地 tar，失败再远程拉取）
         └── 6. docker compose up -d + 启动验证
              ├── 容器层：docker inspect 检查 State/Health
              └── 应用层：扫 server 日志
                   ├── "Started panjia-admin"  → 启动成功（授权必然已激活）
                   └── "LicenseException"/"Application run failed" 等
                        → 立即报错退出 + 引导用 reauth-app.ps1 重新激活
```

**激活机制**：授权码写在 `.env` 的 `PANJIA_AUTH_CODE`，后端启动时自动向授权服务器（`LICENSE_SERVER_URL`，默认 https://panjia.icu）激活，成功后 token 落盘到 `data\panjia-license\`。激活失败后端拒绝启动（硬失败），所以"服务启动成功"即"激活成功"，无需单独的激活步骤。

## 文件清单（完全离线安装包）

| 文件名 | 大小 | 来源 |
|--------|------|------|
| panjia-setup.exe | ~1.8GB | NSIS 编译输出 |
| Docker Desktop Installer.exe | ~600MB | docker/（离线安装 Docker 用） |
| docker-compose.yml | ~3KB | config/ |
| nginx.conf | ~2KB | config/ |
| install.ps1 等脚本 | ~20KB | scripts/ |
| 前端 dist | ~10MB | panjia-ui/dist/ |
| panjia-server.tar | ~500MB | 后端 Docker 镜像 |
| postgres.tar | ~400MB | PostgreSQL 镜像 |
| redis.tar | ~30MB | Redis 镜像 |
| nginx.tar | ~40MB | Nginx 镜像 |
