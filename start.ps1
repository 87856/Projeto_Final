# Arena 3D RAG - Agent Launcher (Windows)
# Skill-equivalent of start.sh for Linux devs.
# Run with: .\start.ps1
#
# Both launchers must stay in sync — they pull the same Ollama models and
# keep the resident model count capped so fast+planner fit in 6 GB VRAM.

$Host.UI.RawUI.WindowTitle = "Arena Agent - Launcher"
Write-Host "============================================" -ForegroundColor Green
Write-Host "  ARENA 3D RAG - Agent Launcher (Windows)" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""

# Keep fast + planner resident together (6 GB VRAM is enough for 1b + 3b Q4).
# Mirrors `export OLLAMA_MAX_LOADED_MODELS=2` in start.sh.
$env:OLLAMA_MAX_LOADED_MODELS = "2"

# Check Java
try {
    $javaVersion = java -version 2>&1
    Write-Host "[OK] Java encontrado." -ForegroundColor Green
} catch {
    Write-Host "[ERRO] Java nao encontrado. Instala Java 11+." -ForegroundColor Red
    Read-Host "Prima Enter para sair"
    exit 1
}

# Find Maven (PATH, common install locations, or IntelliJ bundled)
$mvnCmd = $null

# 1. Try PATH first
if (Get-Command "mvn" -ErrorAction SilentlyContinue) {
    $mvnCmd = "mvn"
}

# 2. Try common Windows install locations
if (-not $mvnCmd) {
    $commonPaths = @(
        "C:\Program Files\Maven\bin\mvn.cmd",
        "C:\Program Files\Apache\maven\bin\mvn.cmd",
        "$env:USERPROFILE\scoop\apps\maven\current\bin\mvn.cmd",
        "$env:USERPROFILE\AppData\Local\Programs\Maven\bin\mvn.cmd"
    )
    foreach ($path in $commonPaths) {
        if (Test-Path $path) {
            $mvnCmd = $path
            break
        }
    }
}

# 3. Try IntelliJ bundled Maven
if (-not $mvnCmd) {
    $ideaPaths = Get-ChildItem "$env:USERPROFILE\.intellijidea*", "$env:APPDATA\JetBrains\IntelliJIdea*" -ErrorAction SilentlyContinue
    foreach ($idea in $ideaPaths) {
        $bundled = Get-ChildItem "$idea\plugins\maven\lib\maven3\bin\mvn.cmd" -ErrorAction SilentlyContinue
        if ($bundled) {
            $mvnCmd = $bundled.FullName
            break
        }
    }
}

# 4. Search MAVEN_HOME or M2_HOME env vars
if (-not $mvnCmd) {
    foreach ($envVar in @($env:MAVEN_HOME, $env:M2_HOME)) {
        if ($envVar -and (Test-Path "$envVar\bin\mvn.cmd")) {
            $mvnCmd = "$envVar\bin\mvn.cmd"
            break
        }
    }
}

if (-not $mvnCmd) {
    Write-Host "[ERRO] Maven nao encontrado. Opcoes:" -ForegroundColor Red
    Write-Host "  1. Instala Maven: https://maven.apache.org/download.cgi" -ForegroundColor Yellow
    Write-Host "  2. Adiciona ao PATH: [pasta maven]\bin" -ForegroundColor Yellow
    Write-Host "  3. Ou usa o IntelliJ para compilar e corre: java -jar target\agente-explorador-1.0-SNAPSHOT.jar" -ForegroundColor Yellow
    Read-Host "Prima Enter para sair"
    exit 1
}

Write-Host "[OK] Maven encontrado: $mvnCmd" -ForegroundColor Green

