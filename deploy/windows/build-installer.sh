#!/bin/bash
# ============================================================================
# 盘家智管安装器构建脚本（Mac / Linux 版）
#
# 用 Docker 运行 NSIS 编译 panjia-setup.exe，无需 Windows 电脑
# 默认离线安装模式：所有 Docker 镜像都打进 EXE，客户机无需联网
#
# 用法：
#   sh build-installer.sh                    完整离线安装包（默认）
#   sh build-installer.sh --online           在线安装包（EXE 小，安装时联网拉镜像）
#   sh build-installer.sh --skip-build       跳过前后端构建（已有 dist 和 JAR）
#   sh build-installer.sh --nsis-only        只编译 NSIS EXE（所有资源已就绪）
#
# 前置条件：
#   - Docker Desktop（运行 NSIS 编译器 + 构建镜像）
#   - JDK 21 + Maven（构建后端，--skip-build 可跳过）
#   - Node.js 20+ + npm（构建前端，--skip-build 可跳过）
# ============================================================================

set -e

# ==================== 寻找可用的 Node.js ====================
# TRAE 自带的 node 可能是 Linux 二进制，在 Mac 上跑不了
# 先找一个能正常工作的 node 和 pnpm/npm
find_working_node() {
    # 按优先级检查候选 node 路径
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

    # 最后检查 PATH 里的 node 是否能用
    if command -v node >/dev/null 2>&1 && node --version >/dev/null 2>&1; then
        command -v node
        return 0
    fi

    return 1
}

NODE_BIN=$(find_working_node || true)
if [ -n "$NODE_BIN" ]; then
    NODE_DIR=$(dirname "$NODE_BIN")
    # 把可用的 node 目录加到 PATH 最前面
    export PATH="$NODE_DIR:$PATH"
    echo "使用 Node.js: $(node --version) ($NODE_BIN)"

    # 检查 pnpm 是否可用（可能在 node 同目录或全局）
    if ! command -v pnpm >/dev/null 2>&1 || ! pnpm --version >/dev/null 2>&1; then
        # 尝试用 corepack 启用 pnpm
        if command -v corepack >/dev/null 2>&1; then
            echo "  通过 corepack 启用 pnpm..."
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

# 基础镜像列表（离线安装需要）
BASE_IMAGES=(
    "postgres:16.9"
    "redis:7-alpine"
    "nginx:stable-alpine"
)

SKIP_BUILD=0
ONLINE_MODE=0
NSIS_ONLY=0

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --online) ONLINE_MODE=1 ;;
        --nsis-only) NSIS_ONLY=1 ;;
        -h|--help)
            sed -n '2,20p' "$0"
            exit 0
            ;;
    esac
done

echo "=========================================="
echo " 盘家智管安装器构建（Docker NSIS）"
if [ "$ONLINE_MODE" = "1" ]; then
    echo " 模式：在线安装（客户端联网拉取镜像）"
else
    echo " 模式：离线安装（所有镜像已打包进 EXE）"
fi
echo "=========================================="
echo ""

# ==================== 0. 检查 Docker ====================
echo "[0/8] 检查 Docker 环境..."
if ! docker info >/dev/null 2>&1; then
    echo " [ERROR] Docker 未运行，请先启动 Docker Desktop"
    exit 1
fi
echo " ✓ Docker 运行正常"
echo ""

if [ "$NSIS_ONLY" = "1" ]; then
    echo "模式：仅编译 NSIS（--nsis-only）"
    echo ""
    STEP_START=6
    SKIP_BUILD=1
    SKIP_IMAGE_BUILD=1
else
    STEP_START=1
    SKIP_IMAGE_BUILD=0
fi

# ==================== 1. 构建后端 ====================
if [ "$SKIP_BUILD" = "0" ]; then
    echo "[$STEP_START/8] 构建后端 JAR..."
    cd "$PROJECT_DIR"

    if [ -z "$JAVA_HOME" ]; then
        if [ -d "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ]; then
            export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
            echo " 自动检测 JAVA_HOME: $JAVA_HOME"
        elif command -v java >/dev/null 2>&1; then
            JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk '{print $3}')
            export JAVA_HOME
        fi
    fi

    mvn clean package -Pprod -DskipTests -q

    if [ ! -f "ruoyi-admin/target/ruoyi-admin.jar" ]; then
        echo " [ERROR] 后端构建失败"
        exit 1
    fi
    echo " ✓ 后端构建完成"
    echo ""
