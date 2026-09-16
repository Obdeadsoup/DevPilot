[CmdletBinding()]
param(
    [ValidateSet("deepseek", "fake")]
    [string]$Mode = $(if ($env:AGENT_MODEL_MODE) { $env:AGENT_MODEL_MODE } else { "deepseek" })
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$envPath = Join-Path $repositoryRoot ".env"
$failures = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()
$settings = @{}

function Write-Check([string]$Result, [string]$Name, [string]$Detail) {
    Write-Host ("{0,-5} {1}  {2}" -f $Result, $Name, $Detail)
}

function Get-Setting([string]$Name, [string]$Default = "") {
    $processValue = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($processValue)) { return $processValue.Trim() }
    if ($settings.ContainsKey($Name)) { return $settings[$Name] }
    return $Default
}

function Require-Setting([string]$Name, [int]$MinimumLength = 1) {
    $value = Get-Setting $Name
    if ([string]::IsNullOrWhiteSpace($value) -or $value.Length -lt $MinimumLength) {
        $failures.Add("$Name is missing or shorter than $MinimumLength characters.")
        Write-Check "FAIL" $Name "missing or invalid (value hidden)"
    } else {
        Write-Check "PASS" $Name "configured (value hidden)"
    }
}

if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    $failures.Add(".env does not exist. Copy .env.example to .env first.")
    Write-Check "FAIL" ".env" "not found"
} else {
    foreach ($line in Get-Content -LiteralPath $envPath) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) { continue }
        $name, $value = $trimmed.Split("=", 2)
        $settings[$name.Trim()] = $value.Trim()
    }
    Write-Check "PASS" ".env" "loaded without printing values"
}

Require-Setting "DEVPILOT_MYSQL_PASSWORD"
Require-Setting "DEVPILOT_MYSQL_ROOT_PASSWORD"
Require-Setting "DEVPILOT_REDIS_PASSWORD"
Require-Setting "DEVPILOT_AGENT_TOOL_SERVICE_KEY" 16
if ($Mode -eq "deepseek") {
    Require-Setting "DEEPSEEK_API_KEY"
    Write-Check "PASS" "Agent mode" "deepseek (real provider)"
} else {
    $fakeTool = Get-Setting "AGENT_FAKE_TOOL_NAME"
    $allowlistedTools = @("project.get_summary", "task.list_open", "project.list_recent_activity")
    if ($fakeTool -and $fakeTool -notin $allowlistedTools) {
        $failures.Add("AGENT_FAKE_TOOL_NAME is not allowlisted.")
        Write-Check "FAIL" "Agent mode" "fake Tool is invalid"
    } elseif (-not $fakeTool) {
        $warnings.Add("Fake mode has no AGENT_FAKE_TOOL_NAME; it will not prove the Tool Gateway lifecycle.")
        Write-Check "WARN" "Agent mode" "fake without Tool lifecycle"
    } else {
        Write-Check "PASS" "Agent mode" "fake with deterministic $fakeTool lifecycle"
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    $failures.Add("Docker CLI is not installed or not on PATH.")
    Write-Check "FAIL" "Docker CLI" "not found"
} else {
    Write-Check "PASS" "Docker CLI" "available"
    & docker version --format "{{.Server.Version}}" *> $null
    if ($LASTEXITCODE -ne 0) {
        $failures.Add("Docker Desktop/Engine is not running.")
        Write-Check "FAIL" "Docker engine" "not reachable"
    } else {
        Write-Check "PASS" "Docker engine" "reachable"
    }
}

$portSettings = @{
    DEVPILOT_WEB_PORT = 5173
    DEVPILOT_GATEWAY_PORT = 8081
    DEVPILOT_CORE_PORT = 8080
    DEVPILOT_MAILPIT_PORT = 8025
    DEVPILOT_MYSQL_PORT = 3307
    DEVPILOT_REDIS_PORT = 6380
    DEVPILOT_NACOS_CONSOLE_PORT = 8082
    DEVPILOT_NACOS_PORT = 8848
    DEVPILOT_NACOS_GRPC_PORT = 9848
}

foreach ($entry in $portSettings.GetEnumerator()) {
    $rawPort = Get-Setting $entry.Key ([string]$entry.Value)
    $port = 0
    if (-not [int]::TryParse($rawPort, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        $failures.Add("$($entry.Key) is not a valid TCP port.")
        Write-Check "FAIL" $entry.Key "invalid port"
        continue
    }
    $listener = $null
    try {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
        $listener.Start()
        Write-Check "PASS" $entry.Key "port $port is available"
    } catch {
        $failures.Add("$($entry.Key) port $port is already in use.")
        Write-Check "FAIL" $entry.Key "port $port is unavailable"
    } finally {
        if ($listener) { $listener.Stop() }
    }
}

if ([string]::IsNullOrWhiteSpace((Get-Setting "DEVPILOT_GITHUB_API_TOKEN_LOCAL"))) {
    $warnings.Add("GitHub API token is empty; repository binding and real GitHub sync will be unavailable.")
    Write-Check "WARN" "GitHub token" "optional until repository binding"
}
if ([string]::IsNullOrWhiteSpace((Get-Setting "DEVPILOT_GITHUB_WEBHOOK_SECRET_LOCAL"))) {
    $warnings.Add("GitHub webhook secret is empty; repository binding will be unavailable.")
    Write-Check "WARN" "Webhook secret" "optional until repository binding"
}

if ($failures.Count -eq 0) {
    Push-Location $repositoryRoot
    $previousMode = $env:AGENT_MODEL_MODE
    try {
        $env:AGENT_MODEL_MODE = $Mode
        & docker compose --profile full config --quiet
        if ($LASTEXITCODE -ne 0) {
            $failures.Add("Docker Compose configuration is invalid.")
            Write-Check "FAIL" "Compose config" "invalid"
        } else {
            Write-Check "PASS" "Compose config" "full profile is valid"
        }
    } finally {
        $env:AGENT_MODEL_MODE = $previousMode
        Pop-Location
    }
}

Write-Host ""
Write-Host "Preflight summary: $($failures.Count) failure(s), $($warnings.Count) warning(s)."
if ($warnings.Count -gt 0) { $warnings | ForEach-Object { Write-Host "WARN  $_" } }
if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL  $_" }
    exit 1
}
Write-Host "PASS  Ready for: docker compose --profile full up --build"
