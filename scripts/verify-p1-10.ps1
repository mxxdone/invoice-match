# P1-10 live-slice verification.
#
# Starts only throwaway resources it creates (ephemeral PostgreSQL container,
# mock-purchasing, the built core-api jar, the built web standalone), records
# their PIDs/ports, waits for readiness with a finite timeout, runs the
# read-API checks, and always cleans up in `finally`. It never stops or removes
# an existing container and never writes a credential to a log.
#
# Prerequisites (build once):
#   cd core-api; ./gradlew bootJar
#   cd web; npm ci; npm run build
#
# Usage:
#   pwsh -File scripts/verify-p1-10.ps1
#   pwsh -File scripts/verify-p1-10.ps1 -CorePort 8080 -WebPort 3100
[CmdletBinding()]
param(
    [int]$PgPort = 55433,
    [int]$MockPort = 8082,
    [int]$CorePort = 8080,
    [int]$WebPort = 3100,
    [int]$ReadinessTimeoutSec = 90
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$coreJar = Join-Path $repo 'core-api\build\libs\core-api-0.1.0-SNAPSHOT.jar'
$webServer = Join-Path $repo 'web\.next\standalone\server.js'
$webStatic = Join-Path $repo 'web\.next\standalone\.next\static'
$pgName = "im-verify-pg-$PID"
$pgPassword = [Guid]::NewGuid().ToString('N')

if (-not (Test-Path $coreJar)) { throw "Missing $coreJar. Run: cd core-api; ./gradlew bootJar" }
if (-not (Test-Path $webServer)) { throw "Missing $webServer. Run: cd web; npm run build" }
if (-not (Test-Path $webStatic)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $webStatic) | Out-Null
    Copy-Item -Recurse -Force (Join-Path $repo 'web\.next\static') $webStatic
}

function Wait-Http([string]$url, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        try { Invoke-WebRequest -Uri $url -TimeoutSec 3 -SkipHttpErrorCheck -UseBasicParsing | Out-Null; return } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "Readiness timeout for $url"
}
function Basic([string]$user, [string]$pass) {
    'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${user}:${pass}"))
}
function Check([string]$name, $actual, $expected) {
    if ($actual -ne $expected) { throw "FAIL $name expected [$expected] got [$actual]" }
    Write-Host "PASS $name"
}

