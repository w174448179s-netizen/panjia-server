#!/usr/bin/env bash
# ============================================================
# Task-0-5: CI 流水线总入口（包装层）
# 调用 mvn test 跑 Java 测试（ArchUnit + JUnit5），聚合结果
# Windows/Linux 通用，本地可直接 mvn compile/mvn test
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.."

echo "========== 1. 编译 =========="
mvn -q compile -pl panjia-modules/panjia-contracts,panjia-modules/panjia-import,panjia-modules/panjia-outbox,panjia-modules/panjia-common -am

echo "========== 2. 运行测试（ArchUnit + JUnit5）=========="
mvn -q test -pl panjia-modules/panjia-contracts,panjia-modules/panjia-import

echo "========== 3. 表名前缀校验 =========="
bash ci/check-table-prefix.sh

echo "========== CI 全部通过 =========="
