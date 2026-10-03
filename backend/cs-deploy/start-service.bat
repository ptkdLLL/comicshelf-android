@echo off
chcp 65001 >nul
title ComicShelf backend - START (foreground, controlled)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-service.ps1" %*
echo.
pause
