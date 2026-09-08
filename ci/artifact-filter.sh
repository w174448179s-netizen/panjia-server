#!/usr/bin/env bash
# ============================================================
# Task-0-5: CI 构件打包 - 剔除全部 U*.sql（U-Undo 脚本）
# U 脚本仅本地调试，禁止进生产交付包（00 卡 §三）
# ============================================================
set -euo pipefail

# 交付包目录（CI 流水线传入，默认 target/deploy）
DEPLOY_DIR="${1:-target/deploy}"

if [ ! -d "$DEPLOY_DIR" ]; then
    echo "SKIP: 交付目录不存在 $DEPLOY_DIR"
    exit 0
fi

# 查找并删除所有 U*.sql
removed=0
while IFS= read -r -d '' f; do
    rm -f "$f"
    echo "REMOVE: 剔除 U 脚本 $(basename "$f")"
    removed=$((removed + 1))
done < <(find "$DEPLOY_DIR" -name 'U*.sql' -print0 2>/dev/null)

echo "PASS: U 脚本剔除完成，共删除 $removed 个文件"
exit 0
