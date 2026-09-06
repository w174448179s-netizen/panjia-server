; ============================================================================
; 盘家智管 - 客户一键安装器（离线安装版）
;
; 功能：
;   1. 欢迎页 / 许可协议 / 安装路径选择
;   2. 授权码输入页
;   3. Docker 环境检测与安装
;   4. 加载本地镜像 + 部署服务
;   5. 激活授权
;   6. 开始菜单快捷方式 + 卸载程序
;
; 编译方法（Mac/Linux，用 Docker）：
;   sh build-installer.sh
;
; 编译方法（Windows）：
;   build-installer.bat
;
; 输出：panjia-setup.exe
; ============================================================================

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "nsDialogs.nsh"

; -------------------- 基本信息 --------------------
!define APPNAME "盘家智管"
!define APPVERSION "1.0.0"
!define APPPUBLISHER "盘家科技"
!define APPURL "https://www.panjia.icu"
!define DEFAULT_INSTALL_DIR "$PROGRAMFILES\Panjia"

; 默认授权服务器地址
!define DEFAULT_LICENSE_SERVER "https://panjia.icu"
!define DEFAULT_IMAGE_TAG "latest"

; -------------------- MUI 配置 --------------------
!define MUI_ABORTWARNING
!define MUI_ICON "${NSISDIR}\Contrib\Graphics\Icons\modern-install.ico"
!define MUI_UNICON "${NSISDIR}\Contrib\Graphics\Icons\modern-uninstall.ico"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "${NSISDIR}\Contrib\Graphics\Header\win.bmp"
!define MUI_WELCOMEFINISHPAGE_BITMAP "${NSISDIR}\Contrib\Graphics\Wizard\win.bmp"
!define MUI_LICENSEPAGE_CHECKBOX
!define MUI_DIRECTORYPAGE_TEXT_DESTINATION "选择安装目录"
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "立即启动盘家智管"
!define MUI_FINISHPAGE_RUN_FUNCTION "LaunchApp"
!define MUI_FINISHPAGE_LINK "访问盘家官网"
!define MUI_FINISHPAGE_LINK_LOCATION "https://www.panjia.icu"

; -------------------- 页面定义 --------------------
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "license.txt"
!insertmacro MUI_PAGE_DIRECTORY
Page custom AuthCodePage AuthCodePageLeave
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; -------------------- 语言 --------------------
!insertmacro MUI_LANGUAGE "SimpChinese"

; -------------------- 变量 --------------------
Var AuthCode
Var hAuthCodePage
Var hAuthCodeText

; ============================================================================
; 安装入口
; ============================================================================
Name "${APPNAME}"
OutFile "panjia-setup.exe"
InstallDir "${DEFAULT_INSTALL_DIR}"
InstallDirRegKey HKLM "Software\Panjia\${APPNAME}" "InstallDir"
RequestExecutionLevel admin
CRCCheck on
ShowInstDetails hide
ShowUnInstDetails hide

; ============================================================================
; 授权码输入页
; ============================================================================
Function AuthCodePage
    !insertmacro MUI_HEADER_TEXT "输入授权码" "请输入您的授权码以激活盘家智管"

    nsDialogs::Create 1018
    Pop $hAuthCodePage

    ${NSD_CreateLabel} 0 0 100% 20u "授权码："
    Pop $0

    ${NSD_CreateText} 0 24u 100% 24u ""
    Pop $hAuthCodeText

    ${NSD_CreateLabel} 0 60u 100% 40u "授权码由系统管理员在盘家智管运营后台生成。$\n如您尚未获取授权码，请联系您的系统管理员。"
    Pop $0

    nsDialogs::Show
FunctionEnd

Function AuthCodePageLeave
    ${NSD_GetText} $hAuthCodeText $AuthCode

    ${If} $AuthCode == ""
        MessageBox MB_OK|MB_ICONEXCLAMATION "请输入授权码！"
        Abort
    ${EndIf}
FunctionEnd

