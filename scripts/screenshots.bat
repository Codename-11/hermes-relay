@echo off
REM screenshots.bat — Interactive Play Store screenshot capture tool.
REM Usage: scripts\screenshots.bat

setlocal enabledelayedexpansion
cd /d "%~dp0\.."

set OUT_DIR=assets\screenshots
set DEVICE_TMP=/sdcard/hermes_screenshot.png
set DEMO_ON=0
set COUNT=0

if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

REM Count existing screenshots
for %%f in ("%OUT_DIR%\*.png") do set /a COUNT+=1

cls
echo.
echo   ╔══════════════════════════════════════════════╗
echo   ║       Hermes Relay — Screenshot Capture      ║
echo   ╚══════════════════════════════════════════════╝
echo.

REM Check device
adb get-state >nul 2>&1
if errorlevel 1 (
    echo   [X] No device connected. Plug in via ADB and retry.
    pause
    exit /b 1
)
for /f "usebackq tokens=*" %%a in (`adb shell getprop ro.product.model`) do set DEVICE=%%a
echo   Device: %DEVICE%
echo   Output: %OUT_DIR%\
echo   Existing screenshots: %COUNT%
echo.

:menu
echo   ┌──────────────────────────────────────────────┐
echo   │  [D] Demo mode on    [R] Restore status bar  │
echo   │  [S] Snap screenshot [L] List screenshots    │
echo   │  [O] Open folder     [Q] Quit                │
echo   └──────────────────────────────────────────────┘
echo.

if %DEMO_ON%==1 (
    echo   Status bar: DEMO MODE ^(clean^)
) else (
    echo   Status bar: normal
)
echo.

set /p CHOICE=  ^>

if /i "!CHOICE!"=="d" goto demo
if /i "!CHOICE!"=="r" goto restore
if /i "!CHOICE!"=="s" goto snap
if /i "!CHOICE!"=="l" goto list
if /i "!CHOICE!"=="o" goto openfolder
if /i "!CHOICE!"=="q" goto quit
echo   [?] Unknown option. Try D, S, R, L, O, or Q.
echo.
goto menu

:demo
echo.
echo   Enabling demo mode...
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo --es command clock --es hhmm 1200 >nul 2>&1
adb shell am broadcast -a com.android.systemui.demo --es command battery --es level 100 --es plugged false >nul 2>&1
adb shell am broadcast -a com.android.systemui.demo --es command network --es wifi show --es level 4 >nul 2>&1
adb shell am broadcast -a com.android.systemui.demo --es command network --es mobile show --es level 4 --es datatype none >nul 2>&1
adb shell am broadcast -a com.android.systemui.demo --es command notifications --es visible false >nul 2>&1
set DEMO_ON=1
echo   [OK] Demo mode active (12:00, full battery, no notifications)
echo.
echo   TIP: If status bar didn't change, enable Demo Mode in
echo        device Settings ^> Developer Options first.
echo.
goto menu

:restore
echo.
echo   Restoring status bar...
adb shell am broadcast -a com.android.systemui.demo --es command exit >nul 2>&1
adb shell settings put global sysui_demo_allowed 0 >nul 2>&1
set DEMO_ON=0
echo   [OK] Status bar restored
echo.
goto menu

:snap
echo.
echo   Suggested names:
echo     01_chat_empty          05_sphere_ambient
echo     02_chat_conversation   06_markdown
echo     03_command_palette     07_reasoning
echo     04_settings            08_tool_cards
echo.
set /p SNAPNAME=  Name (without .png):

if "!SNAPNAME!"=="" (
    echo   [X] Cancelled
    echo.
    goto menu
)

echo   Capturing...
adb shell screencap -p %DEVICE_TMP%
adb pull %DEVICE_TMP% "%OUT_DIR%\!SNAPNAME!.png" >nul 2>&1
adb shell rm -f %DEVICE_TMP%
set /a COUNT+=1

for %%f in ("%OUT_DIR%\!SNAPNAME!.png") do (
    echo   [OK] !SNAPNAME!.png -- %%~zf bytes
)
echo   Total: !COUNT! screenshots
echo.
goto menu

:list
echo.
echo   ┌──────────────────────────────────────────────┐
echo   │  Captured Screenshots                        │
echo   └──────────────────────────────────────────────┘
set FOUND=0
for %%f in ("%OUT_DIR%\*.png") do (
    echo     %%~nxf -- %%~zf bytes
    set FOUND=1
)
if !FOUND!==0 echo     (none yet)
echo.
goto menu

:openfolder
start "" "%OUT_DIR%"
echo   [OK] Opened %OUT_DIR%\
echo.
goto menu

:quit
if %DEMO_ON%==1 (
    echo.
    echo   Demo mode is still on. Restore first?
    set /p CONFIRM=  Restore before quitting? [Y/n]:
    if /i not "!CONFIRM!"=="n" (
        adb shell am broadcast -a com.android.systemui.demo --es command exit >nul 2>&1
        adb shell settings put global sysui_demo_allowed 0 >nul 2>&1
        echo   [OK] Status bar restored
    )
)
echo.
echo   Done. Screenshots in %OUT_DIR%\
endlocal
exit /b 0
