[CmdletBinding()]
param(
    [string]$Server = "http://127.0.0.1:8848",
    [string]$Group = "DEVPILOT",
    [int]$ReadinessTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$configDirectory = $PSScriptRoot
$configs = @(
    @{ DataId = "devpilot-core.yml"; Path = Join-Path $configDirectory "devpilot-core.yml" },
    @{ DataId = "devpilot-gateway.yml"; Path = Join-Path $configDirectory "devpilot-gateway.yml" }
)

function Wait-NacosReady {
    $healthUri = "$Server/nacos/v1/ns/operator/metrics"
    $deadline = (Get-Date).AddSeconds($ReadinessTimeoutSeconds)
    $lastFailure = $null

    while ((Get-Date) -lt $deadline) {
        try {
            # 本机 Nacos 地址必须绕过系统代理，避免 127.0.0.1 请求被代理进程错误转发。
            $health = Invoke-RestMethod -Method Get -Uri $healthUri -NoProxy -TimeoutSec 5
            if ($health.status -eq "UP") {
                return
            }
            $lastFailure = "health status: $($health.status)"
        } catch {
            $lastFailure = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }

    throw "Nacos did not become ready within $ReadinessTimeoutSeconds seconds. Last failure: $lastFailure"
}

Wait-NacosReady

foreach ($config in $configs) {
    $content = Get-Content -LiteralPath $config.Path -Raw
    $published = Invoke-RestMethod `
        -Method Post `
        -Uri "$Server/nacos/v1/cs/configs" `
        -NoProxy `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{ dataId = $config.DataId; group = $Group; type = "yaml"; content = $content }
    if ($published -ne $true -and $published -ne "true") {
        throw "Nacos rejected config $($config.DataId): $published"
    }
    Write-Host "Published $($config.DataId) to group $Group"
}
