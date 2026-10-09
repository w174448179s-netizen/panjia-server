#!/usr/bin/env bash
# ============================================================
# panjia 数据库还原脚本（PostgreSQL / Docker）
#
# 用法:
#   ./restore.sh backups/postgres_20261009_120000.dump
#
# 可通过环境变量覆盖默认连接:
#   CONTAINER=postgres DB_NAME=postgres DB_USER=root DB_PASS=root ./restore.sh <dump文件>
#
# 行为:
#   - pg_restore --clean --if-exists: 先删除库中已存在对象再导入
#   - 还原前要求二次确认(输入 y)
#   - 还原后应用重启时 Flyway 会自动校验/补跑迁移
# ============================================================
set -euo pipefail

# ---- 默认配置(环境变量可覆盖) ----
CONTAINER="${CONTAINER:-postgres}"
DB_NAME="${DB_NAME:-postgres}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-root}"

DUMP_FILE="${1:-}"
if [ -z "$DUMP_FILE" ]; then
  echo "用法: $0 <备份文件.dump>"
  echo "示例: $0 backups/${DB_NAME}_20261009_120000.dump"
  exit 1
fi
if [ ! -s "$DUMP_FILE" ]; then
  echo "[错误] 备份文件不存在或为空: $DUMP_FILE" >&2
  exit 1
fi

# ---- 前置检查 ----
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "[错误] Docker 容器 [$CONTAINER] 未运行" >&2
  exit 1
fi

SIZE="$(du -h "$DUMP_FILE" | cut -f1 | tr -d ' ')"
echo "=========================================="
echo "  即将还原数据库, 现有数据将被覆盖!"
echo "=========================================="
echo "  容器:   $CONTAINER"
echo "  数据库: $DB_NAME"
echo "  备份:   $DUMP_FILE ($SIZE)"
echo "=========================================="
read -r -p "确认还原? 请输入 y 继续: " CONFIRM
if [ "$CONFIRM" != "y" ]; then
  echo "[取消] 未做任何修改"
  exit 0
fi

# ---- 还原 ----
# 先备份文件校验(只读列表, 验证归档完整性)
echo "[校验] 检查备份归档..."
docker exec -i -e PGPASSWORD="$DB_PASS" "$CONTAINER" \
  pg_restore -U "$DB_USER" --list < "$DUMP_FILE" > /dev/null

echo "[还原] 导入 $DUMP_FILE → $DB_NAME (可能需要数分钟)..."
docker exec -i -e PGPASSWORD="$DB_PASS" "$CONTAINER" \
  pg_restore -U "$DB_USER" -d "$DB_NAME" --clean --if-exists --no-owner --no-privileges \
  < "$DUMP_FILE"

echo "[完成] 还原成功: $DUMP_FILE → $DB_NAME"
echo "[提示] 重启应用后 Flyway 将自动校验迁移版本"
