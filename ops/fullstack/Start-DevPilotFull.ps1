[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $repositoryRoot
try {
    docker compose --profile full config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose configuration is invalid." }

    docker compose --profile full up -d --build
    if ($LASTEXITCODE -ne 0) { throw "Full Stack startup failed." }

    docker compose --profile full ps
    Write-Host "Web:      http://localhost:5173"
    Write-Host "Mailpit:  http://localhost:8025"
    Write-Host "Nacos:    http://localhost:8082"
    Write-Host "Run Check-DevPilotFull.ps1 for readiness. Healthy containers are not application E2E evidence."
} finally {
    Pop-Location
}

