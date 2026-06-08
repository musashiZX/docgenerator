# start.ps1 — single launcher for docGenerator (backend + frontend)
# Usage:  .\start.ps1
#         .\start.ps1 -Port 8002    (override default port)
param(
    [int]$Port = 8001
)

$ErrorActionPreference = "SilentlyContinue"
$Root     = $PSScriptRoot
$Frontend = Join-Path $Root "frontend"

# ── helpers ───────────────────────────────────────────────────────────────────

function Find-FreePort([int]$start) {
    for ($p = $start; $p -lt $start + 20; $p++) {
        $inUse = netstat -ano 2>$null | Select-String ":$p\s" | Where-Object { $_ -match "LISTENING" }
        if (-not $inUse) { return $p }
    }
    return $start   # fallback — try anyway
}

function Kill-PortOwner([int]$port) {
    $lines = netstat -ano 2>$null | Select-String ":$port\s" | Where-Object { $_ -match "LISTENING" }
    foreach ($line in $lines) {
        $parts = ($line -replace '\s+', ' ').Trim() -split ' '
        $ownerPid = $parts[-1]
        if ($ownerPid -match '^\d+$' -and [int]$ownerPid -gt 0) {
            Write-Host "  Killing PID $ownerPid (was holding port $port)" -ForegroundColor Yellow
            taskkill /F /PID $ownerPid 2>&1 | Out-Null
        }
    }
    Start-Sleep -Milliseconds 800
}

function Wait-Backend([int]$port, [int]$timeoutSec = 20) {
    $url = "http://localhost:$port/api/health"
    for ($i = 0; $i -lt $timeoutSec; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2
            if ($r.StatusCode -eq 200) { return $true }
        } catch {}
    }
    return $false
}

# ── 1. Stop existing processes ────────────────────────────────────────────────

Write-Host ""
Write-Host "=== docGenerator launcher ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "[1/4] Stopping old Python / Node processes..." -ForegroundColor White

Get-Process -Name "python","python3","node" | Stop-Process -Force
Start-Sleep -Milliseconds 500

# ── 2. Claim a free port ──────────────────────────────────────────────────────

Write-Host "[2/4] Finding a free port starting at $Port..." -ForegroundColor White
$Port = Find-FreePort $Port
Kill-PortOwner $Port
Write-Host "  Using port $Port"

# Write the port into frontend/.env.local so Vite picks it up at startup.
$envLocal = "VITE_API_BASE=http://localhost:$Port"
Set-Content -Path (Join-Path $Frontend ".env.local") -Value $envLocal
Write-Host "  Wrote frontend/.env.local: $envLocal"

# ── 3. Start backend ──────────────────────────────────────────────────────────

Write-Host "[3/4] Starting backend..." -ForegroundColor White
$venvPython = Join-Path $Root ".venv\Scripts\python.exe"

$backend = Start-Process `
    -FilePath $venvPython `
    -ArgumentList "-m","uvicorn","api:app","--port","$Port","--log-level","warning" `
    -WorkingDirectory $Root `
    -PassThru -NoNewWindow

if (-not (Wait-Backend $Port 20)) {
    Write-Host ""
    Write-Host "ERROR: Backend did not become healthy within 20 s." -ForegroundColor Red
    Write-Host "       Check logs/app.log for details."
    Stop-Process -Id $backend.Id
    exit 1
}
Write-Host "  Backend ready → http://localhost:$Port" -ForegroundColor Green

# ── 4. Start frontend ─────────────────────────────────────────────────────────

Write-Host "[4/4] Starting frontend..." -ForegroundColor White
$frontend = Start-Process `
    -FilePath "cmd.exe" `
    -ArgumentList "/c","npm run dev" `
    -WorkingDirectory $Frontend `
    -PassThru -NoNewWindow

Start-Sleep -Seconds 3
Write-Host "  Frontend ready → http://localhost:5173" -ForegroundColor Green

# ── Summary ───────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "┌──────────────────────────────────────────┐" -ForegroundColor Cyan
Write-Host "│  Backend   http://localhost:$Port            │" -ForegroundColor Cyan
Write-Host "│  Frontend  http://localhost:5173           │" -ForegroundColor Cyan
Write-Host "│  Logs      logs/app.log                   │" -ForegroundColor Cyan
Write-Host "└──────────────────────────────────────────┘" -ForegroundColor Cyan
Write-Host ""
Write-Host "Press Ctrl+C to stop both servers." -ForegroundColor Gray
Write-Host ""

# Keep the script alive; kill both children on exit.
try {
    [Console]::TreatControlCAsInput = $false
    Wait-Process -Id $backend.Id
} finally {
    Write-Host "`nShutting down..." -ForegroundColor Yellow
    Stop-Process -Id $backend.Id  -Force
    Stop-Process -Id $frontend.Id -Force
}
