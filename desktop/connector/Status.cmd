@echo off
chcp 65001 >nul
if not exist "%~dp0connector-control.ps1" (
  echo Please install the connector first, then use this shortcut from its installed folder.
  pause
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0connector-control.ps1" -Action Status
pause
