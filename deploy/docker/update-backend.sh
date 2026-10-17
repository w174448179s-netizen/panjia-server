#!/bin/bash
# ============================================================================
# 盘家智管 — 脚本二：后端更新（日常发版）
#
# 只更新后端：本地构建镜像 → 上传 tar → 服务器 load → 重建 server 容器
# 双环境策略：先部署测试验证，通过后再部署生产 —— 用参数指定目标，绝不同时发：
#   bash update-backend.sh test   → 只部署测试环境（:8081）
#   bash update-backend.sh prod   → 只部署生产环境（:80，默认）
# 不会动：前端页面、postgres/redis/minio 数据容器、服务器 .env（密码/license 不变）
#
# 用法（Mac 本地执行，服务器信息读本目录 server.env）：
#   bash update-backend.sh test                     # 先发测试（构建镜像 + 记录指纹）
#   bash update-backend.sh                          # 验证通过后发生产（= prod；默认跳过构建，复用 test 的镜像）
#   bash update-backend.sh prod --rebuild           # 强制重新构建后再发生产（紧急直发用）
#   bash update-backend.sh prod <服务器IP> [SSH用户] # 临时指定其它服务器
#   bash update-backend.sh <服务器IP> [SSH用户]      # 兼容旧用法（= prod）
#
# 防呆：prod 跳过构建时会校验镜像构建指纹（git HEAD + 工作区脏文件）与当前代码一致，
#       不一致说明 test 之后代码又改了，拒绝直接发生产，需重新 test 验证。
#
# 前提：服务器已经跑过 deploy.sh 一次；发测试前需先跑过 setup-test-env.sh
# ============================================================================
set -euo pipefail

# ---- 参数解析：test/prod 为目标环境，--rebuild 强制重建，其余按旧约定当 IP/用户 ----
DEPLOY_ENV=prod
FORCE_BUILD=0
ARG_IP=""
ARG_USER=""
for a in "$@"; do
    case "$a" in
        test|prod)  DEPLOY_ENV="$a" ;;
        --rebuild)  FORCE_BUILD=1 ;;
        *)
            if [ -z "$ARG_IP" ]; then
                ARG_IP="$a"
            elif [ -z "$ARG_USER" ]; then
                ARG_USER="$a"
            fi
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/server.env" ]; then . "$SCRIPT_DIR/server.env"; fi
SERVER_IP="${ARG_IP:-${SERVER_IP:?请传入服务器 IP 或先配置 server.env}}"
SERVER_USER="${ARG_USER:-${SERVER_USER:-ubuntu}}"
REMOTE_DIR="${INSTALL_DIR:-/home/$SERVER_USER/panjia}"
SSH_TARGET="${SERVER_USER}@${SERVER_IP}"
IMAGE_TAR="$SCRIPT_DIR/panjia-server-latest.tar"
META_FILE="$IMAGE_TAR.meta"

# 构建指纹 = git HEAD + 工作区脏文件 md5（防"test 后又改代码直接发生产"）
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

# ---- 目标环境：目录 / 端口 / 展示名 ----
TEST_DEPLOY_DIR="$(dirname "$REMOTE_DIR")/panjia-test"
if [ "$DEPLOY_ENV" = "test" ]; then
    TARGET_DIR="$TEST_DEPLOY_DIR"
    TARGET_URL="http://$SERVER_IP:8081"
    ENV_NAME="测试"
else
    TARGET_DIR="$REMOTE_DIR"
    TARGET_URL="http://$SERVER_IP"
    ENV_NAME="生产"
