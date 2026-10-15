#!/bin/bash
# ============================================================================
# 盘家智管 — 同机搭建独立测试环境（第二套栈）
#
# 在生产服务器上新增一套与生产「完全隔离」的测试栈：
#   独立目录 ~/panjia-test、独立端口、独立数据库(panjia_test)、独立授权码。
#   不触碰生产：生产 docker-compose.yml / deploy.sh / update-*.sh / 容器 均不动。
#
# 用法（Mac 本地执行，服务器信息读本目录 server.env）：
#   PANJIA_TEST_AUTH_CODE=PJ-XXXX-XXXX-XXXX bash setup-test-env.sh
#   PANJIA_TEST_AUTH_CODE=... bash setup-test-env.sh <服务器IP> [SSH用户]
#
# 前提：
#   1) 服务器已跑过 deploy.sh（~/panjia 有生产栈，panjia-server:latest 镜像已 load）
#   2) 已从授权管理后台申请「测试专用」授权码 —— 务必独立于生产码，切勿复用生产码，
#      否则服务端多实例检测会把生产 + 测试一起拉黑（见 MultiInstanceDetector）
#   3) 云安全组已放行测试前端端口（默认 8081）
#
# 重复执行是安全的：脚本幂等，.env / 数据目录 / 已有授权码一律保留。
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARG_IP="${1:-}"
ARG_USER="${2:-}"
if [ -f "$SCRIPT_DIR/server.env" ]; then . "$SCRIPT_DIR/server.env"; fi
SERVER_IP="${ARG_IP:-${SERVER_IP:?请传入服务器 IP 或先配置 server.env}}"
SERVER_USER="${ARG_USER:-${SERVER_USER:-ubuntu}}"
PROD_DIR="${INSTALL_DIR:-/home/$SERVER_USER/panjia}"
if [ "$SERVER_USER" = "root" ]; then PROD_DIR="${INSTALL_DIR:-/root/panjia}"; fi
TEST_DIR="$(dirname "$PROD_DIR")/panjia-test"
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"

# ---- 测试环境端口 / 库名（全部与生产错开）----
TEST_DB=panjia_test
TEST_WEB_PORT=8081
TEST_PG_PORT=5433
TEST_REDIS_PORT=6380
TEST_MINIO_API_PORT=9002
TEST_MINIO_CONSOLE_PORT=9003
TEST_IMAGE_TAG="${IMAGE_TAG:-latest}"

