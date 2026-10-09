#!/usr/bin/env bash
# ============================================================
# panjia 数据库备份脚本（PostgreSQL / Docker）
#
# 用法:
#   ./backup.sh                    # 备份到 ./backups/panjia_YYYYmmdd_HHMMSS.dump
#   ./backup.sh -o /path/to/dir    # 指定输出目录
#
# 可通过环境变量覆盖默认连接:
#   CONTAINER=postgres DB_NAME=postgres DB_USER=root DB_PASS=root ./backup.sh
#
# 特性:
#   - pg_dump 自定义格式(-Fc, 压缩), 还原用 restore.sh
#   - 自动清理旧备份, 默认保留最近 30 份(KEEP=30 可覆盖)
# ============================================================
set -euo pipefail

# ---- 默认配置(环境变量可覆盖) ----
CONTAINER="${CONTAINER:-postgres}"
DB_NAME="${DB_NAME:-postgres}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-root}"
KEEP="${KEEP:-30}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/backups"
while getopts "o:" opt; do
  case "$opt" in
    o) OUT_DIR="$OPTARG" ;;
    *) echo "用法: $0 [-o 输出目录]"; exit 1 ;;
  esac
done
mkdir -p "$OUT_DIR"

STAMP="$(date +%Y%m%d_%H%M%S)"
DUMP_FILE="$OUT_DIR/${DB_NAME}_${STAMP}.dump"

# ---- 前置检查 ----
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "[错误] Docker 容器 [$CONTAINER] 未运行" >&2
  exit 1
fi

echo "[备份] 容器=$CONTAINER 库=$DB_NAME → $DUMP_FILE"
docker exec -e PGPASSWORD="$DB_PASS" "$CONTAINER" \
  pg_dump -U "$DB_USER" -Fc --no-owner --no-privileges "$DB_NAME" > "$DUMP_FILE"

if [ ! -s "$DUMP_FILE" ]; then
  echo "[错误] 备份文件为空, 可能失败" >&2
  rm -f "$DUMP_FILE"
  exit 1
fi

SIZE="$(du -h "$DUMP_FILE" | cut -f1 | tr -d ' ')"
echo "[完成] $DUMP_FILE ($SIZE)"

# ---- 清理旧备份(仅清理本目录同库名前缀) ----
COUNT="$(ls -1t "$OUT_DIR/${DB_NAME}_"*.dump 2>/dev/null | wc -l | tr -d ' ')"
if [ "$COUNT" -gt "$KEEP" ]; then
  ls -1t "$OUT_DIR/${DB_NAME}_"*.dump | tail -n +"$((KEEP + 1))" | while read -r old; do
    echo "[清理] 删除旧备份: $old"
    rm -f "$old"
  done
fi