# Check Ollama
try {
    $ollamaResponse = Invoke-WebRequest -Uri "http://localhost:11434" -TimeoutSec 3 -ErrorAction Stop
    Write-Host "[OK] Ollama esta a correr." -ForegroundColor Green
} catch {
    Write-Host "[AVISO] Ollama nao esta a correr. A tentar iniciar..." -ForegroundColor Yellow
   Start-Process "ollama" -ArgumentList "serve" -WindowStyle Hidden
    Write-Host "[INFO] A aguardar Ollama iniciar..." -ForegroundColor Cyan
    Start-Sleep -Seconds 5

    try {
        Invoke-WebRequest -Uri "http://localhost:11434" -TimeoutSec 3 -ErrorAction Stop | Out-Null
        Write-Host "[OK] Ollama iniciado com sucesso." -ForegroundColor Green
    } catch {
        Write-Host "[AVISO] Ollama pode nao ter iniciado. O agente correra em modo heuristico." -ForegroundColor Yellow
    }
}

# Per-tag model checks (exact match). `ollama list` rows look like
#   nomic-embed-text:latest    0a109f…   274 MB   …
#   llama3.2:1b                bafb0…    1.3 GB   …
# We extract the FIRST whitespace token of each line as the installed tag,
# then `-contains` against the explicit tag list. No regex wildcards, no
# tag-stripping (stripping would mistakenly accept `llama3.2:latest` and skip
# pulling `llama3.2:1b`, which is the wrong default — `llama3.2` is 8B).
# Mirrors start.sh's per-model loop.
Write-Host ""
Write-Host "[INFO] A verificar modelos Ollama..." -ForegroundColor Cyan
$modelsRaw = ollama list 2>&1
$modelTags = @()
foreach ($line in ($modelsRaw -split "`r?`n")) {
    $trim = $line.Trim()
    if ($trim -eq "") { continue }
    $tok = ($trim -split "\s+")[0]
    if ($tok -match "^[A-Za-z0-9][A-Za-z0-9_.:\-/]*$") { $modelTags += $tok }
}
# Explicit tags — never pull the bare `llama3.2` family (8B), only 1b and 3b.
# Use the full tag Ollama reports in `ollama list` so the comparison is exact.
$needed = @("nomic-embed-text:latest", "qwen2.5:1.5b", "qwen2.5:7b")
foreach ($m in $needed) {
    if (-not ($modelTags -contains $m)) {
        Write-Host "[AVISO] Modelo '$m' em falta. A descarregar..." -ForegroundColor Yellow
        & ollama pull $m
    }
}
Write-Host "[OK] Modelos verificados (OLLAMA_MAX_LOADED_MODELS=$env:OLLAMA_MAX_LOADED_MODELS)." -ForegroundColor Green

# Build
Write-Host ""
Write-Host "[INFO] A compilar o projeto com Maven..." -ForegroundColor Cyan
Set-Location $PSScriptRoot
& $mvnCmd clean package -q

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Falha na compilacao. Verifica os erros acima." -ForegroundColor Red
    Read-Host "Prima Enter para sair"
    exit 1
}
Write-Host "[OK] Compilacao bem-sucedida." -ForegroundColor Green

# ---- args -------------------------------------------------------------------
$BotMode       = ""
$AntiBacktrack = $false
foreach ($arg in $args) {
    if ($arg -eq "--no-backtrack") { $AntiBacktrack = $true }
    elseif ($arg -like "--mode") { <# handled below — positional #> }
}
# Simple positional parse: --mode <value>
for ($i = 0; $i -lt $args.Count; $i++) {
    if ($args[$i] -eq "--mode" -and ($i + 1) -lt $args.Count) {
        $BotMode = $args[$i + 1]
        $i++
    }
}

# Run
Write-Host ""
Write-Host "[INFO] A iniciar o Agente Explorador..." -ForegroundColor Cyan
if ($BotMode)       { Write-Host "[INFO] Modo: $BotMode" -ForegroundColor Cyan }
if ($AntiBacktrack) { Write-Host "[INFO] Anti-backtrack: ON" -ForegroundColor Cyan }
Write-Host ""

$jvmArgs = @()
if ($BotMode)       { $jvmArgs += "-Dbot.mode=$BotMode" }
if ($AntiBacktrack) { $jvmArgs += "-Dbot.antiBacktrack=true" }

& java @jvmArgs -jar target\agente-explorador-1.0-SNAPSHOT.jar

Read-Host "Prima Enter para sair"
