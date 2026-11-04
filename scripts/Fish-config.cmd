@echo off
setlocal
"%~dp0Fish.exe" --config %*
exit /b %errorlevel%
