# ============================================================================
# 盘家智管 - 卸载脚本
# ============================================================================

param(
    [string]$InstallDir = "C:\Program Files\Panjia"
)

$ErrorActionPreference = "SilentlyContinue"

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
    Remove-Item -Path "$InstallDir\scripts", "$InstallDir\nginx", "$InstallDir\web" -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host " ✓ 程序文件已删除，数据已保留"
}

Write-Host ""
Write-Host "=========================================="
Write-Host " ✓ 卸载完成"
Write-Host "=========================================="

Start-Sleep -Seconds 2
