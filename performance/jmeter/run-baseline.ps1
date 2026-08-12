[CmdletBinding()]
param(
    [ValidateSet('read', 'task-workflow')]
    [string]$Plan = 'read',
    [ValidateNotNullOrEmpty()]
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [Parameter(Mandatory)]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$WorkspaceId,
    [Parameter(Mandatory)]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$ProjectId,
    [ValidateRange(1, 10000)]
    [int]$Threads = 10,
    [ValidateRange(0, 3600)]
    [int]$RampSeconds = 10,
    [ValidateRange(1, 86400)]
    [int]$DurationSeconds = 60,
    [string]$ResultsRoot = (Join-Path $PSScriptRoot 'results')
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($env:ACCESS_TOKEN)) {
    throw 'ACCESS_TOKEN environment variable is required.'
}

$jmeter = Get-Command jmeter -ErrorAction SilentlyContinue
if ($null -eq $jmeter) {
    throw 'JMeter was not found on PATH. Install it outside this script, then retry.'
}

$baseUri = [Uri]$BaseUrl
if (-not $baseUri.IsAbsoluteUri -or $baseUri.Scheme -notin @('http', 'https')) {
    throw 'BaseUrl must be an absolute http/https URL.'
}

$planFile = switch ($Plan) {
    'read' { Join-Path $PSScriptRoot 'devpilot-read-baseline.jmx' }
    'task-workflow' { Join-Path $PSScriptRoot 'devpilot-task-workflow-baseline.jmx' }
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDirectory = Join-Path $ResultsRoot "$timestamp-$Plan"
$reportDirectory = Join-Path $runDirectory 'report'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

$jmeterPort = if ($baseUri.IsDefaultPort) { '' } else { $baseUri.Port }
$arguments = @(
    '-n',
    '-t', $planFile,
    "-JBASE_URL=$($baseUri.GetLeftPart([UriPartial]::Authority))",
    "-JBASE_PROTOCOL=$($baseUri.Scheme)",
    "-JBASE_HOST=$($baseUri.Host)",
    "-JBASE_PORT=$jmeterPort",
    "-JWORKSPACE_ID=$WorkspaceId",
    "-JPROJECT_ID=$ProjectId",
    "-JTHREADS=$Threads",
    "-JRAMP_SECONDS=$RampSeconds",
    "-JDURATION_SECONDS=$DurationSeconds",
    '-l', (Join-Path $runDirectory 'results.jtl'),
    '-e',
    '-o', $reportDirectory
)

Write-Host "Running $Plan baseline against $($baseUri.GetLeftPart([UriPartial]::Authority)); results: $runDirectory"
& $jmeter.Source @arguments
if ($LASTEXITCODE -ne 0) {
    throw "JMeter exited with code $LASTEXITCODE."
}

Write-Host "JMeter baseline completed: $reportDirectory"
