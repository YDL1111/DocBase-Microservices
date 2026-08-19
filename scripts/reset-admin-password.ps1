[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z][A-Za-z0-9._-]{2,63}$')]
    [string]$Username = "admin",

    [switch]$Reactivate
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot "deploy/compose.yml"
$envFile = Join-Path $repoRoot ".env"
$tempDir = $null
$plainPassword = $null
$confirmPassword = $null
$passwordBase64 = $null

function ConvertTo-PlainText([Security.SecureString]$SecureValue) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

Push-Location $repoRoot
try {
    $securePassword = Read-Host "Enter the new password for $Username (at least 8 characters, at most 72 UTF-8 bytes)" -AsSecureString
    $secureConfirmation = Read-Host "Enter the new password again" -AsSecureString
    $plainPassword = ConvertTo-PlainText $securePassword
    $confirmPassword = ConvertTo-PlainText $secureConfirmation

    $passwordByteCount = [Text.Encoding]::UTF8.GetByteCount($plainPassword)
    if ($plainPassword.Length -lt 8 -or $passwordByteCount -gt 72) {
        throw "Password must be at least 8 characters and at most 72 UTF-8 bytes."
    }
    if ($plainPassword -cne $confirmPassword) {
        throw "The two passwords do not match."
    }

    $javac = Get-Command javac -ErrorAction Stop
    $java = Get-Command java -ErrorAction Stop
    $tempDir = Join-Path ([IO.Path]::GetTempPath()) ("docbase-password-" + [Guid]::NewGuid())
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    $classpathFile = Join-Path $tempDir "runtime-classpath.txt"
    & (Join-Path $repoRoot "mvnw.cmd") -q -pl services/iam-service -am -DskipTests package `
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath `
        "-DincludeScope=runtime" "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $classpathFile)) {
        throw "Unable to build the IAM runtime classpath. Check Maven connectivity, then retry."
    }
    $cryptoJarPath = ((Get-Content -LiteralPath $classpathFile -Raw) -split [IO.Path]::PathSeparator) |
        Where-Object { (Split-Path -Leaf $_) -like "spring-security-crypto-*.jar" } |
        Select-Object -First 1
    if (-not $cryptoJarPath -or -not (Test-Path -LiteralPath $cryptoJarPath)) {
        throw "The project's spring-security-crypto dependency was not found."
    }

    $helperFile = Join-Path $tempDir "DocBasePasswordHash.java"
    $helperSource = @'
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCrypt;

public class DocBasePasswordHash {
    public static void main(String[] args) throws Exception {
        String encoded = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.US_ASCII)).readLine();
        if (encoded == null) throw new IllegalArgumentException("password missing");
        String password = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        System.out.print(BCrypt.hashpw(password, BCrypt.gensalt(12)));
    }
}
'@
    [IO.File]::WriteAllText(
        $helperFile,
        $helperSource,
        [Text.UTF8Encoding]::new($false)
    )

    & $javac.Source -cp $cryptoJarPath $helperFile
    if ($LASTEXITCODE -ne 0) { throw "Password hash helper compilation failed." }
    $passwordBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($plainPassword))
    $passwordHash = $passwordBase64 | & $java.Source -cp "$tempDir;$cryptoJarPath" DocBasePasswordHash
    if ($LASTEXITCODE -ne 0 -or $passwordHash -notmatch '^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$') {
        throw "Password hash generation failed."
    }

    if ($Reactivate) {
        $accountUpdate = "status = 1, deleted = 0, "
        $accountFilter = ""
    } else {
        $accountUpdate = ""
        $accountFilter = " AND status = 1 AND deleted = 0"
    }

    $sql = @"
UPDATE sys_user
SET password = '$passwordHash', ${accountUpdate}update_time = CURRENT_TIMESTAMP
WHERE username = '$Username' AND is_admin = 1$accountFilter;
SELECT user_id FROM sys_user WHERE username = '$Username' AND is_admin = 1$accountFilter LIMIT 1;
"@
    $userId = $sql | docker compose --env-file $envFile -f $composeFile --profile infrastructure exec -T mysql `
        sh -lc 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --batch --skip-column-names docbase_iam'
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL password update failed. If Docker reports Access Denied, retry from an elevated PowerShell."
    }
    $userIdLine = $userId | Select-Object -Last 1
    $userId = if ($null -eq $userIdLine) { "" } else { $userIdLine.Trim() }
    if ($userId -notmatch '^\d+$') {
        $hint = if ($Reactivate) { "" } else { " Use -Reactivate only if you intentionally want to restore an inactive account." }
        throw "An active super administrator '$Username' was not found; no database row was changed.$hint"
    }

    # A missing version key must not be recreated as 1: old tokens commonly carry
    # version 1, so that would falsely report successful invalidation after Redis
    # data loss. Use an epoch-millisecond seed for missing keys and increment
    # existing keys, atomically, with the same 30-day TTL used by TokenStore.
    $resetVersion = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $versionTtlSeconds = 30 * 24 * 60 * 60
    $redisLua = "local seed=tonumber(ARGV[1]); local ttl=tonumber(ARGV[2]); " +
        "for i=1,2 do if redis.call('EXISTS',KEYS[i]) == 1 then " +
        "redis.call('INCR',KEYS[i]); redis.call('EXPIRE',KEYS[i],ttl) " +
        "else redis.call('SET',KEYS[i],seed,'EX',ttl) end end; " +
        "redis.call('DEL',KEYS[3]); return 1"
    $redisCommand = 'EVAL "' + $redisLua + '" 3 ' +
        "docbase:iam:token:auth:$userId docbase:iam:token:session:$userId " +
        "docbase:iam:permission:$userId $resetVersion $versionTtlSeconds"
    $redisResult = $redisCommand | docker compose --env-file $envFile -f $composeFile --profile infrastructure exec -T redis `
        sh -lc 'exec redis-cli -e --raw --user admin --pass "$REDIS_ADMIN_PASSWORD" --no-auth-warning'
    $redisExitCode = $LASTEXITCODE
    $redisResultLine = $redisResult | Select-Object -Last 1
    $redisResultValue = if ($null -eq $redisResultLine) { "" } else { $redisResultLine.Trim() }
    if ($redisExitCode -ne 0 -or $redisResultValue -ne "1") {
        throw "The password was updated, but Redis session invalidation failed. Old tokens may still work; restore Redis connectivity and rerun this command before login."
    }

    Write-Host "Administrator '$Username' password reset; old tokens were invalidated." -ForegroundColor Green
} finally {
    $plainPassword = $null
    $confirmPassword = $null
    $passwordBase64 = $null
    if ($tempDir -and (Test-Path -LiteralPath $tempDir)) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
    Pop-Location
}
