[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFiles = @(
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

    docker compose @composeFiles --profile infrastructure --profile application up -d --build --wait
    docker compose @composeFiles --profile infrastructure --profile application ps
} finally {
    Pop-Location
}
