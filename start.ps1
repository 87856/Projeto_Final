# Arena 3D RAG - Agent Launcher
# Run with: .\start.ps1

$Host.UI.RawUI.WindowTitle = "Arena Agent - Launcher"
Write-Host "============================================" -ForegroundColor Green
Write-Host "  ARENA 3D RAG - Agent Launcher" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""

# Check Java
try {
    $javaVersion = java -version 2>&1
    Write-Host "[OK] Java encontrado." -ForegroundColor Green
} catch {
    Write-Host "[ERRO] Java nao encontrado. Instala Java 11+." -ForegroundColor Red
    Read-Host "Prima Enter para sair"
    exit 1
}

# Check Maven
try {
    $mvnVersion = mvn -version 2>&1
    Write-Host "[OK] Maven encontrado." -ForegroundColor Green
} catch {
    Write-Host "[ERRO] Maven nao encontrado. Instala Maven e adiciona ao PATH." -ForegroundColor Red
    Read-Host "Prima Enter para sair"
    exit 1
}

# Check Ollama
try {
    $ollamaResponse = Invoke-WebRequest -Uri "http://localhost:11434" -TimeoutSec 3 -ErrorAction Stop
    Write-Host "[OK] Ollama esta a correr." -ForegroundColor Green
} catch {
    Write-Host "[AVISO] Ollama nao esta a correr. A tentar iniciar..." -ForegroundColor Yellow
    Start-Process "ollama" -ArgumentList "serve" -WindowStyle Hidden
    Start-Sleep -Seconds 3

    try {
        Invoke-WebRequest -Uri "http://localhost:11434" -TimeoutSec 3 -ErrorAction Stop | Out-Null
        Write-Host "[OK] Ollama iniciado com sucesso." -ForegroundColor Green
    } catch {
        Write-Host "[AVISO] Ollama pode nao ter iniciado. O agente correra em modo heuristico." -ForegroundColor Yellow
    }
}

# Check models
Write-Host ""
Write-Host "[INFO] A verificar modelos Ollama..." -ForegroundColor Cyan
$models = ollama list 2>&1
if ($models -notmatch "nomic-embed-text") {
    Write-Host "[AVISO] Modelo 'nomic-embed-text' nao encontrado. A descarregar..." -ForegroundColor Yellow
    ollama pull nomic-embed-text
}
if ($models -notmatch "qwen2.5-coder") {
    Write-Host "[AVISO] Modelo 'qwen2.5-coder:0.5b-instruct' nao encontrado. A descarregar..." -ForegroundColor Yellow
    ollama pull qwen2.5-coder:0.5b-instruct-q4_K_M
}
Write-Host "[OK] Modelos verificados." -ForegroundColor Green

# Build
Write-Host ""
Write-Host "[INFO] A compilar o projeto com Maven..." -ForegroundColor Cyan
Set-Location $PSScriptRoot
mvn clean package -q

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERRO] Falha na compilacao. Verifica os erros acima." -ForegroundColor Red
    Read-Host "Prima Enter para sair"
    exit 1
}
Write-Host "[OK] Compilacao bem-sucedida." -ForegroundColor Green

# Run
Write-Host ""
Write-Host "[INFO] A iniciar o Agente Explorador..." -ForegroundColor Cyan
Write-Host ""
java -jar target\agente-explorador-1.0-SNAPSHOT.jar

Read-Host "Prima Enter para sair"