# Panjia OSS（MinIO）文件上传/回显配置说明

> 适用：图片上传回显（截图凭证、头像等）与文件上传下载。
> 配套脚本：[fix-oss.sh](./fix-oss.sh) —— 新服务器接入或故障自愈，一条命令搞定，**通常不需要改脚本任何内容**。

## 一、核心原理：三条通道，各走各的

| 通道 | 配置项 | 填什么 | 给谁用 |
|---|---|---|---|
| 上传/删除 | `sys_oss_config.endpoint` | `minio:9000`（compose 内部服务名） | **后端容器 → MinIO**，与浏览器无关 |
| 图片回显 | `sys_oss_config.domain_url` | `服务器IP:端口/oss`（如 `1.2.3.4:8081/oss`） | **浏览器** `<img>` 加载，必须是用户浏览器可达的地址 |
| nginx 代理 | nginx.conf 的 `location /oss/` | 反代到 `http://minio:9000` | domain_url 的落地通道，去掉 `/oss/` 前缀转发给 MinIO |

`sys_oss` 表每条文件的 `url` = `http://{domain_url}/{bucket}/{file_name}`，是**绝对 URL 存库**——所以改配置只影响新文件，存量 URL 要用脚本第 5 步重建。

## 一点五、本地开发环境（Mac）的对齐方式

本地基础设施（postgres/redis/minio）由 `script/docker/docker-compose.yml` 启动（compose 项目名 `docker`，网络 `docker_default`），后端容器 `panjia-local-backend` 是手动 `docker run` 的，默认在 `bridge` 网络——**必须先接入 MinIO 所在网络**，endpoint 才能用和生产一致的 `minio:9000`：

```bash
docker network connect docker_default panjia-local-backend
```

- 已持久化：`docker restart` 后仍生效；**但容器重建（docker rm 后重新 run）需要重新执行**上面的命令
- 本地 `sys_oss_config`：`endpoint=minio:9000`（与生产一致）；`domain_url=panjia.icu:3000/minio`（走 frp → 本地 vite 的 `/minio` 代理，属浏览器回显通道，各环境必须各配各的）
- 本地 MinIO 账号：ruoyi/ruoyi123（compose 里 `MINIO_ROOT_USER/PASSWORD`）

## 二、新服务器接入步骤（照抄即可）

1. `cd <部署目录>`（含 `docker-compose.yml` 和 `.env` 的目录）
2. 确认 `.env` 有：`POSTGRES_USER`、`POSTGRES_DB`（默认 panjia）、`POSTGRES_PASSWORD`、`REDIS_PASSWORD`；内置 MinIO 模式还需 `MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`
3. `docker compose up -d` 全套起起来
4. 执行 `bash fix-oss.sh`（脚本会自动：起 MinIO → 读真实密钥 → 建桶 → 回填 sys_oss_config → 重建存量 URL → 修补 nginx → 重启后端清缓存 → 自检）
5. 验收：最后一行自检必须是 **`200 text/plain`**；页面重新登录后上传一张图片确认能回显

### .env 该改什么？

- **内置 MinIO 模式（默认，推荐）**：`.env` 的 `COMPOSE_PROFILES=embedded-minio`（或缺省），只需保证 `MINIO_ROOT_USER/MINIO_ROOT_PASSWORD` 已设置
- **外部 MinIO 模式**：`.env` 里 `COMPOSE_PROFILES` 留空（不启动内置 minio），并配置：
  ```
  PANJIA_IMPORT_MINIO_ENDPOINT=http://<外部MinIO地址>:9000
  PANJIA_IMPORT_MINIO_ACCESS_KEY=<账号>
  PANJIA_IMPORT_MINIO_SECRET_KEY=<密码>
  ```
  脚本会自动把 endpoint 换成外部地址，nginx 的 `/oss/` 代理目标也要人工改成外部地址（脚本只自动处理内置模式）

## 三、踩坑清单（血泪史，上线前逐条对照）

