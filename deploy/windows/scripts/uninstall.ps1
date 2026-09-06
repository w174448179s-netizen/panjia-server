# ============================================================================
# 盘家智管 - 卸载脚本
# ============================================================================

param(
    [string]$InstallDir = "C:\Program Files\Panjia"
)

$ErrorActionPreference = "SilentlyContinue"

# 64 位真实 Program Files（本脚本可能由 32 位进程拉起，$env:ProgramFiles 会被 WOW64 重定向）
$ProgramFilesNative = if (${env:ProgramW6432}) { ${env:ProgramW6432} } else { $env:ProgramFiles }

Write-Host "=========================================="
Write-Host " 盘家智管卸载"
Write-Host "=========================================="
Write-Host ""

# 停止服务
Write-Host "停止盘家智管服务..."
if (Test-Path "$InstallDir\config\docker-compose.yml") {
    Set-Location "$InstallDir\config"
    docker compose down 2>&1 | Out-Null
    Write-Host " ✓ 服务已停止"
} else {
    Write-Host " 未找到服务配置，跳过"
}

# 删除 Docker 镜像（可选，节省空间）
Write-Host ""
$confirm = Read-Host "是否删除 Docker 镜像？(y/N)"
if ($confirm -eq "y" -or $confirm -eq "Y") {
    Write-Host " 删除 Docker 镜像..."
    docker rmi panjia-server:latest 2>&1 | Out-Null
    docker rmi postgres:16.9 2>&1 | Out-Null
    docker rmi redis:7-alpine 2>&1 | Out-Null
    docker rmi nginx:stable-alpine 2>&1 | Out-Null
    Write-Host " ✓ 镜像已删除"
} else {
    Write-Host " 保留 Docker 镜像"
}

# 询问是否卸载 Docker Desktop（可选）
# 默认保留：Docker 是平台组件，客户机器可能有其他用途；
# 但很多客户的 Docker 是本安装器装的，彻底清理时应提供选项。
Write-Host ""
$removeDocker = Read-Host "是否卸载 Docker Desktop？(y/N)"
if ($removeDocker -eq "y" -or $removeDocker -eq "Y") {
    $dockerInstaller = "$ProgramFilesNative\Docker\Docker\Docker Desktop Installer.exe"
    if (Test-Path $dockerInstaller) {
        Write-Host " 卸载 Docker Desktop（可能需要几分钟）..."
        try {
            $proc = Start-Process -FilePath $dockerInstaller -ArgumentList "uninstall", "--quiet" -Wait -PassThru
            if ($proc.ExitCode -eq 0 -or $proc.ExitCode -eq 3010) {
                Write-Host " ✓ Docker Desktop 已卸载（建议重启电脑完成清理）"
            } else {
                Write-Host " 卸载程序退出码 $($proc.ExitCode)，请检查 Docker 是否仍在（设置-应用）"
            }
        } catch {
            Write-Host " 卸载失败: $($_.Exception.Message)"
            Write-Host " 请从 设置-应用 中手动卸载 Docker Desktop"
        }
    } else {
        Write-Host " 未找到 Docker Desktop 安装程序"
        Write-Host " 如需卸载请从 设置-应用 中手动操作"
    }
} else {
    Write-Host " 保留 Docker Desktop（重新安装盘家智管时无需重装 Docker）"
}

# 询问是否保留数据
Write-Host ""
$keepData = Read-Host "是否保留数据（数据库、授权信息）？(Y/n)"
if ($keepData -eq "n" -or $keepData -eq "N") {
    Write-Host " 删除所有数据..."
    if (Test-Path $InstallDir) {
        Remove-Item -Path $InstallDir -Recurse -Force
        Write-Host " ✓ 数据已全部删除"
    }
} else {
    Write-Host " 保留数据（$InstallDir\data\）"
    # 只删除程序文件，保留数据
    if (Test-Path "$InstallDir\config") {
        Remove-Item -Path "$InstallDir\config\*.yml", "$InstallDir\config\.env" -Force -ErrorAction SilentlyContinue
    }
    # 进度文件必须删掉，否则下次安装会误认为未完成而跳过前几步
    Remove-Item -Path "$InstallDir\config\install-progress.ini" -Force -ErrorAction SilentlyContinue
    Remove-Item -Path "$InstallDir\scripts", "$InstallDir\nginx", "$InstallDir\web" -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host " ✓ 程序文件已删除，数据已保留"
}

Write-Host ""
Write-Host "=========================================="
Write-Host " ✓ 卸载完成"
Write-Host "=========================================="

Start-Sleep -Seconds 2
