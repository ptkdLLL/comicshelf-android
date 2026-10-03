@echo off
chcp 65001 >nul
title ComicShelf Backend - One-Click Deploy
echo.
echo   ================================================
echo    ComicShelf cs-backend  one-click deploy (NVIDIA)
echo    double-click is all you need. re-run = idempotent.
echo   ================================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
set RC=%ERRORLEVEL%
echo.
if "%RC%"=="0" (
    echo   [deploy.bat] finished. this window can be closed.
) else (
    echo   [deploy.bat] finished with code %RC%. scroll up for errors.
)
echo.
pause
