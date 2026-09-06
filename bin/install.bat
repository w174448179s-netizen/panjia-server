@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

REM ============================================================================
REM 盘家智管一键安装脚本（Windows）
REM
REM 功能：
REM   1. 检查前置依赖（Docker、Java 21、pnpm）
REM   2. 构建后端 JAR + 前端 dist + Docker 镜像
REM   3. 生成 .env 配置（首次自动生成随机密码）
REM   4. 启动全部服务（PostgreSQL + Redis + 后端 + nginx）
REM   5. 健康检查
REM
REM 用法：
REM   bin\install.bat                    REM 默认安装
REM   bin\install.bat --skip-build       REM 跳过构建
REM   bin\install.bat --port 9090        REM 指定前端端口
REM ============================================================================

set "PROJECT_DIR=%~dp0.."
set "DEPLOY_DIR=%PROJECT_DIR%\deploy\docker"
set "ENV_FILE=%DEPLOY_DIR%\.env"
set "IMAGE_TAG=latest"
set "SKIP_BUILD=0"
set "RESET_ENV=0"
set "WEB_PORT="

REM 解析参数
:parse_args
if "%~1"=="" goto :done_args
if "%~1"=="--skip-build" set "SKIP_BUILD=1" & shift & goto :parse_args
if "%~1"=="--reset-env" set "RESET_ENV=1" & shift & goto :parse_args
if "%~1"=="--port" (
    shift
    set "WEB_PORT=%~1"
    shift
    goto :parse_args
)
shift
goto :parse_args
:done_args

echo ==========================================
echo  盘家智管一键安装
echo  平台：Windows %PROCESSOR_ARCHITECTURE%
echo ==========================================

REM ==================== 步骤 1：检查前置依赖 ====================
echo.
echo ^>^>^> 步骤 1/5：检查前置依赖

REM 1.1 Docker
echo  检查 Docker...
docker info >nul 2>&1
if errorlevel 1 (
    echo  [ERROR] Docker 未安装或未运行
    echo  请安装 Docker Desktop: https://www.docker.com/products/docker-desktop
    exit /b 1
)
for /f "tokens=3" %%v in ('docker --version 2^>^&1') do set "DOCKER_VER=%%v"
echo  ✓ Docker !DOCKER_VER!

REM 1.2 docker compose v2
echo  检查 docker compose...
docker compose version >nul 2>&1
if errorlevel 1 (
    echo  [ERROR] 未找到 docker compose v2 插件
    echo  请升级 Docker Desktop 到最新版本
    exit /b 1
)
echo  ✓ docker compose v2

REM 1.3 Java 21
if "!SKIP_BUILD!"=="0" (
    echo  检查 Java 21...
    java -version 2>&1 | findstr /C:"version \"21" >nul
    if errorlevel 1 (
        echo  [ERROR] 需要 JDK 21
        echo  请安装: https://adoptium.net/temurin/releases/?version=21
        exit /b 1
    )
    echo  ✓ Java 21

    REM 1.4 Maven
    echo  检查 Maven...
    call mvn --version >nul 2>&1
    if errorlevel 1 (
        if exist "%PROJECT_DIR%\mvnw.cmd" (
            echo  ✓ 使用 mvnw
        ) else (
            echo  [ERROR] 未找到 Maven
            echo  请安装: https://maven.apache.org/install.html
            exit /b 1
        )
    ) else (
        echo  ✓ Maven
    )

    REM 1.5 pnpm
    echo  检查 pnpm...
    set "UI_DIR=%PROJECT_DIR%\..\panjia-ui"
    if not exist "!UI_DIR!" (
        echo  [WARN] 未找到 panjia-ui 目录，跳过前端构建
        set "SKIP_FRONTEND=1"
    ) else (
        set "SKIP_FRONTEND=0"
        call pnpm --version >nul 2>&1
        if errorlevel 1 (
            echo  [WARN] 未找到 pnpm，尝试 npx
            call npx pnpm --version >nul 2>&1
            if errorlevel 1 (
                echo  [ERROR] 未找到 pnpm
                echo  请安装: npm install -g pnpm
                exit /b 1
            )
        )
        echo  ✓ pnpm
    )
)

REM ==================== 步骤 2：构建 ====================
if "!SKIP_BUILD!"=="0" (
    echo.
    echo ^>^>^> 步骤 2/5：构建（后端 + 前端 + Docker 镜像）
    cd /d "%PROJECT_DIR%"
    call bin\build.bat !IMAGE_TAG!
    if errorlevel 1 (
        echo  [ERROR] 构建失败
        exit /b 1
    )
) else (
    echo.
    echo ^>^>^> 步骤 2/5：跳过构建（--skip-build）
    docker image inspect panjia-server:!IMAGE_TAG! >nul 2>&1
    if errorlevel 1 (
        echo  [ERROR] 镜像 panjia-server:!IMAGE_TAG! 不存在
        exit /b 1
    )
    echo  ✓ 镜像已就绪
)

REM ==================== 步骤 3：生成 .env ====================
echo.
echo ^>^>^> 步骤 3/5：生成配置文件

if exist "!ENV_FILE!" if "!RESET_ENV!"=="0" (
    echo  [SKIP] .env 已存在
    goto :env_done
)

if "!RESET_ENV!"=="1" if exist "!ENV_FILE!" (
    echo  [FORCE] 重新生成 .env
)

