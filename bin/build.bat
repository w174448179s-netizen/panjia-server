@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

REM 构建 panjia-server 后端 + panjia-ui 前端
REM 用法：bin\build.bat [镜像标签] [--backend|--frontend]

set "IMAGE_TAG=%~1"
if "%IMAGE_TAG%"=="" set "IMAGE_TAG=latest"
set "PROJECT_DIR=%~dp0.."
set "UI_DIR=%PROJECT_DIR%\..\panjia-ui"
set "BACKEND_IMAGE=panjia-server:%IMAGE_TAG%"

set "BUILD_BACKEND=1"
set "BUILD_FRONTEND=1"
if "%~2"=="--backend" set "BUILD_FRONTEND=0"
if "%~2"=="--frontend" set "BUILD_BACKEND=0"

echo ==========================================
echo  构建
echo  标签：%IMAGE_TAG%
if "!BUILD_BACKEND!"=="1" echo  后端镜像：!BACKEND_IMAGE!
if "!BUILD_FRONTEND!"=="1" echo  前端：pnpm build → deploy\docker\web\dist\
echo ==========================================

REM 1. 后端：Maven 打包 + Docker 镜像
if "!BUILD_BACKEND!"=="1" (
    echo.
    echo [后端 1/2] Maven 打包...
    cd /d "%PROJECT_DIR%"

    if "!JAVA_HOME!"=="" (
        for /f "delims=" %%j in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr "java.home"') do (
            for /f "tokens=3" %%h in ("%%j") do set "JAVA_HOME=%%h"
        )
        if not "!JAVA_HOME!"=="" (
            echo [INFO] 自动检测 JAVA_HOME: !JAVA_HOME!
        )
    )

    if exist mvnw.cmd (
        call mvnw.cmd clean package -Pprod -DskipTests -q
    ) else (
        call mvn clean package -Pprod -DskipTests -q
    )
    if errorlevel 1 (
        echo [ERROR] Maven 打包失败
        exit /b 1
    )
    if not exist "ruoyi-admin\target\ruoyi-admin.jar" (
        echo [ERROR] 未找到 ruoyi-admin\target\ruoyi-admin.jar
        exit /b 1
    )
    echo  ✓ Maven 打包完成

    echo.
    echo [后端 2/2] 构建 Docker 镜像...
    docker build -t "!BACKEND_IMAGE!" .
    if errorlevel 1 (
        echo [ERROR] Docker 构建失败
        exit /b 1
    )
    echo  ✓ 后端镜像构建完成：!BACKEND_IMAGE!
)

REM 2. 前端：pnpm build
if "!BUILD_FRONTEND!"=="1" (
    echo.
    echo [前端] pnpm build...
    cd /d "!UI_DIR!"

    if not exist node_modules (
        echo  安装依赖...
        call pnpm install
        if errorlevel 1 (
            echo [ERROR] pnpm install 失败
            exit /b 1
        )
    )

    call pnpm build
    if errorlevel 1 (
        echo [ERROR] 前端构建失败
        exit /b 1
    )
    if not exist dist (
        echo [ERROR] 未找到 dist 目录
        exit /b 1
    )

    REM 复制前端构建产物
    if not exist "%PROJECT_DIR%\deploy\docker\web\dist" mkdir "%PROJECT_DIR%\deploy\docker\web\dist"
    rmdir /s /q "%PROJECT_DIR%\deploy\docker\web\dist" >nul 2>&1
    mkdir "%PROJECT_DIR%\deploy\docker\web\dist"
    xcopy /e /i /q "dist\*" "%PROJECT_DIR%\deploy\docker\web\dist\" >nul
    echo  ✓ 前端构建完成
)

echo.
echo ==========================================
echo  ✓ 全部构建完成
echo ==========================================
if "!BUILD_BACKEND!"=="1" echo  后端镜像：!BACKEND_IMAGE!
if "!BUILD_FRONTEND!"=="1" echo  前端文件：%PROJECT_DIR%\deploy\docker\web\dist\
echo.
echo  本地启动：bin\start.bat %IMAGE_TAG%

exit /b 0
