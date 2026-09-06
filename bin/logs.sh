#!/bin/bash
#
# 查看盘家智管日志
# 用法：
#   sh bin/logs.sh            # 查看后端日志（默认）
#   sh bin/logs.sh web        # 查看前端日志
#   sh bin/logs.sh postgres   # 查看数据库日志
#   sh bin/logs.sh redis      # 查看 Redis 日志
#   sh bin/logs.sh -n 200     # 查看后端最后 200 行
#   sh bin/logs.sh server -f  # 实时跟踪后端日志
#

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_DIR="$PROJECT_DIR/deploy/docker"

cd "$DEPLOY_DIR"

SERVICE="${1:-server}"
shift 2>/dev/null || true

case "$SERVICE" in
    server|backend) SERVICE="server" ;;
    web|frontend|nginx) SERVICE="web" ;;
    db|postgres|database) SERVICE="postgres" ;;
    redis) SERVICE="redis" ;;
esac

# 默认实时跟踪最后 100 行
if [ $# -eq 0 ]; then
    docker compose logs -f --tail 100 "$SERVICE"
else
    docker compose logs "$@" "$SERVICE"
fi
