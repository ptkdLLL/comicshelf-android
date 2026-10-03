#requires -Version 5.1
<#
.SYNOPSIS
  ComicShelf backend - one-click FOREGROUND starter with old-process cleanup.
  Companion to (but independent of) the deploy script. Double-click start-service.bat.

  What it does:
    1. locate the backend (beside this script / one level up; menu if multiple)
    2. CLEANUP: stop every old backend process - port owners of 8787/8080
       (covers detached deploy.py instances, double-bound llamas, orphans) plus
       pidfile-registered pids of known backends (name/commandline verified)
    3. start TWO VISIBLE console windows (llama-server :8080, cs-backend :8787)
       - live logs in the windows; CLOSE A WINDOW = STOP that service (user control)
    4. print the phone-app address, copy it to the clipboard, save PHONE-ADDRESS.txt

  Params:  -Yes   skip the "stop old processes?" confirmation (non-interactive)
#>
param([switch]$Yes)
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

function Ok([string]$m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Warn([string]$m) { Write-Host "  [!!] $m" -ForegroundColor Yellow }
function Die([string]$m)  { Write-Host "  [XX] $m" -ForegroundColor Red; exit 1 }

# ------------------------------------------------------------------ 1. locate backend
$roots = @($PSScriptRoot, (Split-Path $PSScriptRoot -Parent))
$cands = @()
foreach ($r in $roots) {
    foreach ($c in @((Join-Path $r 'comicshelf-android\backend'),
                     (Join-Path $r 'cs-deploy\comicshelf-android\backend'),
                     $r)) {
        try { $c = [IO.Path]::GetFullPath($c) } catch { continue }
        if ((Test-Path (Join-Path $c 'deploy.py')) -and ($cands -notcontains $c)) { $cands += $c }
    }
}
if ($cands.Count -eq 0) { Die 'backend not found. put this script beside comicshelf-android\ (or inside cs-deploy\).' }
$backend = $cands[0]
if ($cands.Count -gt 1) {
    Write-Host '  multiple backends found:'
    for ($i = 0; $i -lt $cands.Count; $i++) { Write-Host ("    [{0}] {1}" -f ($i + 1), $cands[$i]) }
    $sel = Read-Host '  choose which one to start [1]'
    if ($sel -match '^\d+$' -and [int]$sel -ge 1 -and [int]$sel -le $cands.Count) { $backend = $cands[[int]$sel - 1] }
}
Ok "backend: $backend"

# ------------------------------------------------------------------ 2. prerequisites
if (-not (Test-Path "$backend\venv\Scripts\python.exe")) { Die 'venv missing - run the deploy script first' }
if (-not (Test-Path "$backend\models\hymt\Hy-MT2-1.8B-Q4_K_M.gguf")) { Die 'GGUF model missing - run deploy / fetch-models first' }
if (-not (Test-Path "$backend\models\PaddleOCR-VL-For-Manga\model.safetensors")) { Die 'OCR model missing - run deploy / fetch-models first' }
$llamaBin = Join-Path $backend 'bin\llama-server.exe'
$useBin = Test-Path $llamaBin

# ------------------------------------------------------------------ 3. cleanup old backend processes
function Get-PortOwners {
    $out = @()
    foreach ($p in 8787, 8080) {
        Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
            ForEach-Object { $out += $_.OwningProcess }
    }
    return @($out | Sort-Object -Unique)
}
$targets = @(Get-PortOwners)
# pidfile-registered pids (scoped: only llama-server, or python running OUR uvicorn/llama_cpp server)
foreach ($b in $cands) {
    Get-ChildItem (Join-Path $b 'pids') -Filter '*.pid' -ErrorAction SilentlyContinue | ForEach-Object {
        $v = 0
        try { $v = [int]((Get-Content $_.FullName -Raw) -replace '\D', '') } catch {}
        if ($v -gt 0 -and ($targets -notcontains $v)) {
            $w = Get-CimInstance Win32_Process -Filter "ProcessId = $v" -ErrorAction SilentlyContinue
            if ($w) {
                $cl = "$($w.CommandLine)"
                if ($w.Name -eq 'llama-server.exe' -or $cl -match 'uvicorn server:app' -or $cl -match 'llama_cpp.server') { $targets += $v }
            }
        }
    }
}
$killList = @()
foreach ($v in $targets) {
    $pr = Get-Process -Id $v -ErrorAction SilentlyContinue
    if ($pr) { $killList += $pr }
}
if ($killList.Count -gt 0) {
    $names = ($killList | ForEach-Object { "$($_.Id):$($_.ProcessName)" }) -join '  '
    Write-Host "  old backend processes found: $names"
    if (-not $Yes) {
        $ans = Read-Host '  stop them and start fresh? [Y/n]'
        if ("$ans" -match '^[nN]') { Die 'aborted (old processes left running)' }
    }
    foreach ($pr in $killList) {
        try { Stop-Process -Id $pr.Id -Force -ErrorAction Stop; Ok "stopped $($pr.Id) $($pr.ProcessName)" }
        catch { Warn "cannot stop $($pr.Id) $($pr.ProcessName) - run this script as administrator" }
    }
    foreach ($i in 1..10) { Start-Sleep -Seconds 1; if (@(Get-PortOwners).Count -eq 0) { break } }
    if (@(Get-PortOwners).Count -gt 0) { Die 'ports 8787/8080 still occupied. admin cmd: netstat -ano | findstr "8787 8080" then taskkill /PID <pid> /F' }
    foreach ($b in $cands) { Remove-Item (Join-Path $b 'pids\*.pid') -Force -ErrorAction SilentlyContinue }
    Ok 'old processes cleaned, ports 8787/8080 free'
} else {
    Ok 'no old backend processes running'
}

# ------------------------------------------------------------------ 4. phone address
# default-route aware: the interface that actually carries traffic is the real LAN;
# exclude VPN/TUN/TAP adapters (SSTAP, OpenVPN, WireGuard...) which look like LANs but are not
$exclude = 'Loopback|vEthernet|WSL|VMware|Virtual|Docker|Hyper|SSTAP|TAP|TUN|VPN|WireGuard'
$ips = @()
try {
    $defIdx = @()
    Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop | Sort-Object RouteMetric |
        ForEach-Object { $defIdx += $_.InterfaceIndex }
    $table = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.InterfaceAlias -notmatch $exclude }
    foreach ($ix in $defIdx) { $table | Where-Object { $_.InterfaceIndex -eq $ix } | ForEach-Object { $ips += $_.IPAddress } }
    $table | Where-Object { $ips -notcontains $_.IPAddress } | ForEach-Object { $ips += $_.IPAddress }
} catch {}
$ips = @($ips | Select-Object -Unique)
# 192.168.* wins (typical phone LAN) regardless of ordering
$lan = @($ips | Where-Object { $_ -match '^192\.168\.' })
$oth = @($ips | Where-Object { $_ -notmatch '^192\.168\.' })
$ordered = @($lan + $oth)
$url = ''
if ($ordered.Count -gt 0) { $url = 'http://' + $ordered[0] + ':8787' }
$all = @($ordered) | ForEach-Object { 'http://' + $_ + ':8787' }
Set-Content -Path (Join-Path $backend 'PHONE-ADDRESS.txt') -Value ((($all -join "`r`n")) + "`r`n`r`nSTOP service: close the two service windows (or run stop-service.bat)")
$clipNote = ''
try { Set-Clipboard -Value $url -ErrorAction Stop; $clipNote = 'address copied to CLIPBOARD' }
catch {
    $url | clip.exe 2>$null
    if ($LASTEXITCODE -eq 0) { $clipNote = 'address copied to CLIPBOARD' }
    else { $clipNote = 'clipboard unavailable - copy from screen or PHONE-ADDRESS.txt' }
}

