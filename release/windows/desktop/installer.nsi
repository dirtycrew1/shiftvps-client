; Husi Windows Installer — NSIS script
; Placeholders are replaced by package.sh before compilation.

Unicode true

!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "LogicLib.nsh"
!include "nsDialogs.nsh"
!include "WinMessages.nsh"
!include "WordFunc.nsh"
!include "Win\COM.nsh"
!include "Win\Propkey.nsh"

; --- Metadata ---
!define PACKAGE_NAME    "__HUSI_PACKAGE_NAME__"
!define APP_NAME        "__HUSI_APP_NAME__"
!define APP_NAME_ZH_CN  "__HUSI_APP_NAME_ZH_CN__"
!define APP_VERSION     "__HUSI_APP_VERSION__"
!define APP_VERSION_CODE __HUSI_APP_VERSION_CODE__
!define APP_DESCRIPTION "__HUSI_APP_DESCRIPTION__"
!define APP_URL         "__HUSI_APP_URL__"
!define MAINTAINER      "__HUSI_MAINTAINER__"

Name "${APP_NAME} ${APP_VERSION}"
OutFile "__HUSI_OUTPUT_FILE__"
InstallDir "$LOCALAPPDATA\Programs\${APP_NAME}"
InstallDirRegKey HKCU "Software\${PACKAGE_NAME}\Installer" "InstallDir"
; Everything this installer writes lives under the user profile. Only the
; optional system service needs administrator rights, and it asks for them
; on its own, so restricted accounts can still install the app.
RequestExecutionLevel user

; --- Version info embedded in exe ---
VIProductVersion "__HUSI_VI_VERSION__"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "ProductVersion" "${APP_VERSION}"
VIAddVersionKey "FileVersion" "__HUSI_VI_VERSION__"
VIAddVersionKey "CompanyName" "${MAINTAINER}"
VIAddVersionKey "FileDescription" "${APP_DESCRIPTION}"
VIAddVersionKey "LegalCopyright" "${MAINTAINER}"

; --- MUI settings ---
!define MUI_ABORTWARNING
!define MUI_ICON "__HUSI_ICON_FILE__"
!define MUI_UNICON "__HUSI_ICON_FILE__"

Var CreateDesktopShortcut
Var CreateStartMenuShortcut
Var InstallCoreService
Var CheckboxDesktopShortcut
Var CheckboxStartMenuShortcut
Var CheckboxInstallCoreService
Var UpdateMode
Var UpdatePid
Var PayloadDir
Var RegisteredDir
Var UpdateBackup
Var UpdatePayloadChanged
Var PreviousVersion
Var PreviousVersionCode

; --- Pages ---
!insertmacro MUI_PAGE_LICENSE "__HUSI_LICENSE_FILE__"
!insertmacro MUI_PAGE_DIRECTORY
Page custom optionsPageCreate optionsPageLeave
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "SimpChinese"

