@echo off
REM Hermes-Relay — dev helper scripts (Windows)
REM Usage: scripts\dev.bat <command>

cd /d "%~dp0\.."

if "%1"=="" goto help
if "%1"=="build" goto build
if "%1"=="release" goto release
if "%1"=="bundle" goto bundle
if "%1"=="install" goto install
if "%1"=="run" goto run
if "%1"=="test" goto test
if "%1"=="lint" goto lint
if "%1"=="clean" goto clean
if "%1"=="devices" goto devices
if "%1"=="version" goto version
if "%1"=="relay" goto relay
if "%1"=="certs" goto certs
if "%1"=="relay-tls" goto relay-tls
if "%1"=="help" goto help
goto help

:build
echo Building debug APK...
call gradlew.bat assembleDebug
echo APK: app\build\outputs\apk\debug\app-debug.apk
goto end

:release
echo Building release APK...
call gradlew.bat assembleRelease
if errorlevel 1 goto end
echo.
echo Release APK:
dir /b app\build\outputs\apk\release\*.apk 2>nul
echo Location: app\build\outputs\apk\release\
goto end

:bundle
echo Building release AAB (for Google Play upload)...
call gradlew.bat bundleRelease
if errorlevel 1 goto end
echo.
echo Release AAB:
dir /b app\build\outputs\bundle\release\*.aab 2>nul
echo Location: app\build\outputs\bundle\release\
goto end

:install
echo Building and installing to connected device...
call gradlew.bat installDebug
echo Launching app...
adb shell am start -n com.hermesandroid.relay/.MainActivity
goto end

:run
echo Building, installing, and launching...
call gradlew.bat installDebug
adb shell am start -n com.hermesandroid.relay/.MainActivity
adb logcat -s HermesRelay:* --format=brief
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
echo   build      Build debug APK
echo   release    Build signed release APK
echo   bundle     Build release AAB (for Google Play upload)
echo   install    Build + install to connected device
echo   run        Build + install + launch + logcat
echo   test       Run unit tests
echo   lint       Run lint checks
echo   clean      Clean build outputs
echo   devices    List connected devices
echo   version    Show current version from libs.versions.toml
echo   relay      Start relay server (dev mode, no TLS)
echo   certs      Generate dev TLS certificates
echo   relay-tls  Start relay server with dev TLS
goto end

:end
