#!/bin/bash
#
# 启动盘家智管（Mac/Linux 本地）
# 用法：sh bin/start.sh
#

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_DIR="$PROJECT_DIR/deploy/docker"

cd "$DEPLOY_DIR"

if [ ! -f ".env" ]; then
    echo "[ERROR] 未找到 .env，请先执行：sh bin/install.sh"
    exit 1
fi

echo "启动盘家智管..."
docker compose up -d --wait --wait-timeout 300

echo ""
echo "当前容器状态："
docker compose ps

echo ""
. ./.env
echo "=========================================="
echo " ✓ 启动完成"
echo " 前端地址：http://localhost:${WEB_PORT:-80}"
echo " 后端 API：http://localhost:${SERVER_PORT:-8080}"
echo " 查看日志：sh bin/logs.sh"
echo "=========================================="