LangString OptionsPageTitle ${LANG_ENGLISH} "Options"
LangString OptionsPageTitle ${LANG_SIMPCHINESE} "选项"
LangString ShortcutAppName ${LANG_ENGLISH} "${APP_NAME}"
LangString ShortcutAppName ${LANG_SIMPCHINESE} "${APP_NAME_ZH_CN}"
LangString OptionsPageSubtitle ${LANG_ENGLISH} "Choose how ${APP_NAME} is set up."
LangString OptionsPageSubtitle ${LANG_SIMPCHINESE} "选择 ${APP_NAME} 的安装方式。"
LangString OptionsPageDescription ${LANG_ENGLISH} "${APP_NAME} installs into your own user profile. Only the system service needs administrator rights."
LangString OptionsPageDescription ${LANG_SIMPCHINESE} "${APP_NAME} 安装在当前用户的个人目录下。只有系统服务需要管理员权限。"
LangString DesktopShortcutLabel ${LANG_ENGLISH} "Create a desktop shortcut"
LangString DesktopShortcutLabel ${LANG_SIMPCHINESE} "创建桌面快捷方式"
LangString StartMenuShortcutLabel ${LANG_ENGLISH} "Create a Start Menu shortcut"
LangString StartMenuShortcutLabel ${LANG_SIMPCHINESE} "创建开始菜单快捷方式"
LangString CoreServiceLabel ${LANG_ENGLISH} "Install the system service (needs administrator; required for TUN mode)"
LangString CoreServiceLabel ${LANG_SIMPCHINESE} "安装系统服务（需要管理员权限；TUN 模式必需）"
LangString CoreServiceHint ${LANG_ENGLISH} "Without the service ${APP_NAME} still runs as a local proxy, and the service can be installed later from Settings."
LangString CoreServiceHint ${LANG_SIMPCHINESE} "不安装系统服务时，${APP_NAME} 仍可作为本地代理运行，之后也可以在设置中安装该服务。"
LangString CoreServiceInstalling ${LANG_ENGLISH} "Installing the system service…"
LangString CoreServiceInstalling ${LANG_SIMPCHINESE} "正在安装系统服务……"
LangString CoreServiceSkipped ${LANG_ENGLISH} "System service not installed. You can install it later from Settings."
LangString CoreServiceSkipped ${LANG_SIMPCHINESE} "未安装系统服务。之后可以在设置中安装。"
LangString CoreServiceUninstalling ${LANG_ENGLISH} "Removing the system service…"
LangString CoreServiceUninstalling ${LANG_SIMPCHINESE} "正在移除系统服务……"
LangString CoreServiceUninstallSkipped ${LANG_ENGLISH} "The system service was left installed because administrator rights were declined."
LangString CoreServiceUninstallSkipped ${LANG_SIMPCHINESE} "由于未获得管理员权限，系统服务仍保留在系统中。"
LangString InstallSectionName ${LANG_ENGLISH} "Install"
LangString InstallSectionName ${LANG_SIMPCHINESE} "安装"
LangString UninstallSectionName ${LANG_ENGLISH} "Uninstall"
LangString UninstallSectionName ${LANG_SIMPCHINESE} "卸载"
LangString UninstallShortcutName ${LANG_ENGLISH} "Uninstall"
LangString UninstallShortcutName ${LANG_SIMPCHINESE} "卸载"

Function .onInit
    StrCpy $CreateDesktopShortcut ${BST_CHECKED}
    StrCpy $CreateStartMenuShortcut ${BST_CHECKED}
    StrCpy $InstallCoreService ${BST_CHECKED}
    StrCpy $UpdateMode 0
    StrCpy $UpdatePayloadChanged 0
    ${GetParameters} $R0
    ClearErrors
    ${GetOptions} $R0 "/UPDATE" $R1
    ${IfNot} ${Errors}
        StrCpy $UpdateMode 1
    ${EndIf}
    ReadRegStr $PreviousVersion HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" "DisplayVersion"
    ${If} $PreviousVersion != ""
        ${VersionCompare} $PreviousVersion "${APP_VERSION}" $0
        ${If} $0 == 1
            MessageBox MB_OK|MB_ICONSTOP "A newer version of ${APP_NAME} is already installed."
            SetErrorLevel 2
            Quit
        ${EndIf}
    ${EndIf}
    ${If} $UpdateMode == 1
        ReadRegStr $RegisteredDir HKCU "Software\${PACKAGE_NAME}\Installer" "InstallDir"
        ReadRegDWORD $PreviousVersionCode HKCU "Software\${PACKAGE_NAME}\Installer" "VersionCode"
        ${If} $RegisteredDir == ""
        ${OrIf} $PreviousVersion == ""
            MessageBox MB_OK|MB_ICONSTOP "The registered ${APP_NAME} installation was not found."
            SetErrorLevel 2
            Quit
        ${EndIf}
        ${If} $PreviousVersionCode >= ${APP_VERSION_CODE}
            MessageBox MB_OK|MB_ICONSTOP "This update is not newer than the installed build."
            SetErrorLevel 2
            Quit
        ${EndIf}
        GetFullPathName $RegisteredDir "$RegisteredDir"
        StrCpy $INSTDIR $RegisteredDir
        InitPluginsDir
        StrCpy $PayloadDir "$PLUGINSDIR\payload"
        StrCpy $UpdateBackup "$INSTDIR\.shiftvps-update-backup"
        ; Preserve shortcut choices and only update a service that was already installed.
        StrCpy $CreateDesktopShortcut ${BST_UNCHECKED}
        StrCpy $CreateStartMenuShortcut ${BST_UNCHECKED}
        StrCpy $InstallCoreService ${BST_UNCHECKED}
        ${If} ${FileExists} "$PROGRAMFILES64\shiftvps\husi-core.exe"
            StrCpy $InstallCoreService ${BST_CHECKED}
        ${EndIf}
    ${Else}
        StrCpy $PayloadDir $INSTDIR
    ${EndIf}
