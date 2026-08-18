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
    # Keep containers and networks so the docbase-ms project can be started
    # directly from Docker Desktop on the next work session.
    docker compose @composeFiles --profile "*" stop
    if ($LASTEXITCODE -ne 0) {
        throw "Container stop failed with exit code $LASTEXITCODE"
    }
    Write-Host "Containers stopped and preserved for Docker Desktop one-click startup."
} finally {
    Pop-Location
}
