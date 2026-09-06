#!/bin/bash
#
# 构建 panjia-server 后端 + panjia-ui 前端
# 用法：
#   sh bin/build.sh [镜像标签]             # 构建后端 + 前端
#   sh bin/build.sh [镜像标签] --backend   # 只构建后端
#   sh bin/build.sh [镜像标签] --frontend  # 只构建前端
# 默认标签：latest
#

set -e

IMAGE_TAG="${1:-latest}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# panjia-ui 是 panjia-server 的同级目录
UI_DIR="$(dirname "$PROJECT_DIR")/panjia-ui"

BUILD_BACKEND=true
BUILD_FRONTEND=true

if [ "$2" = "--backend" ]; then
    BUILD_FRONTEND=false
elif [ "$2" = "--frontend" ]; then
    BUILD_BACKEND=false
fi

BACKEND_IMAGE="panjia-server:${IMAGE_TAG}"

echo "=========================================="
echo " 构建"
echo " 标签：$IMAGE_TAG"
if [ "$BUILD_BACKEND" = true ]; then
    echo " 后端镜像：$BACKEND_IMAGE"
fi
if [ "$BUILD_FRONTEND" = true ]; then
    echo " 前端：pnpm build → deploy/docker/web/dist/"
fi
echo "=========================================="

# 1. 后端：Maven 打包 + Docker 镜像
if [ "$BUILD_BACKEND" = true ]; then
    echo ""
    echo "[后端 1/2] Maven 打包..."
    cd "$PROJECT_DIR"

    if [ -z "$JAVA_HOME" ]; then
        if [ -d "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ]; then
            export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
            echo "[INFO] 自动检测 JAVA_HOME: $JAVA_HOME"
        elif command -v java >/dev/null 2>&1; then
            JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')
            export JAVA_HOME
            echo "[INFO] 自动检测 JAVA_HOME: $JAVA_HOME"
        else
            echo "[ERROR] 未设置 JAVA_HOME，且未检测到 Java 21"
            exit 1
        fi
    fi

    mvn clean package -Pprod -DskipTests -q

    if [ ! -f "ruoyi-admin/target/ruoyi-admin.jar" ]; then
        echo "[ERROR] 打包失败，未找到 ruoyi-admin/target/ruoyi-admin.jar"
        exit 1
    fi
    echo " ✓ Maven 打包完成"

    echo ""
    echo "[后端 2/2] 构建 Docker 镜像..."
    docker build -t "$BACKEND_IMAGE" .
    echo " ✓ 后端镜像构建完成：$BACKEND_IMAGE"
fi

# 2. 前端：pnpm build
if [ "$BUILD_FRONTEND" = true ]; then
    echo ""
    echo "[前端] pnpm build..."
    cd "$UI_DIR"

    if [ ! -d node_modules ]; then
        echo " 安装依赖..."
        if command -v pnpm >/dev/null 2>&1; then
            pnpm install
        elif command -v npx >/dev/null 2>&1; then
            npx pnpm install
        else
            echo "[ERROR] 未找到 pnpm，请先安装：npm install -g pnpm"
            exit 1
        fi
    fi

    if command -v pnpm >/dev/null 2>&1; then
        pnpm build
    else
        npx pnpm build
    fi

    if [ ! -d dist ]; then
        echo "[ERROR] 前端构建失败，未找到 $UI_DIR/dist/"
        exit 1
    fi

    # 复制前端构建产物到 deploy/docker/web/dist/
    mkdir -p "$PROJECT_DIR/deploy/docker/web/dist"
    rm -rf "$PROJECT_DIR/deploy/docker/web/dist"/*
    cp -r "$UI_DIR/dist/"* "$PROJECT_DIR/deploy/docker/web/dist/"

    echo " ✓ 前端构建完成：deploy/docker/web/dist/（$(du -sh "$PROJECT_DIR/deploy/docker/web/dist" | cut -f1)）"
fi

echo ""
echo "=========================================="
echo " ✓ 全部构建完成"
echo "=========================================="
if [ "$BUILD_BACKEND" = true ]; then
    echo " 后端镜像：$BACKEND_IMAGE"
fi
if [ "$BUILD_FRONTEND" = true ]; then
    echo " 前端文件：$PROJECT_DIR/deploy/docker/web/dist/"
fi
echo ""
echo "本地启动：sh bin/start.sh $IMAGE_TAG"
