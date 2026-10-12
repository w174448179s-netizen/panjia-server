#!/bin/bash
cd "$(dirname "$0")"
sudo docker compose up -d --wait --wait-timeout 300
echo "服务已启动，查看日志：./logs.sh"
