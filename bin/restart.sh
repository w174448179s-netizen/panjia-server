#!/bin/bash
#
# 重启盘家智管（Mac/Linux 本地）
# 用法：sh bin/restart.sh
#

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=========================================="
echo " 重启盘家智管"
echo "=========================================="

sh "$PROJECT_DIR/bin/stop.sh"
sh "$PROJECT_DIR/bin/start.sh"
