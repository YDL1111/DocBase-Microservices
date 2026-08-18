[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFiles = @(
    "-f", (Join-Path $repoRoot "deploy/compose.yml"),
    "-f", (Join-Path $repoRoot "deploy/compose.dev.yml")
)

Push-Location $repoRoot
try {
    # Explicit teardown path: removes containers and project networks, but never
    # named volumes. Use stop.ps1 for ordinary daily shutdown instead.
    docker compose @composeFiles --profile "*" down --remove-orphans
    if ($LASTEXITCODE -ne 0) {
        throw "Container teardown failed with exit code $LASTEXITCODE"
    }
    Write-Host "Containers and project networks removed. Named volumes were preserved."
} finally {
    Pop-Location
}
