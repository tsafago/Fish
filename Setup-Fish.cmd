@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup-Fish.ps1" %*
set "result=%errorlevel%"
if not "%result%"=="0" echo Fish setup did not finish. Read the error above, then retry.
pause
exit /b %result%
