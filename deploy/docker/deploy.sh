#!/bin/bash
# ============================================================================
# 盘家智管 — 脚本一：全新部署（标准交付，首次上线/换客户服务器用）
#
# 就三步：整包拷到服务器 → docker load → docker compose up
# 自带产物构建，不动数据；日常发版请用 update-backend.sh / update-frontend.sh
#
# 用法（Mac 本地执行）：
#   bash deploy.sh <服务器IP> [SSH用户]          # 首次：打包上传 + 部署
#   bash deploy.sh <服务器IP> --skip-upload      # 服务器已有文件（重跑/重启）
#   bash deploy.sh 162.14.72.197                 # 例：盘家生产
#
# 也可用环境变量（优先级：命令行参数 > 环境变量 > 默认值）：
#   SERVER_IP=1.2.3.4 SERVER_USER=root INSTALL_DIR=/opt/panjia bash deploy.sh
# ============================================================================

set -euo pipefail

# ---- 参数解析：[--skip-upload] <服务器IP> [SSH用户] ----
SKIP_UPLOAD=0
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --skip-upload) SKIP_UPLOAD=1 ;;
        *) ARGS+=("$arg") ;;
    esac
done
set -- "${ARGS[@]:-}"

# 优先级：命令行 > 环境变量 > server.env > 默认值
SERVER_IP="${1:-${SERVER_IP:-}}"
SERVER_USER="${2:-${SERVER_USER:-ubuntu}}"
if [ -f "$(dirname "$0")/server.env" ] && [ -z "$SERVER_IP" ]; then
    . "$(dirname "$0")/server.env"   # 格式：SERVER_IP=x.x.x.x SERVER_USER=ubuntu INSTALL_DIR=/opt/panjia
