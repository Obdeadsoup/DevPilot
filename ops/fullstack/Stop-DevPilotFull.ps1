[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $repositoryRoot
try {
    # Named volumes are intentionally retained; this script does not erase local data.
    docker compose --profile full down
    if ($LASTEXITCODE -ne 0) { throw "Full Stack shutdown failed." }
} finally {
    Pop-Location
}
