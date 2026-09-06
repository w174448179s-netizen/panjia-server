# ============================================================================
# 盘家智管 - 安装核心逻辑
# 由 NSIS 安装器调用，也可独立运行
#
# 参数：
#   -InstallDir   安装目录（默认 C:\Program Files\Panjia）
#   -AuthCode     授权码
#   -LicenseServer 授权服务器地址（默认 https://license.panjia.icu）
#   -ImageTag     镜像标签（默认 latest）
# ============================================================================

param(
    [string]$InstallDir = "C:\Program Files\Panjia",
    [string]$AuthCode = "",
    [string]$LicenseServer = "https://license.panjia.icu",
    [string]$ImageTag = "latest"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# 日志
$LogDir = "$InstallDir\logs"
$LogFile = "$LogDir\install.log"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$timestamp] [$Level] $Message"
    Add-Content -Path $LogFile -Value $line
    Write-Host $line
}

function Write-Step {
    param([int]$Step, [int]$Total, [string]$Title)
    Write-Log ""
    Write-Log "=== 步骤 $Step/$Total : $Title ==="
}

# ==================== 安装进度管理（断点续装） ====================
$ProgressFile = "$InstallDir\config\install-progress.ini"

function Get-Progress {
    if (Test-Path $ProgressFile) {
        $content = Get-Content $ProgressFile -Raw
        if ($content -match 'LastCompletedStep\s*=\s*(\d+)') {
            return [int]$matches[1]
        }
    }
    return 0
}

function Set-Progress {
    param([int]$Step)
    $ini = "[InstallProgress]`r`nLastCompletedStep = $Step`r`nLastUpdate = $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`r`n"
    New-Item -ItemType Directory -Force -Path (Split-Path $ProgressFile) | Out-Null
    Set-Content -Path $ProgressFile -Value $ini -Force
}

function Test-StepDone {
    param([int]$Step)
    return (Get-Progress) -ge $Step
}

# 注册重启后自动继续安装
function Register-RestartContinue {
    $cmd = "powershell -NoProfile -ExecutionPolicy Bypass -File `"$PSScriptRoot\install.ps1`" -InstallDir `"$InstallDir`" -AuthCode `"$AuthCode`" -LicenseServer `"$LicenseServer`" -ImageTag `"$ImageTag`""
    try {
        $regPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\RunOnce"
        if (-not (Test-Path $regPath)) {
            New-Item -Path $regPath -Force | Out-Null
        }
        Set-ItemProperty -Path $regPath -Name "PanjiaInstallContinue" -Value $cmd -Force
        Write-Log "  已注册重启后自动继续安装"
    } catch {
        Write-Log "  警告: 无法注册自动续装: $_" "WARN"
    }
}

$lastCompleted = Get-Progress
if ($lastCompleted -gt 0) {
    Write-Log "检测到未完成的安装（已完成步骤 $lastCompleted），将从断点继续..."
}

Write-Log "=========================================="
Write-Log "盘家智管安装开始"
Write-Log "安装目录: $InstallDir"
if ($AuthCode) {
    Write-Log "授权码: $($AuthCode.Substring(0, [Math]::Min(8, $AuthCode.Length)))****"
}
Write-Log "授权服务器: $LicenseServer"
Write-Log "=========================================="

$TotalSteps = 7

# ==================== 步骤 1：系统检查 ====================
if (-not (Test-StepDone 1)) {
    Write-Step 1 $TotalSteps "系统环境检查"

    # 1.1 操作系统版本
    $os = Get-CimInstance Win32_OperatingSystem
    Write-Log "操作系统: $($os.Caption) $($os.Version) $($os.OSArchitecture)"

    if ($os.BuildNumber -lt 19041) {
        Write-Log "Windows 版本过低，需要 Windows 10 2004 或更高版本" "ERROR"
        exit 1
    }

    # 1.2 内存
    $mem = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
    Write-Log "内存: ${mem}GB"
    if ($mem -lt 4) {
        Write-Log "内存不足 4GB，可能影响运行" "WARN"
    }

    # 1.3 磁盘空间
    $drive = Get-PSDrive ($InstallDir.Substring(0,1))
    $freeGB = [math]::Round($drive.Free / 1GB, 1)
    Write-Log "磁盘可用空间: ${freeGB}GB"
    if ($freeGB -lt 10) {
        Write-Log "磁盘空间不足 10GB" "ERROR"
        exit 1
    }

    Write-Log "系统检查通过"
    Set-Progress 1
}

