[CmdletBinding()]
param(
    [switch]$SkipContainerStart
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFiles = @(
    "-f", (Join-Path $repoRoot "deploy/compose.yml"),
    "-f", (Join-Path $repoRoot "deploy/compose.dev.yml")
)

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

Push-Location $repoRoot
try {
    Assert-Command "java"
    Assert-Command "docker"
    Assert-Command "git"

    Write-Host "== Toolchain =="
    java -version
    docker version
    docker compose version

    Write-Host "== Maven compile and tests =="
    & (Join-Path $repoRoot "mvnw.cmd") -B -ntp clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "Maven verification failed with exit code $LASTEXITCODE"
    }

    Write-Host "== Compose specification =="
    docker compose @composeFiles --profile infrastructure --profile application --profile governance --profile observability config --quiet

    if (-not $SkipContainerStart) {
        Write-Host "== Infrastructure and smoke services =="
        & (Join-Path $PSScriptRoot "start-infra.ps1")
        docker compose @composeFiles --profile infrastructure --profile application up -d --build --wait gateway-service iam-service

        $gatewayHealth = Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -TimeoutSec 10
        if ($gatewayHealth.status -ne "UP") {
            throw "Gateway health is not UP"
        }

        $routed = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/ping" -TimeoutSec 10
        if (-not $routed.success -or $routed.data.service -ne "iam-service") {
            throw "Gateway did not route to iam-service"
        }
    }

    Write-Host "== Secret pattern scan =="
    $secretPatterns = "(sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,}|ghp_[A-Za-z0-9]{30,}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|AKIA[0-9A-Z]{16})"
    $matches = rg --hidden --glob "!.git/**" --glob "!.env.example" --glob "!**/target/**" $secretPatterns .
    if ($LASTEXITCODE -eq 0) {
        throw "Potential real secret material detected:`n$matches"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "Secret scan failed with exit code $LASTEXITCODE"
    }

    Write-Host "Verification completed successfully."
} finally {
    Pop-Location
}