$processes = @()
try {
    Write-Host '== start isolated resources =='
    docker run -d --rm --name $pgName -e POSTGRES_DB=invoice_match -e POSTGRES_USER=invoice_match -e POSTGRES_PASSWORD=$pgPassword -p "${PgPort}:5432" postgres:18-alpine | Out-Null
    $deadline = (Get-Date).AddSeconds($ReadinessTimeoutSec)
    while ((Get-Date) -lt $deadline) {
        docker exec $pgName pg_isready -U invoice_match -d invoice_match *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL did not become ready' }

    $env:PORT = "$MockPort"
    $processes += (Start-Process node -ArgumentList 'server.js' -WorkingDirectory (Join-Path $repo 'mock-purchasing') -PassThru -WindowStyle Hidden)
    Wait-Http "http://localhost:$MockPort/health" $ReadinessTimeoutSec

    $env:DB_URL = "jdbc:postgresql://localhost:$PgPort/invoice_match"
    $env:DB_USER = 'invoice_match'
    $env:DB_PASSWORD = $pgPassword
    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:PURCHASING_BASE_URL = "http://localhost:$MockPort"
    $processes += (Start-Process java -ArgumentList '-jar', $coreJar -WorkingDirectory (Join-Path $repo 'core-api') -PassThru -WindowStyle Hidden)
    Wait-Http "http://localhost:$CorePort/actuator/health" $ReadinessTimeoutSec

    $env:CORE_API_URL = "http://localhost:$CorePort"
    $env:PORT = "$WebPort"
    $env:HOSTNAME = '0.0.0.0'
    $processes += (Start-Process node -ArgumentList 'server.js' -WorkingDirectory (Join-Path $repo 'web\.next\standalone') -PassThru -WindowStyle Hidden)
    Wait-Http "http://localhost:$WebPort/login" $ReadinessTimeoutSec

    Write-Host '== HTTP read-API checks =='
    $anon = Invoke-WebRequest "http://localhost:$CorePort/api/me" -SkipHttpErrorCheck -UseBasicParsing
    Check 'anonymous /api/me is 401' $anon.StatusCode 401
    $approver = Invoke-WebRequest "http://localhost:$CorePort/api/me" -Headers @{ Authorization = (Basic 'approver' 'approver-pass') } -SkipHttpErrorCheck -UseBasicParsing
    Check 'approver /api/me is 200' $approver.StatusCode 200
    Check 'approver role present' ($approver.Content -match 'APPROVER') $true

    $empty = Invoke-WebRequest "http://localhost:$CorePort/api/invoice-cases?page=0&size=20" -Headers @{ Authorization = (Basic 'approver' 'approver-pass') } -SkipHttpErrorCheck -UseBasicParsing
    Check 'approver list is 200' $empty.StatusCode 200

    # Seed one submitted case so partial search and the KST day range are real.
    $sub = @{ Authorization = (Basic 'submitter' 'submitter-pass') }
    $created = (Invoke-WebRequest "http://localhost:$CorePort/api/invoice-cases" -Method Post -Headers $sub -ContentType 'application/json' -Body '{"requestId":"verify-create","supplierId":"SUP-1","purchaseOrderId":"PO-1001","invoiceNumber":"INV-VERIFY-9"}' -UseBasicParsing).Content | ConvertFrom-Json
    $draft = @{ requestId = 'verify-draft'; expectedCaseVersion = $created.version; lines = @(@{ lineNumber = 1; rawItemName = 'Copy Paper'; quantity = 5; unitPrice = 2500; confirmedItemId = 'ITEM-A4-80' }) } | ConvertTo-Json -Compress -Depth 5
    $drafted = (Invoke-WebRequest "http://localhost:$CorePort/api/invoice-cases/$($created.id)/draft" -Method Put -Headers $sub -ContentType 'application/json' -Body $draft -UseBasicParsing).Content | ConvertFrom-Json
    Invoke-WebRequest "http://localhost:$CorePort/api/invoice-cases/$($created.id)/submit" -Method Post -Headers $sub -ContentType 'application/json' -Body (@{ requestId = 'verify-submit'; expectedCaseVersion = $drafted.version } | ConvertTo-Json -Compress) -UseBasicParsing | Out-Null

    $ha = @{ Authorization = (Basic 'approver' 'approver-pass') }
    $partial = (Invoke-WebRequest "http://localhost:$CorePort/api/invoice-cases?invoiceNumber=VERIFY&page=0&size=20" -Headers $ha -UseBasicParsing).Content | ConvertFrom-Json
    Check 'partial invoice search finds the case' $partial.totalItems 1

    $kst = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(9)).ToString('yyyy-MM-dd')
    $range = (Invoke-WebRequest "http://localhost:$CorePort/api/invoice-cases?submittedFrom=$($kst)T00:00:00.000000%2B09:00&submittedTo=$($kst)T23:59:59.999999%2B09:00&size=100" -Headers $ha -UseBasicParsing).Content | ConvertFrom-Json
    Check 'KST inclusive day range finds the case' ($range.items.invoiceNumber -contains 'INV-VERIFY-9') $true

    Write-Host '== proxied checks (browser path) =='
    $proxyAnon = Invoke-WebRequest "http://localhost:$WebPort/backend/api/me" -SkipHttpErrorCheck -UseBasicParsing
    Check 'proxy anonymous /api/me is 401' $proxyAnon.StatusCode 401
    $proxyBlocked = Invoke-WebRequest "http://localhost:$WebPort/backend/api/invoice-cases/$($created.id)/approve" -SkipHttpErrorCheck -UseBasicParsing
    Check 'proxy refuses non-allowlisted path' $proxyBlocked.StatusCode 404

    Write-Host 'ALL CHECKS PASSED'
}
finally {
    Write-Host '== cleanup (only resources created above) =='
    foreach ($p in $processes) { if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
    docker stop $pgName 2>$null | Out-Null
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
}