FunctionEnd

; --- Install section ---
Section "$(InstallSectionName)"
    ${If} $UpdateMode == 1
        Call validateUpdate
        Call waitForUpdateProcess
    ${Else}
        StrCpy $PayloadDir $INSTDIR
    ${EndIf}
    ClearErrors
    SetOutPath "$PayloadDir"
    File "/oname=${APP_NAME}.exe" "__HUSI_LAUNCHER_FILE__"
    File "/oname=husi-core.exe" "__HUSI_CORE_FILE__"
    File "/oname=__HUSI_CORE_LIBRARY_NAME__" "__HUSI_CORE_LIBRARY_FILE__"
    ; Releases before husi-core became a single binary shipped its Go core as this DLL.
    ${If} $UpdateMode != 1
        Delete "$INSTDIR\husicore.dll"
    ${EndIf}
    File "/oname=LICENSE" "__HUSI_LICENSE_FILE__"
    File "/oname=desktop-java-opts.conf.template" "__HUSI_JAVA_OPTS_FILE__"
    File "/oname=desktop-app-args.conf.template" "__HUSI_APP_ARGS_FILE__"
    File "/oname=WinSparkle.dll" "__HUSI_WINSPARKLE_FILE__"
    SetOutPath "$PayloadDir\licenses\WinSparkle"
    File "/oname=COPYING" "__HUSI_WINSPARKLE_COPYING_FILE__"
    File "/oname=COPYING.expat" "__HUSI_WINSPARKLE_EXPAT_FILE__"
    File "/oname=AUTHORS" "__HUSI_WINSPARKLE_AUTHORS_FILE__"

    SetOutPath "$PayloadDir\app"
    File "/oname=${PACKAGE_NAME}.jar" "__HUSI_JAR_FILE__"

    SetOutPath "$PayloadDir"

    ; Bundled Java runtime, empty unless this is a JBR package.
__HUSI_RUNTIME_INSTALL__

    ${If} $UpdateMode == 1
        IfErrors updateExtractionFailed
        Call applyUpdate
    ${EndIf}
    Call installCoreService

    ; Uninstaller
    ClearErrors
    WriteUninstaller "$INSTDIR\uninstall.exe"
    ${If} ${Errors}
        MessageBox MB_OK|MB_ICONSTOP "The uninstaller could not be written. Installation stopped."
        Abort
    ${EndIf}

    ; Install dir registry (for upgrade detection)
    WriteRegStr HKCU "Software\${PACKAGE_NAME}\Installer" "InstallDir" "$INSTDIR"
    WriteRegDWORD HKCU "Software\${PACKAGE_NAME}\Installer" "VersionCode" ${APP_VERSION_CODE}

    ; Add/Remove Programs entry
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "DisplayName" "${APP_NAME}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "DisplayVersion" "${APP_VERSION}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "Publisher" "${MAINTAINER}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "URLInfoAbout" "${APP_URL}"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "UninstallString" '"$INSTDIR\uninstall.exe"'
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "QuietUninstallString" '"$INSTDIR\uninstall.exe" /S'
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "InstallLocation" "$INSTDIR"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "DisplayIcon" "$INSTDIR\${APP_NAME}.exe,0"
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "NoModify" 1
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "NoRepair" 1

    ; Estimated size
    ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
    IntFmt $0 "0x%08X" $0
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" \
        "EstimatedSize" $0

    ; URL scheme registration
__HUSI_URL_SCHEME_REGISTRY__
    Goto installationFinished
