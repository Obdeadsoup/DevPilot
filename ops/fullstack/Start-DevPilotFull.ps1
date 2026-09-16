[CmdletBinding()]
param(
    [switch]$NoBuild,
    [switch]$RefreshBaseImages,
    [ValidateRange(1, 10)]
    [int]$PullRetries = 4,
    [ValidateRange(1, 5)]
    [int]$BuildRetries = 2
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$mode = if ($env:AGENT_MODEL_MODE) { $env:AGENT_MODEL_MODE } else { "deepseek" }
& (Join-Path $PSScriptRoot "Test-DevPilotDemoPreflight.ps1") -Mode $mode
if ($LASTEXITCODE -ne 0) { throw "Full Stack preflight failed." }

function Get-NormalizedProxyUri {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "<no value>") {
        return $null
    }
    if ($Value -match "^[a-z][a-z0-9+.-]*://") {
        return $Value
    }
    return "http://$Value"
}

function Initialize-DevPilotBuildProxy {
    if (-not $env:DEVPILOT_BUILD_HTTPS_PROXY) {
        $dockerHttpsProxy = docker info --format '{{.HTTPSProxy}}'
        if ($LASTEXITCODE -ne 0) { throw "Cannot read the Docker proxy configuration." }
        $env:DEVPILOT_BUILD_HTTPS_PROXY = Get-NormalizedProxyUri $dockerHttpsProxy
    }
    if (-not $env:DEVPILOT_BUILD_HTTP_PROXY) {
        $dockerHttpProxy = docker info --format '{{.HTTPProxy}}'
        if ($LASTEXITCODE -ne 0) { throw "Cannot read the Docker proxy configuration." }
        $env:DEVPILOT_BUILD_HTTP_PROXY = Get-NormalizedProxyUri $dockerHttpProxy
    }

    if (-not $env:DEVPILOT_BUILD_NO_PROXY) {
        $env:DEVPILOT_BUILD_NO_PROXY = "localhost,127.0.0.1,::1"
    }

    if ($env:DEVPILOT_BUILD_HTTPS_PROXY) {
        try {
            $proxyUri = [Uri]$env:DEVPILOT_BUILD_HTTPS_PROXY
            if (-not $env:DEVPILOT_MAVEN_PROXY_HOST) {
                $env:DEVPILOT_MAVEN_PROXY_HOST = $proxyUri.Host
            }
            if (-not $env:DEVPILOT_MAVEN_PROXY_PORT) {
                $env:DEVPILOT_MAVEN_PROXY_PORT = [string]$proxyUri.Port
            }
        } catch {
            throw "Docker HTTPS proxy is not a valid URI: $($env:DEVPILOT_BUILD_HTTPS_PROXY)"
        }
        Write-Host "PASS  Docker build proxy  inherited from Docker Desktop"
    } else {
        Write-Warning "Docker Desktop does not report a proxy. Container build steps will use direct DNS/networking."
    }
}

function Ensure-DevPilotBaseImages {
    $images = @(
        "python:3.12-slim",
        "maven:3.9.11-eclipse-temurin-21",
        "eclipse-temurin:21-jre-noble",
        "node:24-alpine",
        "nginx:1.29-alpine",
        "mysql:8.4",
        "redis:7.4-alpine",
        "nacos/nacos-server:v3.0.3",
        "curlimages/curl:8.16.0",
        "axllent/mailpit:v1.30.4"
    )

    foreach ($image in $images) {
        docker image inspect $image *> $null
        $isPresent = $LASTEXITCODE -eq 0
        if ($isPresent -and -not $RefreshBaseImages) {
            Write-Host "PASS  Base image  $image (local)"
            continue
        }

        $pulled = $false
        for ($attempt = 1; $attempt -le $PullRetries; $attempt++) {
            Write-Host "PULL  $image  attempt $attempt/$PullRetries"
            docker pull $image
            if ($LASTEXITCODE -eq 0) {
                $pulled = $true
                break
            }
            if ($attempt -lt $PullRetries) {
                Start-Sleep -Seconds ([Math]::Min(2 * $attempt, 10))
            }
        }
        if (-not $pulled) {
            throw "[NETWORK] Could not pull $image after $PullRetries attempts. Run Test-DevPilotDockerNetwork.ps1."
        }
    }
}

Push-Location $repositoryRoot
try {
    docker compose --profile full config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose configuration is invalid." }

    if (-not $NoBuild) {
        Initialize-DevPilotBuildProxy
        Ensure-DevPilotBaseImages

        # Build one service at a time. This avoids bursty Docker Hub/Maven/npm/pip
        # traffic on local proxy connections and leaves precise service errors.
        foreach ($service in @("agent-service", "devpilot-core", "devpilot-gateway", "devpilot-web")) {
            $built = $false
            for ($attempt = 1; $attempt -le $BuildRetries; $attempt++) {
                Write-Host "BUILD $service  attempt $attempt/$BuildRetries"
                docker compose --profile full build $service
                if ($LASTEXITCODE -eq 0) {
                    $built = $true
                    break
                }
                if ($attempt -lt $BuildRetries) {
                    Write-Warning "$service build failed; retrying with the existing BuildKit download cache."
                    Start-Sleep -Seconds (3 * $attempt)
                }
            }
            if (-not $built) {
                throw "[BUILD:$service] failed after $BuildRetries attempts. Read the first ERROR above this line."
            }
        }
    }

    docker compose --profile full up -d --no-build
    if ($LASTEXITCODE -ne 0) { throw "[START] Full Stack startup failed after images were built." }

    docker compose --profile full ps
    Write-Host "Web:      http://localhost:5173"
    Write-Host "Mailpit:  http://localhost:8025"
    Write-Host "Nacos:    http://localhost:8082"
    Write-Host "Run Check-DevPilotFull.ps1 for readiness. Healthy containers are not application E2E evidence."
} finally {
    Pop-Location
}

