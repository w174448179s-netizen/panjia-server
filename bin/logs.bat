@echo off
chcp 65001 >nul 2>&1
cd /d "%~dp0..\deploy\docker"

set "SERVICE=%~1"
if "%SERVICE%"=="" set "SERVICE=server"
shift
if "%~1"=="" (
    set "ARGS=-f --tail 100"
) else (
    set "ARGS=%*"
)

if "%SERVICE%"=="web" set "SERVICE=web"
if "%SERVICE%"=="frontend" set "SERVICE=web"
if "%SERVICE%"=="nginx" set "SERVICE=web"
if "%SERVICE%"=="db" set "SERVICE=postgres"
if "%SERVICE%"=="postgres" set "SERVICE=postgres"
if "%SERVICE%"=="database" set "SERVICE=postgres"
if "%SERVICE%"=="redis" set "SERVICE=redis"
if "%SERVICE%"=="backend" set "SERVICE=server"

docker compose logs %ARGS% %SERVICE%
