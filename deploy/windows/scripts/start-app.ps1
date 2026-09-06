# ============================================================================
# 盘家智管 - 启动脚本
# ============================================================================

param(
    [string]$InstallDir = "C:\Program Files\Panjia"
)

$configDir = "$InstallDir\config"

if (-not (Test-Path "$configDir\docker-compose.yml")) {
    Write-Host "[错误] 未找到配置文件，请重新安装" -ForegroundColor Red
    Read-Host "按回车键退出"
    exit 1
}

Set-Location $configDir

Write-Host "启动盘家智管..." -ForegroundColor Cyan
docker compose up -d

# 等待启动
Write-Host "等待服务就绪..."
$maxWait = 120
$waited = 0
while ($waited -lt $maxWait) {
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost" -UseBasicParsing -TimeoutSec 3
        if ($resp.StatusCode -eq 200) {
            break
        }
    } catch {
        # 继续等待
    }
    Start-Sleep -Seconds 3
    $waited += 3
}

Write-Host ""
Write-Host "✓ 盘家智管已启动" -ForegroundColor Green
Write-Host "访问地址: http://localhost"
Write-Host ""

# 尝试打开浏览器
try {
    Start-Process "http://localhost"
} catch {
    Write-Host "请手动打开浏览器访问 http://localhost"
}

Start-Sleep -Seconds 2