REM 生成随机密码
for /f "delims=" %%i in ('powershell -Command "[guid]::NewGuid().ToString('N').Substring(0,24)"') do set "RANDOM_SUFFIX=%%i"
set "DB_USER=panjia"
set "DB_NAME=panjia"
set "DB_PASSWORD=Pg!RANDOM_SUFFIX!"
set "REDIS_PASSWORD=Rd!RANDOM_SUFFIX!"

if "!WEB_PORT!"=="" set "WEB_PORT=80"

(
    echo # 盘家智管环境配置（自动生成）
    echo.
    echo # PostgreSQL
    echo POSTGRES_USER=!DB_USER!
    echo POSTGRES_PASSWORD=!DB_PASSWORD!
    echo POSTGRES_DB=!DB_NAME!
    echo POSTGRES_PORT=5432
    echo.
    echo # Redis
    echo REDIS_PASSWORD=!REDIS_PASSWORD!
    echo REDIS_PORT=6379
    echo.
    echo # 镜像标签
    echo IMAGE_TAG=!IMAGE_TAG!
    echo.
    echo # 端口
    echo WEB_PORT=!WEB_PORT!
    echo SERVER_PORT=8080
    echo.
    echo # JVM 参数
    echo JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC
) > "!ENV_FILE!"
echo  ✓ .env 已生成

:env_done

REM 创建数据目录
if not exist "!DEPLOY_DIR!\data\postgres" mkdir "!DEPLOY_DIR!\data\postgres"
if not exist "!DEPLOY_DIR!\data\redis" mkdir "!DEPLOY_DIR!\data\redis"
if not exist "!DEPLOY_DIR!\data\panjia-license" mkdir "!DEPLOY_DIR!\data\panjia-license"
if not exist "!DEPLOY_DIR!\logs" mkdir "!DEPLOY_DIR!\logs"
if not exist "!DEPLOY_DIR!\web\dist" mkdir "!DEPLOY_DIR!\web\dist"
if not exist "!DEPLOY_DIR!\nginx" mkdir "!DEPLOY_DIR!\nginx"
echo  ✓ 数据目录已创建

REM ==================== 步骤 4：启动服务 ====================
echo.
echo ^>^>^> 步骤 4/5：启动服务

cd /d "!DEPLOY_DIR!"

echo  拉取依赖镜像...
docker compose pull postgres redis web >nul 2>&1

echo  启动容器...
docker compose up -d --wait --wait-timeout 300 --remove-orphans
if errorlevel 1 (
    echo  [ERROR] 启动失败
    exit /b 1
)

echo.
echo  当前容器状态：
docker compose ps

REM ==================== 步骤 5：健康检查 ====================
echo.
echo ^>^>^> 步骤 5/5：健康检查

REM 读取端口
for /f "tokens=2 delims==" %%v in ('findstr /B "WEB_PORT=" "!ENV_FILE!"') do set "HP_WEB_PORT=%%v"
for /f "tokens=2 delims==" %%v in ('findstr /B "SERVER_PORT=" "!ENV_FILE!"') do set "HP_SERVER_PORT=%%v"
if "!HP_WEB_PORT!"=="" set "HP_WEB_PORT=80"
if "!HP_SERVER_PORT!"=="" set "HP_SERVER_PORT=8080"

set "FAIL=0"

REM 前端
powershell -Command "try { $r = Invoke-WebRequest -Uri 'http://localhost:!HP_WEB_PORT!' -UseBasicParsing -TimeoutSec 10; Write-Host ' ✓ 前端访问正常（HTTP' $r.StatusCode '）' } catch { Write-Host ' [WARN] 前端未就绪'; set $? 1 }"
if errorlevel 1 set "FAIL=1"

REM 后端 API
set "SUCC=0"
for /l %%i in (1,1,15) do (
    if "!SUCC!"=="0" (
        powershell -Command "try { $r = Invoke-WebRequest -Uri 'http://localhost:!HP_SERVER_PORT!/actuator/health' -UseBasicParsing -TimeoutSec 5; if ($r.StatusCode -eq 200) { Write-Host ' ✓ 后端 API 正常（第 %%i/15 次）'; exit 0 } else { exit 1 } } catch { exit 1 }"
        if not errorlevel 1 set "SUCC=1"
        if "!SUCC!"=="0" timeout /t 2 /nobreak >nul
    )
)
if "!SUCC!"=="0" (
    echo  [WARN] 后端 API 未就绪
    echo  查看日志：bin\logs.bat
    set "FAIL=1"
)

REM ==================== 完成 ====================
echo.
echo ==========================================
if "!FAIL!"=="0" (
    echo  ✓ 安装完成！
) else (
    echo  ✓ 安装完成（部分健康检查未通过，请稍后访问）
)
echo ==========================================
echo.
echo  前端地址：http://localhost:!HP_WEB_PORT!
echo  后端 API：http://localhost:!HP_SERVER_PORT!
echo.
echo  运维命令：
echo    启动：bin\start.bat
echo    停止：bin\stop.bat
echo    重启：bin\restart.bat
echo    日志：bin\logs.bat
echo.
echo  ★ 重要信息（请保存）：
echo    数据库用户：!DB_USER!
echo    数据库名称：!DB_NAME!
echo    数据库密码：!DB_PASSWORD!
echo    Redis 密码：!REDIS_PASSWORD!
echo ==========================================

exit /b 0
