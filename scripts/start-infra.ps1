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
    $runtimeServices = @("mysql", "redis", "rabbitmq", "minio", "nacos")
    $initServices = @("rabbitmq-init", "minio-init", "nacos-init")

    docker compose @composeFiles --profile infrastructure up -d --wait @runtimeServices
    if ($LASTEXITCODE -ne 0) {
        throw "Infrastructure startup failed with exit code $LASTEXITCODE"
    }

    docker compose @composeFiles --profile infrastructure up -d --no-deps --force-recreate @initServices
    if ($LASTEXITCODE -ne 0) {
        throw "Infrastructure initialization failed to start with exit code $LASTEXITCODE"
    }

    docker compose @composeFiles --profile infrastructure wait @initServices
    if ($LASTEXITCODE -ne 0) {
        throw "Infrastructure initialization failed with exit code $LASTEXITCODE"
    }

    docker compose @composeFiles --profile infrastructure ps
} finally {
    Pop-Location
}
