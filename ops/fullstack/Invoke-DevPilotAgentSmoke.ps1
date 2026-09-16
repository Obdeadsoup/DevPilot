[CmdletBinding()]
param(
    [Parameter(Mandatory)] [long]$WorkspaceId,
    [Parameter(Mandatory)] [long]$ProjectId,
    [long]$RepositoryBindingId,
    [string]$BranchName,
    [string]$BaseUrl = "http://localhost:5173",
    [int]$TimeoutSeconds = 120,
    [switch]$AllowNoToolLifecycle
)

$ErrorActionPreference = "Stop"
$token = $env:DEVPILOT_SMOKE_ACCESS_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
    throw "Set DEVPILOT_SMOKE_ACCESS_TOKEN locally. The script never prints it."
}
if ($BranchName -and $RepositoryBindingId -le 0) {
    throw "BranchName requires a positive RepositoryBindingId."
}

$mode = if ($env:AGENT_MODEL_MODE) { $env:AGENT_MODEL_MODE } else { "deepseek" }
Write-Host "MODE  $mode $(if ($mode -eq 'fake') { '(deterministic test/fallback; not real LLM evidence)' } else { '(real provider)' })"

$client = [Net.Http.HttpClient]::new()
$client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $token)
$client.DefaultRequestHeaders.Accept.ParseAdd("application/json")
$runBase = "$($BaseUrl.TrimEnd('/'))/api/v1/workspaces/$WorkspaceId/projects/$ProjectId/agent-runs"

try {
    $payload = @{ input = "Summarize this project's current delivery risks using the available read-only tools." }
    if ($RepositoryBindingId -gt 0) { $payload.repositoryBindingId = $RepositoryBindingId }
    if ($BranchName) { $payload.branchName = $BranchName }
    $content = [Net.Http.StringContent]::new(
        ($payload | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, "application/json")
    $startResponse = $client.PostAsync($runBase, $content).GetAwaiter().GetResult()
    $startBody = $startResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if (-not $startResponse.IsSuccessStatusCode -or $startBody.code -ne "COMMON_0000") {
        throw "Agent start failed: HTTP $([int]$startResponse.StatusCode) $($startBody.code)"
    }
    $runId = [string]$startBody.data.runId
    Write-Host "PASS  Web/Nginx -> Gateway -> Java Agent API accepted runId=$runId"

    $initial = $client.GetAsync("$runBase/$runId").GetAwaiter().GetResult()
    if (-not $initial.IsSuccessStatusCode) { throw "Initial authoritative Agent Run GET failed." }
    Write-Host "PASS  Java Core -> MySQL authoritative Run projection is readable"

    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, "$runBase/$runId/stream")
    $request.Headers.Accept.ParseAdd("text/event-stream")
    $streamResponse = $client.SendAsync(
        $request, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    if (-not $streamResponse.IsSuccessStatusCode) { throw "Agent SSE connection failed." }
    Write-Host "PASS  Browser-compatible SSE connected through Web and Gateway"

    $reader = [IO.StreamReader]::new($streamResponse.Content.ReadAsStream())
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $eventName = ""
    $sawToolStarted = $false
    $sawToolCompleted = $false
    $terminalEvent = ""
    while ([DateTimeOffset]::UtcNow -lt $deadline -and -not $terminalEvent) {
        $lineTask = $reader.ReadLineAsync()
        $remaining = [Math]::Max(1, [int]($deadline - [DateTimeOffset]::UtcNow).TotalMilliseconds)
        $completed = [Threading.Tasks.Task]::WhenAny(
            $lineTask, [Threading.Tasks.Task]::Delay($remaining)).GetAwaiter().GetResult()
        if ($completed -ne $lineTask) { break }
        $line = $lineTask.GetAwaiter().GetResult()
        if ($null -eq $line) { break }
        if ($line.StartsWith("event:")) { $eventName = $line.Substring(6).Trim(); continue }
        if ($line -ne "") { continue }
        if ($eventName -eq "tool-started") { $sawToolStarted = $true }
        if ($eventName -eq "tool-completed") { $sawToolCompleted = $true }
        if ($eventName -in @("run-succeeded", "run-failed", "run-cancelled")) { $terminalEvent = $eventName }
        $eventName = ""
    }

    if (-not $AllowNoToolLifecycle -and (-not $sawToolStarted -or -not $sawToolCompleted)) {
        throw "No complete Tool lifecycle was observed. In fake mode set AGENT_FAKE_TOOL_NAME=project.get_summary."
    }
    if ($sawToolStarted -and $sawToolCompleted) {
        Write-Host "PASS  Python AgentLoop -> Java Tool Gateway -> RBAC/Application Service -> ToolResult"
    } else {
        Write-Host "WARN  No Tool lifecycle observed; provider/runtime path only"
    }
    if ($terminalEvent -ne "run-succeeded") { throw "Agent terminal event was '$terminalEvent'." }
    Write-Host "PASS  Python gRPC stream -> Java terminal transition -> SSE run-succeeded"

    $finalResponse = $client.GetAsync("$runBase/$runId").GetAwaiter().GetResult()
    $finalBody = $finalResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if (-not $finalResponse.IsSuccessStatusCode -or $finalBody.data.status -ne "SUCCEEDED") {
        throw "Authoritative Agent Run did not finish as SUCCEEDED."
    }
    Write-Host "PASS  Final output and Agent History source of truth are persisted"
    Write-Host "AGENT_GOLDEN_SMOKE_PASS runId=$runId mode=$mode"
} finally {
    $client.Dispose()
}
