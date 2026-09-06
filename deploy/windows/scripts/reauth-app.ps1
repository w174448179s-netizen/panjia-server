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
#   6. 删除旧 token 文件 + 轮询 data\panjia-license\.panjia_token 是否更新/创建（最长 180s）
#   7. 弹 MessageBox 反馈成功/失败原因
#
# 注意：
#   - 激活本身由后端 initOnStartup 流程发起（读 .env 的 PANJIA_AUTH_CODE → 调授权服务器）
#   - 容器会写 data/panjia-license/.panjia_token（宿主机路径 = $InstallDir\data\panjia-license\.panjia_token）
#   - 如果授权码绑定了机器指纹（machine-id），换机器必须先在授权后台解绑
#
# 退出码：0 激活成功/用户取消；1 激活失败
# ============================================================================

param(
    [string]$InstallDir = "C:\Program Files\Panjia"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# 64 位真实 Program Files（本脚本可能由 32 位 PowerShell 跑，$env:ProgramFiles 会被 WOW64 重定向）
$ProgramFilesNative = if (${env:ProgramW6432}) { ${env:ProgramW6432} } else { $env:ProgramFiles }

# ==================== 管理员权限自检 + 自动提权 ====================
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Add-Type -AssemblyName System.Windows.Forms | Out-Null
    [System.Windows.Forms.MessageBox]::Show(
        "重新激活授权需要管理员权限（要改写 .env 和重启 Docker 服务）。`n`n即将弹出 UAC 提权窗口，请点「是」。",
        "盘家智管 - 重新激活授权",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
    try {
        $argList = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", ('"{0}"' -f $PSCommandPath),
            "-InstallDir", ('"{0}"' -f $InstallDir)
        )
        Start-Process -FilePath "powershell" -Verb RunAs -ArgumentList $argList -Wait | Out-Null
    } catch {
        [System.Windows.Forms.MessageBox]::Show(
            "提权被取消或失败，无法重新激活。`n`n请右键本文件，选择「以管理员身份运行」。",
            "盘家智管 - 错误",
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    }
    exit 0
}

Add-Type -AssemblyName System.Windows.Forms | Out-Null
Add-Type -AssemblyName Microsoft.VisualBasic | Out-Null

# ==================== 依赖检查 ====================
if (-not (Test-Path "$InstallDir\config\.env")) {
    [System.Windows.Forms.MessageBox]::Show(
        "未找到 .env 文件：$InstallDir\config\.env`n`n请确认盘家智管已正确安装。",
        "盘家智管 - 错误",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}

$envFile = "$InstallDir\config\.env"
$tokenFile = "$InstallDir\data\panjia-license\.panjia_token"

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
# Microsoft.VisualBasic.Interaction.InputBox 返回空字符串无法区分"输入空"和"点 Cancel"。
# 用全局变量 $script:InputBoxCancelled 区分——控件的 Cancel 事件触发时设为 $true。
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
    public bool Cancelled;

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
    exit 0
}
$newCode = $inputBox.Value
$inputBox.Dispose()

# 去除可能的引号和首尾空格
$newCode = $newCode.Trim().Trim('"', "'")

# 验证非空
if ([string]::IsNullOrWhiteSpace($newCode)) {
    [System.Windows.Forms.MessageBox]::Show(
        "授权码为空，已取消激活。",
        "盘家智管 - 重新激活授权",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Warning) | Out-Null
    exit 0
}

# 如果与当前码一致，给个提示但仍继续（避免用户误以为脚本没干活）
if ($newCode -eq $currentCode -and -not [string]::IsNullOrWhiteSpace($currentCode)) {
    $yn = [System.Windows.Forms.MessageBox]::Show(
        "新授权码与当前一致，是否仍要重启服务重新激活？",
        "盘家智管 - 确认",
        [System.Windows.Forms.MessageBoxButtons]::YesNo,
        [System.Windows.Forms.MessageBoxIcon]::Question)
    if ($yn -ne [System.Windows.Forms.DialogResult]::Yes) {
        exit 0
    }
}

# ==================== 备份并改写 .env ====================
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupFile = "$envFile.bak.$timestamp"
try {
    Copy-Item -Path $envFile -Destination $backupFile -Force
} catch {
    [System.Windows.Forms.MessageBox]::Show(
        "备份 .env 失败：$($_.Exception.Message)",
        "盘家智管 - 错误",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
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
    [System.Windows.Forms.MessageBox]::Show(
        "写入 .env 失败：$($_.Exception.Message)`n`n请检查文件是否被占用（关掉编辑器/重启电脑再试）",
        "盘家智管 - 错误",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}

[System.Windows.Forms.MessageBox]::Show(
    ".env 已更新（原文件已备份为 .env.bak.$timestamp）。`n`n接下来将重启盘家智管服务以触发重新激活...",
    "盘家智管 - 重新激活授权",
    [System.Windows.Forms.MessageBoxButtons]::OK,
    [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null

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
docker compose up -d server 2>&1 | Out-Null
$composeExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap

if ($composeExit -ne 0) {
    [System.Windows.Forms.MessageBox]::Show(
        "docker compose 重启 server 失败（退出码 $composeExit）。`n`n请确认 Docker Desktop 已启动，然后手动执行：`n  cd `"$configDir`"`n  docker compose up -d server",
        "盘家智管 - 错误",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}

# ==================== 等待 token 文件出现（最长 180s）====================
# 记录重启前 token 文件的修改时间，用于区分"新建"和"已存在的旧 token"。
$tokenMtimeBefore = $null
if (Test-Path $tokenFile) {
    $tokenMtimeBefore = (Get-Item $tokenFile).LastWriteTime
}

$activated = $false
for ($i = 0; $i -lt 36; $i++) {
    if (Test-Path $tokenFile) {
        $mtime = (Get-Item $tokenFile).LastWriteTime
        # 文件新建（之前不存在）或修改时间更新于重启前
        if ($null -eq $tokenMtimeBefore -or $mtime -gt $tokenMtimeBefore) {
            $activated = $true
            break
        }
    }
    Start-Sleep -Seconds 5
}

# ==================== 反馈结果 ====================
if ($activated) {
    [System.Windows.Forms.MessageBox]::Show(
        "✓ 授权重新激活成功！`n`n可访问 http://localhost 验证。",
        "盘家智管 - 重新激活授权",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
    exit 0
} else {
    $tips = @"
✗ 180 秒内未检测到激活成功标志。

可能原因：
  1. 授权码无效或已过期（请联系系统管理员确认）
  2. 授权码绑定了其他机器的指纹（换机器必须在授权后台先解绑）
  3. 授权服务器不可达（检查 $InstallDir\config\.env 的 PANJIA_LICENSE_SERVER 配置）
  4. 后端启动未完成（server 容器首次启动较慢）

排查命令：
  docker logs panjia-server --tail 100
"@
    [System.Windows.Forms.MessageBox]::Show(
        $tips,
        "盘家智管 - 重新激活授权失败",
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Warning) | Out-Null
    exit 1
}