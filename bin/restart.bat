@echo off
chcp 65001 >nul 2>&1
cd /d "%~dp0"

echo ==========================================
echo  重启盘家智管
echo ==========================================

call stop.bat
call start.bat
