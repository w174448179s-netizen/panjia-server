#!/bin/bash
# ============================================================================
# 盘家智管 — 脚本三：前端更新（日常发版）
#
# 只更新前端：本地构建 dist → rsync 同步 → nginx reload
# 不会动：后端容器、数据库、.env
#
# 用法（Mac 本地执行，服务器信息读本目录 server.env）：
#   bash update-frontend.sh                      # 推荐
#   bash update-frontend.sh <服务器IP> [SSH用户]  # 临时指定其它服务器
#
# 前提：服务器已经跑过 deploy.sh 一次（目录/nginx 容器已就绪）
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARG_IP="${1:-}"
ARG_USER="${2:-}"
if [ -f "$SCRIPT_DIR/server.env" ]; then . "$SCRIPT_DIR/server.env"; fi
SERVER_IP="${ARG_IP:-${SERVER_IP:?请传入服务器 IP 或先配置 server.env}}"
SERVER_USER="${ARG_USER:-${SERVER_USER:-ubuntu}}"
REMOTE_DIR="${INSTALL_DIR:-/home/$SERVER_USER/panjia}"
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "\033[0;33m[WARN]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 前端更新 → $SSH_TARGET"
echo "=========================================="

# ---- 1. 本地构建 ----
info ">>> 步骤 1/3：本地构建前端"
bash "$SCRIPT_DIR/build.sh" --frontend
[ -d "$SCRIPT_DIR/web/dist" ] || fail "dist 未生成：$SCRIPT_DIR/web/dist"
ok " dist 就绪：$(du -sh "$SCRIPT_DIR/web/dist" | cut -f1)"

# ---- 2. rsync 同步 ----
info ">>> 步骤 2/3：同步 dist 到服务器（增量 + 清理旧文件）"
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "mkdir -p $REMOTE_DIR/web/dist" \
    || fail "SSH 连不上 $SSH_TARGET（先 ssh-copy-id）"
rsync -az --delete \
    "$SCRIPT_DIR/web/dist/" \
    "$SSH_TARGET:$REMOTE_DIR/web/dist/"
ok " 同步完成"

# ---- 3. nginx reload ----
info ">>> 步骤 3/3：nginx reload"
ssh "$SSH_TARGET" "docker exec panjia-web nginx -s reload" \
    && ok " nginx 已 reload" \
    || warn " nginx reload 失败（可能容器未运行：ssh $SSH_TARGET 'sudo docker ps'）"

echo ""
echo "=========================================="
ok " 前端更新完成！访问 http://$SERVER_IP"
echo "=========================================="