# ==================== 步骤 2：检查/安装 Docker ====================
if (-not (Test-StepDone 2)) {
    Write-Step 2 $TotalSteps "Docker 环境检查"

    function Test-DockerInstalled {
        try {
            $null = docker --version 2>&1
            return $true
        } catch {
            return $false
        }
    }

    function Test-DockerRunning {
        try {
            $null = docker info 2>&1
            return $true
        } catch {
            return $false
        }
    }

    $needReboot = $false
    $dockerJustInstalled = $false

    if (Test-DockerInstalled) {
        $ver = docker --version 2>&1
        Write-Log "Docker 已安装: $ver"
    } else {
        Write-Log "未检测到 Docker，开始安装 Docker Desktop..."
        $dockerJustInstalled = $true

        # 优先使用本地安装包（离线模式）
        $localDockerInstaller = "$PSScriptRoot\..\docker\Docker Desktop Installer.exe"
        $dockerInstaller = ""

        if (Test-Path $localDockerInstaller) {
            $sizeMB = [math]::Round((Get-Item $localDockerInstaller).Length / 1MB, 1)
            Write-Log "  找到本地 Docker Desktop 安装包（$sizeMB MB），使用离线安装"
            $dockerInstaller = $localDockerInstaller
        } else {
            Write-Log "  未找到本地安装包，尝试在线下载..."
            $dockerUrl = "https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe"
            $dockerInstaller = "$env:TEMP\DockerDesktopInstaller.exe"

            Write-Log "  下载 Docker Desktop 安装包（约 600MB），请耐心等待..."
            try {
                Invoke-WebRequest -Uri $dockerUrl -OutFile $dockerInstaller -UseBasicParsing
            } catch {
                Write-Log "下载 Docker Desktop 失败: $_" "ERROR"
                Write-Log "请检查网络连接，或手动安装 Docker Desktop 后重试" "ERROR"
                exit 1
            }
        }

        # 启用 WSL2 和虚拟机平台（Docker Desktop 依赖）
        Write-Log "检查并启用 Windows 功能（WSL2 / 虚拟机平台）..."

        try {
            # 启用 Microsoft-Windows-Subsystem-Linux
            $wslState = (Get-WindowsOptionalFeature -Online -FeatureName "Microsoft-Windows-Subsystem-Linux" -ErrorAction Stop).State
            if ($wslState -ne "Enabled") {
                Write-Log "  启用 WSL 子系统..."
                Enable-WindowsOptionalFeature -Online -FeatureName "Microsoft-Windows-Subsystem-Linux" -NoRestart -All | Out-Null
                $needReboot = $true
                Write-Log "  WSL 子系统已启用"
            } else {
                Write-Log "  WSL 子系统已启用"
            }

            # 启用 VirtualMachinePlatform
            $vmState = (Get-WindowsOptionalFeature -Online -FeatureName "VirtualMachinePlatform" -ErrorAction Stop).State
            if ($vmState -ne "Enabled") {
                Write-Log "  启用虚拟机平台..."
                Enable-WindowsOptionalFeature -Online -FeatureName "VirtualMachinePlatform" -NoRestart -All | Out-Null
                $needReboot = $true
                Write-Log "  虚拟机平台已启用"
            } else {
                Write-Log "  虚拟机平台已启用"
            }
        } catch {
            Write-Log "  警告: 启用 Windows 功能时出错: $_" "WARN"
        }

        # 安装 Docker Desktop（静默安装，WSL2 后端）
        Write-Log "安装 Docker Desktop（静默安装，WSL2 后端）..."

        $installArgs = @(
            "install",
            "--quiet",
            "--accept-license",
            "--backend=wsl-2"
        )

        $process = Start-Process -FilePath $dockerInstaller -ArgumentList $installArgs -Wait -PassThru -NoNewWindow

        Write-Log "  Docker Desktop 安装退出码: $($process.ExitCode)"

        # Docker Desktop 安装后 PATH 可能还没刷新，手动加入可能的安装路径
        $dockerCliPath = "$env:ProgramFiles\Docker\Docker\resources\bin"
        if (Test-Path $dockerCliPath) {
            $env:PATH = "$dockerCliPath;$env:PATH"
            Write-Log "  已将 Docker CLI 加入 PATH"
        }

        # 检查安装结果
        if (-not (Test-DockerInstalled)) {
            $dockerExe = "$env:ProgramFiles\Docker\Docker\resources\bin\docker.exe"
            if (Test-Path $dockerExe) {
                Write-Log "Docker Desktop 安装完成（通过完整路径检测）"
            } else {
                Write-Log "Docker Desktop 安装失败" "ERROR"
                exit 1
            }
        } else {
            Write-Log "Docker Desktop 安装完成"
        }
    }

    # 新安装 Docker Desktop 后，如果启用了 WSL2 功能，必须重启才能用
    # 不重启的话 Docker daemon 根本启动不了，干等也没用
    if ($dockerJustInstalled -and $needReboot) {
        Write-Log "Docker Desktop 已安装，WSL2 功能已启用" "WARN"
        Write-Log "必须重启电脑后 Docker 才能正常运行，系统将自动重启..." "WARN"
        Write-Log "重启后安装程序会自动继续，无需手动操作" "WARN"
        Register-RestartContinue
        Write-Log "10 秒后重启电脑..."
        Start-Sleep -Seconds 10
        Restart-Computer -Force
        exit 0
    }

    # 启动 Docker Desktop（已安装且已重启的情况）
    if (-not (Test-DockerRunning)) {
        Write-Log "启动 Docker Desktop..."

        # 预配置 Docker Desktop：跳过首次启动向导和 EULA 弹窗
        $settingsDir = "$env:APPDATA\Docker"
        if (-not (Test-Path $settingsDir)) {
            New-Item -ItemType Directory -Force -Path $settingsDir | Out-Null
        }
        $settingsFile = "$settingsDir\settings-store.json"
        if (-not (Test-Path $settingsFile)) {
            $settings = @{
                AcceptEula = $true
                SkipTutorial = $true
                SkipThankYouPage = $true
                ShowTip = $false
            }
            $settings | ConvertTo-Json | Set-Content -Path $settingsFile -Force
            Write-Log "  已预配置 Docker Desktop 设置（跳过首次向导）"
        }

        $dockerPath = "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
        if (Test-Path $dockerPath) {
            Start-Process $dockerPath
            Write-Log "  已启动 Docker Desktop"
        } else {
            try {
                Start-Process "Docker Desktop" -ErrorAction Stop
                Write-Log "  已启动 Docker Desktop"
            } catch {
                Write-Log "  无法启动 Docker Desktop: $_" "WARN"
            }
        }

        # 等待 Docker daemon 就绪（最多 3 分钟，重启后应该很快）
        Write-Log "等待 Docker 启动..."
        $maxWait = 180
        $waited = 0
        while ($waited -lt $maxWait) {
            if (Test-DockerRunning) {
                break
            }
            Start-Sleep -Seconds 5
            $waited += 5
            if ($waited % 30 -eq 0) {
                Write-Log "  等待中... ${waited}s / ${maxWait}s"
            }
        }

        if (-not (Test-DockerRunning)) {
            Write-Log "Docker 启动超时" "ERROR"
            Write-Log "请手动启动 Docker Desktop，确保 Docker 正常运行后重新运行安装程序" "ERROR"
            exit 1
        }
    }

    Write-Log "Docker 运行正常"

    # 检查 docker compose
    try {
        $composeVer = docker compose version 2>&1
        Write-Log "docker compose 可用: $composeVer"
    } catch {
        Write-Log "docker compose 不可用" "ERROR"
        exit 1
    }

    Set-Progress 2
}

