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

# Check models
Write-Host ""
Write-Host "[INFO] A verificar modelos Ollama..." -ForegroundColor Cyan
$models = ollama list 2>&1
if ($models -notmatch "nomic-embed-text") {
    Write-Host "[AVISO] Modelo 'nomic-embed-text' nao encontrado. A descarregar..." -ForegroundColor Yellow
    ollama pull nomic-embed-text
}
if ($models -notmatch "llama3.2") {
    Write-Host "[AVISO] Modelo 'llama3.2:3b' nao encontrado. A descarregar..." -ForegroundColor Yellow
    ollama pull llama3.2:3b
}
Write-Host "[OK] Modelos verificados." -ForegroundColor Green

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

# Run
Write-Host ""
Write-Host "[INFO] A iniciar o Agente Explorador..." -ForegroundColor Cyan
Write-Host ""
java -jar target\agente-explorador-1.0-SNAPSHOT.jar

Read-Host "Prima Enter para sair"