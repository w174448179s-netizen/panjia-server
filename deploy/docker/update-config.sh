#!/bin/bash
# ============================================================================
# 盘家智管 — 脚本：配置更新（改了 docker-compose.yml 后用，最轻量）
#
# 只做两件事：scp docker-compose.yml 到服务器 → 只重建 server 容器
# 不会动：后端镜像、前端 dist、.env（密码/license 不变）、postgres/redis/minio 数据卷
# 不会重启：postgres/redis/minio/web（无数据中断，只 server 短暂 30~60s）
#
# 用法（Mac 本地执行，服务器信息读本目录 server.env）：
#   bash update-config.sh                       # 推荐
#   bash update-config.sh <服务器IP> [SSH用户]   # 临时指定其它服务器
#
# 前提：服务器已经跑过 deploy.sh 一次（目录/compose/基础镜像已就绪）
# 适用场景：改了 docker-compose.yml 的 environment 段 / env 变量 / server 容器配置
# ============================================================================

set -euo pipefail

# 文件变更列表（只这些会同步过去，其它一律不动）
CONFIG_FILES=(
    "docker-compose.yml"
)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARG_IP="${1:-}"
ARG_USER="${2:-}"
if [ -f "$SCRIPT_DIR/server.env" ]; then . "$SCRIPT_DIR/server.env"; fi
SERVER_IP="${ARG_IP:-${SERVER_IP:?请传入服务器 IP 或先配置 server.env}}"
SERVER_USER="${ARG_USER:-${SERVER_USER:-ubuntu}}"
REMOTE_DIR="${INSTALL_DIR:-/home/$SERVER_USER/panjia}"
if [ "$SERVER_USER" = "root" ]; then REMOTE_DIR="${INSTALL_DIR:-/root/panjia}"; fi
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { printf '%b[INFO]%b  %s\n' "$BLUE" "$NC" "$*"; }
ok()    { printf '%b[OK]%b    %s\n' "$GREEN" "$NC" "$*"; }
warn()  { printf '%b[WARN]%b  %s\n' '\033[0;33m' "$NC" "$*"; }
fail()  { printf '%b[FAIL]%b  %s\n' "$RED" "$NC" "$*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 配置更新 → $SSH_TARGET"
echo "=========================================="

# ---- 前置检查 ----
for f in "${CONFIG_FILES[@]}"; do
    [ -f "$SCRIPT_DIR/$f" ] || fail "本地配置文件不存在：$SCRIPT_DIR/$f"
done
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "echo ok" >/dev/null 2>&1 \
    || fail "SSH 连不上 ${SSH_TARGET}（先 ssh-copy-id）"
ok " SSH 连通 + 本地配置就绪"

# ---- 1. scp 配置文件 ----
info ">>> 步骤 1/3：同步配置文件到 $REMOTE_DIR/"
for f in "${CONFIG_FILES[@]}"; do
    scp -q "$SCRIPT_DIR/$f" "$SSH_TARGET:$REMOTE_DIR/"
    ok " 已同步：$f"
done

# ---- 2. 服务器：只重建 server 容器（不动数据容器） ----
info ">>> 步骤 2/3：服务器重建 server 容器（--no-deps 跳过 pg/redis/minio）"
ssh -o ServerAliveInterval=30 "$SSH_TARGET" sudo bash << REMOTE
set -e
cd $REMOTE_DIR
echo "[服务器] docker compose up -d --no-deps --force-recreate server..."
docker compose up -d --no-deps --force-recreate --wait --wait-timeout 300 server
echo "[服务器] 当前容器状态："
docker compose ps
REMOTE
ok " server 容器已重建（pg/redis/minio/web 未动）"

# ---- 3. 健康检查 ----
info ">>> 步骤 3/3：健康检查"
sleep 3
SUCC=0
for i in $(seq 1 15); do
    CODE=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "http://$SERVER_IP" 2>/dev/null || echo "000")
    if [ "$CODE" = "200" ] || [ "$CODE" = "301" ]; then
        ok " 前端正常（HTTP ${CODE}，第 ${i}/15 次）"
        SUCC=1
        break
    fi
    sleep 2
done
[ "$SUCC" = "0" ] && warn "前端重试 15 次仍返回 ${CODE}（看日志：ssh ${SSH_TARGET} 'cd ${REMOTE_DIR} && sudo ./logs.sh')"

echo ""
echo "=========================================="
ok " 配置更新完成！访问 http://$SERVER_IP"
echo "  日志：  ssh $SSH_TARGET 'cd $REMOTE_DIR && sudo ./logs.sh'"
echo "=========================================="
