#!/bin/bash
# ============================================================================
# 盘家智管 — 本地出包（部署前跑一次）
#
# 产出两样东西（都在本目录，deploy.sh / update-backend.sh 直接用）：
#   1. panjia-server-latest.tar   后端 Docker 镜像
#   2. web/dist/                  前端静态文件
#
# 用法（Mac 本地执行）：
#   bash build.sh                # 后端 + 前端 全量出包
#   bash build.sh --backend      # 只出后端
#   bash build.sh --frontend     # 只出前端
# ============================================================================

set -euo pipefail

BACKEND=1
FRONTEND=1
case "${1:-}" in
    --backend)  FRONTEND=0 ;;
    --frontend) BACKEND=0 ;;
esac

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"          # panjia-server/deploy/docker
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"       # panjia-server/
UI_DIR="$(cd "$PROJECT_DIR/.." && pwd)/panjia-ui"    # panjia-ui/

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; NC='\033[0m'
ok()    { printf '%b[OK]%b    %s\n' "$GREEN" "$NC" "$*"; }
info()  { printf '%b[INFO]%b  %s\n' "$BLUE" "$NC" "$*"; }
fail()  { printf '%b[FAIL]%b  %s\n' "$RED" "$NC" "$*"; exit 1; }

echo ""
echo "=========================================="
echo " 盘家智管 — 本地出包"
echo "=========================================="

# ==================== 后端 ====================
if [ "$BACKEND" = "1" ]; then
    # Java 21
    if [ -z "${JAVA_HOME:-}" ]; then
        if [ -d "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ]; then
            export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
        elif command -v java >/dev/null 2>&1; then
            JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')
            export JAVA_HOME
        fi
    fi
    [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21' \
        || fail "需要 JDK 21：brew install --cask temurin@21"
    command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || fail "Docker 未运行"

    info ">>> Maven 打包（-Pprod，跳过测试）..."
    cd "$PROJECT_DIR"
    if command -v mvn >/dev/null 2>&1; then MVN="mvn"; else MVN="./mvnw"; chmod +x ./mvnw; fi
    $MVN clean package -Pprod -DskipTests -q
    [ -f "ruoyi-admin/target/ruoyi-admin.jar" ] || fail "打包失败"

    # 基础镜像预拉兜底：多镜像源 pull 后 retag，避免 daocloud 等源间歇 502 卡死构建
    BASE_IMAGE="eclipse-temurin:21-jre-jammy"
    if ! docker image inspect "$BASE_IMAGE" >/dev/null 2>&1; then
        info ">>> 预拉基础镜像 $BASE_IMAGE ..."
        PULLED=0
        for MIRROR in "docker.1ms.run/library" "docker.1panel.live/library" "docker.m.daocloud.io/library" ""; do
            SRC="${MIRROR:+$MIRROR/}$BASE_IMAGE"
            if docker pull "$SRC"; then
                [ -n "$MIRROR" ] && docker tag "$SRC" "$BASE_IMAGE"
                PULLED=1
                break
            fi
        done
        [ "$PULLED" = "1" ] || fail "基础镜像拉取失败：$BASE_IMAGE"
    fi

    info ">>> docker build..."
    docker build -t panjia-server:latest "$PROJECT_DIR"

    info ">>> docker save → panjia-server-latest.tar..."
    docker save -o "$SCRIPT_DIR/panjia-server-latest.tar" panjia-server:latest
    ok " 后端镜像：$(du -sh "$SCRIPT_DIR/panjia-server-latest.tar" | cut -f1)"
fi

# ==================== 前端 ====================
if [ "$FRONTEND" = "1" ]; then
    [ -d "$UI_DIR" ] || fail "前端目录不存在：$UI_DIR"
    command -v pnpm >/dev/null 2>&1 || command -v npx >/dev/null 2>&1 || fail "未找到 pnpm：npm install -g pnpm"

    info ">>> 前端构建（${UI_DIR}）..."
    cd "$UI_DIR"
    [ -d node_modules ] || { info " 安装依赖..."; if command -v pnpm >/dev/null 2>&1; then pnpm install; else npx pnpm install; fi; }
    if command -v pnpm >/dev/null 2>&1; then pnpm build; else npx pnpm build; fi
    [ -d dist ] || fail "前端构建失败"

    info ">>> 同步 dist → deploy/docker/web/dist..."
    rm -rf "$SCRIPT_DIR/web/dist"
    mkdir -p "$SCRIPT_DIR/web"
    cp -r "$UI_DIR/dist" "$SCRIPT_DIR/web/dist"
    ok " 前端 dist：$(du -sh "$SCRIPT_DIR/web/dist" | cut -f1)"
fi

echo ""
ok "出包完成，部署：bash deploy.sh <客户服务器IP>（日常发版用 update-backend.sh / update-frontend.sh）"