else
    echo "[$STEP_START/8] 跳过后端构建（--skip-build）"
    echo ""
fi

# ==================== 2. 构建前端 ====================
if [ "$SKIP_BUILD" = "0" ]; then
    STEP_NUM=$((STEP_START + 1))
    echo "[$STEP_NUM/8] 构建前端 dist..."

    if [ ! -d "$UI_DIR" ]; then
        echo " [ERROR] 未找到 panjia-ui 目录: $UI_DIR"
        exit 1
    fi

    cd "$UI_DIR"

    # 优先用 pnpm，没有就用 npm
    if command -v pnpm >/dev/null 2>&1 && pnpm --version >/dev/null 2>&1; then
        PKG_MANAGER="pnpm"
    elif command -v npm >/dev/null 2>&1; then
        PKG_MANAGER="npm"
    else
        echo " [ERROR] 未找到 pnpm 或 npm，请先安装 Node.js"
        exit 1
    fi
    echo "  包管理器: $PKG_MANAGER"

    if [ ! -d node_modules ]; then
        echo "  安装前端依赖..."
        if [ "$PKG_MANAGER" = "pnpm" ]; then
            pnpm install
        else
            npm install
        fi
    fi

    if [ "$PKG_MANAGER" = "pnpm" ]; then
        pnpm build
    else
        npm run build
    fi

    if [ ! -d dist ]; then
        echo " [ERROR] 前端构建失败"
        exit 1
    fi
    echo " ✓ 前端构建完成"
    echo ""
else
    STEP_NUM=$STEP_START
fi

# ==================== 3. 复制前端到安装器 ====================
STEP_NUM=$((STEP_NUM + 1))
echo "[$STEP_NUM/8] 准备安装器资源..."

