[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFiles = @(
    "--env-file", (Join-Path $repoRoot ".env"),
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

    # Remove legacy persistent init containers created by the old `up` workflow.
    # These jobs only publish idempotent configuration; their state lives in the
    # backing services/volumes, not in the containers themselves.
    docker compose @composeFiles --profile infrastructure --profile bootstrap rm -f -s @initServices
    if ($LASTEXITCODE -ne 0) {
        throw "Legacy initialization container cleanup failed with exit code $LASTEXITCODE"
    }

    # Run bootstrap jobs as transient containers. --rm keeps Docker Desktop free
    # of completed one-shot containers; --no-deps avoids touching healthy runtime
    # services that were started and awaited above.
    foreach ($initService in $initServices) {
        Write-Host "== Running transient bootstrap job: $initService =="
        docker compose @composeFiles --profile infrastructure --profile bootstrap run --rm --no-deps -T $initService
        if ($LASTEXITCODE -ne 0) {
            throw "Infrastructure initialization job '$initService' failed with exit code $LASTEXITCODE"
        }
    }

    docker compose @composeFiles --profile infrastructure ps
} finally {
    Pop-Location
}
