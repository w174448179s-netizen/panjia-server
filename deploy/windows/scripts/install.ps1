# ============================================================================
# 盘家智管 - 安装核心逻辑
# 由 NSIS 安装器调用，也可独立运行
#
# 参数：
#   -InstallDir   安装目录（默认 C:\Program Files\Panjia）
#   -AuthCode     授权码
#   -LicenseServer 授权服务器地址（默认 https://panjia.icu）
#   -ImageTag     镜像标签（默认 latest）
#
# 说明：
#   - 需要管理员权限，未提权时会自动弹出 UAC 自我重启
#   - Docker Desktop 以静默方式安装（install --quiet --accept-license）
#   - WSL 内核过旧时自动更新：优先安装离线包 docker\wsl.msi（缺省从
#     https://github.com/microsoft/WSL/releases/latest 下载），在线环境
#     会执行 wsl --update --web-download
#   - 支持断点续装：若安装过程中要求重启，会注册 RunOnce 在重启后继续
#     退出码：0 成功；1 失败；3 需要重启后续装
# ============================================================================

param(
    [string]$InstallDir = "C:\Program Files\Panjia",
    [string]$AuthCode = "",
    [string]$LicenseServer = "https://panjia.icu",
    [string]$ImageTag = "latest"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# ==================== 管理员权限检查 ====================
# 静默安装 Docker Desktop、写入 Program Files 都需要管理员权限。
# 非管理员运行时自动提权重启自身（典型场景：重启后 RunOnce 断点续装）。
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "需要管理员权限，正在请求提权..."
    try {
        # 注意：Start-Process 不会自动给含空格的参数加引号，必须手动加
        $argList = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", ('"{0}"' -f $PSCommandPath),
            "-InstallDir", ('"{0}"' -f $InstallDir),
            "-AuthCode", ('"{0}"' -f $AuthCode),
            "-LicenseServer", ('"{0}"' -f $LicenseServer),
            "-ImageTag", ('"{0}"' -f $ImageTag)
        )
        Start-Process -FilePath "powershell" -Verb RunAs -ArgumentList $argList | Out-Null
    } catch {
        Write-Host "提权被取消或失败: $_"
        exit 1
    }
    exit 0
}

