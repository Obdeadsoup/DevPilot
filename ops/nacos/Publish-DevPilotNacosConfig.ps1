[CmdletBinding()]
param(
    [string]$Server = "http://127.0.0.1:8848",
    [string]$Group = "DEVPILOT"
)

$ErrorActionPreference = "Stop"
$configDirectory = $PSScriptRoot
$configs = @(
    @{ DataId = "devpilot-core.yml"; Path = Join-Path $configDirectory "devpilot-core.yml" },
    @{ DataId = "devpilot-gateway.yml"; Path = Join-Path $configDirectory "devpilot-gateway.yml" }
)

foreach ($config in $configs) {
    $content = Get-Content -LiteralPath $config.Path -Raw
    $published = Invoke-RestMethod `
        -Method Post `
        -Uri "$Server/nacos/v1/cs/configs" `
        -ContentType "application/x-www-form-urlencoded" `
        -Body @{ dataId = $config.DataId; group = $Group; type = "yaml"; content = $content }
    if ($published -ne $true -and $published -ne "true") {
        throw "Nacos rejected config $($config.DataId): $published"
    }
    Write-Host "Published $($config.DataId) to group $Group"
}
