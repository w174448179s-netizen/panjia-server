#!/bin/bash
# ============================================================================
# 盘家智管 — 脚本二：后端更新（日常发版）
#
# 只更新后端：本地构建镜像 → 上传 tar → 服务器 load → 重建 server 容器
# 双环境联动：检测到测试环境（~/panjia-test）时自动「先测试、后生产」一次发版；
#            只有生产环境时行为与原来一致。
# 不会动：前端页面、postgres/redis/minio 数据容器、服务器 .env（密码/license 不变）
#
# 用法（Mac 本地执行，服务器信息读本目录 server.env）：
#   bash update-backend.sh                       # 推荐
#   bash update-backend.sh <服务器IP> [SSH用户]   # 临时指定其它服务器
#
# 前提：服务器已经跑过 deploy.sh 一次（目录/compose/基础镜像已就绪）
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
IMAGE_TAR="$SCRIPT_DIR/panjia-server-latest.tar"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { printf '%b[INFO]%b  %s\n' "$BLUE" "$NC" "$*"; }
ok()    { printf '%b[OK]%b    %s\n' "$GREEN" "$NC" "$*"; }
warn()  { printf '%b[WARN]%b  %s\n' '\033[0;33m' "$NC" "$*"; }
fail()  { printf '%b[FAIL]%b  %s\n' "$RED" "$NC" "$*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 后端更新 → $SSH_TARGET"
echo "=========================================="

# ---- 双环境检测：存在测试环境则一并更新（先测试后生产）----
TEST_DEPLOY_DIR="$(dirname "$REMOTE_DIR")/panjia-test"
TEST_WEB_PORT=8081
HAS_TEST=0
if ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "test -f $TEST_DEPLOY_DIR/docker-compose.yml" 2>/dev/null; then
    HAS_TEST=1
    info "检测到测试环境 $TEST_DEPLOY_DIR：本次将先更新测试、再更新生产"
else
    info "未检测到测试环境：仅更新生产"
fi

# ---- 1. 本地构建 ----
info ">>> 步骤 1/4：本地构建后端镜像"
bash "$SCRIPT_DIR/build.sh" --backend
[ -f "$IMAGE_TAR" ] || fail "镜像 tar 未生成：$IMAGE_TAR"
ok " 镜像就绪：$(du -sh "$IMAGE_TAR" | cut -f1)"

# ---- 2. 上传 ----
info ">>> 步骤 2/4：上传镜像 tar"
ssh -o ConnectTimeout=8 -o BatchMode=yes "$SSH_TARGET" "mkdir -p $REMOTE_DIR" \
    || fail "SSH 连不上 ${SSH_TARGET}（先 ssh-copy-id）"
scp -q "$IMAGE_TAR" "$SSH_TARGET:$REMOTE_DIR/"
ok " 上传完成"

# ---- 3. 服务器：load + 重建 server 容器（先测试后生产） ----
info ">>> 步骤 3/4：服务器导入镜像并重建 server 容器"
ssh -o ServerAliveInterval=30 "$SSH_TARGET" sudo bash << REMOTE
set -e
cd $REMOTE_DIR
echo "[服务器] docker load..."
docker load -i panjia-server-latest.tar

if [ "$HAS_TEST" = "1" ]; then
    echo "[服务器] 重建测试 server 容器（$TEST_DEPLOY_DIR）..."
    cd $TEST_DEPLOY_DIR
    docker compose up -d --force-recreate --wait --wait-timeout 600 --no-deps server
fi

echo "[服务器] 重建生产 server 容器（--force-recreate 必须带：同 tag 镜像 load 后不重建不生效）..."
cd $REMOTE_DIR
docker compose up -d --force-recreate --wait --wait-timeout 600 --no-deps server
echo "[服务器] 当前容器状态："
docker compose ps
REMOTE
if [ "$HAS_TEST" = "1" ]; then
    ok " server 容器已重建（测试 + 生产）"
else
    ok " server 容器已重建（生产）"
fi

# ---- 4. 健康检查 ----
info ">>> 步骤 4/4：健康检查"
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
[ "$SUCC" = "0" ] && warn "重试 15 次仍返回 ${CODE}（看日志：ssh ${SSH_TARGET} 'cd ${REMOTE_DIR} && sudo ./logs.sh')"
if [ "$HAS_TEST" = "1" ]; then
    CODE_T=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "http://$SERVER_IP:$TEST_WEB_PORT" 2>/dev/null || echo "000")
    if [ "$CODE_T" = "200" ] || [ "$CODE_T" = "301" ]; then
        ok " 测试前端正常（HTTP ${CODE_T}）"
    else
        warn " 测试前端返回 ${CODE_T}（看日志：ssh ${SSH_TARGET} 'cd ${TEST_DEPLOY_DIR} && sudo ./logs.sh'）"
    fi
fi

echo ""
echo "=========================================="
ok " 后端更新完成！生产 http://$SERVER_IP"
[ "$HAS_TEST" = "1" ] && ok " 测试环境已同步更新：http://$SERVER_IP:$TEST_WEB_PORT"
echo "=========================================="
