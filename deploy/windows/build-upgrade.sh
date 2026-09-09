#!/bin/bash
# ============================================================================
# 盘家智管升级包构建脚本（Mac / Linux 版）
#
# 构建轻量升级包 panjia-upgrade.exe，面向已安装客户机的版本升级：
#   - 只包含：后端镜像 tar + 前端 dist + 配置/脚本
#   - 不包含：Docker Desktop 安装包、WSL 更新包、基础镜像（postgres/redis/nginx）
#   - 升级过程保留：数据库、密码（.env）、机器指纹（machine-id）、授权数据
#
# 用法：
#   sh build-upgrade.sh                     完整构建（前后端 + 镜像 + NSIS）
#   sh build-upgrade.sh --skip-build        跳过前后端构建（已有 dist 和 JAR）
#   sh build-upgrade.sh --nsis-only         只编译 NSIS EXE（资源已就绪）
#   sh build-upgrade.sh --version 1.2.0     指定升级包版本号（默认 1.1.0）
#
# 前置条件：
#   - Docker Desktop（运行 NSIS 编译器 + 构建镜像）
#   - JDK 21 + Maven（构建后端，--skip-build 可跳过）
#   - Node.js 20+ + npm（构建前端，--skip-build 可跳过）
# ============================================================================

set -e

# ==================== 寻找可用的 Node.js ====================
find_working_node() {
    local candidates=(
        "/usr/local/lib/nodejs/node-v20.20.2-darwin-x64/bin/node"
        "/opt/homebrew/bin/node"
        "/usr/local/bin/node"
    )
    for candidate in "${candidates[@]}"; do
        if [ -x "$candidate" ] && "$candidate" --version >/dev/null 2>&1; then
            echo "$candidate"
            return 0
        fi
    done
    if command -v node >/dev/null 2>&1 && node --version >/dev/null 2>&1; then
        command -v node
        return 0
    fi
    return 1
}

NODE_BIN=$(find_working_node || true)
if [ -n "$NODE_BIN" ]; then
    NODE_DIR=$(dirname "$NODE_BIN")
    export PATH="$NODE_DIR:$PATH"
    if ! command -v pnpm >/dev/null 2>&1 || ! pnpm --version >/dev/null 2>&1; then
        if command -v corepack >/dev/null 2>&1; then
            corepack enable >/dev/null 2>&1 || true
            corepack prepare pnpm@10.34.5 --activate >/dev/null 2>&1 || true
        fi
    fi
fi

PROJECT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
INSTALLER_DIR="$(cd "$(dirname "$0")" && pwd)"
UI_DIR="$(dirname "$PROJECT_DIR")/panjia-ui"
IMAGE_TAG="latest"
NSIS_IMAGE="zachdeibert/nsis:latest"

SKIP_BUILD=0
NSIS_ONLY=0
UPGRADE_VERSION="1.1.0"

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build) SKIP_BUILD=1 ;;
        --nsis-only) NSIS_ONLY=1 ;;
        --version)
            shift
            UPGRADE_VERSION="${1:-}"
            [ -n "$UPGRADE_VERSION" ] || { echo " [ERROR] --version 缺少参数"; exit 1; }
            ;;
        -h|--help)
            sed -n '2,20p' "$0"
            exit 0
            ;;
        *)
            echo " [ERROR] 未知参数: $1"
            exit 1
            ;;
    esac
    shift
done

echo "=========================================="
echo " 盘家智管升级包构建（Docker NSIS）"
echo " 版本：$UPGRADE_VERSION"
echo "=========================================="
echo ""

# ==================== 0. 检查 Docker ====================
echo "[0/5] 检查 Docker 环境..."
if ! docker info >/dev/null 2>&1; then
    echo " [ERROR] Docker 未运行，请先启动 Docker Desktop"
    exit 1
fi
echo " ✓ Docker 运行正常"
echo ""

if [ "$NSIS_ONLY" = "1" ]; then
    SKIP_BUILD=1
fi