mkdir -p "$INSTALLER_DIR/web/dist"
rm -rf "$INSTALLER_DIR/web/dist"/*

if [ -d "$UI_DIR/dist" ] && [ -n "$(ls -A "$UI_DIR/dist" 2>/dev/null)" ]; then
    cp -r "$UI_DIR/dist/"* "$INSTALLER_DIR/web/dist/"
    echo " ✓ 前端文件已复制（$(du -sh "$INSTALLER_DIR/web/dist" | cut -f1)）"
else
    echo " [WARN] 未找到前端 dist 目录，使用占位文件"
    echo "<html><body>Placeholder</body></html>" > "$INSTALLER_DIR/web/dist/index.html"
fi
echo ""

# ==================== 3.5 准备 Docker Desktop 安装包（离线模式） ====================
STEP_NUM=$((STEP_NUM + 1))
if [ "$SKIP_IMAGE_BUILD" = "1" ]; then
    echo "[$STEP_NUM/8] 跳过 Docker Desktop 安装包（--nsis-only）"
    echo ""
elif [ "$ONLINE_MODE" = "0" ]; then
    echo "[$STEP_NUM/8] 准备 Docker Desktop 安装包（离线安装）..."

    DOCKER_INSTALLER="$INSTALLER_DIR/docker/Docker Desktop Installer.exe"
    mkdir -p "$INSTALLER_DIR/docker"

    if [ -f "$DOCKER_INSTALLER" ]; then
        size=$(du -h "$DOCKER_INSTALLER" | cut -f1)
        echo "  ✓ 已存在 Docker Desktop 安装包 ($size)"
    else
        DOCKER_URL="https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe"
        echo "  下载 Docker Desktop 安装包..."
        echo "  URL: $DOCKER_URL"
        echo "  约 600MB，需要一些时间..."

        if command -v curl >/dev/null 2>&1; then
            curl -L -o "$DOCKER_INSTALLER" "$DOCKER_URL" --progress-bar
        elif command -v wget >/dev/null 2>&1; then
            wget -O "$DOCKER_INSTALLER" "$DOCKER_URL" -q --show-progress
        else
            echo " [ERROR] 未找到 curl 或 wget，无法下载 Docker Desktop"
            exit 1
        fi

        if [ ! -f "$DOCKER_INSTALLER" ] || [ $(stat -f%z "$DOCKER_INSTALLER" 2>/dev/null || stat -c%s "$DOCKER_INSTALLER" 2>/dev/null || echo 0) -lt 100000000 ]; then
            echo " [ERROR] Docker Desktop 安装包下载失败或不完整"
            exit 1
        fi

        size=$(du -h "$DOCKER_INSTALLER" | cut -f1)
        echo "  ✓ Docker Desktop 安装包下载完成 ($size)"
    fi
    echo ""
else
    echo "[$STEP_NUM/8] 跳过 Docker Desktop 安装包（--online 在线模式）"
    # 清理旧的，避免误打包
    rm -rf "$INSTALLER_DIR/docker"
    echo ""
fi

# ==================== 4. 构建后端 Docker 镜像 ====================
STEP_NUM=$((STEP_NUM + 1))
if [ "$SKIP_IMAGE_BUILD" = "0" ]; then
    echo "[$STEP_NUM/8] 构建后端 Docker 镜像..."
    cd "$PROJECT_DIR"

    docker build -t panjia-server:$IMAGE_TAG .

    # 导出镜像 tar
    mkdir -p "$INSTALLER_DIR/images"
    docker save -o "$INSTALLER_DIR/images/panjia-server.tar" panjia-server:$IMAGE_TAG
    SERVER_IMG_SIZE=$(du -h "$INSTALLER_DIR/images/panjia-server.tar" | cut -f1)
    echo " ✓ 业务镜像已导出: images/panjia-server.tar ($SERVER_IMG_SIZE)"
    echo ""
else
    echo "[$STEP_NUM/8] 跳过 Docker 镜像构建（--nsis-only）"
    echo ""
fi

# ==================== 4.5 导出基础镜像（仅离线模式） ====================
STEP_NUM=$((STEP_NUM + 1))
if [ "$SKIP_IMAGE_BUILD" = "1" ]; then
    echo "[$STEP_NUM/8] 跳过基础镜像导出（--nsis-only）"
    echo ""
elif [ "$ONLINE_MODE" = "0" ]; then
    echo "[$STEP_NUM/8] 导出基础镜像（离线安装）..."

    for img in "${BASE_IMAGES[@]}"; do
        # 提取文件名：postgres:16.9 -> postgres.tar
        name=$(echo "$img" | cut -d: -f1)
        tar_file="$INSTALLER_DIR/images/${name}.tar"

        # 确保镜像已拉取
        echo "  处理 $img ..."
        if ! docker image inspect "$img" >/dev/null 2>&1; then
            echo "    拉取中..."
            docker pull "$img" -q
        fi

        # 导出为 tar
        docker save -o "$tar_file" "$img"
        size=$(du -h "$tar_file" | cut -f1)
        echo "    ✓ ${name}.tar ($size)"
    done

    # 计算总大小
    TOTAL_SIZE=$(du -sh "$INSTALLER_DIR/images" | cut -f1)
    echo " ✓ 全部镜像导出完成，总大小: $TOTAL_SIZE"
    echo ""
else
    echo "[$STEP_NUM/8] 跳过基础镜像导出（--online 在线模式）"
    echo "  客户端安装时从 Docker Hub 拉取基础镜像"
    # 清理旧的基础镜像 tar（如果有），避免误打包
    for img in "${BASE_IMAGES[@]}"; do
        name=$(echo "$img" | cut -d: -f1)
        rm -f "$INSTALLER_DIR/images/${name}.tar"
    done
    echo ""
fi

# ==================== 5. 用 Docker 编译 NSIS ====================
STEP_NUM=$((STEP_NUM + 1))
echo "[$STEP_NUM/8] 编译安装器 EXE（Docker NSIS）..."

cd "$INSTALLER_DIR"

# 检查 NSIS 镜像
if ! docker image inspect "$NSIS_IMAGE" >/dev/null 2>&1; then
    echo "  拉取 NSIS 编译镜像..."
    docker pull "$NSIS_IMAGE"
fi

# --- 中文编码处理 ---
# NSIS 2.x 是 ANSI 版本，在中文 Windows 上显示中文需要 GBK/GB18030 编码
# 构建时把 .nsi 和 .txt 等文本文件转成 GB18030，放到 build_nsi/ 目录再编译
# GB18030 是 GBK 的超集，支持更多字符，中文 Windows 原生支持
BUILD_NSI_DIR="$INSTALLER_DIR/build_nsi"
rm -rf "$BUILD_NSI_DIR"
mkdir -p "$BUILD_NSI_DIR"

# 复制所有文件到构建目录
cp -R "$INSTALLER_DIR"/config "$BUILD_NSI_DIR/" 2>/dev/null || true
cp -R "$INSTALLER_DIR"/scripts "$BUILD_NSI_DIR/" 2>/dev/null || true
cp -R "$INSTALLER_DIR"/web "$BUILD_NSI_DIR/" 2>/dev/null || true
cp -R "$INSTALLER_DIR"/images "$BUILD_NSI_DIR/" 2>/dev/null || true
cp -R "$INSTALLER_DIR"/docker "$BUILD_NSI_DIR/" 2>/dev/null || true

# 转换 .nsi 脚本为 GB18030
iconv -f UTF-8 -t GB18030 "$INSTALLER_DIR/panjia-setup.nsi" > "$BUILD_NSI_DIR/panjia-setup.nsi"
echo "  ✓ NSIS 脚本已转码为 GB18030"

# 转换 license.txt 为 GB18030（如果存在）
if [ -f "$INSTALLER_DIR/license.txt" ]; then
    iconv -f UTF-8 -t GB18030 "$INSTALLER_DIR/license.txt" > "$BUILD_NSI_DIR/license.txt"
    echo "  ✓ license.txt 已转码为 GB18030"
fi

# 转换 .ps1 脚本为 UTF-8 with BOM
# PowerShell 5.1 在中文 Windows 上默认用 GBK 读文件，必须有 BOM 才会按 UTF-8 解析
# 否则脚本里的中文和特殊字符会导致解析错误
ps1_files=$(find "$BUILD_NSI_DIR/scripts" -name "*.ps1" 2>/dev/null || true)
for f in $ps1_files; do
    # 加 UTF-8 BOM (EF BB BF)
    printf '\xEF\xBB\xBF' > "${f}.bom"
    cat "$f" >> "${f}.bom"
    mv "${f}.bom" "$f"
done
ps1_count=$(echo "$ps1_files" | grep -c . 2>/dev/null || echo 0)
echo "  ✓ 已为 $ps1_count 个 PS1 脚本添加 UTF-8 BOM"

# 用 Docker 运行 makensis（从 build_nsi 目录编译）
# -V2：详细程度 2（显示警告和错误）
docker run --rm \
    -v "$BUILD_NSI_DIR:/work" \
    -w /work \
    "$NSIS_IMAGE" \
    makensis -V2 panjia-setup.nsi

# 把生成的 EXE 移回主目录
if [ -f "$BUILD_NSI_DIR/panjia-setup.exe" ]; then
    mv "$BUILD_NSI_DIR/panjia-setup.exe" "$INSTALLER_DIR/panjia-setup.exe"
fi

# 清理构建目录
rm -rf "$BUILD_NSI_DIR"

if [ ! -f "panjia-setup.exe" ]; then
    echo " [ERROR] EXE 生成失败"
    exit 1
fi

EXE_SIZE=$(du -h panjia-setup.exe | cut -f1)
echo " ✓ EXE 已生成: panjia-setup.exe ($EXE_SIZE)"
echo ""

# ==================== 6. 完成 ====================
STEP_NUM=$((STEP_NUM + 1))
echo "=========================================="
echo " ✓ 安装器构建完成！"
echo "=========================================="
echo ""
echo " 输出文件：$INSTALLER_DIR/panjia-setup.exe"
echo " 文件大小：$EXE_SIZE"
if [ "$ONLINE_MODE" = "1" ]; then
    echo " 安装模式：在线安装（客户机需联网）"
else
    echo " 安装模式：离线安装（客户机无需联网）"
fi
echo ""
echo " 分发方式："
echo "   1. 将 panjia-setup.exe 发送给客户"
echo "   2. 客户在 Windows 电脑上双击运行"
echo "   3. 输入授权码 → 等待安装 → 自动打开浏览器"
echo ""
echo " 构建参数："
echo "   --skip-build   跳过前后端构建"
echo "   --online       在线安装模式（EXE 较小，客户机需联网）"
echo "   --nsis-only    仅编译 NSIS EXE"
echo "=========================================="
