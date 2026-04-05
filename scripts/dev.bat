@echo off
REM Hermes Companion — dev helper scripts (Windows)
REM Usage: scripts\dev.bat <command>

cd /d "%~dp0\.."

if "%1"=="" goto help
if "%1"=="build" goto build
if "%1"=="install" goto install
if "%1"=="run" goto run
if "%1"=="test" goto test
if "%1"=="lint" goto lint
if "%1"=="clean" goto clean
if "%1"=="devices" goto devices
if "%1"=="relay" goto relay
if "%1"=="help" goto help
goto help

:build
echo Building debug APK...
call gradlew.bat assembleDebug
echo APK: app\build\outputs\apk\debug\app-debug.apk
goto end

:install
echo Building and installing to connected device...
call gradlew.bat installDebug
echo Launching app...
adb shell am start -n com.hermesandroid.companion/.CompanionActivity
goto end

:run
echo Building, installing, and launching...
call gradlew.bat installDebug
adb shell am start -n com.hermesandroid.companion/.CompanionActivity
adb logcat -s HermesCompanion:* --format=brief
goto end

:test
echo Running unit tests...
call gradlew.bat test
goto end

:lint
echo Running lint...
call gradlew.bat lint
goto end

:clean
echo Cleaning build...
call gradlew.bat clean
goto end

:devices
adb devices -l
goto end

:relay
echo Starting companion relay...
python -m companion_relay --no-ssl --log-level DEBUG
goto end

:help
echo Hermes Companion Dev Scripts
echo.
echo   build      Build debug APK
echo   install    Build + install to connected device
echo   run        Build + install + launch + logcat
echo   test       Run unit tests
echo   lint       Run lint checks
echo   clean      Clean build outputs
echo   devices    List connected devices
echo   relay      Start companion relay (dev mode)
goto end

:end
