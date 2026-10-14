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
| `setup-test-env.sh` | **脚本④ 测试环境**：同机再建一套独立测试栈（独立库 `panjia_test`/端口/授权码），不动生产 | 否（本地用） |
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

### 文件服务拆到独立服务器（外部 MinIO）

随业务增长要把文件存储从应用机拆出去时，**不用改 compose / 不用重新部署应用**，按下面 6 步切换。系统里文件相关配置有两个面，都要改：

| 配置面 | 管什么 | 改哪 |
|--------|--------|------|
| 导入归档 | 员工表等导入 Excel 的归档 | 应用机 `.env` 的 `PANJIA_IMPORT_MINIO_*` |
| 通用文件服务 | 附件/图片等上传下载 | 管理后台「系统管理 → OSS 配置」（`sys_oss_config` 表，改完即时生效） |

#### 1）文件服务器部署 MinIO

新机器装好 Docker 后，建 `/opt/minio/docker-compose.yml`（数据目录挂独立数据盘，如 `/data/minio`）：

```yaml
services:
  minio:
    image: pgsty/minio:RELEASE.2026-04-17T00-00-00Z
    container_name: minio
    restart: always
    environment:
      TZ: Asia/Shanghai
      MINIO_ROOT_USER: panjia
      MINIO_ROOT_PASSWORD: 改成强密码_至少16位
    command: server --address ':9000' --console-address ':9001' /data
    volumes:
      - /data/minio:/data
    ports:
      - "9000:9000"
      - "127.0.0.1:9001:9001"   # 控制台只绑回环，用 SSH 隧道访问
```

```bash
cd /opt/minio && docker compose up -d
```

#### 2）网络放通（最小暴露）

- 文件机 ufw / 云防火墙放行 9000，**来源只填应用服务器内网 IP**，禁止 0.0.0.0/0
- 两台机放同一 VPC，走内网流量；9001 控制台不对公网开，需要时 `ssh -L 9001:127.0.0.1:9001 ubuntu@文件机IP` 后访问 http://127.0.0.1:9001

#### 3）迁移旧文件（应用机执行，镜像自带 mc）

```bash
docker run --rm --entrypoint sh pgsty/minio:RELEASE.2026-04-17T00-00-00Z -c '
  mc alias set old http://127.0.0.1:9000 旧AK 旧SK
  mc alias set new http://文件机内网IP:9000 新AK 新SK
  mc mirror --overwrite old/panjia-archive new/panjia-archive
  echo "迁移完成，对象数："; mc ls --recursive new/panjia-archive | wc -l'
```

> 通用文件服务如果还有别的桶（在 OSS 配置里看 bucket 名），同样 `mc mirror` 一遍。建议低峰期操作，迁完先别删旧数据。

#### 4）切应用机 `.env`

```bash
ssh ubuntu@应用机IP
cd ~/panjia && sudo nano .env
```

```dotenv
COMPOSE_PROFILES=                                    # 置空 → 内置 minio 容器不再启动
PANJIA_IMPORT_MINIO_ENDPOINT=http://文件机内网IP:9000
PANJIA_IMPORT_MINIO_ACCESS_KEY=panjia
PANJIA_IMPORT_MINIO_SECRET_KEY=文件机的强密码
PANJIA_IMPORT_MINIO_BUCKET=panjia-archive
```

#### 5）改后台 OSS 配置

登录系统 → 系统管理 → OSS 配置，把启用中的那条 MinIO 配置的 **访问站点(endpoint) / accessKey / secretKey** 改成文件服务器的，保存（即时生效，不用重启）。

#### 6）重启验证

```bash
sudo ./restart.sh                                   # 内置 minio 容器自动消失，server/web 正常
sudo docker ps                                       # 应只剩 postgres/redis/server/web 四个容器
```

验证清单：
- [ ] 重新导入一次员工表成功（导入归档走新 MinIO）
- [ ] 页面上传/下载一个附件成功（通用文件服务走新 MinIO）
- [ ] 文件机 `mc ls new/panjia-archive/panjia-import/` 能看到新文件
- [ ] 确认无误后再删应用机旧数据 `~/panjia/data/minio`（保留几天备份更稳妥）

#### 回滚

