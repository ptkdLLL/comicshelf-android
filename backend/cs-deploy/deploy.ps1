#requires -Version 5.1
<#
.SYNOPSIS
  ComicShelf cs-backend Windows one-click deploy (NVIDIA CUDA preferred, zero prerequisites)
.DESCRIPTION
  Triggered by double-clicking deploy.bat. From true zero:
    0. locate / download repository code
    1. find Python 3.10-3.12 (scan every common install location, skip Store stubs;
       auto-download + silent user-level install when absent)
    2. create venv
    3. install deps via domestic pip mirror chain (CUDA/CPU auto-detected)
    4. download llama.cpp Windows CUDA binaries (ghproxy accelerator chain,
       automatic llama-cpp-python CPU fallback)
    5. download the three models (hf-mirror/official x direct/system-proxy, probed per pair)
    6. start services and verify /health
  Idempotent: finished phases are skipped; safe to re-run after interruption.
  Network-routing lessons applied (see deployment archive SS5):
    - probe and download use the SAME stack (curl.exe)
    - validate business headers (x-repo-commit), not bare HTTP 200
    - "direct" must explicitly bypass the registry system proxy (NO_PROXY=* + --noproxy '*')
  NOTE (PS 5.1): $ErrorActionPreference intentionally NOT 'Stop' here - PS 5.1 turns
  native-command stderr into ErrorRecords and any 2>$null redirect would kill the script.
  All failures are handled via explicit $LASTEXITCODE / Test-Path checks instead.
#>
param(
    [switch]$NoStart,     # deploy only, do not start
    [switch]$Cpu,         # force CPU (skip CUDA)
    [switch]$Firewall     # try to add inbound rule for 8787 (may raise UAC)
)
$ErrorActionPreference = 'Continue'
$T0 = Get-Date
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
$script:Root = $PSScriptRoot

# ---------------------------------------------------------------- logging
function Phase([string]$m) { Write-Host "`n==== $m ====" -ForegroundColor Cyan }
function Ok([string]$m)    { Write-Host "  [OK] $m" -ForegroundColor Green }
function Warn([string]$m)  { Write-Host "  [!!] $m" -ForegroundColor Yellow }
function Die([string]$m)   { Write-Host "  [XX] $m" -ForegroundColor Red; exit 1 }

function Test-Cmd([string]$name) { Get-Command $name -ErrorAction SilentlyContinue }

# ---------------------------------------------------------------- network route manager
# three proxy layers: env vars / registry system proxy / proxy-client rules.
# direct = explicitly bypass everything; system = follow system (registry proxy to curl,
#          original env inherited by child processes)
$script:OrigProxyEnv = @{}
foreach ($k in 'HTTP_PROXY','HTTPS_PROXY','http_proxy','https_proxy','NO_PROXY','no_proxy') {
    if (Test-Path ("Env:" + $k)) { $script:OrigProxyEnv[$k] = (Get-Item ("Env:" + $k)).Value }
}
function Get-SystemProxy {
    try {
        $s = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction Stop
        if ($s.ProxyEnable -and $s.ProxyServer) {
            $p = [string]$s.ProxyServer
            if ($p -match '=') {   # "http=...;https=..." form
                $m = ($p -split ';' | Where-Object { $_ -match '^https?=' }) -join ''
                if ($m) { $p = ($m -split '=', 2)[1] }
            }
            if ($p -notmatch '^https?://') { $p = 'http://' + $p }
            return $p
        }
    } catch {}
    return $null
}
function Set-Route([string]$route) {
    if ($route -eq 'direct') {
        foreach ($k in 'HTTP_PROXY','HTTPS_PROXY','http_proxy','https_proxy') {
            Remove-Item ("Env:" + $k) -ErrorAction SilentlyContinue
        }
        $env:NO_PROXY = '*'; $env:no_proxy = '*'
    } else {
        foreach ($k in $script:OrigProxyEnv.Keys) { Set-Item ("Env:" + $k) $script:OrigProxyEnv[$k] }
        foreach ($k in @('NO_PROXY','no_proxy')) {
            if (-not $script:OrigProxyEnv.ContainsKey($k)) { Remove-Item ("Env:" + $k) -ErrorAction SilentlyContinue }
        }
    }
}
function Get-CurlRouteArgs([string]$route) {
    if ($route -eq 'direct') { return @('--noproxy', '*') }
    $p = Get-SystemProxy
    if ($p) { return @('-x', $p) }
    return @()
}
function Test-Url([string]$url, [string]$route, [int]$timeout = 10) {
    $a = Get-CurlRouteArgs $route
    $code = & curl.exe -s -o NUL -w '%{http_code}' -m $timeout @a $url 2>$null
    if ("$code" -match '^\d{3}$') { return [int]$code }
    return 0
}
function Get-File([string]$url, [string]$dest, [string]$route, [int]$minBytes = 1024) {
    $a = Get-CurlRouteArgs $route
    & curl.exe -L --fail --retry 2 -C - -sS -o $dest @a $url 2>$null
    if ($LASTEXITCODE -ne 0) { return $false }
    if (-not (Test-Path $dest)) { return $false }
    if ((Get-Item $dest).Length -lt $minBytes) { return $false }
    return $true
}