; ============================================================================
; 安装节
; ============================================================================
Section "主程序" SecMain
    SectionIn RO

    SetOutPath "$INSTDIR"

    ; 创建目录结构
    CreateDirectory "$INSTDIR\config"
    CreateDirectory "$INSTDIR\data\postgres"
    CreateDirectory "$INSTDIR\data\redis"
    CreateDirectory "$INSTDIR\data\panjia-license"
    CreateDirectory "$INSTDIR\logs"
    CreateDirectory "$INSTDIR\web\dist"
    CreateDirectory "$INSTDIR\nginx"
    CreateDirectory "$INSTDIR\scripts"
    CreateDirectory "$INSTDIR\images"

    ; 释放配置文件
    SetOutPath "$INSTDIR\config"
    File "config\docker-compose.yml"

    SetOutPath "$INSTDIR\nginx"
    File "config\nginx.conf"

    ; 释放脚本
    SetOutPath "$INSTDIR\scripts"
    File "scripts\install.ps1"
    File "scripts\uninstall.ps1"
    File "scripts\start-app.ps1"
    File "scripts\stop-app.ps1"

    ; 释放前端静态资源
    SetOutPath "$INSTDIR\web\dist"
    File /r "web\dist\*.*"

    ; 释放离线镜像包（有啥打啥，编译时自动包含 images 目录下所有 tar）
    SetOutPath "$INSTDIR\images"
    File /nonfatal /r "images\*.tar"

    ; 释放 Docker Desktop 安装包（有就打进去，离线安装用）
    SetOutPath "$INSTDIR\docker"
    File /nonfatal "docker\Docker Desktop Installer.exe"
    ; WSL 内核离线更新包（离线客户机避免 "WSL is too old" 报错）
    File /nonfatal "docker\wsl.msi"

    ; 写入安装信息
    WriteIniStr "$INSTDIR\config\install-config.ini" "General" "InstallDir" "$INSTDIR"
    WriteIniStr "$INSTDIR\config\install-config.ini" "General" "AuthCode" "$AuthCode"
    WriteIniStr "$INSTDIR\config\install-config.ini" "General" "LicenseServer" "${DEFAULT_LICENSE_SERVER}"
    WriteIniStr "$INSTDIR\config\install-config.ini" "General" "Version" "${APPVERSION}"

    ; 注册表信息
    WriteRegStr HKLM "Software\Panjia\${APPNAME}" "InstallDir" "$INSTDIR"
    WriteRegStr HKLM "Software\Panjia\${APPNAME}" "Version" "${APPVERSION}"
    WriteRegStr HKLM "Software\Panjia\${APPNAME}" "AuthCode" "$AuthCode"
    WriteRegStr HKLM "Software\Panjia\${APPNAME}" "LicenseServer" "${DEFAULT_LICENSE_SERVER}"

    ; 创建卸载程序
    WriteUninstaller "$INSTDIR\uninstall.exe"

    ; -------------------- 执行安装脚本 --------------------
    DetailPrint "=========================================="
    DetailPrint " 盘家智管安装程序"
    DetailPrint "=========================================="
    DetailPrint ""
    DetailPrint "安装目录: $INSTDIR"
    DetailPrint "授权服务器: ${DEFAULT_LICENSE_SERVER}"
    DetailPrint ""

    ; 检查 PowerShell
    DetailPrint "检查 PowerShell 环境..."
    nsExec::ExecToLog 'powershell -NoProfile -Command "exit 0"'
    Pop $R0
    ${If} $R0 == 0
        DetailPrint " PowerShell 正常可用"
    ${Else}
        DetailPrint " 警告: 未检测到 PowerShell，安装可能失败"
    ${EndIf}

    ; 执行核心安装脚本
    DetailPrint ""
    DetailPrint "=========================================="
    DetailPrint " 开始执行安装（Docker 环境检查 + 部署 + 激活）"
    DetailPrint " 此过程可能需要 5-15 分钟，请耐心等待..."
    DetailPrint "=========================================="
    DetailPrint ""

    nsExec::ExecToLog 'powershell -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\scripts\install.ps1" -InstallDir "$INSTDIR" -AuthCode "$AuthCode" -LicenseServer "${DEFAULT_LICENSE_SERVER}" -ImageTag "${DEFAULT_IMAGE_TAG}"'
    Pop $R0

    ${If} $R0 != 0
        DetailPrint ""
        DetailPrint "=========================================="
        DetailPrint " [错误] 安装失败，错误代码: $R0"
        DetailPrint " 请查看日志: $INSTDIR\logs\install.log"
        DetailPrint "=========================================="
        MessageBox MB_OK|MB_ICONSTOP "安装失败！$\n$\n请查看日志文件：$INSTDIR\logs\install.log$\n$\n或联系技术支持。"
        Abort
    ${EndIf}

    DetailPrint ""
    DetailPrint "安装脚本执行完成"

SectionEnd

; ============================================================================
; 开始菜单快捷方式
; ============================================================================
Section "开始菜单快捷方式" SecStartMenu
    CreateDirectory "$SMPROGRAMS\${APPNAME}"

    CreateShortCut "$SMPROGRAMS\${APPNAME}\启动盘家智管.lnk" \
        "powershell.exe" \
        '-NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\scripts\start-app.ps1" -InstallDir "$INSTDIR"' \
        "" "" ""

    CreateShortCut "$SMPROGRAMS\${APPNAME}\停止盘家智管.lnk" \
        "powershell.exe" \
        '-NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\scripts\stop-app.ps1" -InstallDir "$INSTDIR"' \
        "" "" ""

    CreateShortCut "$SMPROGRAMS\${APPNAME}\打开盘家智管.lnk" \
        "http://localhost"

    CreateShortCut "$SMPROGRAMS\${APPNAME}\查看日志.lnk" \
        "$INSTDIR\logs"

    CreateShortCut "$SMPROGRAMS\${APPNAME}\卸载${APPNAME}.lnk" \
        "$INSTDIR\uninstall.exe"

    CreateShortCut "$DESKTOP\${APPNAME}.lnk" \
        "http://localhost"

