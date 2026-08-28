[CmdletBinding()]
param(
    [string]$NacosServer = "http://127.0.0.1:8848",
    [string]$GatewayServer = "http://127.0.0.1:8081",
    [string]$Group = "DEVPILOT"
)

$ErrorActionPreference = "Stop"

$health = Invoke-RestMethod "$NacosServer/nacos/v2/core/cluster/node/self/health"
if ($health -notmatch "UP") {
    throw "Nacos health is not UP: $health"
}

foreach ($dataId in @("devpilot-core.yml", "devpilot-gateway.yml")) {
    $content = Invoke-RestMethod "$NacosServer/nacos/v1/cs/configs?dataId=$dataId&group=$Group"
    if ($content -notmatch "config-source:\s*nacos") {
        throw "Nacos config $dataId was not published or does not contain the smoke marker"
    }
}

$services = Invoke-RestMethod "$NacosServer/nacos/v1/ns/service/list?pageNo=1&pageSize=100&groupName=$Group"
$serviceNames = @($services.doms)
foreach ($serviceName in @("devpilot-core", "devpilot-gateway")) {
    if ($serviceNames -notcontains $serviceName -and $serviceNames -notcontains "$Group@@$serviceName") {
        throw "Nacos service list does not contain $serviceName in group $Group"
    }
}

$gatewayHealth = Invoke-RestMethod "$GatewayServer/actuator/health"
if ($gatewayHealth.status -ne "UP") {
    throw "Gateway health is not UP"
}
$gatewayInfo = Invoke-RestMethod "$GatewayServer/actuator/info"
if ($gatewayInfo.devpilot.configSource -ne "nacos") {
    throw "Gateway did not load the Nacos smoke marker"
}

try {
    Invoke-WebRequest "$GatewayServer/not-api" | Out-Null
    throw "A non-API path was unexpectedly routed"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 404) {
        throw
    }
}

Write-Host "Nacos config, service discovery, Gateway health and route boundary smoke passed."
