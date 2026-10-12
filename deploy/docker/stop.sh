#!/bin/bash
cd "$(dirname "$0")"
sudo docker compose down
echo "服务已停止"
