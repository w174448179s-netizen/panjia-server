# ============================================================================
# 盘家智管 - 启动脚本
#
# 功能：
#   1. 确保 Docker Desktop 已运行（未运行则自动拉起）
#   2. 等待 Docker daemon 就绪
#   3. 启动所有容器（docker compose up -d）
#   4. 等待 Web 服务可访问
#
# 使用场景：
#   - 用户手动启动（开始菜单快捷方式）
#   - 开机自启（Windows 计划任务，用户登录时触发）
# ============================================================================

param(
    [string]$InstallDir = "C:\Program Files\Panjia",
    [switch]$AutoStart
)

$ErrorActionPreference = "Stop"

$configDir = "$InstallDir\config"

if (-not (Test-Path "$configDir\docker-compose.yml")) {
    Write-Host "[错误] 未找到配置文件，请重新安装" -ForegroundColor Red
    if (-not $AutoStart) { Read-Host "按回车键退出" }
    exit 1
}

$logFile = "$InstallDir\logs\start-app.log"
$logDir = Split-Path $logFile
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }

function Write-Log {
    param([string]$Message)
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$ts] $Message"
    Add-Content -Path $logFile -Value $line
    Write-Host $line
}

# ==================== 1. 确保 Docker Desktop 已运行 ====================
function Test-DockerRunning {
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        docker info 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $prevEap
    }
    return ($LASTEXITCODE -eq 0)
}

if (-not (Test-DockerRunning)) {
    Write-Log "Docker daemon 未运行，启动 Docker Desktop..."

    $ProgramFilesNative = if (${env:ProgramW6432}) { ${env:ProgramW6432} } else { $env:ProgramFiles }
    $ddExe = "$ProgramFilesNative\Docker\Docker\Docker Desktop.exe"

    if (Test-Path $ddExe) {
        # 用 explorer 中转启动，避免管理员权限继承导致 Docker Desktop 启动失败
        Start-Process -FilePath "explorer.exe" -ArgumentList "`"$ddExe`""
        Write-Log "已触发 Docker Desktop 启动"
    } else {
        Write-Log "未找到 Docker Desktop.exe，无法自动启动" "ERROR"
        if (-not $AutoStart) { Read-Host "按回车键退出" }
        exit 1
    }

    # 等待 Docker daemon 就绪（最多 5 分钟）
    Write-Log "等待 Docker daemon 就绪..."
    $maxWait = 300
    $waited = 0
    while ($waited -lt $maxWait) {
        if (Test-DockerRunning) {
            Write-Log "Docker daemon 已就绪"
            break
        }
        Start-Sleep -Seconds 5
        $waited += 5
        if ($waited % 30 -eq 0) {
            Write-Log "  等待中... $waited s / $maxWait s"
        }
    }

    if (-not (Test-DockerRunning)) {
        Write-Log "Docker daemon 等待超时（$maxWait 秒）" "ERROR"
        if (-not $AutoStart) { Read-Host "按回车键退出" }
        exit 1
    }
} else {
    Write-Log "Docker daemon 已运行"
}

# ==================== 2. 启动容器 ====================
Set-Location $configDir

Write-Log "启动容器（docker compose up -d）..."
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker compose up -d 2>&1 | ForEach-Object { Write-Log "  $_" }
$upExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap

if ($upExit -ne 0) {
    Write-Log "容器启动失败（退出码 $upExit）" "ERROR"
    if (-not $AutoStart) { Read-Host "按回车键退出" }
    exit 1
}

# ==================== 3. 等待 Web 服务可访问 ====================
if (-not $AutoStart) {
    Write-Host "等待服务就绪..." -ForegroundColor Cyan
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
    Write-Host "盘家智管已启动" -ForegroundColor Green
    Write-Host "访问地址: http://localhost"
    Write-Host ""

    # 尝试打开浏览器
    try {
        Start-Process "http://localhost"
    } catch {
        Write-Host "请手动打开浏览器访问 http://localhost"
    }

    Start-Sleep -Seconds 2
} else {
    Write-Log "容器已启动（自启模式，跳过浏览器打开）"
}
