@echo off
rem cs-backend 一键启动 (Windows)。双击即用; 也可带参数: start.bat status
chcp 65001 >nul
cd /d "%~dp0"
where py >nul 2>nul && (set "PYCMD=py -3") || (set "PYCMD=python")
%PYCMD% deploy.py %*
if "%~1"=="" (
  echo.
  echo [deploy] 服务已在后台运行; 本窗口可关闭。停止: start.bat stop
  pause
)
