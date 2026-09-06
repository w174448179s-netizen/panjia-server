#!/bin/bash
cd "$(dirname "$0")"
docker compose restart
echo "服务已重启，查看日志：./logs.sh"
