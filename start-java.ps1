# start-java.ps1 — Java DocIO backend + React frontend
# Usage:  .\start-java.ps1
#         .\start-java.ps1 -Port 8080
param(
    [int]$Port = 8080
)

$ErrorActionPreference = "SilentlyContinue"
$Root      = $PSScriptRoot
$Frontend  = Join-Path $Root "frontend"
$DocServer = Join-Path $Root "doc-agent-server"
$EnvFile   = Join-Path $Root ".env"

# ── Load repo-root .env ───────────────────────────────────────────────────────

function Import-DotEnv([string]$path) {
    if (-not (Test-Path $path)) {
        Write-Host "  No .env at $path — copy .env.example to .env and add SYNCFUSION_LICENSE_KEY" -ForegroundColor Yellow
        return
    }
    Get-Content $path | ForEach-Object {
        $line = $_.Trim()
        if ($line -match '^\s*#' -or $line -eq '') { return }
        $eq = $line.IndexOf('=')
        if ($eq -lt 1) { return }
        $name  = $line.Substring(0, $eq).Trim()
        $value = $line.Substring($eq + 1).Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    Write-Host "  Loaded $path" -ForegroundColor Gray
}

function Find-FreePort([int]$start) {
    for ($p = $start; $p -lt $start + 20; $p++) {
        $inUse = netstat -ano 2>$null | Select-String ":$p\s" | Where-Object { $_ -match "LISTENING" }
        if (-not $inUse) { return $p }
    }
    return $start
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

function Wait-Backend([int]$port, [int]$timeoutSec = 60) {
    $url = "http://localhost:$port/api/health"
    for ($i = 0; $i -lt $timeoutSec; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { return $true }
        } catch {}
    }
    return $false
}

function Write-FrontendEnvLocal([int]$backendPort) {
    $syncKey = $env:SYNCFUSION_LICENSE_KEY
    if (-not $syncKey) { $syncKey = $env:VITE_SYNCFUSION_LICENSE }
    $viteKey = $env:VITE_SYNCFUSION_LICENSE
    if (-not $viteKey) { $viteKey = $syncKey }

    $lines = @(
        "VITE_API_BASE=http://localhost:$backendPort"
    )
    if ($viteKey) {
        $lines += "VITE_SYNCFUSION_LICENSE=$viteKey"
    }
    $dest = Join-Path $Frontend ".env.local"
    Set-Content -Path $dest -Value ($lines -join "`n") -Encoding utf8
    Write-Host "  Wrote frontend/.env.local (API + Syncfusion license)" -ForegroundColor Gray
}

# ── Main ──────────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "=== docGenerator — Java DocIO launcher ===" -ForegroundColor Cyan
Write-Host ""

Write-Host "[1/5] Loading environment..." -ForegroundColor White
Import-DotEnv $EnvFile

# One key for Java + React when Document SDK + DOCX Editor SDK are on the license
if ($env:SYNCFUSION_LICENSE_KEY -and -not $env:VITE_SYNCFUSION_LICENSE) {
    $env:VITE_SYNCFUSION_LICENSE = $env:SYNCFUSION_LICENSE_KEY
}

Write-Host "[2/5] Stopping old Java / Node processes..." -ForegroundColor White
Get-Process -Name "node" -ErrorAction SilentlyContinue | Stop-Process -Force
# Free the target port (avoid killing all JVMs — only port squatters)
Kill-PortOwner $Port

Write-Host "[3/5] Finding backend port..." -ForegroundColor White
$Port = Find-FreePort $Port
Kill-PortOwner $Port
Write-Host "  Using port $Port"
$env:SERVER_PORT = "$Port"
$env:SYNCFUSION_LICENSE_KEY = $env:SYNCFUSION_LICENSE_KEY

Write-FrontendEnvLocal $Port

Write-Host "[4/5] Starting Java backend (Maven)..." -ForegroundColor White
Write-Host "  First run may download dependencies — allow up to 60 s..." -ForegroundColor Gray

$backend = Start-Process `
    -FilePath "mvn" `
    -ArgumentList "-q","spring-boot:run" `
    -WorkingDirectory $DocServer `
    -PassThru -NoNewWindow

if (-not (Wait-Backend $Port 60)) {
    Write-Host ""
    Write-Host "ERROR: Java backend did not become healthy within 60 s." -ForegroundColor Red
    Write-Host "       Run manually: cd doc-agent-server; mvn spring-boot:run" -ForegroundColor Red
    Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
    exit 1
}
Write-Host "  Backend ready → http://localhost:$Port" -ForegroundColor Green

Write-Host "[5/5] Starting frontend..." -ForegroundColor White
$frontend = Start-Process `
    -FilePath "cmd.exe" `
    -ArgumentList "/c","npm run dev" `
    -WorkingDirectory $Frontend `
    -PassThru -NoNewWindow

Start-Sleep -Seconds 3
Write-Host "  Frontend ready → http://localhost:5173" -ForegroundColor Green

Write-Host ""
Write-Host "┌──────────────────────────────────────────┐" -ForegroundColor Cyan
Write-Host "│  Java API  http://localhost:$Port            │" -ForegroundColor Cyan
Write-Host "│  Frontend  http://localhost:5173           │" -ForegroundColor Cyan
Write-Host "│  Docs      doc-agent-server/docs/         │" -ForegroundColor Cyan
Write-Host "│  Secrets   .env (repo root, gitignored)   │" -ForegroundColor Cyan
Write-Host "└──────────────────────────────────────────┘" -ForegroundColor Cyan
Write-Host ""
Write-Host "Press Ctrl+C to stop both servers." -ForegroundColor Gray
Write-Host ""

try {
    Wait-Process -Id $backend.Id
} finally {
    Write-Host "`nShutting down..." -ForegroundColor Yellow
    Stop-Process -Id $backend.Id  -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $frontend.Id -Force -ErrorAction SilentlyContinue
}