SectionEnd

; ============================================================================
; 完成页 - 启动应用
; ============================================================================
Function LaunchApp
    ExecShell "open" "http://localhost"
FunctionEnd

; ============================================================================
; 卸载节
; ============================================================================
Section "Uninstall"
    ; 停止服务
    DetailPrint "停止盘家智管服务..."
    nsExec::ExecToLog 'powershell -NoProfile -ExecutionPolicy Bypass -Command "Set-Location ''$INSTDIR\config''; docker compose down 2>&1 | Out-Null"'
    Pop $0

    ; 删除开始菜单
    Delete "$SMPROGRAMS\${APPNAME}\启动盘家智管.lnk"
    Delete "$SMPROGRAMS\${APPNAME}\停止盘家智管.lnk"
    Delete "$SMPROGRAMS\${APPNAME}\打开盘家智管.lnk"
    Delete "$SMPROGRAMS\${APPNAME}\查看日志.lnk"
    Delete "$SMPROGRAMS\${APPNAME}\卸载${APPNAME}.lnk"
    RMDir "$SMPROGRAMS\${APPNAME}"

    ; 删除桌面快捷方式
    Delete "$DESKTOP\${APPNAME}.lnk"

    ; 删除注册表
    DeleteRegKey HKLM "Software\Panjia\${APPNAME}"

    ; 询问是否卸载 Docker Desktop（默认保留：Docker 是平台组件，客户可能有其他用途）
    MessageBox MB_YESNO|MB_ICONQUESTION|MB_DEFBUTTON2 "是否同时卸载 Docker Desktop？$\n$\n选择「是」将卸载 Docker Desktop（只有本次安装时才装的 Docker 才需要卸载；若是机器本来就有 Docker，请选择「否」）。$\n$\n选择「否」将保留 Docker Desktop（重新安装盘家智管时无需重装 Docker）。$\n$\n推荐：选择「否」（默认）。" IDYES doUninstallDocker IDNO keepDocker

    doUninstallDocker:
        DetailPrint "正在卸载 Docker Desktop（可能需要 1-3 分钟）..."
        ; 从注册表读取 Docker Desktop 真实安装路径（避免 32 位 uninstaller 的
        ; WOW64 重定向问题和客户机自定义安装路径问题）。注册表无值时 fallback 到
        ; 64 位默认路径（绝大多数 64 位 Windows 的标准安装位置）。
        ReadRegStr $R1 HKLM "SOFTWARE\Docker Inc.\Docker\Desktop" "InstallLocation"
        ${If} $R1 == ""
            StrCpy $R1 "C:\Program Files\Docker\Docker"
        ${EndIf}
        ${If} ${FileExists} "$R1\Docker Desktop Installer.exe"
            ExecWait '"$R1\Docker Desktop Installer.exe" uninstall --quiet'
            DetailPrint " Docker Desktop 已卸载（建议重启电脑完成清理）"
        ${Else}
            DetailPrint " 未找到 Docker Desktop 卸载程序（$R1\Docker Desktop Installer.exe）"
            DetailPrint " 如需卸载请从「设置 - 应用」中手动卸载 Docker Desktop"
        ${EndIf}
        Goto askKeepData

    keepDocker:
        DetailPrint "保留 Docker Desktop"

    askKeepData:
    ; 询问是否保留数据
    MessageBox MB_YESNO|MB_ICONQUESTION "是否保留数据文件？$\n$\n选择「是」将保留数据库和授权信息，选择「否」将删除全部数据。" IDYES keepData
    DetailPrint "删除全部数据..."
    RMDir /r "$INSTDIR"
    Goto done

    keepData:
    DetailPrint "保留数据文件..."
    Delete "$INSTDIR\uninstall.exe"
    Delete "$INSTDIR\config\docker-compose.yml"
    Delete "$INSTDIR\config\.env"
    Delete "$INSTDIR\config\install-config.ini"
    Delete "$INSTDIR\config\install-info.json"
    Delete "$INSTDIR\config\install-progress.ini"
    Delete "$INSTDIR\nginx\nginx.conf"
    RMDir /r "$INSTDIR\scripts"
    RMDir /r "$INSTDIR\web"
    RMDir /r "$INSTDIR\logs"
    RMDir /r "$INSTDIR\nginx"
    RMDir /r "$INSTDIR\images"

    done:
    DetailPrint ""
    DetailPrint "卸载完成"
SectionEnd
