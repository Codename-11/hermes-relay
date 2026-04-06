@echo off
REM Generate self-signed TLS cert for dev use with relay server
REM Requires: openssl (comes with Git for Windows)
REM Outputs: certs\dev.crt and certs\dev.key

set HOSTNAME=%1
if "%HOSTNAME%"=="" set HOSTNAME=localhost
set CERT_DIR=%~dp0\..\certs

if not exist "%CERT_DIR%" mkdir "%CERT_DIR%"

echo Generating self-signed cert for: %HOSTNAME%

openssl req -x509 -newkey rsa:2048 ^
    -keyout "%CERT_DIR%\dev.key" ^
    -out "%CERT_DIR%\dev.crt" ^
    -days 365 ^
    -nodes ^
    -subj "/CN=%HOSTNAME%" ^
    -addext "subjectAltName=DNS:%HOSTNAME%,DNS:localhost,IP:127.0.0.1,IP:10.0.2.2"

echo.
echo Certificate generated:
echo   Cert: %CERT_DIR%\dev.crt
echo   Key:  %CERT_DIR%\dev.key
echo.
echo Start relay with TLS:
echo   python -m relay_server --ssl-cert certs/dev.crt --ssl-key certs/dev.key
