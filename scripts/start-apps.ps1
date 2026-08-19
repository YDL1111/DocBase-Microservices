[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFiles = @(
    "--env-file", (Join-Path $repoRoot ".env"),
    "-f", (Join-Path $repoRoot "deploy/compose.yml"),
    "-f", (Join-Path $repoRoot "deploy/compose.dev.yml")
)

Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        & (Join-Path $repoRoot "mvnw.cmd") -B -ntp clean verify
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE"
        }
    }

    # Always finish the idempotent transient bootstrap jobs before applications
    # start. This also covers a first run against fresh named volumes.
    & (Join-Path $PSScriptRoot "start-infra.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "Infrastructure bootstrap failed with exit code $LASTEXITCODE"
    }

    docker compose @composeFiles --profile infrastructure --profile application up -d --build --wait
    if ($LASTEXITCODE -ne 0) {
        throw "Application startup failed with exit code $LASTEXITCODE"
    }
    docker compose @composeFiles --profile infrastructure --profile application ps
} finally {
    Pop-Location
}
