@echo off
chcp 65001 >nul 2>&1
cd /d "%~dp0..\deploy\docker"

if not exist ".env" (
    echo [ERROR] 未找到 .env，请先执行：bin\install.bat
    exit /b 1
)

echo 启动盘家智管...
docker compose up -d --wait --wait-timeout 300

echo.
echo 当前容器状态：
docker compose ps

echo.
for /f "tokens=2 delims==" %%v in ('findstr /B "WEB_PORT=" .env') do set "WP=%%v"
for /f "tokens=2 delims==" %%v in ('findstr /B "SERVER_PORT=" .env') do set "SP=%%v"
if "!WP!"=="" set "WP=80"
if "!SP!"=="" set "SP=8080"

echo ==========================================
echo  ✓ 启动完成
echo  前端地址：http://localhost:!WP!
echo  后端 API：http://localhost:!SP!
echo  查看日志：bin\logs.bat
echo ==========================================
