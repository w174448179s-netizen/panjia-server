@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

REM ============================================================================
REM 盘家智管安装器构建脚本（Windows）
REM
REM 功能：
REM   1. 构建后端 JAR
REM   2. 构建前端 dist
REM   3. 构建 Docker 镜像并导出为 tar
REM   4. 复制所有文件到安装器目录
REM   5. 调用 NSIS 编译生成 EXE
REM
REM 用法：
REM   build-installer.bat                    完整构建
REM   build-installer.bat --skip-build       跳过前后端构建（已有镜像时）
REM   build-installer.bat --skip-docker      跳过 Docker 镜像打包
REM
REM 前置条件：
REM   - JDK 21 + Maven
REM   - Node.js 20+ + pnpm
REM   - Docker Desktop
REM   - NSIS（makensis 命令可用）
REM ============================================================================

set "PROJECT_DIR=%~dp0..\.."
set "INSTALLER_DIR=%~dp0"
set "UI_DIR=%PROJECT_DIR%\..\panjia-ui"
set "IMAGE_TAG=latest"
set "SKIP_BUILD=0"
set "SKIP_DOCKER=0"

REM 解析参数
:parse_args
if "%~1"=="" goto :done_args
if "%~1"=="--skip-build" set "SKIP_BUILD=1" & shift & goto :parse_args
if "%~1"=="--skip-docker" set "SKIP_DOCKER=1" & shift & goto :parse_args
shift
goto :parse_args
:done_args

echo ==========================================
echo  盘家智管安装器构建
echo ==========================================
echo.

REM ==================== 检查 NSIS ====================
echo [1/6] 检查 NSIS...
where makensis >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 未找到 NSIS（makensis）
    echo 请安装 NSIS: https://nsis.sourceforge.io/Download
    echo 安装后确保 makensis 在 PATH 中
    exit /b 1
)
for /f "tokens=3" %%v in ('makensis /VERSION 2^>^&1') do set "NSIS_VER=%%v"
echo  ✓ NSIS: !NSIS_VER!
echo.

REM ==================== 构建后端 ====================
if "!SKIP_BUILD!"=="0" (
    echo [2/6] 构建后端 JAR...
    cd /d "%PROJECT_DIR%"
    call mvn clean package -Pprod -DskipTests -q
    if errorlevel 1 (
        echo [ERROR] 后端构建失败
        exit /b 1
    )
    echo  ✓ 后端构建完成
    echo.
) else (
    echo [2/6] 跳过后端构建（--skip-build）
    echo.
)

REM ==================== 构建前端 ====================
if "!SKIP_BUILD!"=="0" (
    echo [3/6] 构建前端 dist...
    if not exist "%UI_DIR%" (
        echo [ERROR] 未找到 panjia-ui 目录: %UI_DIR%
        exit /b 1
    )
    cd /d "%UI_DIR%"
    call pnpm build
    if errorlevel 1 (
        echo [ERROR] 前端构建失败
        exit /b 1
    )
    echo  ✓ 前端构建完成
    echo.
) else (
    echo [3/6] 跳过前端构建（--skip-build）
    echo.
)

REM ==================== 复制前端到安装器 ====================
echo [4/6] 准备安装器资源...

if not exist "%INSTALLER_DIR%\web\dist" mkdir "%INSTALLER_DIR%\web\dist"
rmdir /s /q "%INSTALLER_DIR%\web\dist" >nul 2>&1
mkdir "%INSTALLER_DIR%\web\dist"

if exist "%UI_DIR%\dist" (
    xcopy /e /i /q /y "%UI_DIR%\dist\*" "%INSTALLER_DIR%\web\dist\" >nul
    echo  ✓ 前端文件已复制
) else (
    echo [WARN] 未找到前端 dist 目录
)
echo.

REM ==================== 构建 Docker 镜像 ====================
if "!SKIP_DOCKER!"=="0" (
    echo [5/6] 构建 Docker 镜像...
    cd /d "%PROJECT_DIR%"

    docker build -t panjia-server:%IMAGE_TAG% .
    if errorlevel 1 (
        echo [ERROR] Docker 镜像构建失败
        exit /b 1
    )

    REM 导出镜像 tar（安装时加载）
    if not exist "%INSTALLER_DIR%\images" mkdir "%INSTALLER_DIR%\images"
    docker save -o "%INSTALLER_DIR%\images\panjia-server.tar" panjia-server:%IMAGE_TAG%
    echo  ✓ Docker 镜像已导出: images\panjia-server.tar
    echo.
) else (
    echo [5/6] 跳过 Docker 镜像打包（--skip-docker）
    echo.
)

REM ==================== 编译 NSIS 安装器 ====================
echo [6/6] 编译安装器 EXE...
cd /d "%INSTALLER_DIR%"

makensis /V2 panjia-setup.nsi
if errorlevel 1 (
    echo [ERROR] 安装器编译失败
    exit /b 1
)

echo.
echo ==========================================
echo  ✓ 安装器构建完成！
echo ==========================================
echo.
echo  输出文件: %INSTALLER_DIR%panjia-setup.exe
echo.
echo  使用方法：
echo    1. 将 panjia-setup.exe 分发给客户
echo    2. 客户双击运行
echo    3. 输入授权码
echo    4. 等待安装完成
echo ==========================================

exit /b 0