updateExtractionFailed:
    MessageBox MB_OK|MB_ICONSTOP "The update could not be staged. The installed files were not replaced."
    Abort
installationFinished:
SectionEnd

Function optionsPageCreate
    !insertmacro MUI_HEADER_TEXT "$(OptionsPageTitle)" "$(OptionsPageSubtitle)"

    nsDialogs::Create 1018
    Pop $0
    StrCmp $0 error 0 +2
    Abort

    ${NSD_CreateLabel} 0 0 100% 24u "$(OptionsPageDescription)"
    Pop $0

    ${NSD_CreateCheckbox} 0 32u 100% 12u "$(DesktopShortcutLabel)"
    Pop $CheckboxDesktopShortcut
    ${NSD_SetState} $CheckboxDesktopShortcut $CreateDesktopShortcut

    ${NSD_CreateCheckbox} 0 50u 100% 12u "$(StartMenuShortcutLabel)"
    Pop $CheckboxStartMenuShortcut
    ${NSD_SetState} $CheckboxStartMenuShortcut $CreateStartMenuShortcut

    ${NSD_CreateCheckbox} 0 68u 100% 12u "$(CoreServiceLabel)"
    Pop $CheckboxInstallCoreService
    ${NSD_SetState} $CheckboxInstallCoreService $InstallCoreService

    ${NSD_CreateLabel} 0 86u 100% 24u "$(CoreServiceHint)"
    Pop $0

    nsDialogs::Show
FunctionEnd

Function optionsPageLeave
    ${NSD_GetState} $CheckboxDesktopShortcut $CreateDesktopShortcut
    ${NSD_GetState} $CheckboxStartMenuShortcut $CreateStartMenuShortcut
    ${NSD_GetState} $CheckboxInstallCoreService $InstallCoreService
FunctionEnd

Function validateUpdate
    GetFullPathName $0 "$INSTDIR"
    ${If} $0 != $RegisteredDir
        MessageBox MB_OK|MB_ICONSTOP "An update can only replace the registered installation."
        Abort
    ${EndIf}
    ${IfNot} ${FileExists} "$INSTDIR\${APP_NAME}.exe"
    ${OrIfNot} ${FileExists} "$INSTDIR\app\${PACKAGE_NAME}.jar"
    ${OrIfNot} ${FileExists} "$INSTDIR\uninstall.exe"
        MessageBox MB_OK|MB_ICONSTOP "The registered application files were not found."
        Abort
    ${EndIf}
    ; Do not follow a junction outside the registered application directory.
    System::Call 'kernel32::GetFileAttributesW(w "$INSTDIR") i.r0'
    IntOp $0 $0 & 0x400
    ${If} $0 != 0
        MessageBox MB_OK|MB_ICONSTOP "The update directory cannot be a junction or symbolic link."
        Abort
    ${EndIf}
    ${If} ${FileExists} "$UpdateBackup"
        MessageBox MB_OK|MB_ICONSTOP "A previous update backup exists at $UpdateBackup. Recover it before trying again."
        Abort
    ${EndIf}
FunctionEnd

Function waitForUpdateProcess
    ${GetParameters} $R0
    ClearErrors
    ${GetOptions} $R0 "/UPDATEPID=" $UpdatePid
    ${If} ${Errors}
        MessageBox MB_OK|MB_ICONSTOP "The update did not identify the application process."
        Abort
    ${EndIf}
    System::Call 'shlwapi::StrSpnW(w "$UpdatePid", w "0123456789") i.r0'
    StrLen $1 $UpdatePid
    ${If} $UpdatePid == ""
    ${OrIf} $0 != $1
    ${OrIf} $UpdatePid <= 0
        MessageBox MB_OK|MB_ICONSTOP "The application process identifier is invalid."
        Abort
    ${EndIf}
    System::Call 'kernel32::OpenProcess(i 0x100000, i 0, i $UpdatePid) p.r0 ?e'
    Pop $1
    ${If} $0 == 0
        ${If} $1 == 87 ; ERROR_INVALID_PARAMETER: the process has already exited.
            Return
        ${EndIf}
        MessageBox MB_OK|MB_ICONSTOP "The update could not wait for the application to close."
        Abort
    ${EndIf}
    System::Call 'kernel32::WaitForSingleObject(p r0, i 60000) i.r1'
    System::Call 'kernel32::CloseHandle(p r0)'
    ${If} $1 != 0
        MessageBox MB_OK|MB_ICONSTOP "The application is still running. Close it before updating."
        Abort
    ${EndIf}
    ; The launcher exits after its Java child. No processes are terminated by this installer.
    Sleep 1000