# ==================== 步骤 3：创建目录结构 ====================
if (-not (Test-StepDone 3)) {
    Write-Step 3 $TotalSteps "创建安装目录"

    $dirs = @(
        "$InstallDir",
        "$InstallDir\config",
        "$InstallDir\data\postgres",
        "$InstallDir\data\redis",
        "$InstallDir\data\panjia-license",
        "$InstallDir\logs",
        "$InstallDir\web\dist",
        "$InstallDir\nginx",
        "$InstallDir\scripts"
    )

    foreach ($dir in $dirs) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }

    Write-Log "目录结构创建完成: $InstallDir"
    Set-Progress 3
}

# ==================== 步骤 4：部署配置文件 ====================
if (-not (Test-StepDone 4)) {
    Write-Step 4 $TotalSteps "部署配置文件"

    # 复制 docker-compose.yml
    $composeSource = "$PSScriptRoot\..\config\docker-compose.yml"
    $composeDest = "$InstallDir\config\docker-compose.yml"
    if (Test-Path $composeSource) {
        Copy-Item $composeSource $composeDest -Force
        Write-Log "docker-compose.yml 已部署"
    } else {
        Write-Log "警告: 未找到 docker-compose.yml" "WARN"
    }

    # 复制 nginx.conf
    $nginxSource = "$PSScriptRoot\..\config\nginx.conf"
    $nginxDest = "$InstallDir\nginx\nginx.conf"
    if (Test-Path $nginxSource) {
        Copy-Item $nginxSource $nginxDest -Force
        Write-Log "nginx.conf 已部署"
    }

    # 生成随机密码（只在首次安装时生成，续装时读取已有 .env）
    $envFile = "$InstallDir\config\.env"
    if (Test-Path $envFile) {
        Write-Log ".env 配置文件已存在，跳过生成"
    } else {
        $dbUser = "panjia"
        $dbName = "panjia"
        $dbPassword = "Pg" + [guid]::NewGuid().ToString("N").Substring(0, 24) + "!"
        $redisPassword = "Rd" + [guid]::NewGuid().ToString("N").Substring(0, 24)

        $envContent = @"
# 盘家智管环境配置（安装时自动生成）
# 生成时间: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")

# PostgreSQL
POSTGRES_USER=$dbUser
POSTGRES_PASSWORD=$dbPassword
POSTGRES_DB=$dbName
POSTGRES_PORT=5432

# Redis
REDIS_PASSWORD=$redisPassword
REDIS_PORT=6379

# 镜像标签
IMAGE_TAG=$ImageTag

# 端口
WEB_PORT=80
SERVER_PORT=8080

# JVM 参数
JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC

# License 授权
PANJIA_LICENSE_SERVER=$LicenseServer
PANJIA_AUTH_CODE=$AuthCode
"@

        $envContent | Out-File -FilePath $envFile -Encoding UTF8
        Write-Log ".env 配置文件已生成"

        # 保存安装信息
        $installInfo = @"
{
    "installDir": "$InstallDir",
    "installDate": "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    "licenseServer": "$LicenseServer",
    "authCode": "$AuthCode",
    "imageTag": "$ImageTag",
    "dbUser": "$dbUser",
    "dbName": "$dbName",
    "dbPassword": "$dbPassword",
    "redisPassword": "$redisPassword"
}
"@
        $installInfo | Out-File -FilePath "$InstallDir\config\install-info.json" -Encoding UTF8
        Write-Log "安装信息已保存"
    }

    Set-Progress 4
}

