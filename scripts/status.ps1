[CmdletBinding()]
param()

$ErrorActionPreference = "Continue"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFiles = @(
    "-f", (Join-Path $repoRoot "deploy/compose.yml"),
    "-f", (Join-Path $repoRoot "deploy/compose.dev.yml")
)

Push-Location $repoRoot
try {
    docker compose @composeFiles --profile "*" ps --all

    $checks = [ordered]@{
        "Gateway" = "http://localhost:8080/actuator/health"
        "Gateway -> IAM" = "http://localhost:8080/api/auth/ping"
        "Nacos API" = "http://localhost:8848/nacos/v1/ns/operator/metrics"
        "RabbitMQ Console" = "http://localhost:15672"
        "MinIO API" = "http://localhost:9000/minio/health/live"
        "Web" = "http://localhost:3000"
    }

    foreach ($item in $checks.GetEnumerator()) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $item.Value -TimeoutSec 5
            Write-Host ("[UP]   {0,-20} HTTP {1}" -f $item.Key, $response.StatusCode)
        } catch {
            Write-Host ("[DOWN] {0,-20} {1}" -f $item.Key, $_.Exception.Message)
        }
    }
} finally {
    Pop-Location
}
