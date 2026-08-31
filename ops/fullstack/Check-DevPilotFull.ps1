[CmdletBinding()]
param(
    [int]$TimeoutSeconds = 10
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Test-HttpEndpoint {
    param([string]$Name, [string]$Uri)

    try {
        $response = Invoke-WebRequest -Uri $Uri -TimeoutSec $TimeoutSeconds -UseBasicParsing
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
            Write-Host "PASS  $Name  $Uri"
            return $true
        }
    } catch {
        Write-Host "FAIL  $Name  $Uri  $($_.Exception.Message)"
        return $false
    }
    Write-Host "FAIL  $Name  $Uri  unexpected status"
    return $false
}

Push-Location $repositoryRoot
try {
    docker compose --profile full ps
    $checks = @(
        (Test-HttpEndpoint -Name "Web" -Uri "http://localhost:5173/healthz"),
        (Test-HttpEndpoint -Name "Gateway" -Uri "http://localhost:8081/actuator/health"),
        (Test-HttpEndpoint -Name "Core readiness" -Uri "http://localhost:8080/actuator/health/readiness"),
        (Test-HttpEndpoint -Name "Mailpit" -Uri "http://localhost:8025/readyz"),
        (Test-HttpEndpoint -Name "Nacos" -Uri "http://localhost:8848/nacos/v1/ns/operator/metrics")
    )
    if ($checks -contains $false) { throw "One or more readiness checks failed." }
    Write-Host "Readiness PASS. Continue with real registration, GitHub API, DeepSeek Tool/SSE, and Outbox E2E."
} finally {
    Pop-Location
}