# ==================== 步骤 5：加载 Docker 镜像（优先离线） ====================
if (-not (Test-StepDone 5)) {
    Write-Step 5 $TotalSteps "加载 Docker 镜像"

    $configDir = "$InstallDir\config"
    $imagesDir = "$PSScriptRoot\..\images"

    # 辅助函数：优先从本地 tar 加载，没有则远程 pull
    function Import-DockerImage {
        param(
            [string]$ImageName,
            [string]$LocalTarFile,
            [string]$RemoteImage
        )

        try {
            $null = docker inspect $ImageName 2>&1
            Write-Log "  [$ImageName] 已存在，跳过"
            return
        } catch {}

        if (Test-Path $LocalTarFile) {
            Write-Log "  [$ImageName] 从本地镜像包加载..."
            try {
                docker load -i $LocalTarFile 2>&1 | ForEach-Object { Write-Log "    $_" }
                Write-Log "  [$ImageName] 本地加载成功"
                return
            } catch {
                Write-Log "  [$ImageName] 本地加载失败: $_" "WARN"
            }
        }

        if ($RemoteImage) {
            Write-Log "  [$ImageName] 从远程拉取..."
            try {
                docker pull $RemoteImage 2>&1 | ForEach-Object { Write-Log "    $_" }
                if ($RemoteImage -ne $ImageName) {
                    docker tag $RemoteImage $ImageName 2>&1 | Out-Null
                }
                Write-Log "  [$ImageName] 远程拉取成功"
                return
            } catch {
                Write-Log "  [$ImageName] 远程拉取失败: $_" "WARN"
            }
        }

        Write-Log "  [$ImageName] 加载失败（本地无包且远程不可达）" "ERROR"
        throw "镜像 $ImageName 加载失败"
    }

    Write-Log "检查本地离线镜像包..."
    $localImageCount = 0
    if (Test-Path $imagesDir) {
        $tarFiles = Get-ChildItem -Path $imagesDir -Filter "*.tar"
        $localImageCount = $tarFiles.Count
        Write-Log "  发现 $localImageCount 个本地镜像包"
        foreach ($f in $tarFiles) {
            Write-Log "    - $($f.Name) ($([math]::Round($f.Length / 1MB, 1)) MB)"
        }
    } else {
        Write-Log "  未找到本地镜像目录，将全部从远程拉取"
    }

    Write-Log ""
    Write-Log "加载基础镜像..."

    Import-DockerImage -ImageName "postgres:16.9" -LocalTarFile "$imagesDir\postgres.tar" -RemoteImage "postgres:16.9"
    Import-DockerImage -ImageName "redis:7-alpine" -LocalTarFile "$imagesDir\redis.tar" -RemoteImage "redis:7-alpine"
    Import-DockerImage -ImageName "nginx:stable-alpine" -LocalTarFile "$imagesDir\nginx.tar" -RemoteImage "nginx:stable-alpine"

    Write-Log ""
    Write-Log "加载业务镜像..."

    $serverImageName = "panjia-server:$ImageTag"
    $serverTar = "$imagesDir\panjia-server.tar"

    try {
        $null = docker inspect $serverImageName 2>&1
        Write-Log "  [$serverImageName] 已存在，跳过"
    } catch {
        if (Test-Path $serverTar) {
            Write-Log "  [$serverImageName] 从本地镜像包加载 ($([math]::Round((Get-Item $serverTar).Length / 1MB, 1)) MB)..."
            try {
                docker load -i $serverTar 2>&1 | ForEach-Object { Write-Log "    $_" }
                Write-Log "  [$serverImageName] 本地加载成功"
            } catch {
                Write-Log "  [$serverImageName] 本地加载失败: $_" "WARN"
                throw "业务镜像加载失败"
            }
        } else {
            Write-Log "  [$serverImageName] 未找到本地镜像包" "ERROR"
            Write-Log "  离线安装需要 panjia-server.tar 镜像包" "ERROR"
            Write-Log "  请确认安装包是否完整，或联系技术支持" "ERROR"
            exit 1
        }
    }

    Write-Log ""
    Write-Log "全部镜像就绪"
    Set-Progress 5
}

