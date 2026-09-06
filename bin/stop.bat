@echo off
chcp 65001 >nul 2>&1
cd /d "%~dp0..\deploy\docker"

echo 停止盘家智管...
docker compose down

echo.
echo ==========================================
echo  ✓ 已停止
echo ==========================================
