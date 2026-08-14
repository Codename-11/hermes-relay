Unicode True
RequestExecutionLevel user
SetCompressor /SOLID lzma

!include "MUI2.nsh"

Name "Hermes-Relay CLI UI"
OutFile "${OUT_FILE}"
InstallDir "$PROFILE\.hermes\bin"
InstallDirRegKey HKCU "Software\HermesRelay" "InstallDir"
Icon "${ICON_FILE}"
UninstallIcon "${ICON_FILE}"
BrandingText "Hermes-Relay CLI UI ${VERSION}"
VIProductVersion "${VERSION_NUM}"
VIAddVersionKey "ProductName" "Hermes-Relay CLI UI"
VIAddVersionKey "FileDescription" "Hermes-Relay CLI and compact Windows management UI installer"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "MIT License"

Var ExistingStartup
Var InstallAttempt

Function .onInit
  ReadRegStr $ExistingStartup HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "HermesRelayTray"
FunctionEnd

!define MUI_ABORTWARNING
!define MUI_ICON "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"
!define MUI_FINISHPAGE_RUN "$INSTDIR\hermes-relay-tray.exe"
!define MUI_FINISHPAGE_RUN_PARAMETERS "--show"
!define MUI_FINISHPAGE_RUN_TEXT "Start Hermes-Relay CLI UI"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "Hermes-Relay CLI and management UI" SEC_CORE
  SectionIn RO
  ; Stop the tray first so its snapshot polling cannot launch another CLI
  ; process while setup is trying to replace hermes-relay.exe. The following
  ; /T also terminates a poll already in flight before payload extraction.
  nsExec::ExecToLog '"$SYSDIR\taskkill.exe" /IM hermes-relay-tray.exe /T /F'
  IfFileExists "$INSTDIR\hermes-relay.exe" 0 processes_quiesced
  nsExec::ExecToLog '"$INSTDIR\hermes-relay.exe" daemon stop'
  nsExec::ExecToLog '"$SYSDIR\taskkill.exe" /IM hermes-relay.exe /T /F'
processes_quiesced:
  ; Drain the daemon and any short-lived tray/management CLI children that
  ; raced with shutdown. An explicit bundle update is the lifecycle boundary
  ; for installed Hermes-Relay processes.
  Sleep 350
  Delete "$INSTDIR\hermes-relay.new.exe"
  Delete "$INSTDIR\hermes-relay.old.exe"
  Delete "$INSTDIR\hermes-relay.exe.bak"
  Delete "$INSTDIR\hermes-relay-tray.exe.bak"
  StrCpy $InstallAttempt "0"
install_attempt:
  SetOutPath "$INSTDIR"
  ClearErrors
  File /oname=hermes-relay.exe "${CLI_EXE}"
  File /oname=hermes-relay-tray.exe "${TRAY_EXE}"
  File /oname=hermes-relay-path.ps1 "${PATH_HELPER}"
  File /oname=hermes-relay-ui.cmd "${UI_SHIM}"
  IfErrors install_attempt_failed

  ; ClearErrors/IfErrors covers both CLI and tray writes, so setup cannot
  ; publish registry metadata after a locked or partial replacement.
  Goto cli_verified
install_attempt_failed:
  StrCmp $InstallAttempt "0" 0 cli_verification_failed
  StrCpy $InstallAttempt "1"
  nsExec::ExecToLog '"$SYSDIR\taskkill.exe" /IM hermes-relay-tray.exe /T /F'
  nsExec::ExecToLog '"$SYSDIR\taskkill.exe" /IM hermes-relay.exe /T /F'
  Sleep 350
  Goto install_attempt
cli_verification_failed:
  MessageBox MB_ICONSTOP "Hermes-Relay CLI UI ${VERSION} could not replace the running installation. Close Hermes-Relay processes and run setup again." /SD IDOK
  SetErrorLevel 1
  Quit