# 测试授权码（不传则栈照常起，但测试后端会因未激活反复重启）
TEST_AUTH_CODE="${PANJIA_TEST_AUTH_CODE:-}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { printf '%b[INFO]%b  %s\n' "$BLUE" "$NC" "$*"; }
ok()    { printf '%b[OK]%b    %s\n' "$GREEN" "$NC" "$*"; }
warn()  { printf '%b[WARN]%b  %s\n' '\033[0;33m' "$NC" "$*"; }
fail()  { printf '%b[FAIL]%b  %s\n' "$RED" "$NC" "$*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 同机测试环境 → $SERVER_IP"
echo "=========================================="
echo ""

# ---- 本地检查 ----
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "echo ok" >/dev/null 2>&1 \
    || fail "SSH 连不上 ${SSH_TARGET}（先 ssh-copy-id）"
ok " SSH 连通，测试目录：$TEST_DIR"

if [ -z "$TEST_AUTH_CODE" ]; then
    warn "未提供 PANJIA_TEST_AUTH_CODE：栈会正常起来，但测试后端会因未激活反复重启；"
    warn "        安全组放行 8081 后，把测试授权码写入 $TEST_DIR/.env 并重跑本脚本即可补齐（幂等）"
fi

# ---- 服务器端配置 + 起栈 ----
# 变量经 sudo env 显式透传（heredoc 是 quoted 的，不会自动透传）
ssh -o ServerAliveInterval=30 "$SSH_TARGET" "sudo env \
  SERVER_IP='${SERVER_IP}' \
  PROD_DIR='${PROD_DIR}' \
  TEST_DIR='${TEST_DIR}' \
  TEST_DB='${TEST_DB}' \
  TEST_WEB_PORT='${TEST_WEB_PORT}' \
  TEST_PG_PORT='${TEST_PG_PORT}' \
  TEST_REDIS_PORT='${TEST_REDIS_PORT}' \
  TEST_MINIO_API_PORT='${TEST_MINIO_API_PORT}' \
  TEST_MINIO_CONSOLE_PORT='${TEST_MINIO_CONSOLE_PORT}' \
  TEST_IMAGE_TAG='${TEST_IMAGE_TAG}' \
  TEST_AUTH_CODE='${TEST_AUTH_CODE}' \
  TEST_OWNER='${SERVER_USER}' \
  bash" << 'REMOTE'
set -e

echo "[测试] 目标目录：$TEST_DIR"

# ---- 1. 前置检查：生产已部署 + 镜像已 load ----
[ -f "$PROD_DIR/docker-compose.yml" ] \
    || { echo "[测试] 未找到生产部署 ${PROD_DIR}，请先跑 deploy.sh"; exit 1; }
docker image inspect "panjia-server:${TEST_IMAGE_TAG}" >/dev/null 2>&1 \
    || { echo "[测试] 缺少镜像 panjia-server:${TEST_IMAGE_TAG}，请先跑 deploy.sh 导入镜像"; exit 1; }

# ---- 2. 建目录 + 拷贝产物（排除 data/logs/镜像 tar，避免重复占用磁盘）----
mkdir -p "$TEST_DIR"
cp -f "$PROD_DIR/docker-compose.yml" "$TEST_DIR/"
rm -rf "$TEST_DIR/nginx" "$TEST_DIR/web"
mkdir -p "$TEST_DIR/nginx" "$TEST_DIR/web"
cp -r "$PROD_DIR/nginx/." "$TEST_DIR/nginx/"
[ -d "$PROD_DIR/web/dist" ] && cp -r "$PROD_DIR/web/dist" "$TEST_DIR/web/dist"
cp -f "$PROD_DIR"/*.sh "$TEST_DIR/" 2>/dev/null || true
chmod +x "$TEST_DIR"/*.sh 2>/dev/null || true
echo "[测试] compose / nginx / 前端 dist / 运维脚本 已就绪"

# ---- 3. 定向改容器名 → panjia-test-*（只动 container_name 行，幂等）----
# 不用全局替换 panjia-，避免误伤 ${PANJIA_IMPORT_MINIO_BUCKET:-panjia-archive} 等
sed -i -E 's/container_name: panjia-(test-)?/container_name: panjia-test-/' "$TEST_DIR/docker-compose.yml"
echo "[测试] 容器名已改为 panjia-test-*"

# ---- 4. .env（幂等：已存在则保留密码与授权码，绝不覆盖）----
if [ ! -f "$TEST_DIR/.env" ]; then
    DB_PW="Pg$(openssl rand -hex 10)!"
    RD_PW="Rd$(openssl rand -hex 10)!"
    MN_PW="Mn$(openssl rand -hex 10)!"
    cat > "$TEST_DIR/.env" << ENVEOF
POSTGRES_USER=panjia
POSTGRES_PASSWORD=$DB_PW
POSTGRES_DB=$TEST_DB
POSTGRES_PORT=$TEST_PG_PORT
REDIS_PASSWORD=$RD_PW
REDIS_PORT=$TEST_REDIS_PORT
MINIO_ROOT_USER=panjia
MINIO_ROOT_PASSWORD=$MN_PW
MINIO_API_PORT=$TEST_MINIO_API_PORT
MINIO_CONSOLE_PORT=$TEST_MINIO_CONSOLE_PORT
IMAGE_TAG=$TEST_IMAGE_TAG
WEB_PORT=$TEST_WEB_PORT
COMPOSE_PROJECT_NAME=panjia-test
COMPOSE_PROFILES=embedded-minio
JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"
PANJIA_AUTH_CODE=$TEST_AUTH_CODE
ENVEOF
    chmod 600 "$TEST_DIR/.env"
    echo "[测试] .env 已生成（随机强密码，请记录）："
    echo "[测试]   DB 密码：$DB_PW"
    echo "[测试]   Redis 密码：$RD_PW"
    echo "[测试]   MinIO 密码：$MN_PW"
else
    echo "[测试] .env 已存在，保留现有密码 / 授权码"
    # 补齐遗漏的测试授权码（仅当 .env 里为空且本次提供了码）
    if [ -n "$TEST_AUTH_CODE" ] && ! grep -q '^PANJIA_AUTH_CODE=.\+' "$TEST_DIR/.env"; then
        sed -i "s#^PANJIA_AUTH_CODE=.*#PANJIA_AUTH_CODE=$TEST_AUTH_CODE#" "$TEST_DIR/.env"
        echo "[测试] .env 已补写测试授权码"
    fi
fi

# ---- 4.5 把配置类文件交还给 SSH 用户（本块以 sudo 运行，mkdir 出来的目录归 root，
#      会导致本地 scp/rsync 直写时报 Permission denied）。绝不 chown ./data 与 ./logs：
#      它们归容器内用户（postgres=999 等），改了属主会让容器启动/运行失败。----
chown "$TEST_OWNER:$TEST_OWNER" "$TEST_DIR"
chown -R "$TEST_OWNER:$TEST_OWNER" "$TEST_DIR/web" "$TEST_DIR/nginx"
chown "$TEST_OWNER:$TEST_OWNER" "$TEST_DIR/.env" "$TEST_DIR"/*.yml "$TEST_DIR"/*.sh 2>/dev/null || true
echo "[测试] 目录属主已交还 $TEST_OWNER（data/logs 保持容器属主不变）"

cd "$TEST_DIR"
set -a; . ./.env; set +a

# ---- 5. 起栈 ----
echo "[测试] docker compose up -d（创建全部容器）..."
docker compose up -d
echo "[测试] 等待基础设施健康（postgres/redis/内置minio，最多 5 分钟）..."
docker compose up -d --wait --wait-timeout 300 postgres redis minio

# ---- 6. 初始化 MinIO 桶（mc 已内置在 minio 镜像里，幂等）----
echo "[测试] 初始化测试 MinIO 桶..."
docker exec panjia-test-minio sh -c '
    mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1
    for b in panjia-archive panjia-file; do
        if mc ls "local/$b" >/dev/null 2>&1; then
            echo "[测试] 桶 $b 已存在"
        else
            mc mb "local/$b" && echo "[测试] 已创建桶 $b"
        fi
    done
    mc anonymous set download local/panjia-file >/dev/null && echo "[测试] panjia-file 已开放公开下载"'

# ---- 7. 等 server 健康后修正 sys_oss_config（对齐测试 MinIO 与 8081 入口）----
echo "[测试] 等待后端 server 健康（宽限 ~150 秒，不阻断）..."
SRV_HEALTH="starting"
for i in $(seq 1 30); do
    SRV_HEALTH=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' panjia-test-server 2>/dev/null || echo missing)
    [ "$SRV_HEALTH" = "healthy" ] && break
    sleep 5
done

if [ "$SRV_HEALTH" = "healthy" ]; then
    echo "[测试] 修正 sys_oss_config（endpoint/domain_url/凭证 对齐测试 MinIO）..."
    docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" panjia-test-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
        "UPDATE sys_oss_config SET endpoint='minio:9000', domain_url='http://${SERVER_IP}:${TEST_WEB_PORT}/oss', bucket_name='panjia-file', access_key='${MINIO_ROOT_USER}', secret_key='${MINIO_ROOT_PASSWORD}', update_time=now() WHERE config_key='minio';"
    # 重启使变更生效：web 重读（bind-mount nginx.conf）；server 刷新 OSS 客户端缓存
    docker compose restart web server
else
    echo "[WARN] 测试后端 server 未健康（常见原因：尚未配置测试授权码）"
    echo "        处理：把测试授权码写入 $TEST_DIR/.env 后，本地重跑本脚本即可自动补齐 OSS 配置（幂等）"
fi

echo "[测试] 当前容器状态："
docker compose ps
REMOTE
ok " 服务器端配置完成"

# ---- 收尾提示 ----
echo ""
echo "=========================================="
ok " 测试环境已创建：http://$SERVER_IP:$TEST_WEB_PORT"
echo "  测试库：${TEST_DB}（独立 postgres 容器，与生产 panjia 完全隔离）"
echo "  目录：  ssh $SSH_TARGET 'cd $TEST_DIR'"
echo "  日志：  ssh $SSH_TARGET 'cd $TEST_DIR && ./logs.sh'"
echo ""
echo "  若未配授权码：sudo nano $TEST_DIR/.env 填 PANJIA_AUTH_CODE=<测试码>"
echo "               再本地重跑本脚本补齐（幂等）"
echo "  ⚠ 别忘了腾讯云安全组放行 $TEST_WEB_PORT"
echo "=========================================="
