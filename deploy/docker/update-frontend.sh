#!/bin/bash
# ============================================================================
# 盘家智管 — 脚本三：前端更新（日常发版）
#
# 只更新前端：本地构建 dist → rsync 同步 → nginx reload
# 双环境策略：先部署测试验证，通过后再部署生产 —— 用参数指定目标，绝不同时发：
#   bash update-frontend.sh test   → 只部署测试环境（:8081）
#   bash update-frontend.sh prod   → 只部署生产环境（:80，默认）
# 不会动：后端容器、数据库、.env
#
# 用法（Mac 本地执行，服务器信息读本目录 server.env）：
#   bash update-frontend.sh test                     # 先发测试
#   bash update-frontend.sh                          # 验证通过后发生产（= prod）
#   bash update-frontend.sh prod <服务器IP> [SSH用户] # 临时指定其它服务器
#   bash update-frontend.sh <服务器IP> [SSH用户]      # 兼容旧用法（= prod）
#
# 前提：服务器已经跑过 deploy.sh 一次；发测试前需先跑过 setup-test-env.sh
# ============================================================================
set -euo pipefail

# ---- 参数解析：第一个参数为 test/prod 时视为目标环境，否则按旧约定当服务器 IP ----
DEPLOY_ENV=prod
if [ "${1:-}" = "test" ] || [ "${1:-}" = "prod" ]; then
    DEPLOY_ENV="$1"
    shift
fi
ARG_IP="${1:-}"
ARG_USER="${2:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/server.env" ]; then . "$SCRIPT_DIR/server.env"; fi
SERVER_IP="${ARG_IP:-${SERVER_IP:?请传入服务器 IP 或先配置 server.env}}"
SERVER_USER="${ARG_USER:-${SERVER_USER:-ubuntu}}"
REMOTE_DIR="${INSTALL_DIR:-/home/$SERVER_USER/panjia}"
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"

# ---- 目标环境：目录 / 端口 / 容器名 / 展示名 ----
TEST_DEPLOY_DIR="$(dirname "$REMOTE_DIR")/panjia-test"
if [ "$DEPLOY_ENV" = "test" ]; then
    TARGET_DIR="$TEST_DEPLOY_DIR"
    TARGET_URL="http://$SERVER_IP:8081"
    WEB_CONTAINER="panjia-test-web"
    ENV_NAME="测试"
else
    TARGET_DIR="$REMOTE_DIR"
    TARGET_URL="http://$SERVER_IP"
    WEB_CONTAINER="panjia-web"
    ENV_NAME="生产"
fi

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { printf '%b[INFO]%b  %s\n' "$BLUE" "$NC" "$*"; }
ok()    { printf '%b[OK]%b    %s\n' "$GREEN" "$NC" "$*"; }
warn()  { printf '%b[WARN]%b  %s\n' '\033[0;33m' "$NC" "$*"; }
fail()  { printf '%b[FAIL]%b  %s\n' "$RED" "$NC" "$*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 前端更新【${ENV_NAME}环境】→ $SSH_TARGET"
echo "=========================================="

# ---- 前置校验 ----
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "echo ok" >/dev/null 2>&1 \
    || fail "SSH 连不上 ${SSH_TARGET}（先 ssh-copy-id）"
if [ "$DEPLOY_ENV" = "test" ]; then
    ssh -o BatchMode=yes "$SSH_TARGET" "test -f $TARGET_DIR/docker-compose.yml" 2>/dev/null \
        || fail "服务器上未找到测试环境 ${TARGET_DIR}，请先运行 setup-test-env.sh"
fi
info "目标：${ENV_NAME}环境 ${TARGET_DIR}（${TARGET_URL}）"

# ---- 1. 本地构建 ----
info ">>> 步骤 1/3：本地构建前端"
bash "$SCRIPT_DIR/build.sh" --frontend
[ -d "$SCRIPT_DIR/web/dist" ] || fail "dist 未生成：$SCRIPT_DIR/web/dist"
ok " dist 就绪：$(du -sh "$SCRIPT_DIR/web/dist" | cut -f1)"

# ---- 2. rsync 同步到目标环境 ----
info ">>> 步骤 2/3：同步 dist 到【${ENV_NAME}】（增量 + 清理旧文件）"
ssh "$SSH_TARGET" "mkdir -p $TARGET_DIR/web/dist"
rsync -az --delete \
    "$SCRIPT_DIR/web/dist/" \
    "$SSH_TARGET:$TARGET_DIR/web/dist/"
ok " $ENV_NAME dist 同步完成"

# ---- 3. nginx reload ----
info ">>> 步骤 3/3：nginx reload（${WEB_CONTAINER}）"
ssh "$SSH_TARGET" "docker exec $WEB_CONTAINER nginx -s reload" \
    && ok " $ENV_NAME nginx 已 reload" \
    || warn " $ENV_NAME nginx reload 失败（可能容器未运行：ssh $SSH_TARGET 'sudo docker ps'）"

echo ""
echo "=========================================="
ok " 前端更新完成【${ENV_NAME}环境】！$TARGET_URL"
if [ "$DEPLOY_ENV" = "test" ]; then
    echo "  测试验证通过后，再发生产：bash update-frontend.sh"
else
    echo "  （本次未动测试环境；如需先发测试：bash update-frontend.sh test）"
fi
echo "=========================================="