fi

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { printf '%b[INFO]%b  %s\n' "$BLUE" "$NC" "$*"; }
ok()    { printf '%b[OK]%b    %s\n' "$GREEN" "$NC" "$*"; }
warn()  { printf '%b[WARN]%b  %s\n' '\033[0;33m' "$NC" "$*"; }
fail()  { printf '%b[FAIL]%b  %s\n' "$RED" "$NC" "$*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 后端更新【${ENV_NAME}环境】→ $SSH_TARGET"
echo "=========================================="

# ---- 前置校验 ----
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "echo ok" >/dev/null 2>&1 \
    || fail "SSH 连不上 ${SSH_TARGET}（先 ssh-copy-id）"
if [ "$DEPLOY_ENV" = "test" ]; then
    ssh -o BatchMode=yes "$SSH_TARGET" "test -f $TARGET_DIR/docker-compose.yml" 2>/dev/null \
        || fail "服务器上未找到测试环境 ${TARGET_DIR}，请先运行 setup-test-env.sh"
    info "目标：测试环境 ${TARGET_DIR}（${TARGET_URL}）"
else
    ssh -o BatchMode=yes "$SSH_TARGET" "test -f $TARGET_DIR/docker-compose.yml" 2>/dev/null \
        || fail "服务器上未找到生产部署 ${TARGET_DIR}，请先运行 deploy.sh"
    info "目标：生产环境 ${TARGET_DIR}（${TARGET_URL}）"
fi

# ---- 1. 本地构建（prod 默认复用 test 构建产物，一次构建两环境通用）----
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
if [ "$DEPLOY_ENV" = "prod" ] && [ "$FORCE_BUILD" = "0" ]; then
    [ -f "$IMAGE_TAR" ] || fail "未找到已构建镜像 $IMAGE_TAR（先 bash update-backend.sh test，或加 --rebuild 强制重建）"
    [ -f "$META_FILE" ] || fail "未找到构建指纹 $META_FILE（该镜像非本流程构建，请先 bash update-backend.sh test 验证）"
    . "$META_FILE"
    CUR_FP="$(git_fingerprint "$REPO_ROOT")"
    [ "${BUILD_FP:-}" = "$CUR_FP" ] \
        || fail "镜像指纹与当前代码不一致（镜像构建于 ${BUILD_TIME:-未知}）——test 之后代码有变动，请重新 test 验证；紧急直发加 --rebuild"
    info ">>> 步骤 1/4：跳过构建，复用测试验证过的镜像（构建于 ${BUILD_TIME}）"
    ok " 镜像：$(du -sh "$IMAGE_TAR" | cut -f1)，指纹一致"
else
    info ">>> 步骤 1/4：本地构建后端镜像"
    bash "$SCRIPT_DIR/build.sh" --backend
    [ -f "$IMAGE_TAR" ] || fail "镜像 tar 未生成：$IMAGE_TAR"
    {   echo "BUILD_FP=\"$(git_fingerprint "$REPO_ROOT")\""
        echo "BUILD_TIME=\"$(date '+%Y-%m-%d %H:%M:%S')\""
    } > "$META_FILE"
    ok " 镜像就绪：$(du -sh "$IMAGE_TAR" | cut -f1)（指纹已记录，发生产时免重建）"
fi

# ---- 2. 上传 ----
info ">>> 步骤 2/4：上传镜像 tar"
scp -q "$IMAGE_TAR" "$SSH_TARGET:$TARGET_DIR/"
ok " 上传完成 → $TARGET_DIR"

# ---- 3. 服务器：load + 只重建目标环境的 server 容器 ----
info ">>> 步骤 3/4：服务器导入镜像并重建【${ENV_NAME}】server 容器"
ssh -o ServerAliveInterval=30 "$SSH_TARGET" sudo bash << REMOTE
set -e
cd $TARGET_DIR
echo "[服务器] docker load..."
docker load -i panjia-server-latest.tar
echo "[服务器] 重建 server 容器（--force-recreate 必须带：同 tag 镜像 load 后不重建不生效）..."
docker compose up -d --force-recreate --wait --wait-timeout 600 --no-deps server
echo "[服务器] 当前容器状态："
docker compose ps
REMOTE
ok " $ENV_NAME server 容器已重建"

# ---- 4. 健康检查 ----
info ">>> 步骤 4/4：健康检查"
sleep 3
SUCC=0
for i in $(seq 1 15); do
    CODE=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "$TARGET_URL" 2>/dev/null || echo "000")
    if [ "$CODE" = "200" ] || [ "$CODE" = "301" ]; then
        ok " ${ENV_NAME}前端正常（HTTP ${CODE}，第 ${i}/15 次）"
        SUCC=1
        break
    fi
    sleep 2
done
[ "$SUCC" = "0" ] && warn "重试 15 次仍返回 ${CODE}（看日志：ssh ${SSH_TARGET} 'cd ${TARGET_DIR} && sudo ./logs.sh')"

echo ""
echo "=========================================="
ok " 后端更新完成【${ENV_NAME}环境】！$TARGET_URL"
if [ "$DEPLOY_ENV" = "test" ]; then
    echo "  测试验证通过后，再发生产：bash update-backend.sh"
else
    echo "  （本次未动测试环境；如需先发测试：bash update-backend.sh test）"
fi
echo "=========================================="
