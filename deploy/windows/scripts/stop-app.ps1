# ============================================================================
# 盘家智管 - 停止脚本
# ============================================================================

param(
    [string]$InstallDir = "C:\Program Files\Panjia"
)

$configDir = "$InstallDir\config"

if (-not (Test-Path "$configDir\docker-compose.yml")) {
    Write-Host "[错误] 未找到配置文件" -ForegroundColor Red
    Read-Host "按回车键退出"
    exit 1
}

Set-Location $configDir

Write-Host "停止盘家智管..." -ForegroundColor Cyan
docker compose down

Write-Host ""
Write-Host "✓ 盘家智管已停止" -ForegroundColor Green
Start-Sleep -Seconds 2