# ---------------------------------------------------------------- mirror / endpoint tables
$script:PipMirrors = @(
    'https://pypi.tuna.tsinghua.edu.cn/simple',
    'https://mirrors.aliyun.com/pypi/simple/',
    'https://mirrors.cloud.tencent.com/pypi/simple',
    'https://mirror.sjtu.edu.cn/pypi/web/simple'
)
$script:TorchBases = @('https://mirrors.aliyun.com/pytorch-wheels', 'https://mirror.sjtu.edu.cn/pytorch-wheels')
$script:PythonInstallMirrors = @(
    @{ n = 'npmmirror';   u = 'https://registry.npmmirror.com/-/binary/python/3.11.9/python-3.11.9-amd64.exe' },
    @{ n = 'huaweicloud'; u = 'https://mirrors.huaweicloud.com/python/3.11.9/python-3.11.9-amd64.exe' }
)
$script:GhAccels = @('https://ghfast.top/', 'https://gh-proxy.com/', 'https://mirror.ghproxy.com/', '')
$script:RepoZipUrl = 'https://github.com/ptkdLLL/comicshelf-android/archive/refs/heads/main.zip'
# llama.cpp pinned known-good build (b-tagged pre-release; bump these two lines to upgrade)
$script:LlamaTag = 'b11368'
$script:LlamaZips = @(
    "llama-$($script:LlamaTag)-bin-win-cuda-12.4-x64.zip",
    "cudart-llama-bin-win-cuda-12.4-x64.zip"
)
$script:HfEndpoints = @('https://hf-mirror.com', 'https://huggingface.co')
$script:Routes = @('direct', 'system')   # domestic mirrors prefer direct, fall back to system proxy

# ================================================================ phase 0: preflight
Phase '0/7 preflight'
if (-not (Test-Cmd curl.exe)) { Die 'curl.exe not found (needs Windows 10 1803+). Please update Windows.' }
$sysProxy = Get-SystemProxy
if ($sysProxy) { Warn "registry system proxy detected: $sysProxy (script tests BOTH direct and proxied routes)" }
else { Ok 'no registry system proxy' }

