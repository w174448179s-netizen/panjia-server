#!/bin/bash
# ============================================================================
# 盘家智管一键安装脚本（Mac / Linux）
#
# 功能：
#   1. 检查前置依赖（Docker、Java 21、pnpm）
#   2. 构建后端 JAR + 前端 dist + Docker 镜像
#   3. 生成 .env 配置（首次自动生成随机密码）
#   4. 启动全部服务（PostgreSQL + Redis + 后端 + nginx）
#   5. 健康检查
#
# 用法：
#   sh bin/install.sh                    # 默认安装
#   sh bin/install.sh --skip-build       # 跳过构建（已有镜像时用）
#   sh bin/install.sh --port 8080       # 指定前端端口
#   sh bin/install.sh --reset-env        # 强制重新生成 .env
#
# 前置条件：
#   - 已安装 Docker Desktop（Mac/Windows）或 Docker Engine（Linux）
#   - 已安装 JDK 21 + Maven
#   - 已安装 Node.js 20+ + pnpm
# ============================================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY_DIR="$PROJECT_DIR/deploy/docker"
ENV_FILE="$DEPLOY_DIR/.env"

SKIP_BUILD=0
RESET_ENV=0
WEB_PORT=""
IMAGE_TAG="latest"

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --reset-env) RESET_ENV=1 ;;
        --port) shift_next=1 ;;
        *)
            if [ "$shift_next" = "1" ]; then
                WEB_PORT="$arg"
                shift_next=0
            fi
            ;;
    esac
done

echo "=========================================="
echo " 盘家智管一键安装"
echo " 平台：$(uname -s) $(uname -m)"
echo "=========================================="

# ==================== 步骤 1：检查前置依赖 ====================
echo ""
echo ">>> 步骤 1/5：检查前置依赖"

# 1.1 Docker
echo " 检查 Docker..."
if ! command -v docker >/dev/null 2>&1; then
    echo " [ERROR] 未安装 Docker"
    echo " Mac:   请安装 Docker Desktop → https://www.docker.com/products/docker-desktop"
    echo " Linux: curl -fsSL https://get.docker.com | sh"
    exit 1
fi
if ! docker info >/dev/null 2>&1; then
    echo " [ERROR] Docker 未运行，请先启动 Docker"
    echo " Mac:   打开 Docker Desktop 应用"
    echo " Linux: sudo systemctl start docker"
    exit 1
fi
echo " ✓ Docker $(docker --version | awk '{print $3}' | sed 's/,//')"

# 1.2 docker compose v2
echo " 检查 docker compose..."
if docker compose version >/dev/null 2>&1; then
    echo " ✓ docker compose v2"
else
    echo " [ERROR] 未找到 docker compose v2 插件"
    echo " 请安装 docker-compose-plugin 或升级 Docker"
    exit 1
fi

