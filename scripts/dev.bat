@echo off
REM Hermes-Relay — dev helper scripts (Windows)
REM Usage: scripts\dev.bat <command>

cd /d "%~dp0\.."

REM Secondary worktrees normally do not carry local.properties. Reuse the
REM standard per-user SDK location when the caller has not selected one.
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

if "%1"=="" goto help
if "%1"=="build" goto build
if "%1"=="release" goto release
if "%1"=="bundle" goto bundle
if "%1"=="install" goto install
if "%1"=="run" goto run
if "%1"=="compile" goto compile
if "%1"=="test-one" goto testone
if "%1"=="install-fast" goto installfast
if "%1"=="test" goto test
if "%1"=="lint" goto lint
if "%1"=="prepush" goto prepush
if "%1"=="clean" goto clean
if "%1"=="devices" goto devices
if "%1"=="version" goto version
if "%1"=="relay" goto relay
if "%1"=="certs" goto certs
if "%1"=="relay-tls" goto relay-tls
if "%1"=="help" goto help
goto help

:build
echo Building sideload debug APK...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle :app:assembleSideloadDebug --console=plain
if errorlevel 1 exit /b %errorlevel%
echo APK: app\build\outputs\apk\sideload\debug\
goto end

:release
echo Building release APK...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle assembleRelease
if errorlevel 1 exit /b %errorlevel%
echo.
echo Release APK:
dir /b app\build\outputs\apk\release\*.apk 2>nul
echo Location: app\build\outputs\apk\release\
goto end

:bundle
echo Building release AAB (for Google Play upload)...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle bundleRelease
if errorlevel 1 exit /b %errorlevel%
echo.
echo Release AAB:
dir /b app\build\outputs\bundle\release\*.aab 2>nul
echo Location: app\build\outputs\bundle\release\
goto end

:install
echo Building and installing sideload debug to connected device...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle :app:installSideloadDebug --console=plain
if errorlevel 1 exit /b %errorlevel%
echo Launching app...
REM Explicit FQCN: the sideload applicationId includes the flavor suffix but the
REM namespace (and thus the real class FQCN) is still com.hermesandroid.relay,
REM so the `.MainActivity` shorthand no longer resolves correctly.
adb shell am start -n com.axiomlabs.hermesrelay.sideload/com.hermesandroid.relay.MainActivity
goto end

:run
echo Building, installing, and launching sideload debug...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle :app:installSideloadDebug --console=plain
if errorlevel 1 exit /b %errorlevel%
REM Explicit FQCN: the sideload applicationId includes the flavor suffix but the
REM namespace (and thus the real class FQCN) is still com.hermesandroid.relay,
REM so the `.MainActivity` shorthand no longer resolves correctly.
adb shell am start -n com.axiomlabs.hermesrelay.sideload/com.hermesandroid.relay.MainActivity
adb logcat -s HermesRelay:* --format=brief
goto end

:compile
echo Compiling sideload debug Kotlin...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle :app:compileSideloadDebugKotlin --console=plain
if errorlevel 1 exit /b %errorlevel%
goto end

:testone
if "%~2"=="" (
    echo Usage: scripts\dev.bat test-one ^<fully-qualified-test-class-or-pattern^>
    exit /b 2
)
echo Running focused sideload test: %~2
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle :app:testSideloadDebugUnitTest --tests "%~2" --console=plain
if errorlevel 1 exit /b %errorlevel%
goto end

:installfast
echo Building arm64 sideload debug and installing to connected phone...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle :app:installSideloadDebug -Phermes.devAbi=arm64-v8a --console=plain
if errorlevel 1 exit /b %errorlevel%
echo Launching app...
adb shell am start -n com.axiomlabs.hermesrelay.sideload/com.hermesandroid.relay.MainActivity
goto end

:test
echo Running sideload debug unit tests...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle :app:testSideloadDebugUnitTest --console=plain
if errorlevel 1 exit /b %errorlevel%
goto end

:lint
echo Running sideload debug lint...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle :app:lintSideloadDebug --console=plain
if errorlevel 1 exit /b %errorlevel%
goto end

:prepush
echo Running Android pre-push checks...
python scripts\android-prepush.py
if errorlevel 1 exit /b %errorlevel%
goto end

:clean
echo Cleaning build...
call powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\android-lane.ps1 gradle clean
if errorlevel 1 exit /b %errorlevel%
goto end

:devices
adb devices -l
goto end

:version
for /f "tokens=2 delims==" %%a in ('findstr "appVersionName" gradle\libs.versions.toml') do set "VER=%%~a"
for /f "tokens=2 delims==" %%a in ('findstr "appVersionCode" gradle\libs.versions.toml') do set "CODE=%%~a"
REM Strip surrounding whitespace and quotes
set "VER=%VER: =%"
set "VER=%VER:"=%"
set "CODE=%CODE: =%"
set "CODE=%CODE:"=%"
echo Hermes-Relay v%VER% (versionCode %CODE%)
goto end

:relay
echo Starting relay server...
python -m relay_server --no-ssl --log-level DEBUG
goto end

:certs
echo Generating dev TLS certificates...
call "%~dp0\gen-dev-cert.bat" %2
goto end

:relay-tls
echo Starting relay server with dev TLS...
if not exist "certs\dev.crt" (
    echo No dev certs found. Generating...
    call "%~dp0\gen-dev-cert.bat" localhost
)
python -m relay_server --ssl-cert certs/dev.crt --ssl-key certs/dev.key --log-level DEBUG
goto end

:help
echo Hermes-Relay Dev Scripts
echo.
echo   build      Build sideload debug APK
echo   release    Build signed release APK
echo   bundle     Build release AAB (for Google Play upload)
echo   install    Build universal sideload + install
echo   run        Build + install + launch + logcat
echo   compile    Compile sideload debug Kotlin only
echo   test-one   Run one test class or wildcard pattern
echo   install-fast  Build arm64 only + install + launch
echo   test       Run sideload debug unit tests
echo   lint       Run lint checks
echo   prepush    Run Android repository checks, lint, and focused CI tests
echo   clean      Clean build outputs
echo   devices    List connected devices
echo   version    Show current version from libs.versions.toml
echo   relay      Start relay server (dev mode, no TLS)
echo   certs      Generate dev TLS certificates
echo   relay-tls  Start relay server with dev TLS
goto end

:end