fi
SERVER_IP="${SERVER_IP:?请传入服务器 IP：bash deploy.sh <IP> [用户]，或在 server.env 里配置}"
REMOTE_DIR="${INSTALL_DIR:-/home/$SERVER_USER/panjia}"
if [ "$SERVER_USER" = "root" ]; then REMOTE_DIR="${INSTALL_DIR:-/root/panjia}"; fi
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE_TAR="$SCRIPT_DIR/panjia-server-latest.tar"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { printf '%b[INFO]%b  %s\n' "$BLUE" "$NC" "$*"; }
ok()    { printf '%b[OK]%b    %s\n' "$GREEN" "$NC" "$*"; }
warn()  { printf '%b[WARN]%b  %s\n' '\033[0;33m' "$NC" "$*"; }
fail()  { printf '%b[FAIL]%b  %s\n' "$RED" "$NC" "$*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 整包部署到 $SERVER_IP"
echo "=========================================="
echo ""

# ==================== 本地检查 ====================
[ -f "$IMAGE_TAR" ] || fail "镜像 tar 不存在：$IMAGE_TAR"
[ -d "$SCRIPT_DIR/web/dist" ] || fail "前端 dist 不存在：$SCRIPT_DIR/web/dist"
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "echo ok" >/dev/null 2>&1 \
    || fail "SSH 连不上 ${SSH_TARGET}（先 ssh-copy-id）"
ok " 本地检查通过（tar $(du -sh "$IMAGE_TAR" | cut -f1)）"

# ==================== 1. 打包上传 ====================
if [ "$SKIP_UPLOAD" = "0" ]; then
    info ">>> 步骤 1/3：打包上传（排除 data/.env/logs，不动服务器数据）"
    TARBALL="/tmp/panjia-deploy.tgz"
    tar czf "$TARBALL" -C "$SCRIPT_DIR" \
        --exclude './data' --exclude './.env' --exclude './logs' \
        --exclude './data/*' --exclude './logs/*' \
        .
    info " 上传 $(du -sh "$TARBALL" | cut -f1)..."
    ssh "$SSH_TARGET" "mkdir -p $REMOTE_DIR"
    scp -q "$TARBALL" "$SSH_TARGET:/tmp/"
    ssh "$SSH_TARGET" "tar xzf /tmp/panjia-deploy.tgz -C $REMOTE_DIR && rm /tmp/panjia-deploy.tgz"
    rm -f "$TARBALL"
    ok " 上传解压完成"
else
    info ">>> 步骤 1/3：跳过上传（--skip-upload）"
fi

# ==================== 2. 服务器：修加速源 + load 镜像 ====================
info ">>> 步骤 2/3：服务器配置（加速源 + 导入镜像）"
# PANJIA_AUTH_CODE 通过 env 显式传给远程 sudo bash（heredoc 是 quoted 的，不会自动透传）
ssh -o ServerAliveInterval=30 "$SSH_TARGET" "sudo env PANJIA_AUTH_CODE='${PANJIA_AUTH_CODE:-}' bash" << 'REMOTE'
set -e
cd /home/ubuntu/panjia

# ---- Docker 加速源（幂等：没配才写，写了就 restart 生效）----
if ! grep -q registry-mirrors /etc/docker/daemon.json 2>/dev/null; then
    echo "[服务器] 配置镜像加速源..."
    mkdir -p /etc/docker
    cat > /etc/docker/daemon.json << 'DCEOF'
{
    "registry-mirrors": [
        "https://mirror.ccs.tencentyun.com",
        "https://docker.m.daocloud.io",
        "https://docker.1ms.run",
        "https://docker.1panel.live"
    ],
    "log-driver": "json-file",
    "log-opts": { "max-size": "100m", "max-file": "5" },
    "live-restore": true
}
DCEOF
    systemctl restart docker
    sleep 2
    echo "[服务器] 加速源已配置（第一个走腾讯云内网，CVM 必通）"
else
    echo "[服务器] 加速源已配置，跳过"
fi

# ---- 导入后端镜像（每次都 load 覆盖同 tag 旧镜像；不能"已存在跳过"，否则镜像更新不生效）----
echo "[服务器] docker load 后端镜像..."
docker load -i panjia-server-latest.tar

# ---- 预拉基础镜像（postgres/redis/minio/nginx，走加速源）----
for img in postgres:16.9 redis:7-alpine nginx:stable-alpine pgsty/minio:RELEASE.2026-04-17T00-00-00Z; do
    if docker image inspect "$img" >/dev/null 2>&1; then
        echo "[服务器] $img 已存在"
    else
        echo "[服务器] 拉取 $img ..."
        docker pull "$img"
    fi
done

# ---- .env（幂等：已有则保留，绝不覆盖密码）----
if [ ! -f .env ]; then
    echo "[服务器] 首次生成 .env（随机强密码）..."
    DB_PW="Pg$(openssl rand -hex 10)!"
    RD_PW="Rd$(openssl rand -hex 10)!"
    MN_PW="Mn$(openssl rand -hex 10)!"
    cat > .env << ENVEOF
POSTGRES_USER=panjia
POSTGRES_PASSWORD=$DB_PW
POSTGRES_DB=panjia
POSTGRES_PORT=5432
REDIS_PASSWORD=$RD_PW
REDIS_PORT=6379
MINIO_ROOT_USER=panjia
MINIO_ROOT_PASSWORD=$MN_PW
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
IMAGE_TAG=latest
WEB_PORT=80
# 内置 MinIO 随 compose 启动；改用外部独立 MinIO 时把本行置空并填好下面 4 项
COMPOSE_PROFILES=embedded-minio
# PANJIA_IMPORT_MINIO_ENDPOINT=http://文件服务器IP:9000
# PANJIA_IMPORT_MINIO_ACCESS_KEY=外部MinIO账号
# PANJIA_IMPORT_MINIO_SECRET_KEY=外部MinIO密码
# PANJIA_IMPORT_MINIO_BUCKET=panjia-archive
JAVA_OPTS="-Xms2048m -Xmx4096m -XX:+UseG1GC"
PANJIA_AUTH_CODE=${PANJIA_AUTH_CODE:-}
ENVEOF
    chmod 600 .env
    echo "[服务器] .env 已生成"
    if [ -n "${PANJIA_AUTH_CODE:-}" ]; then echo "[服务器] 已写入授权码（首次启动自动激活）"; fi
else
    echo "[服务器] .env 已存在，保留现有密码"
    # 老版本 .env 升级：补齐 MinIO / License 授权码配置（幂等）
    if ! grep -q MINIO_ROOT_PASSWORD .env; then
        MN_PW="Mn$(openssl rand -hex 10)!"
        cat >> .env << MNEOF

# ---- MinIO 对象存储（自动补充）----
MINIO_ROOT_USER=panjia
MINIO_ROOT_PASSWORD=$MN_PW
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
MNEOF
        echo "[服务器] .env 已补充 MinIO 配置（MinIO 密码：${MN_PW}）"
    fi
    if ! grep -q '^PANJIA_AUTH_CODE=' .env; then
        echo "PANJIA_AUTH_CODE=${PANJIA_AUTH_CODE:-}" >> .env
        echo "[服务器] .env 已补充 PANJIA_AUTH_CODE"
    fi
    # 老版本升级：默认启用内置 MinIO（外置 MinIO 用户自行置空）
    if ! grep -q '^COMPOSE_PROFILES=' .env; then
        echo "COMPOSE_PROFILES=embedded-minio" >> .env
        echo "[服务器] .env 已补充 COMPOSE_PROFILES=embedded-minio"
    fi
fi

# ---- 清理上次失败残留的容器，再启动 ----
docker compose down --remove-orphans 2>/dev/null || true
chmod +x *.sh 2>/dev/null || true

# 启动策略（关键）：
# 1) up -d 全部创建启动，不阻塞 —— web 只 depends_on server 启动，后端挂了 nginx 也照常跑
# 2) 基础设施（pg/redis/minio）必须健康，才继续；不健康直接判失败
# 3) server 只做宽限等待（最多 ~150s），不健康不阻断 —— 常见原因是还没配授权码，
#    部署完成后补 .env 再 ./restart.sh 即可，不应拖死整个部署
echo "[服务器] docker compose up -d（全部容器创建启动）..."
docker compose up -d

# 判断内置/外部 MinIO（.env 里 COMPOSE_PROFILES 含 embedded-minio 为内置）
set -a; . ./.env; set +a
MC_IMAGE="pgsty/minio:RELEASE.2026-04-17T00-00-00Z"
IMPORT_BUCKET="${PANJIA_IMPORT_MINIO_BUCKET:-panjia-archive}"

if docker compose config --services | grep -q '^minio$'; then
    echo "[服务器] 等待基础设施健康（postgres/redis/内置minio，最多 5 分钟）..."
    docker compose up -d --wait --wait-timeout 300 postgres redis minio
    # OSS 客户端不会自动建桶；mc 已内置在 minio 镜像里，幂等
    echo "[服务器] 确保内置 MinIO 归档桶 ${IMPORT_BUCKET} ..."
    docker exec panjia-minio sh -c '
        mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1
        if mc ls "local/'"$IMPORT_BUCKET"'" >/dev/null 2>&1; then
            echo "[服务器] 桶 '"$IMPORT_BUCKET"' 已存在"
        else
            mc mb "local/'"$IMPORT_BUCKET"'" && echo "[服务器] 已创建桶 '"$IMPORT_BUCKET"'"
        fi'
else
    EXT_EP="${PANJIA_IMPORT_MINIO_ENDPOINT:?外置 MinIO 模式必须在 .env 配置 PANJIA_IMPORT_MINIO_ENDPOINT}"
    echo "[服务器] 外置 MinIO 模式：${EXT_EP}（内置 minio 容器不启动）"
    echo "[服务器] 等待基础设施健康（postgres/redis，最多 5 分钟）..."
    docker compose up -d --wait --wait-timeout 300 postgres redis
    echo "[服务器] 连接外置 MinIO 并确保归档桶 ${IMPORT_BUCKET} ..."
    docker run --rm --entrypoint sh "$MC_IMAGE" -c '
        mc alias set ext "'"$EXT_EP"'" "'"$PANJIA_IMPORT_MINIO_ACCESS_KEY"'" "'"$PANJIA_IMPORT_MINIO_SECRET_KEY"'" >/dev/null 2>&1 \
            || { echo "[服务器] 连不上外置 MinIO，请检查地址/密钥/安全组"; exit 1; }
        if mc ls "ext/'"$IMPORT_BUCKET"'" >/dev/null 2>&1; then
            echo "[服务器] 桶 '"$IMPORT_BUCKET"' 已存在"
        else
            mc mb "ext/'"$IMPORT_BUCKET"'" && echo "[服务器] 已创建桶 '"$IMPORT_BUCKET"'"
        fi'
fi

echo "[服务器] 等待后端 server 健康（宽限 ~150 秒，不阻断部署）..."
SRV_HEALTH="starting"
for i in $(seq 1 30); do
    SRV_HEALTH=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' panjia-server 2>/dev/null || echo missing)
    [ "$SRV_HEALTH" = "healthy" ] && break
    sleep 5
done

echo "[服务器] 当前容器状态："
docker compose ps
if [ "$SRV_HEALTH" != "healthy" ]; then
    echo ""
    echo "[WARN] 后端 server 当前状态：${SRV_HEALTH}（前端 nginx 不受影响，已正常启动）"
    echo "[WARN] 常见原因：尚未配置授权码。处理："
    echo "         1) sudo nano $PWD/.env  填入 PANJIA_AUTH_CODE=PJ-XXXX-XXXX-XXXX"
    echo "         2) sudo ./restart.sh"
    echo "         查日志：sudo ./logs.sh"
    echo ""
fi
REMOTE
ok " 服务器启动完成"

# ==================== 3. 健康检查 ====================
info ">>> 步骤 3/3：健康检查"
sleep 3
CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "http://$SERVER_IP" 2>/dev/null || echo "000")
[ "$CODE" = "200" ] || [ "$CODE" = "301" ] && ok " 前端正常（HTTP ${CODE}）" || warn " 前端返回 ${CODE}"

echo ""
echo "=========================================="
ok " 部署完成！访问 http://$SERVER_IP"
echo "  日志：  ssh $SSH_TARGET 'cd $REMOTE_DIR && ./logs.sh'"
echo "  重启：  ssh $SSH_TARGET 'cd $REMOTE_DIR && ./restart.sh'"
echo "  密码：  ssh $SSH_TARGET 'sudo cat $REMOTE_DIR/.env'"
echo "=========================================="