# ------------------------------------------------------------------ 5. two foreground windows
$ngl = '0'
if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) { $ngl = '99' }
$GGUF = '.\models\hymt\Hy-MT2-1.8B-Q4_K_M.gguf'
# window headers ALWAYS show the real phone address - uvicorn prints 0.0.0.0 (all-interfaces
# marker), which users cannot type into the app; the header removes that confusion
$hdr1 = "Write-Host '== llama-server  port 8080 (translation engine) ==  close this window to STOP ==' -ForegroundColor Cyan; Write-Host '   phone app address: $url' -ForegroundColor Yellow"
$hdr2 = "Write-Host '============================================================' -ForegroundColor Cyan; Write-Host '  cs-backend  port 8787   (close this window to STOP)' -ForegroundColor Cyan; Write-Host '' ; Write-Host '  PHONE APP ADDRESS (fill THIS into the app):' -ForegroundColor Yellow; Write-Host '      $url' -ForegroundColor Yellow; Write-Host '' ; Write-Host '  note: the 0.0.0.0 printed by uvicorn below only means' -ForegroundColor Gray; Write-Host '        listening on ALL adapters - use the address above' -ForegroundColor Gray; Write-Host '============================================================' -ForegroundColor Cyan"
if ($useBin) {
    $extra = ''
    if (((& $llamaBin --help 2>$null | Out-String) -match 'cache-ram')) { $extra = ' --cache-ram 1024' }
    $cmd1 = "$hdr1; Set-Location -LiteralPath '$backend'; & '.\bin\llama-server.exe' -m '$GGUF' --host 127.0.0.1 --port 8080 -c 4096 -ngl $ngl -t 8$extra"
} else {
    $cmd1 = "$hdr1; Set-Location -LiteralPath '$backend'; & '.\venv\Scripts\python.exe' -m llama_cpp.server --model '$GGUF' --host 127.0.0.1 --port 8080 --n_ctx 4096 --n_gpu_layers $ngl"
}
$cmd2 = "$hdr2; Set-Location -LiteralPath '$backend'; `$env:LLAMA_URL='http://127.0.0.1:8080'; & '.\venv\Scripts\python.exe' -m uvicorn server:app --host 0.0.0.0 --port 8787"
# single-string ArgumentList with the -Command payload in double quotes; payload itself
# uses only single quotes -> no nested-quote corruption, works with spaces/CJK in $backend
Start-Process powershell.exe -ArgumentList ('-NoExit -Command "' + $cmd1 + '"')
Start-Sleep -Seconds 2
Start-Process powershell.exe -ArgumentList ('-NoExit -Command "' + $cmd2 + '"')
Ok 'two service windows launched (llama-server :8080 / cs-backend :8787)'

# ------------------------------------------------------------------ 6. wait for health + banner
$health = $null
foreach ($i in 1..30) {
    Start-Sleep -Seconds 2
    $h = & curl.exe -s -m 3 http://127.0.0.1:8787/health 2>$null
    if ("$h" -match '"ok" *: *true') { $health = "$h"; break }
}
Write-Host ''
Write-Host '  ======================================================' -ForegroundColor Cyan
if ($health) { Write-Host '   ComicShelf backend : RUNNING' -ForegroundColor Green }
else { Write-Host '   ComicShelf backend : still starting (OCR loads ~10-15s)' -ForegroundColor Yellow }
Write-Host ''
Write-Host "   >>>  PHONE APP ADDRESS :  $url  <<<" -ForegroundColor Black -BackgroundColor Yellow
Write-Host "   $clipNote ; also saved to PHONE-ADDRESS.txt"
foreach ($u in @($all | Where-Object { $_ -ne $url })) { Write-Host "       alt: $u" }
Write-Host '   STOP : close the two service windows (or stop-service.bat)'
Write-Host '  ======================================================' -ForegroundColor Cyan
