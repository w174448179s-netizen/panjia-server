#!/bin/bash
cd "$(dirname "$0")"
docker compose down
echo "服务已停止"
