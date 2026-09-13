[CmdletBinding()]
param()

$ErrorActionPreference = "Continue"
$failures = 0

function Write-Check {
    param([bool]$Passed, [string]$Name, [string]$Detail)
    if ($Passed) {
        Write-Host "PASS  $Name  $Detail"
    } else {
        Write-Host "FAIL  $Name  $Detail" -ForegroundColor Red
        $script:failures++
    }
}

function Write-DiagnosticWarning {
    param([string]$Name, [string]$Detail)
    Write-Host "WARN  $Name  $Detail" -ForegroundColor Yellow
}

$docker = Get-Command docker -ErrorAction SilentlyContinue
Write-Check ([bool]$docker) "Docker CLI" $(if ($docker) { $docker.Source } else { "not found" })
if (-not $docker) { exit 1 }

docker info *> $null
Write-Check ($LASTEXITCODE -eq 0) "Docker engine" $(if ($LASTEXITCODE -eq 0) { "reachable" } else { "unreachable; start Docker Desktop" })
if ($LASTEXITCODE -ne 0) { exit 1 }

$desktopProxy = docker info --format '{{.HTTPSProxy}}'
$hasDesktopProxy = $LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($desktopProxy) -and $desktopProxy -ne "<no value>"
Write-Check $hasDesktopProxy "Docker proxy" $(if ($hasDesktopProxy) { "configured" } else { "missing" })

$internetSettings = Get-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
$systemProxyEnabled = $internetSettings.ProxyEnable -eq 1
Write-Check $systemProxyEnabled "Windows system proxy" $(if ($systemProxyEnabled) { "enabled" } else { "disabled" })

if ($internetSettings.ProxyServer -match "^(?:https?://)?([^:;]+):(\d+)$") {
    $proxyHost = $Matches[1]
    $proxyPort = [int]$Matches[2]
    $proxyReachable = Test-NetConnection $proxyHost -Port $proxyPort -InformationLevel Quiet -WarningAction SilentlyContinue
    Write-Check $proxyReachable "Local proxy port" "$proxyHost`:$proxyPort"
}

$authAddresses = @(Resolve-DnsName auth.docker.io -Type A -ErrorAction SilentlyContinue | Where-Object IPAddress | Select-Object -ExpandProperty IPAddress)
if ($authAddresses.Count -gt 0) {
    Write-Check $true "Host DNS" "auth.docker.io resolved"
} elseif ($hasDesktopProxy) {
    Write-DiagnosticWarning "Host DNS" "auth.docker.io did not resolve directly; Docker proxy must provide remote DNS"
} else {
    Write-Check $false "Host DNS" "auth.docker.io did not resolve and Docker proxy is missing"
}

docker buildx imagetools inspect docker.io/library/python:3.12-slim --format '{{.Manifest.MediaType}}' *> $null
Write-Check ($LASTEXITCODE -eq 0) "Docker Hub" $(if ($LASTEXITCODE -eq 0) { "registry metadata reachable" } else { "registry request failed" })

if ($hasDesktopProxy) {
    $proxyUri = if ($desktopProxy -match "^[a-z][a-z0-9+.-]*://") { $desktopProxy } else { "http://$desktopProxy" }
    docker image inspect python:3.12-slim *> $null
    if ($LASTEXITCODE -eq 0) {
        docker run --rm -e "HTTPS_PROXY=$proxyUri" -e "HTTP_PROXY=$proxyUri" python:3.12-slim python -c "import urllib.request; print(urllib.request.urlopen('https://repo.maven.apache.org/maven2/', timeout=20).status)" *> $null
        Write-Check ($LASTEXITCODE -eq 0) "Build-container network" $(if ($LASTEXITCODE -eq 0) { "Maven Central reachable through Docker proxy" } else { "proxy path failed" })
    } else {
        Write-Host "SKIP  Build-container network  python:3.12-slim is not local"
    }
}

Write-Host ""
Write-Host "Diagnostic summary: $failures failure(s)."
if ($failures -gt 0) { exit 1 }