# 日志
$LogDir = "$InstallDir\logs"
$LogFile = "$LogDir\install.log"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# 64 位真实的 Program Files：本脚本由 32 位 NSIS 安装器拉起，PowerShell 也是 32 位，
# WOW64 会把 $env:ProgramFiles 重定向为 C:\Program Files (x86)——
# 用它找 Docker Desktop（装在 64 位 Program Files 下）必然 Test-Path 失败。
$ProgramFilesNative = if (${env:ProgramW6432}) { ${env:ProgramW6432} } else { $env:ProgramFiles }

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
        # 用 Get-Command 探测命令是否存在，兼容 PS5.1 / PS7
        # （PS7 下原生命令写 stderr 不会抛异常，try/catch 会误判为已安装）
        return ($null -ne (Get-Command docker -ErrorAction SilentlyContinue))
    }

    function Test-DockerRunning {
        # 用退出码判断 daemon 是否就绪，兼容 PS5.1 / PS7
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            docker info 2>&1 | Out-Null
        } finally {
            $ErrorActionPreference = $prevEap
        }
        return ($LASTEXITCODE -eq 0)
    }

    # 需要重启时：注册 RunOnce 断点续装，提示用户，以退出码 3 结束
    function Request-RebootAndContinue {
        param([string]$Reason)
        Write-Log $Reason "WARN"
        Register-RestartContinue
        Write-Log "已注册重启后自动续装，请重启电脑"
        [System.Reflection.Assembly]::LoadWithPartialName("System.Windows.Forms") | Out-Null
        [System.Windows.Forms.MessageBox]::Show(
            "$Reason。`n`n重启后将自动继续安装盘家智管，无需再次运行安装程序。",
            "盘家智管 - 需要重启",
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Information
        ) | Out-Null
        exit 3
    }

    # 更新 WSL2 内核。Docker Desktop 内核过旧时会报错：
    #   "Your version of Windows Subsystem for Linux (WSL) is too old."
    # 更新顺序：本地离线包 wsl.msi（推荐，离线环境可用）
    #          → wsl --update --web-download（绕过 Microsoft Store）
    #          → wsl --update
    # wsl.exe 的输出是 UTF-16LE 编码，PowerShell 5.1 默认按 ANSI/OEM 解码会得到乱码，
    # 且乱码中夹杂的控制字符会破坏日志行（把关键日志行搅在一起，无法判断执行到哪一步）。
    # 统一走本函数执行 wsl：临时把控制台编码切为 Unicode，并清洗控制字符。
    function Invoke-WslCommand {
        param([Parameter(Mandatory)][string[]]$WslArgs)
        $prevEnc = [Console]::OutputEncoding
        $prevEap = $ErrorActionPreference
        try {
            [Console]::OutputEncoding = [System.Text.Encoding]::Unicode
            $ErrorActionPreference = "Continue"
            $output = @(wsl @WslArgs 2>&1)
            $exitCode = $LASTEXITCODE
        } finally {
            [Console]::OutputEncoding = $prevEnc
            $ErrorActionPreference = $prevEap
        }
        # 注意：foreach 语句不能直接接管道（PS5.1 报「不允许使用空管道元素」），
        # 必须用管道形式的 ForEach-Object。
        $clean = @($output | ForEach-Object {
            ("$_" -replace '[\x00-\x08\x0B\x0C\x0E-\x1F]', '')
        } | Where-Object { "$_" -match '\S' })
        return @{ Output = $clean; ExitCode = $exitCode }
    }

    function Update-Wsl {
        # wsl.exe 不存在说明 WSL 功能未启用，交给 Docker Desktop 安装器处理
        if ($null -eq (Get-Command wsl -ErrorAction SilentlyContinue)) {
            Write-Log "  未检测到 wsl.exe，跳过 WSL 内核检查（由 Docker Desktop 安装器启用）"
            return
        }

        # 检查内核版本（旧版内置 WSL 不支持 --version，退出码非 0，说明必须更新）
        $verResult = Invoke-WslCommand @("--version")
        $verOut = $verResult.Output
        $verExit = $verResult.ExitCode

        if ($verExit -eq 0) {
            # 输出为本地化文本，如 "WSL 版本: 2.6.1.0" / "内核版本: 6.6.87.2-1"
            # 或英文 "Kernel version: 6.6.87.2-1"，取前三个数字段比较即可
            $kernelLine = ($verOut | Where-Object { "$_" -match 'Kernel\s*version|内核版本' } | Select-Object -First 1)
            if ("$kernelLine" -match '(\d+)\.(\d+)\.(\d+)') {
                $kernelVer = [version]"$($Matches[1]).$($Matches[2]).$($Matches[3])"
                if ($kernelVer -ge [version]"5.10.0") {
                    Write-Log "  WSL 内核版本 $kernelVer 满足要求，无需更新"
                    return
                }
                Write-Log "  WSL 内核版本过旧（$kernelVer），需要更新"
            } else {
                Write-Log "  无法解析 WSL 内核版本，尝试更新"
            }
        } else {
            Write-Log "  WSL 版本过旧（不支持 --version 命令），需要更新"
        }

        # 方式一：本地离线 MSI（随安装包分发，放在 docker\wsl.msi）
        # 下载地址：https://github.com/microsoft/WSL/releases/latest
        $wslMsi = "$PSScriptRoot\..\docker\wsl.msi"
        if (Test-Path $wslMsi) {
            Write-Log "  从本地离线包更新 WSL（$([System.IO.Path]::GetFileName($wslMsi))）..."
            $process = Start-Process -FilePath "msiexec.exe" -Wait -PassThru `
                -ArgumentList "/i", "`"$wslMsi`"", "/quiet", "/norestart"
            $msiExit = $process.ExitCode
            Write-Log "  WSL MSI 安装退出码: $msiExit"
            if ($msiExit -eq 0) {
                Write-Log "  WSL 内核更新完成"
                return
            }
            if ($msiExit -eq 3010) {
                # 3010: 安装成功但需要重启
                Request-RebootAndContinue "WSL 内核更新完成，需要重启电脑才能继续"
            }
            Write-Log "  WSL MSI 安装失败（退出码 $msiExit），尝试在线更新" "WARN"
        }

        # 方式二：在线更新（--web-download 不依赖 Microsoft Store）
        foreach ($updArgs in @(@("--update", "--web-download"), @("--update"))) {
            $cmdText = "wsl $($updArgs -join ' ')"
            Write-Log "  执行 $cmdText ..."
            $updResult = Invoke-WslCommand $updArgs
            $updResult.Output | ForEach-Object { Write-Log "    $_" }
            if ($updResult.ExitCode -eq 0) {
                Write-Log "  WSL 更新完成"
                return
            }
            Write-Log "  $cmdText 失败（退出码 $($updResult.ExitCode)）" "WARN"
        }

        Write-Log "  WSL 更新失败。若 Docker 启动时仍报 WSL 版本过旧，" "WARN"
        Write-Log "  请以管理员身份手动执行: wsl --update" "WARN"
    }

    $dockerJustInstalled = $false

    if (Test-DockerInstalled) {
        $ver = docker --version 2>&1
        Write-Log "Docker 已安装: $ver"
    } else {
        Write-Log "未检测到 Docker，需要安装 Docker Desktop"
        $dockerJustInstalled = $true

        # 找 Docker Desktop 安装包
        $localDockerInstaller = "$PSScriptRoot\..\docker\Docker Desktop Installer.exe"
        $dockerInstaller = ""
        $dockerInstallerDownloaded = $false

        if (Test-Path $localDockerInstaller) {
            $sizeMB = [math]::Round((Get-Item $localDockerInstaller).Length / 1MB, 1)
            Write-Log "  找到本地 Docker Desktop 安装包（$sizeMB MB）"
            $dockerInstaller = $localDockerInstaller
        } else {
            Write-Log "  未找到本地安装包，尝试在线下载..."
            $dockerUrl = "https://desktop.docker.com/win/main/amd64/Docker%20Desktop%20Installer.exe"
            $dockerInstaller = "$env:TEMP\DockerDesktopInstaller.exe"
            $dockerInstallerDownloaded = $true

            Write-Log "  下载 Docker Desktop 安装包（约 600MB），请耐心等待..."
            try {
                Invoke-WebRequest -Uri $dockerUrl -OutFile $dockerInstaller -UseBasicParsing
            } catch {
                Write-Log "下载 Docker Desktop 失败: $_" "ERROR"
                Write-Log "请检查网络连接，或手动安装 Docker Desktop 后重试" "ERROR"
                exit 1
            }
        }

        # 静默安装 Docker Desktop（无界面，自动接受许可协议）
        Write-Log "以静默方式安装 Docker Desktop（可能需要几分钟）..."

        # PowerShell 下参数必须通过 ArgumentList 传递，且需放在 flags 之前
        $process = Start-Process -FilePath $dockerInstaller -Wait -PassThru `
            -ArgumentList "install", "--quiet", "--accept-license"
        $dockerInstallExit = $process.ExitCode
        Write-Log "  Docker Desktop 安装程序退出码: $dockerInstallExit"

        if ($dockerInstallExit -ne 0 -and $dockerInstallExit -ne 3010) {
            Write-Log "Docker Desktop 静默安装失败（退出码 $dockerInstallExit）" "ERROR"
            Write-Log "请手动安装 Docker Desktop 后重新运行本安装程序" "ERROR"
            exit 1
        }

        # 清理在线下载的安装包（约 600MB）
        if ($dockerInstallerDownloaded -and (Test-Path $dockerInstaller)) {
            Remove-Item $dockerInstaller -Force -ErrorAction SilentlyContinue
            Write-Log "  已清理下载的 Docker 安装包"
        }

        if ($dockerInstallExit -eq 3010) {
            # 3010: 安装成功但需要重启（WSL/系统组件变更未生效）
            Request-RebootAndContinue "Docker Desktop 安装完成，需要重启电脑才能继续"
        }
        Write-Log "Docker Desktop 静默安装完成"

        # Docker Desktop 安装后 PATH 可能还没刷新，手动加入
        # 注意用 $ProgramFilesNative（32 位进程下 $env:ProgramFiles 被 WOW64 重定向）
        $dockerCliPath = "$ProgramFilesNative\Docker\Docker\resources\bin"
        if (Test-Path $dockerCliPath) {
            $env:PATH = "$dockerCliPath;$env:PATH"
            Write-Log "  已将 Docker CLI 加入 PATH"
        }
        # 从注册表刷新 PATH（Docker 安装后会写入系统 PATH）
        try {
            $sysEnvKey = "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment"
            $sysPath = (Get-ItemProperty -Path $sysEnvKey -ErrorAction Stop).Path
            if ($sysPath) {
                $env:PATH = "$sysPath;$env:PATH"
            }
        } catch {}

        # 再次确认 Docker 已安装
        if (Test-DockerInstalled) {
            Write-Log "Docker Desktop 安装完成"
        } else {
            Write-Log "未检测到 docker 命令" "ERROR"
            Write-Log "请确认 Docker Desktop 是否已安装成功" "ERROR"
            exit 1
        }
    }

    # 确保 Docker daemon 已启动（静默安装后不会自动启动；重启续装时也需要手动拉起）
    if (-not (Test-DockerRunning)) {
        # 先检查/更新 WSL 内核，避免 Docker Desktop 启动时报 WSL 版本过旧
        # （放这里而非安装前：无论是新装、重启续装还是 Docker 已装未运行，都会走到）
        Write-Log "检查 WSL 内核版本..."
        Update-Wsl

        $dockerDesktopExe = "$ProgramFilesNative\Docker\Docker\Docker Desktop.exe"
        if (Test-Path $dockerDesktopExe) {
            Write-Log "启动 Docker Desktop..."
            # 注意：本脚本以管理员身份运行，直接 Start-Process 会让 Docker Desktop
            # 继承管理员权限运行——这种模式下 Docker Desktop 经常启动失败
            # （用户双击是普通权限，反而正常）。
            # 通过 explorer.exe 中转启动，使其回落到当前登录用户的普通权限。
            Start-Process -FilePath "explorer.exe" -ArgumentList "`"$dockerDesktopExe`""
        } else {
            Write-Log "未找到 Docker Desktop.exe，请手动启动 Docker Desktop" "WARN"
        }

        Write-Log "等待 Docker Desktop 启动（首次启动可能需要几分钟）..."
        Write-Log "请查看右下角托盘，Docker 鲸鱼图标变绿表示已就绪"

        $dockerTimeout = 300
        $waited = 0
        $fallbackTried = $false
        while ($waited -lt $dockerTimeout) {
            if (Test-DockerRunning) {
                break
            }
            # explorer 中转若无效，60 秒后用 runas /trustlevel 再补一次
            # （trustlevel:0x20000 以受限令牌运行，即普通用户权限，无需密码）
            if (-not $fallbackTried -and $waited -ge 60 -and (Test-Path $dockerDesktopExe)) {
                Write-Log "  尝试备用启动方式（runas /trustlevel 降权）..."
                $prevEap = $ErrorActionPreference
                $ErrorActionPreference = "Continue"
                try {
                    Start-Process -FilePath "runas.exe" `
                        -ArgumentList "/trustlevel:0x20000", "`"$dockerDesktopExe`"" 2>&1 | Out-Null
                } catch {
                    Write-Log "  runas 降权启动失败: $_" "WARN"
                } finally {
                    $ErrorActionPreference = $prevEap
                }
                $fallbackTried = $true
            }
            # 若 Docker Desktop 进程根本没存活（启动即失败或闪退），每 60 秒补拉一次
            if ($waited -gt 0 -and $waited % 60 -eq 0 `
                -and $null -eq (Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue) `
                -and (Test-Path $dockerDesktopExe)) {
                Write-Log "  Docker Desktop 进程未运行，重新尝试启动..."
                Start-Process -FilePath "explorer.exe" -ArgumentList "`"$dockerDesktopExe`""
            }
            Start-Sleep -Seconds 5
            $waited += 5
            if ($waited % 30 -eq 0) {
                Write-Log "  等待中... $waited s / $dockerTimeout s"
            }
        }

        if (-not (Test-DockerRunning)) {
            Write-Log "Docker 尚未就绪" "WARN"
            # 把失败原因写进日志，便于排查（进程状态、docker info 的错误输出、WSL 状态）
            Write-Log "---- 诊断信息 ----"
            $ddProc = Get-Process -Name "Docker Desktop" -ErrorAction SilentlyContinue
            if ($ddProc) {
                Write-Log "  [进程] Docker Desktop 运行中（PID: $($ddProc.Id -join ', ')），但引擎未就绪"
            } else {
                Write-Log "  [进程] Docker Desktop 未运行（自动启动失败或已退出）"
            }
            $prevEap = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            try {
                $infoOut = docker info 2>&1
                $infoOut | Where-Object { "$_" -match '\S' } | ForEach-Object { Write-Log "  [docker info] $_" }
            } finally {
                $ErrorActionPreference = $prevEap
            }
            $wslResult = Invoke-WslCommand @("--status")
            $wslResult.Output | ForEach-Object { Write-Log "  [wsl --status] $_" }
            Write-Log "------------------"
            Write-Log ""

            # 弹窗提醒用户手工启动 Docker Desktop，再给 5 分钟等待窗口
            Add-Type -AssemblyName System.Windows.Forms | Out-Null
            [System.Windows.Forms.MessageBox]::Show(
                "Docker Desktop 自动启动未成功。`n`n请手工启动 Docker Desktop：`n双击桌面或开始菜单中的 Docker Desktop 图标，`n等右下角托盘的鲸鱼图标停止动画（变绿）。`n`n点击「确定」后，安装程序会继续等待其就绪。",
                "盘家智管 - 请手工启动 Docker",
                [System.Windows.Forms.MessageBoxButtons]::OK,
                [System.Windows.Forms.MessageBoxIcon]::Warning) | Out-Null
            Write-Log "已弹窗提醒用户手工启动 Docker Desktop，继续等待（最长 300 秒）..."
            $manualTimeout = 300
            $manualWaited = 0
            while ($manualWaited -lt $manualTimeout -and -not (Test-DockerRunning)) {
                Start-Sleep -Seconds 5
                $manualWaited += 5
                if ($manualWaited % 30 -eq 0) {
                    Write-Log "  等待手工启动... $manualWaited s / $manualTimeout s"
                }
            }

            if (Test-DockerRunning) {
                Write-Log "Docker 已手工启动并就绪，继续安装"
            } else {
                Write-Log "手工启动等待超时，Docker 仍未就绪" "ERROR"
                Write-Log "请检查：" "WARN"
                Write-Log "  1. 右下角托盘是否有 Docker 图标（鲸鱼）" "WARN"
                Write-Log "  2. Docker 是否提示需要更新 WSL 或重启电脑" "WARN"
                Write-Log "  3. 如果提示需要重启，请重启电脑后重新运行本安装程序" "WARN"
                Write-Log "  4. 若 docker info 提示虚拟化/WSL 相关错误，" "WARN"
                Write-Log "     请确认 BIOS 已开启虚拟化（VT-x/AMD-V），" "WARN"
                Write-Log "     且 Windows「虚拟机平台」功能已启用" "WARN"
                Write-Log ""
                Write-Log "重新运行后会从断点继续，不会重复安装" "WARN"
                [System.Windows.Forms.MessageBox]::Show(
                    "Docker Desktop 仍未就绪，安装暂时中止。`n`n请手工启动 Docker Desktop（桌面或开始菜单双击图标），`n确认右下角鲸鱼图标变绿后，重新运行本安装程序。`n`n之前已完成的步骤会自动跳过，不会重复安装。",
                    "盘家智管 - Docker 未就绪",
                    [System.Windows.Forms.MessageBoxButtons]::OK,
                    [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
                exit 1
            }
        }
    }

    Write-Log "Docker 运行正常"

    # 检查 docker compose
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $composeVer = docker compose version 2>&1
    $composeExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($composeExit -ne 0) {
        Write-Log "docker compose 不可用: $composeVer" "ERROR"
        exit 1
    }
    Write-Log "docker compose 可用: $composeVer"

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

    # Docker Desktop 以普通用户权限运行（文件共享层同样非提权），
    # 而 Program Files 下的目录 ACL 只允许管理员写入，
    # 容器写宿主目录（postgres/redis 数据、业务日志）会报 permission denied。
    # 授权 Users 对 data/logs 修改权限（仅这两个目录，最小化范围）。
    foreach ($writableDir in @("$InstallDir\data", "$InstallDir\logs")) {
        if (Test-Path $writableDir) {
            $icaclOut = icacls $writableDir /grant "Users:(OI)(CI)M" 2>&1
            Write-Log "  已授权 Users 写入: $writableDir"
        }
    }

    Write-Log "目录结构创建完成: $InstallDir"
    Set-Progress 3
}