FunctionEnd

!macro UpdatePayload action
    !insertmacro ${action} "${APP_NAME}.exe" file
    !insertmacro ${action} "husi-core.exe" file
    !insertmacro ${action} "__HUSI_CORE_LIBRARY_NAME__" file
    !insertmacro ${action} "WinSparkle.dll" file
    !insertmacro ${action} "LICENSE" file
    !insertmacro ${action} "desktop-java-opts.conf.template" file
    !insertmacro ${action} "desktop-app-args.conf.template" file
    !insertmacro ${action} "uninstall.exe" file
    !insertmacro ${action} "app" directory
    !insertmacro ${action} "runtime" directory
    !insertmacro ${action} "licenses" directory
!macroend

!macro BackupUpdatePayload name kind
    ${If} ${FileExists} "$INSTDIR\${name}"
        System::Call 'kernel32::GetFileAttributesW(w "$INSTDIR\${name}") i.r0'
        IntOp $0 $0 & 0x400
        ${If} $0 != 0
            MessageBox MB_OK|MB_ICONSTOP "An installed application path is a junction or symbolic link. Update stopped."
            Abort
        ${EndIf}
        ClearErrors
        CopyFiles /SILENT "$INSTDIR\${name}" "$UpdateBackup"
        ${If} ${Errors}
            MessageBox MB_OK|MB_ICONSTOP "The installed files could not be backed up. The update was stopped."
            Abort
        ${EndIf}
    ${EndIf}
!macroend

!macro RestoreUpdatePayload name kind
    !if "${kind}" == "directory"
        RMDir /r "$INSTDIR\${name}"
    !else
        Delete "$INSTDIR\${name}"
    !endif
    ${If} ${FileExists} "$UpdateBackup\${name}"
        CopyFiles /SILENT "$UpdateBackup\${name}" "$INSTDIR"
    ${EndIf}
!macroend

Function applyUpdate
    CreateDirectory "$UpdateBackup"
    !insertmacro UpdatePayload BackupUpdatePayload
    StrCpy $UpdatePayloadChanged 1
    ClearErrors
    CopyFiles /SILENT "$PayloadDir\*" "$INSTDIR"
    ${If} ${Errors}
        MessageBox MB_OK|MB_ICONSTOP "The update could not replace the installed files. Recovery will now be attempted."
        Abort
    ${EndIf}
FunctionEnd

Function .onInstFailed
    ${If} $UpdateMode == 1
    ${AndIf} $UpdatePayloadChanged == 1
        ClearErrors
        !insertmacro UpdatePayload RestoreUpdatePayload
        WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}" "DisplayVersion" "$PreviousVersion"
        WriteRegDWORD HKCU "Software\${PACKAGE_NAME}\Installer" "VersionCode" $PreviousVersionCode
        MessageBox MB_OK|MB_ICONSTOP "The update failed. Recovery of the application files was attempted. The backup remains at $UpdateBackup. The system service may require repair from Settings."
    ${EndIf}
    SetErrorLevel 1
FunctionEnd

; ExecShellWait cannot return the elevated child's exit code. Use the Windows
; process handle so a daemon installation failure cannot be reported as success.
Function installElevatedCoreService
    StrCpy $0 -1
    System::Call '*(i 60, i 0x40, p $HWNDPARENT, w "runas", w "$INSTDIR\husi-core.exe", w "service install", w "$INSTDIR", i 0, p 0, p 0, i 0, p 0, i 0, p 0, p 0) p.r1'
    System::Call 'shell32::ShellExecuteExW(p r1) i.r2'
    ${If} $2 != 0
        System::Call '*$1(i, i, p, p, p, p, p, i, p, p, i, p, i, p, p.r2)'
        ${If} $2 != 0
            System::Call 'kernel32::WaitForSingleObject(p r2, i -1)'
            System::Call 'kernel32::GetExitCodeProcess(p r2, *i .r0)'
            System::Call 'kernel32::CloseHandle(p r2)'
        ${EndIf}
    ${EndIf}
    System::Free $1