cli_verified:
  WriteUninstaller "$INSTDIR\uninstall-hermes-relay.exe"

  WriteRegStr HKCU "Software\HermesRelay" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "DisplayName" "Hermes-Relay CLI UI"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "Publisher" "Axiom Labs"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "DisplayIcon" '"$INSTDIR\hermes-relay-tray.exe",0'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "UninstallString" '"$INSTDIR\uninstall-hermes-relay.exe"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay" "NoRepair" 1

  Delete "$SMPROGRAMS\Hermes Relay\Hermes Relay TUI.lnk"
  Delete "$SMPROGRAMS\Hermes Relay\Hermes Relay Tray.lnk"
  Delete "$SMPROGRAMS\Hermes Relay\Hermes Relay Systray.lnk"
  Delete "$SMPROGRAMS\Hermes Relay\Uninstall Hermes Relay.lnk"
  RMDir "$SMPROGRAMS\Hermes Relay"
  CreateDirectory "$SMPROGRAMS\Hermes-Relay CLI"
  CreateShortCut "$SMPROGRAMS\Hermes-Relay CLI\Hermes-Relay CLI.lnk" "$INSTDIR\hermes-relay.exe" "" "$INSTDIR\hermes-relay-tray.exe" 0
  CreateShortCut "$SMPROGRAMS\Hermes-Relay CLI\Hermes-Relay CLI UI.lnk" "$INSTDIR\hermes-relay-ui.cmd" "" "$INSTDIR\hermes-relay-tray.exe" 0
  CreateShortCut "$SMPROGRAMS\Hermes-Relay CLI\Uninstall Hermes-Relay CLI.lnk" "$INSTDIR\uninstall-hermes-relay.exe"

  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "HermesRelayTray"
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\hermes-relay-path.ps1" add "$INSTDIR"'
SectionEnd

Section "Start tray when I sign in" SEC_STARTUP
  IfSilent 0 normal_startup
  StrCmp $ExistingStartup "" startup_done
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "HermesRelayTray" '"$INSTDIR\hermes-relay-tray.exe"'
  Goto startup_done
normal_startup:
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "HermesRelayTray" '"$INSTDIR\hermes-relay-tray.exe"'
startup_done:
SectionEnd

Section "Uninstall"
  nsExec::ExecToLog '"$SYSDIR\taskkill.exe" /IM hermes-relay-tray.exe /F'
  nsExec::ExecToLog '"$INSTDIR\hermes-relay.exe" daemon stop'
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "HermesRelayTray"
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\hermes-relay-path.ps1" remove "$INSTDIR"'

  Delete "$SMPROGRAMS\Hermes Relay\Hermes Relay TUI.lnk"
  Delete "$SMPROGRAMS\Hermes Relay\Hermes Relay Tray.lnk"
  Delete "$SMPROGRAMS\Hermes Relay\Hermes Relay Systray.lnk"
  Delete "$SMPROGRAMS\Hermes Relay\Uninstall Hermes Relay.lnk"
  RMDir "$SMPROGRAMS\Hermes Relay"
  Delete "$SMPROGRAMS\Hermes-Relay CLI\Hermes-Relay CLI.lnk"
  Delete "$SMPROGRAMS\Hermes-Relay CLI\Hermes-Relay CLI UI.lnk"
  Delete "$SMPROGRAMS\Hermes-Relay CLI\Uninstall Hermes-Relay CLI.lnk"
  RMDir "$SMPROGRAMS\Hermes-Relay CLI"

  Delete "$INSTDIR\hermes-relay.exe"
  Delete "$INSTDIR\hermes-relay.new.exe"
  Delete "$INSTDIR\hermes-relay.old.exe"
  Delete "$INSTDIR\hermes-relay.exe.bak"
  Delete "$INSTDIR\hermes-relay-tray.exe"
  Delete "$INSTDIR\hermes-relay-tray.exe.bak"
  Delete "$INSTDIR\hermes-relay-path.ps1"
  Delete "$INSTDIR\hermes-relay-ui.cmd"
  Delete "$INSTDIR\uninstall-hermes-relay.exe"
  RMDir "$INSTDIR"

  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\HermesRelay"
  DeleteRegKey HKCU "Software\HermesRelay"
SectionEnd
