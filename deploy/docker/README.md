# 盘家智管 · Docker 标准部署包

客户服务器上的整套系统 = 5 个容器：**PostgreSQL + Redis + MinIO + 后端(panjia-server) + Nginx(前端+反代)**。
本目录自包含全部产物，拿到任何一台全新 Ubuntu 服务器，两条命令即可上线。

---

## 一、目录说明

| 文件/目录 | 说明 | 是否传服务器 |
|-----------|------|:---:|
| `deploy.sh` | **脚本① 全新部署**：整包上传 → 配加速源 → docker load → 生成 .env → compose up（首次上线用） | 否（本地用） |
| `update-backend.sh` | **脚本② 后端更新**：构建镜像 → 传 tar → load → 只重建 server 容器（日常发版） | 否（本地用） |
| `update-frontend.sh` | **脚本③ 前端更新**：构建 dist → rsync → nginx reload（日常发版） | 否（本地用） |
| `build.sh` | 出包子命令：被上面三个脚本调用，也可单独跑（`--backend` / `--frontend`） | 否（本地用） |
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
- [ ] 裸机先初始化：`bash ../ubuntu/setup-server.sh`（装 Docker + 防火墙 + 加速源，一次性，幂等）
- [ ] 腾讯云/阿里云**安全组**放行：`22`(SSH)、`80`(HTTP)；其余端口全部不需要（后端/数据库只走容器内网或回环）
- [ ] 本地已配免密：`ssh-copy-id ubuntu@<客户服务器IP>`
- [ ] 本地产物已就绪：`panjia-server-latest.tar` + `web/dist/` 存在（没有就先跑 `bash build.sh`）
- [ ] **已从授权管理后台生成该客户的授权码**（首次部署必填，写入服务器 `.env` 的 `PANJIA_AUTH_CODE`，激活后 token 落盘、此值可清空）

### 部署

```bash
cd panjia-server/deploy/docker

# 一键全新部署（自动构建前后端 + 上传 + 初始化 + 首启自动激活 License）
PANJIA_AUTH_CODE=PJ-XXXX-XXXX-XXXX bash deploy.sh <客户服务器IP> [ssh用户]   # 用户默认 ubuntu
```

> ⚠️ 授权码绑定机器指纹：服务器**重装系统/换机器**后原码可能失效，回授权管理后台重新生成即可（token 已落盘的日常重启不受影响）。

脚本自动完成：整包上传 → 配置国内镜像加速源（腾讯云内网源优先）→ `docker load` 后端镜像 → 拉取 postgres/redis/minio/nginx 基础镜像 → 首次生成 `.env`（随机强密码，8G 内存 JVM 参数）→ `docker compose up --wait` → 健康检查。

完成后浏览器访问 `http://<客户服务器IP>`。

> **重复执行是安全的**：脚本幂等，`.env` 已存在则保留密码不动，数据目录不碰。
> 只重启/服务器上已有文件时：`bash deploy.sh <IP> --skip-upload`

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

sudo docker compose ps   # 容器状态（.env 为 root 600，compose 命令需 sudo，运维脚本内已带）
```

## 四、密码与配置

```bash
sudo cat ~/panjia/.env     # 数据库/Redis 密码都在这（root 600 权限）
```

- 首次部署由脚本随机生成并**打印在 deploy.sh 结尾**，请交付时记录给客户
- 改 `JAVA_OPTS`/端口等：编辑 `.env` 后 `./restart.sh`
- ⚠️ **`.env` 里的 `POSTGRES_PASSWORD` 首次启动后就和数据库绑定了**，中途改密码需同步改库内用户密码，否则后端连不上

## 五、端口一览

| 端口 | 用途 | 发布范围 |
|------|------|---------|
| **80** | 前端页面 + API 入口（nginx，容器内反代后端） | ✅ 唯一对公网发布，云防火墙放行 |
| 8080 | 后端服务 | 仅容器内网（expose，不发布宿主机），**无法绕过 nginx 直连** |
| 5432 | PostgreSQL | 仅绑宿主机 127.0.0.1 |
| 6379 | Redis | 仅绑宿主机 127.0.0.1 |
| 9000 | MinIO API | 仅绑宿主机 127.0.0.1 |
| 9001 | MinIO 控制台 | 仅绑宿主机 127.0.0.1 |

> 本机需要直连数据库/Redis/MinIO 调试时走 SSH 隧道（不用改任何发布配置）：
> `ssh -L 5432:127.0.0.1:5432 -L 9001:127.0.0.1:9001 ubuntu@服务器IP`，然后本地连 127.0.0.1 对应端口。

## 六、代码更新 → 发版流程

```bash
# 本地（脚本自动读本目录 server.env 里的服务器信息）
bash update-backend.sh                # 只改了后端：构建+上传+重建 server 容器
bash update-frontend.sh               # 只改了前端：构建+同步+reload

# 前后端都改了，或 compose/镜像源等有变化：跑一次全新部署（幂等，不丢数据）
bash deploy.sh <IP>
```

## 七、常见问题

| 现象 | 处理 |
|------|------|
| 后端反复重启，日志见 `未检测到有效 token` | 首次部署没配授权码：`.env` 里写 `PANJIA_AUTH_CODE=<授权码>` 后 `sudo ./restart.sh`；激活成功后 token 落盘 `data/panjia-license/` |
| 拉镜像超时 `registry-1.docker.io` | `deploy.sh` 会自动写加速源并重启 docker；手动检查：`docker info \| grep -A5 Mirrors` |
| 前端 502 | 后端没起来：`./logs.sh server` 看启动日志（首次 Flyway 建表约 1-2 分钟） |
| 端口被占用 | `ss -tlnp \| grep ':80'`，改 `.env` 里 `WEB_PORT` 后 `./restart.sh`（8080/5432 等只绑回环，不会与外部冲突） |
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