# ==================== 1. 构建后端 ====================
if [ "$SKIP_BUILD" = "0" ]; then
    echo "[1/5] 构建后端 JAR..."
    cd "$PROJECT_DIR"

    if [ -z "$JAVA_HOME" ]; then
        if [ -d "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ]; then
            export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
        elif command -v java >/dev/null 2>&1; then
            JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')
            export JAVA_HOME
        fi
    fi

    # 修复 MapStruct Plus 增量标记文件（文件损坏会导致编译失败：ExceptionInInitializerError）
    # 内容必须是纯数字，损坏后重置为 0 即可
    mkdir -p ~/.msp
    echo "0" > ~/.msp/incrementMark

    mvn clean package -Pprod -DskipTests -q

    if [ ! -f "ruoyi-admin/target/ruoyi-admin.jar" ]; then
        echo " [ERROR] 后端构建失败"
        exit 1
    fi
    echo " ✓ 后端构建完成"
    echo ""
else
    echo "[1/5] 跳过后端构建"
    echo ""
fi

# ==================== 2. 构建前端 ====================
if [ "$SKIP_BUILD" = "0" ]; then
    echo "[2/5] 构建前端 dist..."
    if [ ! -d "$UI_DIR" ]; then
        echo " [ERROR] 未找到 panjia-ui 目录: $UI_DIR"
        exit 1
    fi
    cd "$UI_DIR"

    if command -v pnpm >/dev/null 2>&1 && pnpm --version >/dev/null 2>&1; then
        PKG_MANAGER="pnpm"
    elif command -v npm >/dev/null 2>&1; then
        PKG_MANAGER="npm"
    else
        echo " [ERROR] 未找到 pnpm 或 npm，请先安装 Node.js"
        exit 1
    fi

    if [ ! -d node_modules ]; then
        if [ "$PKG_MANAGER" = "pnpm" ]; then pnpm install; else npm install; fi
    fi
    if [ "$PKG_MANAGER" = "pnpm" ]; then pnpm build; else npm run build; fi

    if [ ! -d dist ]; then
        echo " [ERROR] 前端构建失败"
        exit 1
    fi
    echo " ✓ 前端构建完成"
    echo ""
else
    echo "[2/5] 跳过前端构建"
    echo ""
fi

# ==================== 3. 准备升级包资源 ====================
echo "[3/5] 准备升级包资源（前端 + 后端镜像）..."

mkdir -p "$INSTALLER_DIR/web/dist"
rm -rf "$INSTALLER_DIR/web/dist"/*

if [ -d "$UI_DIR/dist" ] && [ -n "$(ls -A "$UI_DIR/dist" 2>/dev/null)" ]; then
    cp -r "$UI_DIR/dist/"* "$INSTALLER_DIR/web/dist/"
    echo "  ✓ 前端文件已复制（$(du -sh "$INSTALLER_DIR/web/dist" | cut -f1)）"
else
    echo "  [ERROR] 未找到前端 dist 目录（panjia-ui/dist），请先构建前端"
    exit 1
fi

if [ "$NSIS_ONLY" = "0" ]; then
    echo "  构建后端 Docker 镜像..."
    cd "$PROJECT_DIR"
    docker build -t panjia-server:$IMAGE_TAG .

    mkdir -p "$INSTALLER_DIR/images"
    docker save -o "$INSTALLER_DIR/images/panjia-server.tar" panjia-server:$IMAGE_TAG
    SERVER_IMG_SIZE=$(du -h "$INSTALLER_DIR/images/panjia-server.tar" | cut -f1)
    echo "  ✓ 业务镜像已导出: images/panjia-server.tar ($SERVER_IMG_SIZE)"
else
    if [ ! -f "$INSTALLER_DIR/images/panjia-server.tar" ]; then
        echo "  [WARN] 未找到 images/panjia-server.tar，升级包将不含后端镜像"
        echo "         （纯前端/配置升级场景可用，否则请去掉 --nsis-only 重新构建）"
    else
        echo "  ✓ 使用已有镜像包: images/panjia-server.tar ($(du -h "$INSTALLER_DIR/images/panjia-server.tar" | cut -f1))"
    fi
fi
echo ""

# ==================== 4. 用 Docker 编译 NSIS ====================
echo "[4/5] 编译升级包 EXE（Docker NSIS）..."

cd "$INSTALLER_DIR"

if ! docker image inspect "$NSIS_IMAGE" >/dev/null 2>&1; then
    echo "  拉取 NSIS 编译镜像..."
    docker pull "$NSIS_IMAGE"
fi

# --- 中文编码处理（与 build-installer.sh 相同策略）---
BUILD_NSI_DIR="$INSTALLER_DIR/build_nsi_upgrade"
rm -rf "$BUILD_NSI_DIR"
mkdir -p "$BUILD_NSI_DIR"

cp -R "$INSTALLER_DIR"/config "$BUILD_NSI_DIR/" 2>/dev/null || true
cp -R "$INSTALLER_DIR"/nginx "$BUILD_NSI_DIR/" 2>/dev/null || true
cp -R "$INSTALLER_DIR"/scripts "$BUILD_NSI_DIR/" 2>/dev/null || true
cp -R "$INSTALLER_DIR"/web "$BUILD_NSI_DIR/" 2>/dev/null || true
cp -R "$INSTALLER_DIR"/images "$BUILD_NSI_DIR/" 2>/dev/null || true

# 升级包只打包业务镜像，剔除基础镜像 tar（客户机已装过）
for base in postgres redis nginx; do
    rm -f "$BUILD_NSI_DIR/images/${base}.tar"
done

iconv -f UTF-8 -t GB18030 "$INSTALLER_DIR/panjia-upgrade.nsi" > "$BUILD_NSI_DIR/panjia-upgrade.nsi"
echo "  ✓ NSIS 脚本已转码为 GB18030"

# .ps1 加 UTF-8 BOM（PowerShell 5.1 在中文 Windows 上按 GBK 解析无 BOM 的脚本）
ps1_files=$(find "$BUILD_NSI_DIR/scripts" -name "*.ps1" 2>/dev/null || true)
for f in $ps1_files; do
    printf '\xEF\xBB\xBF' > "${f}.bom"
    cat "$f" >> "${f}.bom"
    mv "${f}.bom" "$f"
done

docker run --rm \
    -v "$BUILD_NSI_DIR:/work" \
    -w /work \
    "$NSIS_IMAGE" \
    makensis -V2 -DUPGRADE_VERSION="$UPGRADE_VERSION" panjia-upgrade.nsi

if [ -f "$BUILD_NSI_DIR/panjia-upgrade.exe" ]; then
    mv "$BUILD_NSI_DIR/panjia-upgrade.exe" "$INSTALLER_DIR/panjia-upgrade.exe"
fi

rm -rf "$BUILD_NSI_DIR"

if [ ! -f "panjia-upgrade.exe" ]; then
    echo " [ERROR] 升级包 EXE 生成失败"
    exit 1
fi

EXE_SIZE=$(du -h panjia-upgrade.exe | cut -f1)
echo " ✓ 升级包已生成: panjia-upgrade.exe ($EXE_SIZE)"
echo ""

# ==================== 5. 完成 ====================
echo "=========================================="
echo " ✓ 升级包构建完成！"
echo "=========================================="
echo ""
echo " 输出文件：$INSTALLER_DIR/panjia-upgrade.exe"
echo " 文件大小：$EXE_SIZE"
echo " 升级版本：$UPGRADE_VERSION"
echo ""
echo " 与完整安装包的区别："
echo "   完整包 panjia-setup.exe  —— 全新客户机首次安装（约 1.2G）"
echo "   升级包 panjia-upgrade.exe —— 已装客户机版本升级（只有镜像 + 前端）"
echo ""
echo " 分发方式："
echo "   1. 将 panjia-upgrade.exe 发送给已安装的客户"
echo "   2. 客户双击运行（自动定位安装目录，数据/密码/授权全保留）"
echo "   3. 升级完成后强刷浏览器（Ctrl+F5）即可看到新版本"
echo "=========================================="
