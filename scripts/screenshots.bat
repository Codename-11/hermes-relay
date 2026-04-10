@echo off
REM Thin shim — the real TUI lives in screenshots.py.
REM We use Python + rich because Windows cmd/PowerShell both have
REM latent Unicode/argument-binding bugs that make a hand-rolled
REM TUI fragile. Python's subprocess + rich sidesteps all of them.
where py >nul 2>&1
if %ERRORLEVEL%==0 (
    py -3 "%~dp0screenshots.py" %*
) else (
    python "%~dp0screenshots.py" %*
)
exit /b %ERRORLEVEL%