$lockPath = Join-Path $script:Root '.deploy.lock'
if (Test-Path $lockPath) {
    $age = ((Get-Date) - (Get-Item $lockPath).LastWriteTime).TotalHours
    if ($age -lt 6) { Die "another deploy seems running (lock < 6h old): $lockPath. If not, delete it and retry." }
    Warn 'stale lock (>6h) removed'
    Remove-Item $lockPath -Force
}
Set-Content -Path $lockPath -Value ("pid=" + $PID + " time=" + (Get-Date -Format s))
try {
    # ============================================================ phase 1: repository
    Phase '1/7 locate / fetch repository code'
    $backend = $null
    $parent = Split-Path $script:Root -Parent
    foreach ($c in @((Join-Path $script:Root 'comicshelf-android\backend'),
                     (Join-Path $script:Root 'backend'),
                     (Join-Path $parent 'comicshelf-android\backend'),   # script inside a subfolder of the repo's parent
                     (Join-Path $parent 'backend'),
                     $script:Root)) {
        if (Test-Path (Join-Path $c 'deploy.py')) { $backend = $c; break }
    }
    if (-not $backend) {
        Warn 'repo not found next to script -> downloading archive (ghproxy accelerator chain)'
        $zip = Join-Path $env:TEMP 'comicshelf-main.zip'
        $got = $false
        :dl foreach ($r in $script:Routes) {
            foreach ($acc in $script:GhAccels) {
                $u = $acc + $script:RepoZipUrl
                Write-Host "  try $u [$r]"
                Set-Route $r
                if (Get-File $u $zip $r 100000) { $got = $true; break :dl }
            }
        }
        Set-Route 'system'
        if (-not $got) { Die 'repo zip download failed via every accelerator (repo may be private/deleted, or network blocked). Put an extracted comicshelf-android\ folder next to deploy.bat and retry.' }
        $ex = Join-Path $env:TEMP 'comicshelf-extract'
        if (Test-Path $ex) { Remove-Item $ex -Recurse -Force }
        try { Expand-Archive -Path $zip -DestinationPath $ex -Force -ErrorAction Stop } catch { Die "archive extract failed: $_" }
        $inner = Get-ChildItem $ex -Directory | Select-Object -First 1
        $dest = Join-Path $script:Root 'comicshelf-android'
        if (Test-Path $dest) {
            $bak = "$dest.bak." + (Get-Date -Format 'yyyyMMddHHmmss')
            Warn "destination exists but has no backend\deploy.py -> renamed to $bak"
            Rename-Item $dest $bak
        }
        Move-Item $inner.FullName $dest
        Remove-Item $ex -Recurse -Force -ErrorAction SilentlyContinue
        $backend = Join-Path $dest 'backend'
    }
    if (-not (Test-Path (Join-Path $backend 'deploy.py'))) { Die "deploy.py not found under $backend" }
    Ok "backend directory: $backend"

    # ============================================================ phase 2: Python
    Phase '2/7 Python 3.10 - 3.12 (auto-install if missing)'
    function Get-PyVer([string]$exe) {
        # NOTE: no quotes inside -c payload! PS 5.1 native arg binding strips embedded
        # double quotes -> python would receive a syntax error. Print space-separated digits.
        try {
            $o = & $exe -c 'import sys; print(sys.version_info[0], sys.version_info[1], sys.version_info[2])' 2>$null
            if ($LASTEXITCODE -eq 0 -and "$o" -match '^(3) (\d+) (\d+)') {
                return [version]("{0}.{1}.{2}" -f $Matches[1], $Matches[2], $Matches[3])
            }
        } catch {}
        return $null
    }
    function Find-Python {
        $cands = New-Object System.Collections.Generic.List[string]
        # 1) py launcher full list (more reliable than probing py -3.x one by one)
        $py = Test-Cmd py
        if ($py) {
            $list = (& py -0p 2>$null | Out-String) -split "`n"
            foreach ($ln in $list) {
                if ($ln -match '(\S*python\.exe)\s*$') {
                    $e = $Matches[1]
                    if ((Test-Path $e) -and $e -notmatch 'WindowsApps') { $cands.Add($e) }
                }
            }
        }
        # 2) PATH (skip Microsoft Store stubs)
        foreach ($n in 'python', 'python3', 'python3.12', 'python3.11', 'python3.10') {
            $c = Test-Cmd $n
            if ($c -and $c.Source -notmatch 'WindowsApps') { $cands.Add($c.Source) }
        }
        # 3) well-known fixed locations (incl. the one this script installs)
        $fixed = @()
        foreach ($v in '10', '11', '12') {
            $fixed += "$env:LOCALAPPDATA\Programs\Python\Python3$v\python.exe"
            $fixed += "C:\Python3$v\python.exe"
            $fixed += "C:\Program Files\Python3$v\python.exe"
        }
        $fixed += "$env:USERPROFILE\.local\bin\python3.10.exe"
        $fixed += "$env:USERPROFILE\.local\bin\python3.11.exe"
        $fixed += "$env:USERPROFILE\.local\bin\python3.12.exe"
        $fixed += "$env:USERPROFILE\miniconda3\python.exe"
        $fixed += "$env:LOCALAPPDATA\ComicShelf\Python311\python.exe"
        foreach ($f in $fixed) { if (Test-Path $f) { $cands.Add($f) } }
        foreach ($e in $cands) {
            $v = Get-PyVer $e
            if ($v -and $v -ge [version]'3.10.0' -and $v -le [version]'3.12.99') { return @{ exe = $e; ver = "$v" } }
        }
        return $null
    }
    $pyInfo = Find-Python
    if ($pyInfo) {
        Ok "Python $($pyInfo.ver) found: $($pyInfo.exe)"
    } else {
        Warn 'no Python 3.10-3.12 -> downloading 3.11.9 (domestic mirror, silent user-level install)'
        $inst = Join-Path $env:TEMP 'python-3.11.9-amd64.exe'
        $got = $false
        :pdl foreach ($r in $script:Routes) {
            foreach ($m in $script:PythonInstallMirrors) {
                Write-Host "  try $($m.n) [$r]"
                Set-Route $r
                if (Get-File $m.u $inst $r 5000000) { $got = $true; break :pdl }
            }
        }
        Set-Route 'system'
        if (-not $got) { Die 'python installer download failed from all mirrors' }
        $tgt = "$env:LOCALAPPDATA\ComicShelf\Python311"
        Write-Host '  installing (1-3 min, no UAC, no PATH pollution)...'
        $p = Start-Process -FilePath $inst -ArgumentList "/quiet InstallAllUsers=0 TargetDir=`"$tgt`" Include_doc=0 Include_test=0 Include_launcher=0 PrependPath=0 Shortcuts=0" -Wait -PassThru
        if ($p.ExitCode -ne 0 -or -not (Test-Path "$tgt\python.exe")) {
            Die "python silent install failed (code $($p.ExitCode)). Run $inst manually, then re-run deploy."
        }
        $pyInfo = @{ exe = "$tgt\python.exe"; ver = "$(Get-PyVer "$tgt\python.exe")" }
        Ok "Python $($pyInfo.ver) installed to $tgt"
    }
    $script:VenvPy = Join-Path $backend 'venv\Scripts\python.exe'

    # ============================================================ phase 3: venv
    Phase '3/7 virtualenv'
    if (Test-Path $script:VenvPy) {
        Ok "venv exists (Python $(Get-PyVer $script:VenvPy))"
    } else {
        & $pyInfo.exe -m venv (Join-Path $backend 'venv')
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $script:VenvPy)) { Die 'venv creation failed' }
        Ok 'venv created'
    }

    # ============================================================ phase 4: dependencies
    Phase '4/7 dependencies (torch / onnxruntime-gpu / requirements)'
    $wantCuda = $false
    if ($Cpu) { Warn '-Cpu switch: CPU mode forced' }
    else {
        $smi = Test-Cmd nvidia-smi
        if ($smi) {
            $drv = (& nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>$null | Select-Object -First 1)
            $gpuName = (& nvidia-smi --query-gpu=name --format=csv,noheader 2>$null | Select-Object -First 1)
            $vramMB = 0
            $vr = (& nvidia-smi --query-gpu=memory.total --format=csv,noheader 2>$null | Select-Object -First 1)
            if ("$vr" -match '(\d+)') { $vramMB = [int]$Matches[1] }
            $drvMajor = 0; if ("$drv" -match '^(\d+)\.') { $drvMajor = [int]$Matches[1] }
            Ok "GPU: $gpuName  driver=$drv  VRAM=${vramMB}MB"
            if ($drvMajor -ge 528) { $wantCuda = $true }   # CUDA 12.x minor-compat floor
            else { Warn "driver $drv too old for CUDA-12 wheels -> CPU mode (update NVIDIA driver to enable CUDA)" }
            if ($vramMB -gt 0 -and $vramMB -lt 6000) { Warn "VRAM ${vramMB}MB < 6GB: OCR (fp16) may OOM; det stays CUDA; consider -Cpu" }
        } else { Warn 'nvidia-smi not found -> CPU mode' }
    }
    # dependency gate: only skip when everything imports AND cuda state matches expectation
    # (quote-free -c payload, same PS 5.1 reason as Get-PyVer)
    $probe = 'import torch, transformers, onnxruntime, cv2, fastapi; print(torch.cuda.is_available())'
    $o = & $script:VenvPy -c $probe 2>$null
    if ($LASTEXITCODE -eq 0) {
        $cudaNow = ("$o" -match 'True')
        if ($cudaNow -eq $wantCuda) { $depsOk = $true; Ok "deps already satisfied (cuda=$cudaNow)" }
        else { Warn "deps present but cuda=$cudaNow while expected=$wantCuda -> reinstalling" }
    }
    if (-not $depsOk) {
        $torchTag = 'cpu'; if ($wantCuda) { $torchTag = 'cu126' }
        $done = $false
        :pip foreach ($pi in $script:PipMirrors) {
            $torchIdx = @($script:TorchBases | ForEach-Object { "$_/$torchTag" })
            foreach ($ti in (@($torchIdx + @('')))) {   # trailing '' = let deploy.py probe by itself
                foreach ($r in $script:Routes) {
                    Write-Host "  pip via $pi torch=$ti [$r]"
                    Set-Route $r
                    $env:PIP_INDEX_URL = $pi
                    if ($ti) { $env:CS_TORCH_INDEX = $ti } else { Remove-Item Env:CS_TORCH_INDEX -ErrorAction SilentlyContinue }
                    $setupArgs = @('deploy.py', 'setup', '--no-models')
                    if ($wantCuda) { $setupArgs += '--cuda' } else { $setupArgs += '--cpu' }
                    Push-Location $backend
                    & $script:VenvPy @setupArgs 2>&1 | ForEach-Object { Write-Host "    $_" }
                    $code = $LASTEXITCODE
                    Pop-Location
                    if ($code -eq 0) { $done = $true; break :pip }
                    Warn '  -> failed, trying next mirror/route...'
                }
            }
        }
        Set-Route 'system'
        if (-not $done) { Die "pip install failed on all mirrors. Check network, delete $backend\venv, re-run." }
        $o = & $script:VenvPy -c $probe 2>$null
        if ($LASTEXITCODE -ne 0) { Die 'deps verify failed after install; see messages above' }
        if ($wantCuda -and ("$o" -notmatch 'True')) { Warn 'torch installed but CUDA unavailable -> continuing' }
        Ok 'dependencies installed'
    }

    # ============================================================ phase 5: llama.cpp binary
    Phase '5/7 llama.cpp server binary (CUDA)'
    $llamaExe = Join-Path $backend 'bin\llama-server.exe'
    $needLlama = -not (Test-Path $llamaExe)
    if (-not $needLlama) {
        & $llamaExe --version 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { Ok "llama-server already present: $llamaExe" }
        else { Warn 'existing llama-server.exe broken -> re-downloading'; $needLlama = $true }
    }
    if ($needLlama) {
        $binDir = Join-Path $backend 'bin'
        New-Item -ItemType Directory -Force -Path $binDir | Out-Null
        $base = "https://github.com/ggml-org/llama.cpp/releases/download/$($script:LlamaTag)"
        $gotAll = $true
        foreach ($z in $script:LlamaZips) {
            $zp = Join-Path $env:TEMP $z
            $got = $false
            :lz foreach ($r in $script:Routes) {
                foreach ($acc in $script:GhAccels) {
                    $u = $acc + "$base/$z"
                    Write-Host "  try $u [$r]"
                    Set-Route $r
                    if (Get-File $u $zp $r 1000000) { $got = $true; break :lz }
                }
            }
            Set-Route 'system'
            if (-not $got) { $gotAll = $false; Warn "  FAILED: $z"; break }
            try { Expand-Archive -Path $zp -DestinationPath $binDir -Force -ErrorAction Stop } catch { $gotAll = $false; Warn "extract failed: $_"; break }
            Ok "  extracted $z"
        }
        if ($gotAll -and (Test-Path $llamaExe)) {
            & $llamaExe --version 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { Ok "llama-server $($script:LlamaTag) installed" }
            else { Warn 'llama-server.exe fails to run -> falling back'; $gotAll = $false }
        } else { $gotAll = $false }
        if (-not $gotAll) {
            # automatic fallback: pip llama-cpp-python (CPU; deploy.py tier-2 uses it automatically)
            Warn 'GPU binary unavailable -> installing llama-cpp-python (CPU) as automatic fallback'
            $fb = $false
            foreach ($pi in $script:PipMirrors) {
                foreach ($r in $script:Routes) {
                    Set-Route $r
                    $env:PIP_INDEX_URL = $pi
                    & $script:VenvPy -m pip install llama-cpp-python 2>&1 | ForEach-Object { Write-Host "    $_" }
                    if ($LASTEXITCODE -eq 0) { $fb = $true; break }
                }
                if ($fb) { break }
            }
            Set-Route 'system'
            if (-not $fb) { Die 'neither llama-server binary nor llama-cpp-python could be installed' }
            Warn 'translation LLM now on CPU (10-30 tok/s). A GPU llama-server.exe can be dropped into backend\bin\ later.'
        }
    }

    # ============================================================ phase 6: models
    Phase '6/7 models (CTBD / PaddleOCR-VL / Hy-MT2)'
    $md = Join-Path $backend 'models'
    function Test-Models {
        $ok = (Test-Path "$md\CTBD\detector.onnx") -and ((Get-Item "$md\CTBD\detector.onnx" -ErrorAction SilentlyContinue).Length -gt 150MB)
        $ok = $ok -and (Test-Path "$md\PaddleOCR-VL-For-Manga\model.safetensors") -and ((Get-Item "$md\PaddleOCR-VL-For-Manga\model.safetensors" -ErrorAction SilentlyContinue).Length -gt 1.5GB)
        $ok = $ok -and (Test-Path "$md\hymt\Hy-MT2-1.8B-Q4_K_M.gguf") -and ((Get-Item "$md\hymt\Hy-MT2-1.8B-Q4_K_M.gguf" -ErrorAction SilentlyContinue).Length -gt 1GB)
        return $ok
    }
    if (Test-Models) { Ok 'models already downloaded (3/3 verified by size)' }
    else {
        # endpoint x route probing: validate HF business header (x-repo-commit), not bare 200.
        # priority: mirror direct -> mirror via system proxy -> official via system proxy -> official direct
        $cands = @(
            @{ ep = $script:HfEndpoints[0]; r = 'direct'  },
            @{ ep = $script:HfEndpoints[0]; r = 'system' },
            @{ ep = $script:HfEndpoints[1]; r = 'system' },
            @{ ep = $script:HfEndpoints[1]; r = 'direct'  }
        )
        $probeUrl = '/ogkalu/comic-text-and-bubble-detector/resolve/main/detector.onnx'
        $chosen = $null
        foreach ($c in $cands) {
            $a = Get-CurlRouteArgs $c.r
            $h = & curl.exe -sIL -m 20 @a ($c.ep + $probeUrl) 2>$null | Out-String
            if ($h -match '(?i)x-repo-commit') {
                Write-Host "  probe OK: $($c.ep) [$($c.r)]"
                $chosen = $c; break
            } else {
                Write-Host "  probe fail: $($c.ep) [$($c.r)]"
            }
        }
        if (-not $chosen) { Die 'no HF endpoint reachable (mirror direct / proxied / official all failed)' }
        $done = $false
        foreach ($c in (@($chosen) + @($cands | Where-Object { $_ -ne $chosen }))) {   # auto-failover on download error
            if ($done) { break }
            if ($c -ne $chosen) { Warn "retrying with $($c.ep) [$($c.r)]" }
            Set-Route $c.r
            $env:HF_ENDPOINT = $c.ep
            Write-Host "  fetch_models via $($c.ep) [$($c.r)] (~3.1GB, resumable)"
            Push-Location $backend
            & $script:VenvPy fetch_models.py 2>&1 | ForEach-Object { Write-Host "    $_" }
            $code = $LASTEXITCODE
            Pop-Location
            if ($code -eq 0 -and (Test-Models)) { $done = $true }
        }
        Set-Route 'system'
        if (-not $done) { Die 'model download failed on all endpoint/route combos. re-run to resume.' }
        Ok 'models downloaded (3/3 verified by size)'
    }

    # ============================================================ phase 7: start
    Phase '7/7 start services'
    if ($Firewall) {
        $fw = 'netsh advfirewall firewall add rule name="ComicShelf backend 8787" dir=in action=allow protocol=TCP localport=8787'
        $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        if ($isAdmin) { cmd /c $fw | Out-Null; Ok 'firewall inbound rule added (8787/tcp)' }
        else {
            try { Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -Command $fw" -Wait; Ok 'firewall rule added (elevated)' }
            catch { Warn 'UAC declined. manual one-liner (admin cmd):'; Write-Host "    $fw" }
        }
    } else {
        Warn 'firewall: if the phone cannot connect later, run this in an ADMIN cmd:'
        Write-Host '    netsh advfirewall firewall add rule name="ComicShelf backend 8787" dir=in action=allow protocol=TCP localport=8787' -ForegroundColor DarkGray
    }
    # convenience launchers for daily use after deploy
    foreach ($p in @(@{ n = 'start-backend.bat';  a = 'start'  },
                     @{ n = 'stop-backend.bat';   a = 'stop'   },
                     @{ n = 'status-backend.bat'; a = 'status' },
                     @{ n = 'logs-backend.bat';   a = 'logs'   })) {
        $f = Join-Path $backend $p.n
        if (-not (Test-Path $f)) {
            $c = "@echo off`r`nchcp 65001 >nul`r`ncd /d %~dp0`r`nvenv\Scripts\python.exe deploy.py $($p.a)`r`npause`r`n"
            [IO.File]::WriteAllText($f, $c)
        }
    }
    Ok "convenience launchers written into $backend (start/stop/status/logs-backend.bat)"

    if ($NoStart) { Warn '-NoStart: service start skipped' }
    else {
        Push-Location $backend
        & $script:VenvPy deploy.py start 2>&1 | ForEach-Object { Write-Host "  $_" }
        $code = $LASTEXITCODE
        Pop-Location
        if ($code -ne 0) { Die "deploy.py start failed (code $code). see $backend\server.log / llama_server.log" }
        # poll health (OCR loads in background ~10s)
        $health = $null
        foreach ($i in 1..30) {
            Start-Sleep -Seconds 2
            $h = & curl.exe -s -m 3 http://127.0.0.1:8787/health 2>$null
            if ("$h" -match '"ok" *: *true') { $health = "$h"; break }
        }
        Write-Host ''
        Write-Host '  ================== DEPLOY RESULT ==================' -ForegroundColor Cyan
        if ($health) {
            Ok "health: $health"
            $dev = 'cpu'; if ($health -match '"device" *: *"([^"]+)"') { $dev = $Matches[1] }
            $ocrS = 'not yet'; if ($health -match '"ready" *: *true') { $ocrS = 'yes' }
            $llmS = 'no';     if ($health -match '"llm" *: *\{[^}]*"ok" *: *true') { $llmS = 'yes' }
            Write-Host ("   OCR device : {0}" -f $dev)
            Write-Host ("   OCR ready  : {0}    LLM ready: {1}" -f $ocrS, $llmS)
            $phys = @()
            try {
                $phys = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
                    Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.InterfaceAlias -notmatch 'Loopback|vEthernet|WSL|VMware|Virtual|Docker|Hyper' } |
                    Select-Object -ExpandProperty IPAddress -Unique
            } catch {}
            if (-not $phys) { $phys = @('<LAN-IP-from-deploy-output-above>') }
            Write-Host ''
            Write-Host '   PHONE APP: set the backend address to one of:' -ForegroundColor Yellow
            foreach ($ip in $phys) { Write-Host ("       http://{0}:8787" -f $ip) -ForegroundColor Yellow }
            Write-Host '   USB alternative: adb reverse tcp:8787 tcp:8787  +  http://127.0.0.1:8787'
        } else {
            Warn 'service not healthy yet; check via backend\logs-backend.bat'
        }
        Write-Host '  ==================================================='
    }
    $el = ((Get-Date) - $T0).ToString('mm\:ss')
    Ok "total time: $el (re-run any time; finished steps are skipped)"
} finally {
    Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
}