# ==================== 步骤 6：启动服务 ====================
if (-not (Test-StepDone 6)) {
    Write-Step 6 $TotalSteps "启动服务"

    $configDir = "$InstallDir\config"
    Set-Location $configDir

    Write-Log "启动容器（docker compose up -d）..."
    docker compose up -d 2>&1 | ForEach-Object { Write-Log "  $_" }

    # 等待健康检查
    Write-Log "等待服务启动（最多 5 分钟）..."
    $maxWait = 300
    $waited = 0
    $allHealthy = $false

    while ($waited -lt $maxWait) {
        $status = docker compose ps --format json 2>&1 | ConvertFrom-Json -ErrorAction SilentlyContinue
        if ($status -and $status.Count -gt 0) {
            $healthyCount = ($status | Where-Object { $_.State -eq "running" -and $_.Health -eq "healthy" }).Count
            $totalCount = $status.Count
            if ($healthyCount -eq $totalCount) {
                $allHealthy = $true
                break
            }
        }
        Start-Sleep -Seconds 5
        $waited += 5
    }

    if (-not $allHealthy) {
        Write-Log "警告: 部分容器健康检查未通过，继续下一步..." "WARN"
    }

    Write-Log "服务启动完成"
    docker compose ps 2>&1 | ForEach-Object { Write-Log "  $_" }
    Set-Progress 6
}

