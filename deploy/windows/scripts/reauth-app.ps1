# ============================================================================
# 盘家智管 - 重新激活授权工具
# ============================================================================
# 场景：安装时授权码输错、客户机器换了但授权未解绑、授权过期需续期等。
#
# 行为：
#   1. 检测管理员权限（缺失则自动 UAC 提权重启自身）
#   2. 弹窗输入新授权码（默认填入当前 .env 中的码，方便微调）
#   3. 备份当前 .env（.env.bak.YYYYMMDD-HHmmss）
#   4. 改写 .env 的 PANJIA_AUTH_CODE 行（其它配置不动；不存在则追加）
#   5. docker compose up -d server 重建容器（容器启动时自动调授权服务器激活）
#   6. 删除旧 token 文件 + 轮询 data\panjia-license\.panjia_token 是否更新/创建（最长 240s）
#   7. 弹 MessageBox 反馈成功/失败原因
#
# 注意：
#   - 激活本身由后端 initOnStartup 流程发起（读 .env 的 PANJIA_AUTH_CODE → 调授权服务器）
#   - 容器会写 data/panjia-license/.panjia_token（宿主机路径 = $InstallDir\data\panjia-license\.panjia_token）
#   - 如果授权码绑定了机器指纹（machine-id），换机器必须先在授权后台解绑
#   - 本脚本可能被 NSIS 安装器（nsExec）拉起：安装器窗口在前台，所有弹窗必须
#     TopMost，否则被挡住后脚本阻塞等待点击，安装器看起来像"卡死"。
#     进度信息一律走 Write-Host（nsExec 会把 stdout 写进安装器详情日志）。
#
# 退出码：0 激活成功；1 激活失败；2 用户取消（供安装器失败重试分支区分）
# ============================================================================

