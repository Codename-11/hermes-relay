Unicode True
RequestExecutionLevel user
SetCompressor /SOLID lzma

!include "MUI2.nsh"

Name "Hermes Relay CLI + Systray"
OutFile "${OUT_FILE}"
InstallDir "$PROFILE\.hermes\bin"
InstallDirRegKey HKCU "Software\HermesRelay" "InstallDir"
Icon "${ICON_FILE}"
UninstallIcon "${ICON_FILE}"
BrandingText "Hermes Relay ${VERSION}"
VIProductVersion "${VERSION_NUM}"
VIAddVersionKey "ProductName" "Hermes Relay CLI + Systray"
VIAddVersionKey "FileDescription" "Hermes Relay CLI and optional menu-only Windows systray installer"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "MIT License"

!define MUI_ABORTWARNING
!define MUI_ICON "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"
!define MUI_FINISHPAGE_RUN "$INSTDIR\hermes-relay-tray.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Start the Hermes Relay systray"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "Hermes Relay CLI and systray" SEC_CORE
  SectionIn RO
  SetOutPath "$INSTDIR"
  File /oname=hermes-relay.exe "${CLI_EXE}"
  File /oname=hermes-relay-tray.exe "${TRAY_EXE}"
  File /oname=hermes-relay-path.ps1 "${PATH_HELPER}"
  WriteUninstaller "$INSTDIR\uninstall-hermes-relay.exe"

  WriteRegStr HKCU "Software\HermesRelay" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "DisplayName" "Hermes Relay CLI + Systray"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "Publisher" "Axiom Labs"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "UninstallString" '"$INSTDIR\uninstall-hermes-relay.exe"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "NoRepair" 1

  CreateDirectory "$SMPROGRAMS\Hermes Relay"
  CreateShortCut "$SMPROGRAMS\Hermes Relay\Hermes Relay TUI.lnk" "$INSTDIR\hermes-relay.exe"
  CreateShortCut "$SMPROGRAMS\Hermes Relay\Hermes Relay Systray.lnk" "$INSTDIR\hermes-relay-tray.exe"
  CreateShortCut "$SMPROGRAMS\Hermes Relay\Uninstall Hermes Relay.lnk" "$INSTDIR\uninstall-hermes-relay.exe"

  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "HermesRelayTray"
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\hermes-relay-path.ps1" add "$INSTDIR"'
SectionEnd

Section "Start systray when I sign in" SEC_STARTUP
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "HermesRelayTray" '"$INSTDIR\hermes-relay-tray.exe"'
SectionEnd

Section "Uninstall"
  nsExec::ExecToLog '"$SYSDIR\taskkill.exe" /IM hermes-relay-tray.exe /F'
  nsExec::ExecToLog '"$INSTDIR\hermes-relay.exe" daemon stop'
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "HermesRelayTray"
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\hermes-relay-path.ps1" remove "$INSTDIR"'

  Delete "$SMPROGRAMS\Hermes Relay\Hermes Relay TUI.lnk"
  Delete "$SMPROGRAMS\Hermes Relay\Hermes Relay Systray.lnk"
  Delete "$SMPROGRAMS\Hermes Relay\Uninstall Hermes Relay.lnk"
  RMDir "$SMPROGRAMS\Hermes Relay"

  Delete "$INSTDIR\hermes-relay.exe"
  Delete "$INSTDIR\hermes-relay-tray.exe"
  Delete "$INSTDIR\hermes-relay-path.ps1"
  Delete "$INSTDIR\uninstall-hermes-relay.exe"
  RMDir "$INSTDIR"

  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay"
  DeleteRegKey HKCU "Software\HermesRelay"
SectionEnd
