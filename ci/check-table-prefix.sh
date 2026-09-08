#!/usr/bin/env bash
# ============================================================
# Task-0-5: CI 校验 - 表名前缀白名单
# 核心逻辑在 Java 测试（ArchUnit/JUnit5），此脚本为 CI 流水线包装
# 扫描所有 db/migration/*.sql 的 CREATE TABLE，校验表名前缀
# 白名单：pj_（盘家业务域）/ sys_ ruoyi_（RuoYi 原生放行）
# ============================================================
set -euo pipefail

# 扫描范围：所有模块的 db/migration 目录
SQL_DIRS=$(find . -path "*/db/migration/*.sql" -not -path "*/target/*" 2>/dev/null)

VALID_PREFIX_REGEX='^CREATE TABLE[[:space:]]+(IF NOT EXISTS[[:space:]]+)?(pj_|sys_|ruoyi_)[a-z_]+'

ERRORS=0
for sql in $SQL_DIRS; do
    # 提取 CREATE TABLE 语句的表名
    while IFS= read -r line; do
        # 转小写后匹配前缀
        lower=$(echo "$line" | tr '[:upper:]' '[:lower:]')
        if echo "$lower" | grep -qE 'create table'; then
            # 提取表名
            table=$(echo "$lower" | sed -E 's/.*create table[^a-z_]*//; s/[[:space:]]*(if not exists)?[[:space:]]*//; s/\(.*//; s/[[:space:]]*$//')
            if ! echo "$table" | grep -qE '^(pj_|sys_|ruoyi_)'; then
                echo "ERROR: $sql 非法表名前缀: '$table' (允许 pj_/sys_/ruoyi_)"
                ERRORS=$((ERRORS + 1))
            fi
        fi
    done < <(grep -i 'create table' "$sql")
done

if [ $ERRORS -gt 0 ]; then
    echo "FAIL: 表名前缀校验未通过，共 $ERRORS 处非法前缀"
    exit 1
fi

echo "PASS: 表名前缀校验通过"
exit 0
