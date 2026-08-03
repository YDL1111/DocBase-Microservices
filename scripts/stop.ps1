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
    docker compose @composeFiles --profile "*" down --remove-orphans
    Write-Host "Containers and networks stopped. Named volumes were preserved."
} finally {
    Pop-Location
}