# 1.3 Java 21（仅在需要构建时检查）
if [ "$SKIP_BUILD" = "0" ]; then
    echo " 检查 Java 21..."
    if [ -z "$JAVA_HOME" ]; then
        if [ -d "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ]; then
            export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
        elif command -v java >/dev/null 2>&1; then
            JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')
            export JAVA_HOME
        fi
    fi
    if [ -z "$JAVA_HOME" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
        echo " [ERROR] 需要 JDK 21"
        echo " Mac:   brew install --cask temurin@21"
        echo " Linux: sudo apt install temurin-21-jdk 或 sudo dnf install java-21-temurin"
        exit 1
    fi
    echo " ✓ Java 21"

    # 1.4 Maven
    echo " 检查 Maven..."
    if command -v mvn >/dev/null 2>&1; then
        echo " ✓ Maven $(mvn --version 2>/dev/null | head -1 | awk '{print $3}')"
    else
        echo " [WARN] 未找到 mvn，尝试使用项目自带 mvnw"
        if [ -f "$PROJECT_DIR/mvnw" ]; then
            chmod +x "$PROJECT_DIR/mvnw"
            echo " ✓ 使用 mvnw"
        else
            echo " [ERROR] 未找到 Maven，请安装：https://maven.apache.org/install.html"
            exit 1
        fi
    fi

    # 1.5 pnpm / Node.js
    echo " 检查 Node.js + pnpm..."
    UI_DIR="$(dirname "$PROJECT_DIR")/panjia-ui"
    if [ ! -d "$UI_DIR" ]; then
        echo " [WARN] 未找到 panjia-ui 目录（$UI_DIR），跳过前端构建"
        SKIP_FRONTEND=1
    else
        SKIP_FRONTEND=0
        if command -v pnpm >/dev/null 2>&1; then
            echo " ✓ pnpm $(pnpm --version)"
        elif command -v npx >/dev/null 2>&1; then
            echo " ✓ npx 可用（将通过 npx 调用 pnpm）"
        else
            echo " [ERROR] 未找到 pnpm，请先安装：npm install -g pnpm"
            exit 1
        fi
    fi
fi

# ==================== 步骤 2：构建 ====================
if [ "$SKIP_BUILD" = "0" ]; then
    echo ""
    echo ">>> 步骤 2/5：构建（后端 + 前端 + Docker 镜像）"
    sh "$PROJECT_DIR/bin/build.sh" "$IMAGE_TAG"
else
    echo ""
    echo ">>> 步骤 2/5：跳过构建（--skip-build）"
    if ! docker image inspect "panjia-server:$IMAGE_TAG" >/dev/null 2>&1; then
        echo " [ERROR] 镜像 panjia-server:$IMAGE_TAG 不存在，请去掉 --skip-build 重新执行"
        exit 1
    fi
    echo " ✓ 镜像已就绪"
fi

# ==================== 步骤 3：生成 .env ====================
echo ""
echo ">>> 步骤 3/5：生成配置文件"

if [ -f "$ENV_FILE" ] && [ "$RESET_ENV" = "0" ]; then
    echo " [SKIP] .env 已存在（如需重置：sh bin/install.sh --reset-env）"
else
    if [ "$RESET_ENV" = "1" ] && [ -f "$ENV_FILE" ]; then
        echo " [FORCE] 重新生成 .env"
    fi

    # 生成随机密码
    DB_USER="panjia"
    DB_NAME="panjia"
    DB_PASSWORD="Pg$(openssl rand -hex 12)!"
    REDIS_PASSWORD="Rd$(openssl rand -hex 12)!"

    cat > "$ENV_FILE" << EOF
# 盘家智管环境配置（自动生成于 $(date '+%Y-%m-%d %H:%M:%S')）

# PostgreSQL
POSTGRES_USER=$DB_USER
POSTGRES_PASSWORD=$DB_PASSWORD
POSTGRES_DB=$DB_NAME
POSTGRES_PORT=5432

# Redis
REDIS_PASSWORD=$REDIS_PASSWORD
REDIS_PORT=6379

# 镜像标签
IMAGE_TAG=$IMAGE_TAG

# 端口
WEB_PORT=${WEB_PORT:-80}
SERVER_PORT=8080

# JVM 参数
JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC
EOF
    chmod 600 "$ENV_FILE"
    echo " ✓ .env 已生成"
fi

# 创建数据目录
mkdir -p "$DEPLOY_DIR/data/postgres" "$DEPLOY_DIR/data/redis" "$DEPLOY_DIR/data/panjia-license"
mkdir -p "$DEPLOY_DIR/logs" "$DEPLOY_DIR/web/dist" "$DEPLOY_DIR/nginx"
echo " ✓ 数据目录已创建"

# ==================== 步骤 4：启动服务 ====================
echo ""
echo ">>> 步骤 4/5：启动服务"

cd "$DEPLOY_DIR"

# 拉取依赖镜像
echo " 拉取依赖镜像..."
docker compose pull postgres redis web 2>/dev/null || true

# 启动（--wait 等健康检查通过）
echo " 启动容器（--wait 等健康检查通过，最多 5 分钟）..."
docker compose up -d --wait --wait-timeout 300 --remove-orphans

echo ""
echo " 当前容器状态："
docker compose ps

# ==================== 步骤 5：健康检查 ====================
echo ""
echo ">>> 步骤 5/5：健康检查"

# 读取 .env 中的端口
. "$ENV_FILE"
WEB_PORT="${WEB_PORT:-80}"

FAIL=0

# 前端
HTTP_CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "http://localhost:${WEB_PORT}" 2>/dev/null || echo "000")
if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "301" ]; then
    echo " ✓ 前端访问正常（HTTP $HTTP_CODE）"
else
    echo " [WARN] 前端返回 $HTTP_CODE（可能后端还在启动中）"
    FAIL=1
fi

# 后端 API（重试）
SUCC=0
for i in $(seq 1 15); do
    HEALTH_CODE=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "http://localhost:${SERVER_PORT:-8080}/actuator/health" 2>/dev/null || echo "000")
    if [ "$HEALTH_CODE" = "200" ]; then
        echo " ✓ 后端 API 正常（第 $i/15 次）"
        SUCC=1
        break
    fi
    sleep 2
done
if [ "$SUCC" = "0" ]; then
    echo " [WARN] 后端 API 未就绪（返回 $HEALTH_CODE）"
    echo " 查看日志：sh bin/logs.sh"
    FAIL=1
fi

# ==================== 完成 ====================
echo ""
echo "=========================================="
if [ "$FAIL" = "0" ]; then
    echo " ✓ 安装完成！"
else
    echo " ✓ 安装完成（部分健康检查未通过，请稍后访问）"
fi
echo "=========================================="
echo ""
echo " 前端地址：http://localhost:${WEB_PORT}"
echo " 后端 API：http://localhost:${SERVER_PORT:-8080}"
echo " 数据库：  localhost:${POSTGRES_PORT:-5432}/${POSTGRES_DB}"
echo " Redis：   localhost:${REDIS_PORT:-6379}"
echo ""
echo " 运维命令："
echo "   启动：sh bin/start.sh"
echo "   停止：sh bin/stop.sh"
echo "   重启：sh bin/restart.sh"
echo "   日志：sh bin/logs.sh"
echo ""
echo " ★ 重要信息（请保存）："
echo "   数据库用户：$DB_USER"
echo "   数据库名称：$DB_NAME"
if [ -f "$ENV_FILE" ]; then
    . "$ENV_FILE"
    echo "   数据库密码：$POSTGRES_PASSWORD"
    echo "   Redis 密码：$REDIS_PASSWORD"
fi
echo "=========================================="
