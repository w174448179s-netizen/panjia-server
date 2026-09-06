# ============================================================================
# 盘家智管 - 升级脚本（由 panjia-upgrade.exe 调用）
#
# 前置条件：已通过完整安装包（panjia-setup.exe）安装过。
# 升级内容：后端镜像（docker load）+ 前端静态资源 + 配置/脚本
#           （文件由 NSIS 释放到 $InstallDir，本脚本只负责生效）。
#
# 保护范围（绝不触碰）：
#   - data\（数据库、授权指纹数据卷）
#   - config\.env（密码等环境变量）
#   - config\machine-id（机器指纹，授权绑定）
#
# 用法：
#   powershell -File upgrade.ps1 -InstallDir "C:\Program Files (x86)\Panjia"
#
# 退出码：0 成功；1 失败
# ============================================================================

param(
    [string]$InstallDir = ""
)

$ErrorActionPreference = "Continue"

# 64 位真实 Program Files（本脚本可能由 32 位进程拉起，$env:ProgramFiles 会被 WOW64 重定向）
$ProgramFilesNative = if (${env:ProgramW6432}) { ${env:ProgramW6432} } else { $env:ProgramFiles }

# ==================== 定位安装目录 ====================
if ([string]::IsNullOrWhiteSpace($InstallDir)) {
    $reg = Get-ItemProperty "HKLM:\Software\Panjia\盘家智管" -ErrorAction SilentlyContinue
    if ($reg -and $reg.InstallDir) { $InstallDir = $reg.InstallDir }
}
if ([string]::IsNullOrWhiteSpace($InstallDir)) { $InstallDir = "C:\Program Files (x86)\Panjia" }

# ==================== 日志 ====================
if (-not (Test-Path "$InstallDir\logs")) {
    New-Item -ItemType Directory -Force -Path "$InstallDir\logs" | Out-Null
}
$LogFile = "$InstallDir\logs\upgrade.log"

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $line = "[{0}] [{1}] {2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Level, $Message
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

# ==================== 前置校验 ====================
Write-Log "=========================================="
Write-Log "盘家智管升级"
Write-Log "=========================================="
Write-Log "安装目录: $InstallDir"

if (-not (Test-Path "$InstallDir\config\docker-compose.yml") -or -not (Test-Path "$InstallDir\config\.env")) {
    Write-Log "未检测到已安装的盘家智管（缺少 docker-compose.yml 或 .env）" "ERROR"
    Write-Log "请先运行完整安装包 panjia-setup.exe 安装后再升级" "ERROR"
    exit 1
}

# Docker CLI 路径（Docker Desktop 安装后可能未进当前 PATH）
$dockerBin = "$ProgramFilesNative\Docker\Docker\resources\bin"
if ((Test-Path "$dockerBin\docker.exe") -and ($env:PATH -notlike "*$dockerBin*")) {
    $env:PATH = "$dockerBin;$env:PATH"
}

# ==================== 步骤 1：确保 Docker 引擎可用 ====================
function Test-DockerReady {
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker info *> $null
    $ok = ($LASTEXITCODE -eq 0)
    $ErrorActionPreference = $prevEap
    return $ok
}

if (-not (Test-DockerReady)) {
    Write-Log "Docker 引擎未运行，尝试启动 Docker Desktop..."
    $ddExe = "$ProgramFilesNative\Docker\Docker\Docker Desktop.exe"
    if (Test-Path $ddExe) {
        # explorer 中转启动，回落到普通用户权限（管理员上下文直接启动经常失败）
        Start-Process -FilePath "explorer.exe" -ArgumentList "`"$ddExe`""
    } else {
        Write-Log "未找到 Docker Desktop，请先安装完整安装包" "ERROR"
        exit 1
    }
    $dockerUp = $false
    for ($i = 0; $i -lt 60; $i++) {
        if (Test-DockerReady) { $dockerUp = $true; break }
        # 进程没活过来就补拉一次
        if (($i % 12) -eq 11 -and $null -eq (Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue)) {
            Start-Process -FilePath "explorer.exe" -ArgumentList "`"$ddExe`""
        }
        Start-Sleep -Seconds 5
    }
    if (-not $dockerUp) {
        Write-Log "Docker 引擎 5 分钟内未就绪，请手动启动 Docker Desktop 后重试" "ERROR"
        exit 1
    }
    Write-Log "Docker 引擎已就绪"
} else {
    Write-Log "Docker 引擎运行中"
}