# ==================== 步骤 4：部署配置文件 ====================
if (-not (Test-StepDone 4)) {
    Write-Step 4 $TotalSteps "部署配置文件"

    # NSIS 调用时配置文件已被直接释放到 $InstallDir（源和目标是同一个文件），
    # Copy-Item 会报 "无法使用项自身覆盖该项"；独立运行时源在安装包目录，正常复制。
    function Copy-ConfigFile {
        param([string]$Source, [string]$Dest, [string]$Name)
        if (-not (Test-Path $Source)) {
            Write-Log "警告: 未找到 $Name" "WARN"
            return
        }
        $srcFull = [System.IO.Path]::GetFullPath($Source)
        $dstFull = [System.IO.Path]::GetFullPath($Dest)
        if ($srcFull -ieq $dstFull) {
            Write-Log "$Name 已就位（安装器已释放），跳过复制"
            return
        }
        Copy-Item $Source $Dest -Force
        Write-Log "$Name 已部署"
    }

    Copy-ConfigFile -Source "$PSScriptRoot\..\config\docker-compose.yml" `
        -Dest "$InstallDir\config\docker-compose.yml" -Name "docker-compose.yml"

    Copy-ConfigFile -Source "$PSScriptRoot\..\config\nginx.conf" `
        -Dest "$InstallDir\nginx\nginx.conf" -Name "nginx.conf"

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

        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        docker inspect $ImageName 2>&1 | Out-Null
        $inspectExit = $LASTEXITCODE
        $ErrorActionPreference = $prevEap
        if ($inspectExit -eq 0) {
            Write-Log "  [$ImageName] 已存在，跳过"
            return
        }

        if (Test-Path $LocalTarFile) {
            Write-Log "  [$ImageName] 从本地镜像包加载..."
            $prevEap = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            docker load -i $LocalTarFile 2>&1 | ForEach-Object { Write-Log "    $_" }
            $loadExit = $LASTEXITCODE
            $ErrorActionPreference = $prevEap
            if ($loadExit -eq 0) {
                Write-Log "  [$ImageName] 本地加载成功"
                return
            }
            Write-Log "  [$ImageName] 本地加载失败（退出码 $loadExit）" "WARN"
        }

        if ($RemoteImage) {
            Write-Log "  [$ImageName] 从远程拉取..."
            $prevEap = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            docker pull $RemoteImage 2>&1 | ForEach-Object { Write-Log "    $_" }
            $pullExit = $LASTEXITCODE
            $ErrorActionPreference = $prevEap
            if ($pullExit -ne 0) {
                Write-Log "  [$ImageName] 远程拉取失败（退出码 $pullExit）" "WARN"
            } else {
                if ($RemoteImage -ne $ImageName) {
                    docker tag $RemoteImage $ImageName 2>&1 | Out-Null
                }
                Write-Log "  [$ImageName] 远程拉取成功"
                return
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

    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker inspect $serverImageName 2>&1 | Out-Null
    $serverInspectExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap

    if ($serverInspectExit -eq 0) {
        Write-Log "  [$serverImageName] 已存在，跳过"
    } elseif (Test-Path $serverTar) {
        Write-Log "  [$serverImageName] 从本地镜像包加载 ($([math]::Round((Get-Item $serverTar).Length / 1MB, 1)) MB)..."
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        docker load -i $serverTar 2>&1 | ForEach-Object { Write-Log "    $_" }
        $loadExit = $LASTEXITCODE
        $ErrorActionPreference = $prevEap
        if ($loadExit -ne 0) {
            Write-Log "  [$serverImageName] 本地加载失败（退出码 $loadExit）" "WARN"
            throw "业务镜像加载失败"
        }
        Write-Log "  [$serverImageName] 本地加载成功"
    } else {
        Write-Log "  [$serverImageName] 未找到本地镜像包" "ERROR"
        Write-Log "  离线安装需要 panjia-server.tar 镜像包" "ERROR"
        Write-Log "  请确认安装包是否完整，或联系技术支持" "ERROR"
        exit 1
    }

    Write-Log ""
    Write-Log "全部镜像就绪"
    Set-Progress 5
}

# ==================== 机器指纹文件（授权绑定依赖，幂等） ====================
# 后端授权校验要求容器内存在 /etc/machine-id（生产 Linux 由宿主机直接挂载）。
# Windows 宿主机没有该文件，改用注册表 MachineGuid（随 Windows 安装生成，
# 重装系统才变化，比 WSL 的 machine-id 稳定）写入固定文件后挂载进容器。
# 幂等设计：文件已存在则不覆盖——重装/升级不换机，授权不受影响。
$machineIdFile = "$InstallDir\config\machine-id"
if (-not (Test-Path $machineIdFile)) {
    Write-Log "生成机器指纹文件（授权绑定用）..."

    $machineGuid = $null
    try {
        $machineGuid = (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Cryptography" -ErrorAction Stop).MachineGuid
        Write-Log "  使用 Windows MachineGuid 作为机器指纹"
    } catch {
        Write-Log "  MachineGuid 读取失败，改用随机指纹（本机首次生成后固定）" "WARN"
    }
    if ([string]::IsNullOrWhiteSpace($machineGuid)) {
        $machineGuid = [guid]::NewGuid().ToString()
    }

    # ASCII 无 BOM、无换行：容器内按纯文本整行读取，BOM/CRLF 会污染指纹
    [IO.File]::WriteAllText($machineIdFile, $machineGuid, (New-Object System.Text.ASCIIEncoding))
    Write-Log "  机器指纹文件已生成: $machineIdFile"

    # 升级场景：容器已在运行但缺挂载 → 重建 server 容器使挂载生效
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $existingServer = docker ps -a --filter "name=panjia-server" --format "{{.Names}}" 2>$null
    $ErrorActionPreference = $prevEap
    if ($existingServer) {
        Write-Log "  重建 server 容器以挂载机器指纹..."
        Push-Location "$InstallDir\config"
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        docker compose up -d server 2>&1 | ForEach-Object { Write-Log "  $_" }
        $ErrorActionPreference = $prevEap
        Pop-Location
    }
} else {
    Write-Log "机器指纹文件已存在（重装不换机）: $machineIdFile"
}

# ==================== Docker Desktop 登录自启（幂等） ====================
# 静默安装场景下 Docker Desktop 可能未注册自启 Run 键，
# 导致重启后 Docker 不随登录启动、容器（unless-stopped）也无法拉起。
$ddExeForRun = "$ProgramFilesNative\Docker\Docker\Docker Desktop.exe"
$runKeyPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
if (Test-Path $ddExeForRun) {
    $existingRun = (Get-ItemProperty $runKeyPath -Name "Docker Desktop" -ErrorAction SilentlyContinue)."Docker Desktop"
    if ([string]::IsNullOrWhiteSpace($existingRun)) {
        New-ItemProperty -Path $runKeyPath -Name "Docker Desktop" `
            -Value "`"$ddExeForRun`" -Autostart" -PropertyType String -Force | Out-Null
        Write-Log "已注册 Docker Desktop 开机自启（登录时启动）"
    } else {
        Write-Log "Docker Desktop 开机自启已存在，无需处理"
    }
}

# ==================== 步骤 6：启动服务 ====================
if (-not (Test-StepDone 6)) {
    Write-Step 6 $TotalSteps "启动服务"

    $configDir = "$InstallDir\config"
    Set-Location $configDir

    Write-Log "启动容器（docker compose up -d）..."
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker compose up -d 2>&1 | ForEach-Object { Write-Log "  $_" }
    $upExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($upExit -ne 0) {
        Write-Log "容器启动失败（退出码 $upExit），请查看上方日志" "ERROR"
        exit 1
    }

    # 等待健康检查
    Write-Log "等待服务启动（最多 5 分钟）..."
    $maxWait = 300
    $waited = 0
    $allHealthy = $false

    while ($waited -lt $maxWait) {
        # 注意：不要用 docker compose ps --format json——实测在 PS 5.1 下有两个坑：
        #   ① 输出含 UTF-8 字符（如 /run/desktop/mnp 的特殊字符），被 GBK 控制台
        #      解码后变成乱码，JSON 本身已损坏，ConvertFrom-Json 必然失败；
        #   ② PS 5.1 的 ConvertFrom-Json 抛的是 .NET 级 ArgumentException，
        #      -ErrorAction SilentlyContinue 压不住（try/catch 才能接住），报错刷屏。
        # 改用 docker inspect 模板输出，纯 ASCII，无编码/无 JSON 解析风险。
        $status = @()
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $cids = @(docker compose ps -aq 2>$null)
        foreach ($cid in $cids) {
            $line = docker inspect --format "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" $cid 2>$null
            if ("$line" -match '^(\S+) (\S+)$') {
                $status += [pscustomobject]@{ State = $matches[1]; Health = $matches[2] }
            }
        }
        $ErrorActionPreference = $prevEap
        if ($status.Count -gt 0) {
            $totalCount = @($status).Count
            $healthyCount = @($status | Where-Object {
                $_.State -eq "running" -and ($_.Health -eq "healthy" -or $_.Health -eq "none")
            }).Count
            if ($healthyCount -eq $totalCount -and $totalCount -gt 0) {
                $allHealthy = $true
                break
            }
            if ($waited -gt 0 -and $waited % 30 -eq 0) {
                Write-Log "  等待中... 健康 $healthyCount/$totalCount（$waited s / $maxWait s）"
            }
        } elseif ($waited -gt 0 -and $waited % 30 -eq 0) {
            Write-Log "  等待中... $waited s / $maxWait s（暂无法读取容器状态）"
        }
        Start-Sleep -Seconds 5
        $waited += 5
    }

    if (-not $allHealthy) {
        Write-Log "警告: 部分容器健康检查未通过，继续下一步..." "WARN"
    }

    Write-Log "服务启动完成"
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    docker compose ps 2>&1 | ForEach-Object { Write-Log "  $_" }
    $ErrorActionPreference = $prevEap
    Set-Progress 6
}

# ==================== 步骤 7：激活授权 ====================
if (-not (Test-StepDone 7)) {
    Write-Step 7 $TotalSteps "激活授权码"

    if ([string]::IsNullOrWhiteSpace($AuthCode)) {
        Write-Log "警告: 未提供授权码，跳过自动激活" "WARN"
        Write-Log "手动激活方法：编辑 $InstallDir\config\.env 设置 PANJIA_AUTH_CODE=你的授权码，"
        Write-Log "然后执行: cd `"$InstallDir\config`" ; docker compose up -d server"
    } else {
        Write-Log "授权服务器: $LicenseServer"
        Write-Log "激活方式: 后端启动时读取 .env 中的授权码，自动向授权服务器激活"

        # 激活发生在后端启动流程里（initOnStartup → 调授权服务器 /api/auth/activate），
        # 成功的标志是把 token 落盘（容器 /data/panjia-license/.panjia_token
        # → 宿主机 data\panjia-license\.panjia_token）。
        # 注意：不能调 http://localhost:8080——server 服务只有 expose 没有 ports，
        # 宿主机根本访问不到 8080；且后端也不存在 /api/license/activate 接口。
        $tokenFile = "$InstallDir\data\panjia-license\.panjia_token"
        $activated = $false
        for ($i = 0; $i -lt 60; $i++) {
            if (Test-Path $tokenFile) {
                $activated = $true
                break
            }
            if ($i -gt 0 -and $i % 10 -eq 0) {
                Write-Log "  等待后端自动激活...（$($i * 5) s / 300 s）"
            }
            Start-Sleep -Seconds 5
        }
        if ($activated) {
            Write-Log "授权激活成功（token 已生成）"
        } else {
            Write-Log "警告: 300 秒内未检测到激活 token" "WARN"
            Write-Log "可能原因: 授权码无效 / 授权服务器不可达 / 后端未启动完成"
            Write-Log "请查看后端日志排查: docker logs panjia-server --tail 100"
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
