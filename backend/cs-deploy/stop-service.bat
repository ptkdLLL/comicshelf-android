@echo off
chcp 65001 >nul
title ComicShelf backend - STOP
echo stopping ComicShelf backend services (ports 8787 / 8080)...
powershell -NoProfile -Command "foreach($p in 8787,8080){ Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | ForEach-Object { $pr = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue; if ($pr) { Stop-Process -Id $pr.Id -Force -ErrorAction SilentlyContinue; 'stopped ' + $pr.Id + ' ' + $pr.ProcessName } } }; 'ports 8787/8080 are free now.'"
echo.
echo (foreground windows can also be stopped by simply closing them)
pause