FunctionEnd

; The service lives outside the user profile, so it is the one step that needs
; administrator rights. A declined UAC prompt only skips the service: the app
; itself is already installed, and Settings can install the service later.
Function installCoreService
    ${If} $InstallCoreService != ${BST_CHECKED}
        Return
    ${EndIf}

    DetailPrint "$(CoreServiceInstalling)"
    ${If} $UpdateMode == 1
        ; /UPDATE is only passed after the user approves installation in WinSparkle.
        Call installElevatedCoreService
    ${ElseIf} ${Silent}
        ; A silent install must never pop a UAC prompt: run in place and let it
        ; fail when the silent install was not started elevated.
        nsExec::ExecToLog '"$INSTDIR\husi-core.exe" service install'
        Pop $0
    ${Else}
        ClearErrors
        ExecShellWait "runas" "$INSTDIR\husi-core.exe" "service install" SW_HIDE
        ${If} ${Errors}
            StrCpy $0 "error"
        ${Else}
            StrCpy $0 0
        ${EndIf}
    ${EndIf}

    ${If} $0 != 0
        DetailPrint "$(CoreServiceSkipped)"
        ${If} $UpdateMode == 1
            MessageBox MB_OK|MB_ICONSTOP "The system service could not be updated. The application update was stopped."
            Abort
        ${EndIf}
    ${EndIf}
FunctionEnd

; Give launcher shortcuts the same identity as the JVM window/notification process.
Function setShortcutIdentity
    Exch $9
    Push $0
    Push $1
    Push $2
    Push $3
    Push $4
    Push $5
    System::Call 'ole32::CoInitializeEx(p 0, i 2)i.r5'
    System::Call 'shell32::SHGetPropertyStoreFromParsingName(w r9, p 0, i 2, g "${IID_IPropertyStore}", *p.r0)i.r1'
    ${If} $1 == 0
        System::Call '*${SYSSTRUCT_PROPERTYKEY}(${PKEY_AppUserModel_ID})p.r2'
        System::Call 'shlwapi::SHStrDupW(w "${PACKAGE_NAME}", *p.r3)i.r1'
        ${If} $1 == 0
            ; Keep the 8-byte PROPVARIANT union even in a 32-bit installer.
            System::Call '*${SYSSTRUCT_PROPVARIANT}(${VT_LPWSTR},,r3)p.r4'
            System::Call '$0->6(p r2, p r4)i.r1'
            ${If} $1 == 0
                System::Call '$0->7()i.r1'
            ${EndIf}
            !insertmacro PropVariantClear $4
            System::Free $4
        ${EndIf}
        System::Free $2
        System::Call '$0->2()'
    ${EndIf}
    ${If} $1 != 0
        DetailPrint "The shortcut application identity could not be saved."
    ${EndIf}
    ${If} $5 >= 0
        System::Call 'ole32::CoUninitialize()'
    ${EndIf}
    Pop $5
    Pop $4
    Pop $3
    Pop $2
    Pop $1
    Pop $0
    Pop $9
FunctionEnd

Function refreshShortcutIdentities
    ${If} ${FileExists} "$DESKTOP\${APP_NAME}.lnk"
        Push "$DESKTOP\${APP_NAME}.lnk"
        Call setShortcutIdentity
    ${EndIf}
    ${If} ${FileExists} "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
        Push "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
        Call setShortcutIdentity
    ${EndIf}
FunctionEnd

