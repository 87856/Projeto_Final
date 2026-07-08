# multi.ps1 — launch several bots into the same arena room for bot-vs-bot testing.
#
# Usage:
#   .\multi.ps1 -Room SALA123 [-Modes "berserker,coward,explorer"] [-NoGui] [-NoBuild]
#
# Ctrl+C stops all bots.

param(
    [Parameter(Mandatory=$true)][string]$Room,
    [string]$Modes = "opportunist,berserker,coward",
    [switch]$NoGui,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
$BotNames = @("Alpha","Beta","Gamma","Delta","Epsilon","Zeta","Eta","Theta")
$Jar = "target\agente-explorador-1.0-SNAPSHOT.jar"

Set-Location $PSScriptRoot

$env:OLLAMA_MAX_LOADED_MODELS = "2"

if (-not $NoBuild) {
    Write-Host "[multi] Building..." -ForegroundColor Cyan
    & mvn clean package -q
    if ($LASTEXITCODE -ne 0) { Write-Host "[multi] Build failed." -ForegroundColor Red; exit 1 }
    Write-Host "[multi] Build OK." -ForegroundColor Green
}

if (-not (Test-Path $Jar)) {
    Write-Host "[multi] Jar not found: $Jar — run without -NoBuild first." -ForegroundColor Red
    exit 1
}

$ModeList = $Modes -split ","
Write-Host "[multi] Room: $Room | Bots: $($ModeList.Count) | GUI: $(if ($NoGui) {'OFF'} else {'ON'})" -ForegroundColor Green
Write-Host ""

$Jobs = @()
for ($i = 0; $i -lt $ModeList.Count; $i++) {
    $Name = if ($i -lt $BotNames.Count) { $BotNames[$i] } else { "Bot$i" }
    $Mode = $ModeList[$i].Trim()

    $jvmArgs = @(
        "-Dbot.name=$Name",
        "-Dbot.room=$Room",
        "-Dbot.mode=$Mode"
    )
    if ($NoGui) { $jvmArgs += "-Dbot.noGui=true" }
    $jvmArgs += @("-jar", $Jar)

    Write-Host "[multi] Starting $Name ($Mode)..." -ForegroundColor Cyan
    $job = Start-Process -FilePath "java" -ArgumentList $jvmArgs -PassThru -NoNewWindow
    $Jobs += $job
    Start-Sleep -Milliseconds 300
}

Write-Host ""
Write-Host "[multi] $($Jobs.Count) bots running. Press Ctrl+C to stop all." -ForegroundColor Yellow

try {
    foreach ($job in $Jobs) { $job.WaitForExit() }
} finally {
    Write-Host "[multi] Stopping all bots..." -ForegroundColor Yellow
    foreach ($job in $Jobs) {
        if (-not $job.HasExited) { $job.Kill() }
    }
}