出问题把 `.env` 改回 `COMPOSE_PROFILES=embedded-minio`、`PANJIA_IMPORT_MINIO_*` 注释回内置值，OSS 配置改回原 endpoint，`sudo ./restart.sh` 即可（旧数据未删时无损）。

#### 架构建议

- **endpoint 用内网域名不用裸 IP**（内网 DNS 或 hosts，如 `http://oss.panjia:9000`）：以后再迁文件机只改一条 DNS，应用配置和数据库里的历史 URL 都不受影响
- 两机同 VPC：免公网带宽费，9000 无需对公网暴露
- 文件机数据盘独立挂载、定期快照；MinIO 单节点够用，数据增长后再考虑分布式模式

## 六、代码更新 → 发版流程

```bash
# 本地（脚本自动读本目录 server.env 里的服务器信息）
bash update-backend.sh                # 只改了后端：构建+上传+重建 server 容器
bash update-frontend.sh               # 只改了前端：构建+同步+reload

# 前后端都改了，或 compose/镜像源等有变化：跑一次全新部署（幂等，不丢数据）
bash deploy.sh <IP>
```

> **双环境一次发版**：服务器上存在测试环境（`~/panjia-test`，由 `setup-test-env.sh` 创建）时，
> `update-backend.sh` / `update-frontend.sh` 会自动「先更新测试、再更新生产」——
> 只需跑一次脚本，测试（`:8081`）和生产（`:80`）同时生效，无需部署两次。

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

## 九、同机搭建独立测试环境（可选）

一台服务器上并存「生产 + 测试」两套栈，两套互相隔离、连两个不同的库。由 `setup-test-env.sh` 一键创建，**不动生产**（生产 compose / 脚本 / 容器 / 数据一律不碰）。

### 隔离方式

| 项目 | 生产 | 测试 |
|------|------|------|
| 部署目录 | `~/panjia` | `~/panjia-test` |
| 容器名前缀 | `panjia-*` | `panjia-test-*` |
| 前端端口 | 80 | **8081** |
| PostgreSQL / 库名 | 5432 / `panjia` | **5433 / `panjia_test`** |
| Redis 端口 | 6379 | **6380** |
| MinIO API / 控制台 | 9000 / 9001 | **9002 / 9003** |
| JVM | `-Xmx4096m` | **`-Xmx1024m`** |
| 授权码 | 生产码 | **测试专用码** |

### 前置

- [ ] 测试栈用**独立的授权码**（授权管理后台单独生成）——**切勿复用生产码**。指纹 = `/etc/machine-id` + 数据卷 `instanceId`，同机复用同一授权码会被服务端多实例检测自动拉黑（连生产一起）。
- [ ] 云安全组放行 **8081**。
- [ ] 服务器已跑过 `deploy.sh`（`panjia-server:latest` 镜像已在，测试栈直接复用，不重传）。

### 创建

```bash
cd panjia-server/deploy/docker
PANJIA_TEST_AUTH_CODE=PJ-XXXX-XXXX-XXXX bash setup-test-env.sh   # 服务器信息读 server.env
```

脚本自动完成：建 `~/panjia-test` → 拷贝 compose/nginx/前端 → 容器名改 `panjia-test-*` → 生成 `.env`（随机强密码、端口错开、小 JVM）→ 起栈 → 建 MinIO 桶 → 等后端健康后修正测试库 `sys_oss_config`（图片直链指向 `http://IP:8081/oss`，不串生产）。

完成后访问 `http://<服务器IP>:8081`。**重复执行安全**：`.env`/数据/授权码保留；拿到测试授权码后重跑一次即自动补齐。

### 测试环境运维

```bash
ssh ubuntu@<服务器IP>
cd ~/panjia-test
./logs.sh          # 测试后端日志
./restart.sh       # 重启测试栈
sudo docker compose ps
```

测试后端发版（可选）：把新镜像 tar 传到 `~/panjia-test` 后
`cd ~/panjia-test && sudo docker load -i panjia-server-latest.tar && sudo docker compose up -d --force-recreate server`（生产用 `update-backend.sh`，两者互不影响）。

> 注意：日常代码发版**不需要**上面的手动操作——`update-backend.sh` / `update-frontend.sh`
> 检测到 `~/panjia-test` 会自动双环境发版（见第六节）。上面的手动方式仅用于单独动测试环境。