Function createShortcuts
    Delete "$DESKTOP\${APP_NAME}.lnk"
    Delete "$DESKTOP\${APP_NAME_ZH_CN}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME_ZH_CN}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\Uninstall.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\卸载.lnk"
    RMDir "$SMPROGRAMS\${APP_NAME}"

    ${If} $CreateDesktopShortcut == ${BST_CHECKED}
        CreateShortCut "$DESKTOP\$(ShortcutAppName).lnk" "$INSTDIR\${APP_NAME}.exe" "" "$INSTDIR\${APP_NAME}.exe" 0 "" "" "${APP_DESCRIPTION}"
    ${EndIf}

    ${If} $CreateStartMenuShortcut == ${BST_CHECKED}
        CreateDirectory "$SMPROGRAMS\${APP_NAME}"
        CreateShortCut "$SMPROGRAMS\${APP_NAME}\$(ShortcutAppName).lnk" "$INSTDIR\${APP_NAME}.exe" "" "$INSTDIR\${APP_NAME}.exe" 0 "" "" "${APP_DESCRIPTION}"
        CreateShortCut "$SMPROGRAMS\${APP_NAME}\$(UninstallShortcutName).lnk" "$INSTDIR\uninstall.exe" "" "$INSTDIR\uninstall.exe" 0
    ${EndIf}
    Call refreshShortcutIdentities
FunctionEnd

Function .onInstSuccess
    ${If} $UpdateMode == 1
        RMDir /r "$UpdateBackup"
        Call refreshShortcutIdentities
        Exec '"$INSTDIR\${APP_NAME}.exe"'
    ${Else}
        Call createShortcuts
    ${EndIf}
FunctionEnd

; --- Uninstall section ---
Section "un.$(UninstallSectionName)"
    Call un.uninstallCoreService

    ; Remove files
    Delete "$INSTDIR\${APP_NAME}.exe"
    Delete "$INSTDIR\husi-core.exe"
    Delete "$INSTDIR\WinSparkle.dll"
    RMDir /r "$INSTDIR\licenses\WinSparkle"
    RMDir "$INSTDIR\licenses"
    Delete "$INSTDIR\__HUSI_CORE_LIBRARY_NAME__"
    Delete "$INSTDIR\husicore.dll" ; left by releases before husi-core became a single binary
    Delete "$INSTDIR\LICENSE"
    Delete "$INSTDIR\desktop-java-opts.conf.template"
    Delete "$INSTDIR\desktop-app-args.conf.template"
    Delete "$INSTDIR\app\${PACKAGE_NAME}.jar"
    RMDir "$INSTDIR\app"
__HUSI_RUNTIME_UNINSTALL__
    Delete "$INSTDIR\uninstall.exe"
    Delete "$DESKTOP\${APP_NAME}.lnk"
    Delete "$DESKTOP\${APP_NAME_ZH_CN}.lnk"
    RMDir "$INSTDIR"

    ; Start Menu
    Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME_ZH_CN}.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\Uninstall.lnk"
    Delete "$SMPROGRAMS\${APP_NAME}\$(UninstallShortcutName).lnk"
    RMDir "$SMPROGRAMS\${APP_NAME}"

    ; Registry cleanup
    DeleteRegKey HKCU "Software\${PACKAGE_NAME}\Installer"
    DeleteRegKey HKCU "Software\${PACKAGE_NAME}"
    DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${PACKAGE_NAME}"

    ; URL schemes
__HUSI_URL_SCHEME_UNREGISTRY__
SectionEnd

; Mirror of installCoreService. The copy under Program Files only tells us the
; service was installed at all — the uninstall is driven from $INSTDIR, because
; the installed binary cannot delete itself while it is the running process. A
; declined UAC prompt leaves the service behind rather than aborting.
Function un.uninstallCoreService
    ${IfNot} ${FileExists} "$PROGRAMFILES64\shiftvps\husi-core.exe"
        Return
    ${EndIf}

    DetailPrint "$(CoreServiceUninstalling)"
    ${If} ${Silent}
        nsExec::ExecToLog '"$INSTDIR\husi-core.exe" service uninstall'
        Pop $0
    ${Else}
        ClearErrors
        ExecShellWait "runas" "$INSTDIR\husi-core.exe" "service uninstall" SW_HIDE
        ${If} ${Errors}
            StrCpy $0 "error"
        ${Else}
            StrCpy $0 0
        ${EndIf}
    ${EndIf}

    ${If} $0 != 0
        DetailPrint "$(CoreServiceUninstallSkipped)"
    ${EndIf}
FunctionEnd