param(
    [string]$InstallDir = ""
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# 64 位真实 Program Files（本脚本可能由 32 位 PowerShell 跑，$env:ProgramFiles 会被 WOW64 重定向）
$ProgramFilesNative = if (${env:ProgramW6432}) { ${env:ProgramW6432} } else { $env:ProgramFiles }

# 安装目录：未传参时按脚本自身位置推断（脚本位于 $INSTDIR\scripts\ 下，取上上级）。
# 兼容安装到 Program Files (x86)\Panjia（32 位安装器默认路径）或自定义盘符的情况。
if ([string]::IsNullOrWhiteSpace($InstallDir)) {
    $InstallDir = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
}

# ==================== 置顶弹窗助手 ====================
# 用 TopMost 的隐形 Form 作为 owner，保证 MessageBox 永远显示在最上层，
# 不会被前台的 NSIS 安装器窗口挡住（否则脚本阻塞等点击，安装器看起来卡死）。
function Show-MsgBox {
    param($Text, $Title, $Buttons, $Icon)
    if (-not $Buttons) { $Buttons = [System.Windows.Forms.MessageBoxButtons]::OK }
    if (-not $Icon) { $Icon = [System.Windows.Forms.MessageBoxIcon]::Information }
    $owner = New-Object System.Windows.Forms.Form
    $owner.TopMost = $true
    $owner.ShowInTaskbar = $false
    $owner.WindowState = [System.Windows.Forms.FormWindowState]::Minimized
    $owner.Opacity = 0
    $owner.Show()
    try {
        return [System.Windows.Forms.MessageBox]::Show($owner, $Text, $Title, $Buttons, $Icon)
    } finally {
        $owner.Close()
        $owner.Dispose()
    }
}

# ==================== 管理员权限自检 + 自动提权 ====================
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Add-Type -AssemblyName System.Windows.Forms | Out-Null
    Show-MsgBox -Text "重新激活授权需要管理员权限（要改写 .env 和重启 Docker 服务）。`n`n即将弹出 UAC 提权窗口，请点「是」。" -Title "盘家智管 - 重新激活授权" | Out-Null
    try {
        $argList = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", ('"{0}"' -f $PSCommandPath),
            "-InstallDir", ('"{0}"' -f $InstallDir)
        )
        Start-Process -FilePath "powershell" -Verb RunAs -ArgumentList $argList -Wait | Out-Null
    } catch {
        Show-MsgBox -Text "提权被取消或失败，无法重新激活。`n`n请右键本文件，选择「以管理员身份运行」。" -Title "盘家智管 - 错误" -Icon ([System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    }
    exit 0
}

Add-Type -AssemblyName System.Windows.Forms | Out-Null

Write-Host "盘家智管重新激活工具已启动"
Write-Host "安装目录: $InstallDir"

# ==================== 依赖检查 ====================
if (-not (Test-Path "$InstallDir\config\.env")) {
    Show-MsgBox -Text "未找到 .env 文件：$InstallDir\config\.env`n`n请确认盘家智管已正确安装。" -Title "盘家智管 - 错误" -Icon ([System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}

$envFile = "$InstallDir\config\.env"
$tokenFile = "$InstallDir\data\panjia-license\.panjia_token"

# ==================== 定位 docker.exe ====================
# 从 32 位上下文（NSIS 安装器）或提权后的精简 PATH 环境里跑时，
# PATH 可能不含 Docker（报 CommandNotFoundException）。显式解析完整路径兜底。
$DockerExe = $null
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue
$ErrorActionPreference = $prevEap
if ($dockerCmd) {
    $DockerExe = $dockerCmd.Source
}
if (-not $DockerExe) {
    $dockerCandidates = @(
        "$ProgramFilesNative\Docker\Docker\resources\bin\docker.exe",
        "$env:ProgramFiles\Docker\Docker\resources\bin\docker.exe"
    )
    foreach ($c in $dockerCandidates) {
        if ($c -and (Test-Path $c)) {
            $DockerExe = $c
            break
        }
    }
}
if (-not $DockerExe) {
    Show-MsgBox -Text "未找到 docker.exe。`n`n请确认 Docker Desktop 已安装并启动后重试；`n如仍未解决，请重启电脑后再试。" -Title "盘家智管 - 错误" -Icon ([System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}
# 把 docker.exe 所在目录前置到 PATH，保证后续所有 docker 调用稳定可用
$dockerBinDir = Split-Path -Parent $DockerExe
if (($env:PATH -split ';') -notcontains $dockerBinDir) {
    $env:PATH = "$dockerBinDir;$env:PATH"
}
Write-Host "Docker: $DockerExe"

# ==================== 读当前授权码作为默认值 ====================
$currentCode = ""
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$lines = @(Get-Content -Path $envFile -Encoding UTF8 -ErrorAction SilentlyContinue)
$ErrorActionPreference = $prevEap
foreach ($line in $lines) {
    if ($line -match '^PANJIA_AUTH_CODE=(.*)$') {
        $currentCode = $Matches[1].Trim()
        break
    }
}

# ==================== 弹窗输入新授权码 ====================
# 自定义 WinForms InputBox：TextResult.DialogResult 可以显式区分「确定」和「取消」，
# TopMost 保证不被前台的 NSIS 安装器窗口挡住。
$script:InputBoxCancelled = $false
Add-Type -TypeDefinition @"
using System;
using System.Drawing;
using System.Windows.Forms;

public class AuthCodeInputBox : Form
{
    private Label _lbl;
    private TextBox _txt;
    private Button _btnOk;
    private Button _btnCancel;
    public string Value { get { return _txt.Text; } }

    public AuthCodeInputBox(string title, string prompt, string defaultValue)
    {
        Text = title;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;
        TopMost = true;
        ClientSize = new Size(480, 150);
        AcceptButton = _btnOk;
        CancelButton = _btnCancel;

        _lbl = new Label();
        _lbl.Text = prompt;
        _lbl.Location = new Point(12, 12);
        _lbl.Size = new Size(456, 20);
        _lbl.AutoSize = false;
        Controls.Add(_lbl);

        _txt = new TextBox();
        _txt.Location = new Point(12, 40);
        _txt.Size = new Size(456, 25);
        _txt.Text = defaultValue;
        _txt.SelectAll();
        Controls.Add(_txt);

        _btnOk = new Button();
        _btnOk.Text = "确定";
        _btnOk.Location = new Point(310, 80);
        _btnOk.Size = new Size(75, 30);
        _btnOk.DialogResult = DialogResult.OK;
        Controls.Add(_btnOk);

        _btnCancel = new Button();
        _btnCancel.Text = "取消";
        _btnCancel.Location = new Point(393, 80);
        _btnCancel.Size = new Size(75, 30);
        _btnCancel.DialogResult = DialogResult.Cancel;
        Controls.Add(_btnCancel);
    }
}
"@ -ReferencedAssemblies "System.Drawing","System.Windows.Forms" | Out-Null

$inputBox = New-Object AuthCodeInputBox -ArgumentList @(
    "盘家智管 - 重新激活授权",
    "请输入新的授权码（联系系统管理员获取）：",
    $currentCode
)
$result = $inputBox.ShowDialog()
if ($result -ne [System.Windows.Forms.DialogResult]::OK) {
    # 用户点取消
    exit 2
}
$newCode = $inputBox.Value
$inputBox.Dispose()

# 去除可能的引号和首尾空格
$newCode = $newCode.Trim().Trim('"', "'")

# 验证非空
if ([string]::IsNullOrWhiteSpace($newCode)) {
    Show-MsgBox -Text "授权码为空，已取消激活。" -Title "盘家智管 - 重新激活授权" -Icon ([System.Windows.Forms.MessageBoxIcon]::Warning) | Out-Null
    exit 2
}

# 如果与当前码一致，给个提示但仍继续（避免用户误以为脚本没干活）
if ($newCode -eq $currentCode -and -not [string]::IsNullOrWhiteSpace($currentCode)) {
    $yn = Show-MsgBox -Text "新授权码与当前一致，是否仍要重启服务重新激活？" -Title "盘家智管 - 确认" -Buttons ([System.Windows.Forms.MessageBoxButtons]::YesNo) -Icon ([System.Windows.Forms.MessageBoxIcon]::Question)
    if ($yn -ne [System.Windows.Forms.DialogResult]::Yes) {
        exit 2
    }
}

# ==================== 备份并改写 .env ====================
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupFile = "$envFile.bak.$timestamp"
try {
    Copy-Item -Path $envFile -Destination $backupFile -Force
} catch {
    Show-MsgBox -Text "备份 .env 失败：$($_.Exception.Message)" -Title "盘家智管 - 错误" -Icon ([System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}

# 改写 PANJIA_AUTH_CODE 行（保留其它行原样）
$envLines = @(Get-Content -Path $envFile -Encoding UTF8)
$newLines = @()
$replaced = $false
foreach ($line in $envLines) {
    if ($line -match '^PANJIA_AUTH_CODE=.*$') {
        $newLines += "PANJIA_AUTH_CODE=$newCode"
        $replaced = $true
    } else {
        $newLines += $line
    }
}
if (-not $replaced) {
    # .env 里没有 PANJIA_AUTH_CODE 行，追加
    $newLines += "PANJIA_AUTH_CODE=$newCode"
}

try {
    $newLines | Out-File -FilePath $envFile -Encoding UTF8 -Force
} catch {
    Show-MsgBox -Text "写入 .env 失败：$($_.Exception.Message)`n`n请检查文件是否被占用（关掉编辑器/重启电脑再试）" -Title "盘家智管 - 错误" -Icon ([System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}

Write-Host ".env 已更新（原文件已备份为 .env.bak.$timestamp），正在重启服务触发重新激活..."

# ==================== 删除旧 token 文件 ====================
# 旧 token 是按当前 machine-id 激活的产物，新授权码若绑定不同机器必然失败。
# 删除以确保"成功标志"是本次激活产生。
if (Test-Path $tokenFile) {
    try {
        Remove-Item -Path $tokenFile -Force -ErrorAction SilentlyContinue
    } catch {}
}

# ==================== 重启 server 容器 ====================
$configDir = "$InstallDir\config"
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
Set-Location $configDir
& $DockerExe compose up -d server 2>&1 | Out-Null
$composeExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap

if ($composeExit -ne 0) {
    Show-MsgBox -Text "docker compose 重启 server 失败（退出码 $composeExit）。`n`n请确认 Docker Desktop 已启动，然后手动执行：`n  cd `"$configDir`"`n  docker compose up -d server" -Title "盘家智管 - 错误" -Icon ([System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}

# ==================== 等待 token 文件出现（最长 240s）====================
# 记录重启前 token 文件的修改时间，用于区分"新建"和"已存在的旧 token"。
$tokenMtimeBefore = $null
if (Test-Path $tokenFile) {
    $tokenMtimeBefore = (Get-Item $tokenFile).LastWriteTime
}

$maxWaitSeconds = 240
$activated = $false
for ($i = 0; $i -lt ($maxWaitSeconds / 5); $i++) {
    if (Test-Path $tokenFile) {
        $mtime = (Get-Item $tokenFile).LastWriteTime
        # 文件新建（之前不存在）或修改时间更新于重启前
        if ($null -eq $tokenMtimeBefore -or $mtime -gt $tokenMtimeBefore) {
            $activated = $true
            break
        }
    }
    if ($i % 2 -eq 0) {
        Write-Host "等待后端激活... ($($i * 5) s / $maxWaitSeconds s)"
    }
    Start-Sleep -Seconds 5
}

# ==================== 反馈结果 ====================
if ($activated) {
    Write-Host "授权重新激活成功"
    Show-MsgBox -Text "授权重新激活成功！`n`n可访问 http://localhost 验证。" -Title "盘家智管 - 重新激活授权" | Out-Null
    exit 0
} else {
    Write-Host "240 秒内未检测到激活成功标志"
    $tips = @"
激活失败：240 秒内未检测到激活成功标志。

可能原因：
  1. 授权码无效或已过期（请联系系统管理员确认）
  2. 授权码绑定了其他机器的指纹（换机器必须在授权后台先解绑）
  3. 授权服务器不可达（检查 $InstallDir\config\.env 的 PANJIA_LICENSE_SERVER_URL 配置）
  4. 后端启动未完成（server 容器首次启动较慢）

排查命令：
  docker logs panjia-server --tail 100
"@
    Show-MsgBox -Text $tips -Title "盘家智管 - 重新激活授权失败" -Icon ([System.Windows.Forms.MessageBoxIcon]::Warning) | Out-Null
    exit 1
}