# ==================== 步骤 2：机器指纹兜底（老版本安装的客户机可能缺失） ====================
$machineIdFile = "$InstallDir\config\machine-id"
if (-not (Test-Path $machineIdFile)) {
    Write-Log "补生成机器指纹文件（老版本安装缺失，授权依赖）..."
    $machineGuid = $null
    try {
        $machineGuid = (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Cryptography" -ErrorAction Stop).MachineGuid
    } catch {}
    if ([string]::IsNullOrWhiteSpace($machineGuid)) {
        $machineGuid = [guid]::NewGuid().ToString()
    }
    [IO.File]::WriteAllText($machineIdFile, $machineGuid, (New-Object System.Text.ASCIIEncoding))
    Write-Log "  机器指纹文件已生成: $machineIdFile"
}

# ==================== 步骤 3：Docker Desktop 登录自启兜底 ====================
$ddExeForRun = "$ProgramFilesNative\Docker\Docker\Docker Desktop.exe"
$runKeyPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
if (Test-Path $ddExeForRun) {
    $existingRun = (Get-ItemProperty $runKeyPath -Name "Docker Desktop" -ErrorAction SilentlyContinue)."Docker Desktop"
    if ([string]::IsNullOrWhiteSpace($existingRun)) {
        New-ItemProperty -Path $runKeyPath -Name "Docker Desktop" `
            -Value "`"$ddExeForRun`" -Autostart" -PropertyType String -Force | Out-Null
        Write-Log "已注册 Docker Desktop 开机自启"
    }
}

# ==================== 步骤 4：加载新后端镜像 ====================
$imageTar = "$InstallDir\images\panjia-server.tar"
if (Test-Path $imageTar) {
    $sizeMB = [math]::Round((Get-Item $imageTar).Length / 1MB)
    Write-Log "加载后端镜像（${sizeMB} MB，约 1-3 分钟）..."
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker load -i $imageTar 2>&1 | ForEach-Object { Write-Log "  $_" }
    $loadExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($loadExit -ne 0) {
        Write-Log "镜像加载失败（退出码 $loadExit）" "ERROR"
        exit 1
    }
    Write-Log "后端镜像加载完成"
} else {
    Write-Log "未找到后端镜像包（images\panjia-server.tar），跳过镜像升级" "WARN"
}

# ==================== 步骤 5：重建容器 ====================
# compose 会自动检测变化：server 镜像变了 → 重建 server；
# postgres/redis 无变化 → 不动；web 的静态资源是 bind mount，文件已替换即生效。
Write-Log "应用变更（docker compose up -d）..."
Push-Location "$InstallDir\config"
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker compose up -d 2>&1 | ForEach-Object { Write-Log "  $_" }
$upExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($upExit -ne 0) {
    Write-Log "容器更新失败（退出码 $upExit）" "ERROR"
    Pop-Location
    exit 1
}

# ==================== 步骤 6：等待 server 健康 ====================
Write-Log "等待服务就绪（最多 5 分钟）..."
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$healthy = $false
for ($i = 0; $i -lt 60; $i++) {
    $health = docker inspect --format "{{.State.Health.Status}}" panjia-server 2>$null
    if ($health -eq "healthy") { $healthy = $true; break }
    Start-Sleep -Seconds 5
}
$ErrorActionPreference = $prevEap
Pop-Location

if ($healthy) {
    Write-Log "服务已就绪（healthy）"
} else {
    Write-Log "服务健康检查未通过，请查看容器日志：docker logs panjia-server" "WARN"
}

# ==================== 完成 ====================
Write-Log ""
Write-Log "=========================================="
Write-Log "升级完成！"
Write-Log "=========================================="
Write-Log "访问地址: http://localhost（请强制刷新浏览器缓存 Ctrl+F5）"
Write-Log "升级日志: $LogFile"

exit 0
