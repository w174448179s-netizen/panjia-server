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
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "\033[0;33m[WARN]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 整包部署到 $SERVER_IP"
echo "=========================================="
echo ""

# ==================== 本地检查 ====================
[ -f "$IMAGE_TAR" ] || fail "镜像 tar 不存在：$IMAGE_TAR"
[ -d "$SCRIPT_DIR/web/dist" ] || fail "前端 dist 不存在：$SCRIPT_DIR/web/dist"
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "echo ok" >/dev/null 2>&1 \
    || fail "SSH 连不上 $SSH_TARGET（先 ssh-copy-id）"
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
SERVER_PORT=8080
JAVA_OPTS=-Xms2048m -Xmx4096m -XX:+UseG1GC
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
        echo "[服务器] .env 已补充 MinIO 配置（MinIO 密码：$MN_PW）"
    fi
    if ! grep -q '^PANJIA_AUTH_CODE=' .env; then
        echo "PANJIA_AUTH_CODE=${PANJIA_AUTH_CODE:-}" >> .env
        echo "[服务器] .env 已补充 PANJIA_AUTH_CODE"
    fi
fi

# ---- 清理上次失败残留的容器，再启动 ----
docker compose down --remove-orphans 2>/dev/null || true
chmod +x *.sh 2>/dev/null || true

echo "[服务器] docker compose up（--wait 等健康检查）..."
docker compose up -d --wait --wait-timeout 600
docker compose ps
REMOTE
ok " 服务器启动完成"

# ==================== 3. 健康检查 ====================
info ">>> 步骤 3/3：健康检查"
sleep 3
CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "http://$SERVER_IP" 2>/dev/null || echo "000")
[ "$CODE" = "200" ] || [ "$CODE" = "301" ] && ok " 前端正常（HTTP $CODE）" || warn " 前端返回 $CODE"

echo ""
echo "=========================================="
ok " 部署完成！访问 http://$SERVER_IP"
echo "  日志：  ssh $SSH_TARGET 'cd $REMOTE_DIR && ./logs.sh'"
echo "  重启：  ssh $SSH_TARGET 'cd $REMOTE_DIR && ./restart.sh'"
echo "  密码：  ssh $SSH_TARGET 'cat $REMOTE_DIR/.env'"
echo "=========================================="
