@echo off
echo ========================================================
echo   Evi Unofficial - Publish Release to GitHub
echo ========================================================
echo.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-release.ps1"
echo.
pause
