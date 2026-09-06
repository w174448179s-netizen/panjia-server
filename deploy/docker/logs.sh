#!/bin/bash
cd "$(dirname "$0")"

SERVICE="${1:-server}"
shift 2>/dev/null || true

case "$SERVICE" in
    server|backend) SERVICE="server" ;;
    web|frontend|nginx) SERVICE="web" ;;
    db|postgres|database) SERVICE="postgres" ;;
    redis) SERVICE="redis" ;;
esac

if [ $# -eq 0 ]; then
    docker compose logs -f --tail 100 "$SERVICE"
else
    docker compose logs "$@" "$SERVICE"
fi
