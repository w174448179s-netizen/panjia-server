# bin/ — 本地开发与运维脚本

> 运行位置：开发者的本地电脑（Mac / Windows / Linux）。这些脚本不会进 Docker 镜像，也不会传到服务器。

## 脚本一览

| 脚本 | 用途 | 运行时机 |
|---|---|---|
| `install.sh` / `install.bat` | **一键安装**：检查依赖 → 构建 → 生成配置 → 启动 → 健康检查 | 首次安装、环境重置 |
| `build.sh` / `build.bat` | 构建后端 JAR + 前端 dist + Docker 镜像 | 改完代码、准备部署前 |
| `start.sh` / `start.bat` | 启动全部服务（docker compose up） | 每次启动 |
| `stop.sh` / `stop.bat` | 停止全部服务（docker compose down） | 每次停止 |
| `restart.sh` / `restart.bat` | 重启全部服务 | 改配置后 |
| `logs.sh` / `logs.bat` | 查看日志（后端 / 前端 / 数据库 / Redis） | 排查问题 |

## 一键安装

### Mac / Linux

```bash
cd panjia-server
sh bin/install.sh
```

可选参数：
```bash
sh bin/install.sh --skip-build     # 跳过构建（已有镜像时用）
sh bin/install.sh --port 9090       # 指定前端端口
sh bin/install.sh --reset-env       # 强制重新生成 .env
```

### Windows

```cmd
cd panjia-server
bin\install.bat
```

可选参数：
```cmd
bin\install.bat --skip-build
bin\install.bat --port 9090
bin\install.bat --reset-env
```

## 前置条件

### 所有平台
- **Docker Desktop**（Mac/Windows）或 **Docker Engine**（Linux）

### 构建时需要（`--skip-build` 可跳过）
- **JDK 21**（Eclipse Temurin / BellSoft Liberica）
- **Maven 3.9+**（或使用项目自带 `mvnw`）
- **Node.js 20+** + **pnpm 10+**（前端构建）
- **panjia-ui 源码**与 panjia-server 同级目录

## 服务架构

```
docker compose 启动 4 个容器：

┌─────────────────────────────────────────────────┐
│  panjia-web (nginx:stable-alpine)               │
│  端口: ${WEB_PORT:-80} → 80                      │
│  - 前端静态文件 (panjia-ui/dist)                 │
│  - 反向代理 /prod-api/ → server:8080             │
│  - SSE 透传 /resource/                           │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│  panjia-server (panjia-server:latest)            │
│  端口: ${SERVER_PORT:-8080} → 8080               │
│  - Spring Boot + Flyway + Sa-Token               │
│  - 连接 PostgreSQL + Redis                       │
└──────┬──────────────────────────────────────┬──┘
       │                                      │
┌──────▼─────────────────┐  ┌─────────────────▼──┐
│  panjia-postgres       │  │  panjia-redis       │
│  (postgres:16.9)       │  │  (redis:7-alpine)   │
│  端口: 5432             │  │  端口: 6379         │
└────────────────────────┘  └─────────────────────┘
```

## 目录结构

```
panjia-server/
├── bin/
│   ├── install.sh / install.bat    一键安装
│   ├── build.sh / build.bat        构建
│   ├── start.sh / start.bat        启动
│   ├── stop.sh / stop.bat          停止
│   ├── restart.sh / restart.bat    重启
│   ├── logs.sh / logs.bat          日志
│   └── README.md                   本文档
├── deploy/
│   └── docker/
│       ├── docker-compose.yml      容器编排
│       ├── .env / .env.example     环境配置
│       ├── Dockerfile              后端镜像（在项目根目录）
│       ├── nginx/
│       │   ├── nginx.conf           HTTP 模式
│       │   └── nginx-https.conf     HTTPS 模式
│       ├── start.sh                 服务器端启动
│       ├── stop.sh                  服务器端停止
│       ├── restart.sh               服务器端重启
│       ├── logs.sh                  服务器端日志
│       ├── data/                    数据卷（postgres/redis）
│       ├── logs/                    应用日志
│       └── web/dist/               前端构建产物
└── Dockerfile                       后端 Docker 镜像定义
```

## 运维操作

| 命令 | 作用 |
|------|------|
| `sh bin/start.sh` | 启动全部服务 |
| `sh bin/stop.sh` | 停止全部服务 |
| `sh bin/restart.sh` | 重启全部服务 |
| `sh bin/logs.sh` | 查看后端日志 |
| `sh bin/logs.sh web` | 查看前端日志 |
| `sh bin/logs.sh postgres` | 查看数据库日志 |
| `sh bin/logs.sh redis` | 查看 Redis 日志 |
| `docker compose ps` | 查看服务状态 |

## 常见问题

### 端口冲突？
编辑 `.env` 里的 `WEB_PORT` / `SERVER_PORT` / `POSTGRES_PORT` / `REDIS_PORT`，然后 `sh bin/restart.sh`。

### 忘记密码？
```bash
cat deploy/docker/.env    # 查看生成的密码
# 或重新生成：sh bin/install.sh --reset-env
```

### 想修改 JVM 参数？
编辑 `.env` 里的 `JAVA_OPTS`，然后 `sh bin/restart.sh`。

### 前端改动后如何更新？
```bash
sh bin/build.sh latest --frontend
sh bin/restart.sh
```

### 后端改动后如何更新？
```bash
sh bin/build.sh latest --backend
sh bin/restart.sh
```