# ==================== 步骤 7：激活授权 ====================
if (-not (Test-StepDone 7)) {
    Write-Step 7 $TotalSteps "激活授权码"

    if ([string]::IsNullOrWhiteSpace($AuthCode)) {
        Write-Log "警告: 未提供授权码，跳过激活" "WARN"
        Write-Log "请手动激活：打开 http://localhost 按提示操作"
    } else {
        Write-Log "授权服务器: $LicenseServer"
        Write-Log "正在激活授权码..."

        Write-Log "等待后端 API 就绪..."
        $apiReady = $false
        for ($i = 0; $i -lt 30; $i++) {
            try {
                $resp = Invoke-WebRequest -Uri "http://localhost:8080/actuator/health" -UseBasicParsing -TimeoutSec 5
                if ($resp.StatusCode -eq 200) {
                    $apiReady = $true
                    break
                }
            } catch {}
            Start-Sleep -Seconds 3
        }

        if ($apiReady) {
            Write-Log "后端 API 就绪"
            try {
                $activateBody = @{
                    authCode = $AuthCode
                } | ConvertTo-Json

                $resp = Invoke-WebRequest -Uri "http://localhost:8080/api/license/activate" `
                    -Method POST -Body $activateBody -ContentType "application/json" `
                    -UseBasicParsing -TimeoutSec 30

                Write-Log "授权激活成功"
            } catch {
                Write-Log "警告: 授权激活接口调用失败: $_" "WARN"
                Write-Log "请打开 http://localhost 手动激活"
            }
        } else {
            Write-Log "警告: 后端 API 未就绪，跳过自动激活" "WARN"
            Write-Log "请打开 http://localhost 按提示操作激活"
        }
    }

    Set-Progress 7
}

# ==================== 完成 ====================
Write-Log ""
Write-Log "=========================================="
Write-Log "盘家智管安装完成！"
Write-Log "=========================================="
Write-Log ""
Write-Log "访问地址: http://localhost"
Write-Log "安装目录: $InstallDir"
Write-Log ""
Write-Log "开始菜单：盘家智管"
Write-Log "  - 启动盘家智管"
Write-Log "  - 停止盘家智管"
Write-Log "  - 查看日志"
Write-Log "  - 卸载"
Write-Log ""
Write-Log "如遇问题，请查看日志: $LogFile"
Write-Log "=========================================="

# 清理进度文件
if (Test-Path $ProgressFile) {
    Remove-Item $ProgressFile -Force
}

exit 0
