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
#   bash update-frontend.sh test                     # 先发测试（构建 dist + 记录指纹）
#   bash update-frontend.sh                          # 验证通过后发生产（= prod；默认跳过构建，复用 test 的 dist）
#   bash update-frontend.sh prod --rebuild           # 强制重新构建后再发生产（紧急直发用）
#   bash update-frontend.sh prod <服务器IP> [SSH用户] # 临时指定其它服务器
#   bash update-frontend.sh <服务器IP> [SSH用户]      # 兼容旧用法（= prod）
#
# 防呆：prod 跳过构建时会校验 dist 构建指纹（panjia-ui 的 git HEAD + 工作区脏文件）与当前代码一致，
#       不一致说明 test 之后前端代码又改了，拒绝直接发生产，需重新 test 验证。
#
# 前提：服务器已经跑过 deploy.sh 一次；发测试前需先跑过 setup-test-env.sh
# ============================================================================
set -euo pipefail

# ---- 参数解析：test/prod 为目标环境，--rebuild 强制重建，其余按旧约定当 IP/用户 ----
DEPLOY_ENV=prod
FORCE_BUILD=0
ARGS=()
for a in "$@"; do
    case "$a" in
        test|prod)  DEPLOY_ENV="$a" ;;
        --rebuild)  FORCE_BUILD=1 ;;
        *)          ARGS+=("$a") ;;
    esac
done
ARG_IP="${ARGS[0]:-}"
ARG_USER="${ARGS[1]:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/server.env" ]; then . "$SCRIPT_DIR/server.env"; fi
SERVER_IP="${ARG_IP:-${SERVER_IP:?请传入服务器 IP 或先配置 server.env}}"
SERVER_USER="${ARG_USER:-${SERVER_USER:-ubuntu}}"
REMOTE_DIR="${INSTALL_DIR:-/home/$SERVER_USER/panjia}"
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"
DIST_DIR="$SCRIPT_DIR/web/dist"
META_FILE="$SCRIPT_DIR/web/.dist-meta"

# 构建指纹 = panjia-ui 的 git HEAD + 工作区脏文件 md5（防"test 后又改代码直接发生产"）
git_fingerprint() {
    local repo="$1"
    if git -C "$repo" rev-parse HEAD >/dev/null 2>&1; then
        local head dirty
        head="$(git -C "$repo" rev-parse HEAD)"
        dirty="$(git -C "$repo" status --porcelain | md5 -q 2>/dev/null || git -C "$repo" status --porcelain | md5sum | cut -d' ' -f1)"
        echo "${head}|${dirty}"
    else
        echo "no-git"
    fi
}

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

# ---- 1. 本地构建（prod 默认复用 test 构建产物，一次构建两环境通用）----
UI_REPO="$(cd "$SCRIPT_DIR/../../../panjia-ui" 2>/dev/null && pwd || echo "")"
if [ "$DEPLOY_ENV" = "prod" ] && [ "$FORCE_BUILD" = "0" ]; then
    [ -d "$DIST_DIR" ] || fail "未找到已构建 dist：$DIST_DIR（先 bash update-frontend.sh test，或加 --rebuild 强制重建）"
    [ -f "$META_FILE" ] || fail "未找到构建指纹 $META_FILE（该 dist 非本流程构建，请先 bash update-frontend.sh test 验证）"
    . "$META_FILE"
    CUR_FP="$(git_fingerprint "$UI_REPO")"
    [ "${BUILD_FP:-}" = "$CUR_FP" ] \
        || fail "dist 指纹与当前代码不一致（dist 构建于 ${BUILD_TIME:-未知}）——test 之后前端代码有变动，请重新 test 验证；紧急直发加 --rebuild"
    info ">>> 步骤 1/3：跳过构建，复用测试验证过的 dist（构建于 ${BUILD_TIME}）"
    ok " dist：$(du -sh "$DIST_DIR" | cut -f1)，指纹一致"
else
    info ">>> 步骤 1/3：本地构建前端"
    bash "$SCRIPT_DIR/build.sh" --frontend
    [ -d "$DIST_DIR" ] || fail "dist 未生成：$DIST_DIR"
    {   echo "BUILD_FP=\"$(git_fingerprint "$UI_REPO")\""
        echo "BUILD_TIME=\"$(date '+%Y-%m-%d %H:%M:%S')\""
    } > "$META_FILE"
    ok " dist 就绪：$(du -sh "$DIST_DIR" | cut -f1)（指纹已记录，发生产时免重建）"
fi

# ---- 2. rsync 同步到目标环境 ----
info ">>> 步骤 2/3：同步 dist 到【${ENV_NAME}】（增量 + 清理旧文件）"
ssh "$SSH_TARGET" "mkdir -p $TARGET_DIR/web/dist"
rsync -az --delete \
    "$DIST_DIR/" \
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
