#!/bin/bash
#
# 停止盘家智管（Mac/Linux 本地）
# 用法：sh bin/stop.sh
#

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_DIR="$PROJECT_DIR/deploy/docker"

cd "$DEPLOY_DIR"

echo "停止盘家智管..."
docker compose down

echo ""
echo "=========================================="
echo " ✓ 已停止"
echo "=========================================="
