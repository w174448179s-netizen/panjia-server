; ============================================================================
; 盘家智管 - 升级包安装器（轻量）
;
; 面向已安装客户机的版本升级：只包含后端镜像 + 前端静态资源 + 配置/脚本，
; 不含 Docker Desktop 安装包、WSL 更新包、基础镜像（postgres/redis/nginx）。
;
; 保护范围（本安装器绝不删除/覆盖）：
;   - $INSTDIR\data\（数据库、授权指纹数据卷）
;   - $INSTDIR\config\.env（密码等环境变量）
;   - $INSTDIR\config\machine-id（机器指纹）
;
; 编译方法（Mac/Linux，用 Docker）：
;   sh build-upgrade.sh
;
; 输出：panjia-upgrade.exe
; ============================================================================

!include "MUI2.nsh"
!include "LogicLib.nsh"

; -------------------- 基本信息 --------------------
!define APPNAME "盘家智管"
!ifndef UPGRADE_VERSION
    !define UPGRADE_VERSION "1.1.0"
!endif
!define APPVERSION "${UPGRADE_VERSION}"
!define APPPUBLISHER "盘家科技"

; -------------------- MUI 配置 --------------------
!define MUI_ABORTWARNING
!define MUI_ICON "${NSISDIR}\Contrib\Graphics\Icons\modern-install.ico"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "${NSISDIR}\Contrib\Graphics\Header\win.bmp"
!define MUI_WELCOMEFINISHPAGE_BITMAP "${NSISDIR}\Contrib\Graphics\Wizard\win.bmp"
!define MUI_DIRECTORYPAGE_TEXT_DESTINATION "确认已安装盘家智管的目录"

; -------------------- 页面定义 --------------------
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

; -------------------- 语言 --------------------
!insertmacro MUI_LANGUAGE "SimpChinese"

; ============================================================================
; 安装入口
; ============================================================================
Name "${APPNAME} 升级程序 ${APPVERSION}"
OutFile "panjia-upgrade.exe"
InstallDir "$PROGRAMFILES\Panjia"
InstallDirRegKey HKLM "Software\Panjia\${APPNAME}" "InstallDir"
RequestExecutionLevel admin
CRCCheck on
ShowInstDetails hide

; ============================================================================
; 安装节
; ============================================================================
Section "升级盘家智管" SecUpgrade
    SectionIn RO

    ; -------------------- 校验已有安装 --------------------
    ${IfNot} ${FileExists} "$INSTDIR\config\.env"
        MessageBox MB_OK|MB_ICONSTOP "未在所选目录检测到已安装的盘家智管。$\n$\n请确认目录，或先运行完整安装包 panjia-setup.exe。"
        Abort
    ${EndIf}

    DetailPrint "=========================================="
    DetailPrint " 盘家智管升级程序 ${APPVERSION}"
    DetailPrint "=========================================="
    DetailPrint ""
    DetailPrint "升级目录: $INSTDIR"
    DetailPrint "数据、密码、授权信息均保留"
    DetailPrint ""

    ; -------------------- 替换前端静态资源 --------------------
    ; 先清空旧 dist（NSIS File 不会删除已不存在的旧 hash 资源文件）
    DetailPrint "更新前端静态资源..."
    RMDir /r "$INSTDIR\web\dist"
    CreateDirectory "$INSTDIR\web\dist"
    SetOutPath "$INSTDIR\web\dist"
    File /r "web\dist\*.*"

    ; -------------------- 替换配置与脚本 --------------------
    DetailPrint "更新配置与脚本..."
    SetOutPath "$INSTDIR\config"
    File "config\docker-compose.yml"

    SetOutPath "$INSTDIR\nginx"
    File "config\nginx.conf"

    SetOutPath "$INSTDIR\scripts"
    File "scripts\upgrade.ps1"
    File "scripts\install.ps1"
    File "scripts\uninstall.ps1"
    File "scripts\start-app.ps1"
    File "scripts\stop-app.ps1"

    ; -------------------- 替换后端镜像包 --------------------
    SetOutPath "$INSTDIR\images"
    File /nonfatal "images\panjia-server.tar"

    ; -------------------- 执行升级脚本 --------------------
    DetailPrint ""
    DetailPrint " 开始应用升级（加载镜像 + 重建容器）..."
    DetailPrint " 此过程可能需要 3-10 分钟，请耐心等待..."
    DetailPrint ""

    nsExec::ExecToLog 'powershell -NoProfile -ExecutionPolicy Bypass -File "$INSTDIR\scripts\upgrade.ps1" -InstallDir "$INSTDIR"'
    Pop $R0

    ${If} $R0 != 0
        DetailPrint ""
        DetailPrint "=========================================="
        DetailPrint " [错误] 升级失败，错误代码: $R0"
        DetailPrint " 请查看日志: $INSTDIR\logs\upgrade.log"
        DetailPrint "=========================================="
        MessageBox MB_OK|MB_ICONSTOP "升级失败！$\n$\n请查看日志文件：$INSTDIR\logs\upgrade.log$\n$\n或联系技术支持。"
        Abort
    ${EndIf}

    ; -------------------- 更新版本信息 --------------------
    WriteRegStr HKLM "Software\Panjia\${APPNAME}" "Version" "${APPVERSION}"
    WriteIniStr "$INSTDIR\config\install-config.ini" "General" "Version" "${APPVERSION}"

    DetailPrint ""
    DetailPrint "=========================================="
    DetailPrint " 升级完成，版本: ${APPVERSION}"
    DetailPrint "=========================================="
SectionEnd