1. **endpoint 绝不能填 `host.docker.internal`**——那是 Mac Docker 专属域名，Linux 容器解析不了，报 `UnknownHostException`。容器内一律用 compose 服务名 `minio:9000`
2. **domain_url 绝不能填 `minio:9000` 或 `localhost`**——那是浏览器的地址，用户浏览器解析不了容器内网名和服务器自己的 localhost。必须填 `服务器IP:端口/oss`
3. **密钥以运行中的 MinIO 容器 env 为准**——容器是用旧 .env 创建的话，改了 .env 不等于改了容器（`docker compose up -d minio` 检测到 env 变化才会重建）。脚本第 2 步直接 `docker inspect` 容器读真实值，杜绝此类错配（报 `The Access Key Id you provided does not exist` 就是这个问题）
4. **nginx `/oss/` 块有两条铁律**——① 必须有 `resolver 127.0.0.11`（变量 proxy_pass 没配 resolver 时 nginx 返回 500）；② `set $minio_upstream` 必须写在 `rewrite ^/oss/(.*)$ /$1 break;` **之前**（`rewrite ... break` 会终止后续 rewrite 阶段指令，`set` 放在后面永远不执行，`proxy_pass` 拿到空值报 500 `invalid URL prefix`）。脚本第 6 步把整个块替换成标准版，两条都保证
5. **校验图片通道必须看 Content-Type**——SPA fallback 会把未知路径兜底成 index.html，返回 **200 text/html**，状态码好看但 `<img>` 必裂。正确通道返回 `200 text/plain`（health）或 `image/*`（真图）
6. **sys_oss / sys_oss_config 有 Redis 缓存**——直改 SQL 后必须清缓存：删 `sys_oss_config` 缓存必须紧跟重启后端（删了缓存不重启会导致运行中后端配置实例丢失，上传报「配置信息不存在」）；`sys_oss` 缓存删掉即可（按需重建）。脚本第 7 步按正确顺序处理
7. **多环境同机部署（如测试+生产）**——容器名带项目前缀（`panjia-postgres` vs `panjia-test-postgres`），**严禁脚本写死容器名**；fix-oss.sh 通过 `docker compose ps -q <服务名>` 在当前部署目录内定位容器，只作用于当前环境
8. **改完配置旧图不会自愈**——存量 `sys_oss.url` 是写死的绝对地址，脚本第 5 步按新域名整体重建

## 四、故障速查

| 现象 | 原因 | 处理 |
|---|---|---|
| 上传报 UnknownHostException | endpoint 填了浏览器侧地址 | 跑 fix-oss.sh |
| 上传报 Access Key Id does not exist (403) | 数据库密钥与 MinIO 容器不一致 | 跑 fix-oss.sh |
| 上传报「'xx'配置信息不存在」 | sys_oss_config 缓存被清但没重启 | 重启后端 server 容器 |
| 上传成功、图片裂 | /oss/ 通道 500（缺 resolver）或域名错 | 跑 fix-oss.sh 后看自检结果 |
| 自检 200 text/html | nginx 缺 /oss/ location（SPA fallback 假象） | 跑 fix-oss.sh（会插入块），人工核对 nginx.conf |
| 自检 502 | nginx 能解析但 MinIO 没起来 | `docker compose up -d minio` 或检查 COMPOSE_PROFILES |

## 五、生产上线检查清单

- [ ] `.env` 的 MINIO_ROOT_USER/PASSWORD 已设置且非默认弱口令（**生产务必改掉 panjia/panjia123456**）
- [ ] `bash fix-oss.sh` 自检输出 `200 text/plain`
- [ ] 有公网域名/HTTPS 时，把 `sys_oss_config.domain_url` 改为 `https://你的域名/oss`（可在界面 系统管理→文件配置管理 里改，UI 保存自动刷新缓存无需重启），并重跑脚本第 5 步或手动重建存量 URL
- [ ] 服务器防火墙**无需**对外开放 MinIO 的 9000 端口（全部流量走 nginx /oss/ 代理）
- [ ] 上传一张真实图片验证回显，`curl -I` 确认 Content-Type 为 `image/*`
