# 盘家智管 · Docker 标准部署包

客户服务器上的整套系统 = 5 个容器：**PostgreSQL + Redis + MinIO + 后端(panjia-server) + Nginx(前端+反代)**。
本目录自包含全部产物，拿到任何一台全新 Ubuntu 服务器，两条命令即可上线。

---

## 一、目录说明

| 文件/目录 | 说明 | 是否传服务器 |
|-----------|------|:---:|
| `build.sh` | 本地出包：Maven 打包 → docker build → save 出 tar；前端 pnpm build → dist | 否（本地用） |
| `push-server.sh` | **标准部署脚本**：整包上传 → 配加速源 → docker load → compose up | 否（本地用） |
| `docker-compose.yml` | 5 个服务编排（postgres/redis/minio/server/web） | ✅ |
| `.env.example` | 环境变量模板（服务器上首次自动生成 `.env`，含随机强密码） | ✅ |
| `panjia-server-latest.tar` | 后端镜像（273MB，`docker load` 导入，不走 Docker Hub） | ✅ |
| `web/dist/` | 前端静态文件（nginx 容器挂载） | ✅ |
| `nginx/nginx.conf` | nginx 配置（静态文件 + `/prod-api/` 反代后端） | ✅ |
| `start.sh` `stop.sh` `restart.sh` `logs.sh` | 服务器端运维脚本 | ✅ |
| `data/` `logs/` | 运行时数据（PG/Redis 数据、应用日志），**部署时自动排除不上传** | 运行生成 |

## 二、新客户部署（就两条命令）

### 前置检查（一次性）

- [ ] 客户服务器：Ubuntu 22.04 / 24.04（其他发行版脚本会自动适配 apt）
- [ ] 腾讯云/阿里云**安全组**放行：`22`(SSH)、`80`(HTTP)、`8080`(后端直连，可选)
- [ ] 本地已配免密：`ssh-copy-id ubuntu@<客户服务器IP>`
- [ ] 本地产物已就绪：`panjia-server-latest.tar` + `web/dist/` 存在（没有就先跑 `bash build.sh`）

### 部署

```bash
cd panjia-server/deploy/docker

# 1.（如代码有改动）本地重新出包
bash build.sh

# 2. 一键部署到客户服务器
bash push-server.sh <客户服务器IP> [ssh用户]        # 用户默认 ubuntu
```

脚本自动完成：整包上传 → 配置国内镜像加速源（腾讯云内网源优先）→ `docker load` 后端镜像 → 拉取 postgres/redis/minio/nginx 基础镜像 → 首次生成 `.env`（随机强密码，8G 内存 JVM 参数）→ `docker compose up --wait` → 健康检查。

完成后浏览器访问 `http://<客户服务器IP>`。

> **重复执行是安全的**：脚本幂等，`.env` 已存在则保留密码不动，数据目录不碰。
> 只重启/服务器上已有文件时：`bash push-server.sh <IP> --skip-upload`

## 三、服务器端日常运维

```bash
ssh ubuntu@<客户服务器IP>
cd ~/panjia

./start.sh            # 启动全部（等健康检查通过）
./stop.sh             # 停止全部
./restart.sh          # 重启
./logs.sh             # 跟踪后端日志
./logs.sh web         # 看 nginx 日志（也可: postgres / redis）
./logs.sh postgres -n 50

docker compose ps     # 容器状态
```

## 四、密码与配置

```bash
cat ~/panjia/.env     # 数据库/Redis 密码都在这（600 权限）
```

- 首次部署由脚本随机生成并**打印在 push-server.sh 结尾**，请交付时记录给客户
- 改 `JAVA_OPTS`/端口等：编辑 `.env` 后 `./restart.sh`
- ⚠️ **`.env` 里的 `POSTGRES_PASSWORD` 首次启动后就和数据库绑定了**，中途改密码需同步改库内用户密码，否则后端连不上

## 五、端口一览

| 端口 | 用途 | 安全组建议 |
|------|------|-----------|
| 80 | 前端页面 + API 入口（nginx） | 放行 |
| 8080 | 后端直连（调试用） | 可关闭，走 80 反代即可 |
| 5432 | PostgreSQL | **不对公网放行** |
| 6379 | Redis | **不对公网放行** |
| 9000 | MinIO API | 按需（后端走容器内网，一般无需对公网） |
| 9001 | MinIO 控制台（网页管理） | 按需放行，管理对象存储时用 |

## 六、代码更新 → 发版流程

```bash
# 本地
bash build.sh                        # 重新出包（或 --backend / --frontend 只出一端）
bash push-server.sh <IP>             # 整包重新上传部署（幂等）
```

只改了前端：`bash build.sh --frontend && bash push-server.sh <IP>`
只改了后端：`bash build.sh --backend && bash push-server.sh <IP>`

## 七、常见问题

| 现象 | 处理 |
|------|------|
| 拉镜像超时 `registry-1.docker.io` | `push-server.sh` 会自动写加速源并重启 docker；手动检查：`docker info \| grep -A5 Mirrors` |
| 前端 502 | 后端没起来：`./logs.sh server` 看启动日志（首次 Flyway 建表约 1-2 分钟） |
| 端口被占用 | `ss -tlnp \| grep -E ':80\|:8080'`，改 `.env` 里 `WEB_PORT`/`SERVER_PORT` 后 `./restart.sh` |
| 服务器重启后自动恢复 | compose 已配 `restart: always`，无需人工干预 |
| 磁盘满 | docker 日志已限 100m×5；清旧镜像：`docker image prune -a` |

## 八、数据备份（客户交接必读）

业务数据全在 `~/panjia/data/`（PG 数据 + Redis 持久化 + license）：

```bash
# 备份（停机一致性备份示例）
cd ~/panjia && ./stop.sh
tar czf ~/panjia-data-$(date +%F).tgz data/
./start.sh
```
