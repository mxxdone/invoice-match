#requires -Version 7
<#
.SYNOPSIS
  Bounded Linux verification of the P2-04 parser inside a small official
  Python 3.12 runtime container.

.DESCRIPTION
  Installs the package as a real wheel from its pinned dependencies, runs the
  full parser pytest suite (including the real OS-level child
  timeout/memory/output/cleanup tests) and smokes the installed entry point
  without PYTHONPATH=src. TimeoutSeconds bounds image preparation, install,
  tests and CLI. Only the official python:3.12-slim runtime is downloaded; all
  generated material stays on D and the container is force-removed in
  `finally`, including interrupted runs. Existing user/demo services are never
  touched.
#>
[CmdletBinding()]
param(
    [string]$Image = 'python:3.12-slim',
    [int]$TimeoutSeconds = 900,
    [string]$OutputRoot = 'D:/workspace/invoice-match/output/p2-04'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$aiWorker = Join-Path $repo 'ai-worker'
$container = "invoice-match-p2-04-parser-$PID"
$logs = Join-Path $OutputRoot 'logs'
$cache = Join-Path $OutputRoot 'cache/pip'
$dockerOut = ($OutputRoot -replace '\\', '/')
$dockerRepo = ($aiWorker -replace '\\', '/')
$logFile = Join-Path $logs 'linux-verify.log'

New-Item -ItemType Directory -Force -Path $logs, $cache | Out-Null
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$exit = 1
$job = $null

function Write-Section([string]$text) { Write-Output "== $text ==" }
function Get-Remaining {
    $remaining = $TimeoutSeconds - [int]$stopwatch.Elapsed.TotalSeconds
    if ($remaining -lt 1) { throw "Overall deadline of ${TimeoutSeconds}s exceeded before container start." }
    return $remaining
}

Write-Section 'Preflight'
$cFree = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
$dFree = [math]::Round((Get-PSDrive D).Free / 1GB, 2)
Write-Output "C free: $cFree GiB; D free: $dFree GiB; deadline: ${TimeoutSeconds}s"
if ($cFree -lt 2) { throw "Not enough C free space ($cFree GiB) for a runtime pull." }

try {
    $imageDigest = (docker image inspect $Image --format '{{index .RepoDigests 0}}' 2>$null) -join ''
    if (-not $imageDigest) {
        Write-Output "Pulling small runtime image $Image ..."
        docker pull $Image
        $imageDigest = (docker image inspect $Image --format '{{index .RepoDigests 0}}').Trim()
    }
    Write-Output "Runtime image: $Image digest=$imageDigest"

    $innerTimeout = Get-Remaining
    $script = @'
set -e
cd /w
if [ ! -d /out/venv-linux ]; then
  python -m venv /out/venv-linux
fi
V=/out/venv-linux/bin
$V/python -m pip install --disable-pip-version-check --cache-dir /out/cache/pip -r requirements-dev.txt
rm -rf /out/pkg-src
cp -r /w /out/pkg-src
$V/python -m pip install --disable-pip-version-check --no-build-isolation --no-deps --cache-dir /out/cache/pip /out/pkg-src
$V/python -m pytest -p no:cacheprovider tests -v
cd /tmp
$V/python -c "import ai_worker, ai_worker.composition; print('import ok', ai_worker.PARSER_VERSION)"
$V/ai-worker version
$V/python -c "import sys; sys.path.insert(0, '/w/tests'); from fixtures import build_pdf; open('/out/smoke.pdf', 'wb').write(build_pdf(['가나다', None]))"
SIZE=$(stat -c %s /out/smoke.pdf)
SHA=$(sha256sum /out/smoke.pdf | cut -d' ' -f1)
$V/ai-worker parse /out/smoke.pdf --document-id smoke --media-type application/pdf --size-bytes "$SIZE" --sha256 "$SHA" > /out/isolated.json
$V/python -c "import json; d=json.load(open('/out/isolated.json')); assert d['kind']=='pdf' and d['pdf']['pages'][0]['text']=='가나다'; print('cli smoke ok')"
'@

    Write-Section "Run (deadline ${innerTimeout}s, container $container)"
    $job = Start-Job -ScriptBlock {
        param($Image, $Name, $Repo, $Out, $Script, $InnerTimeout)
        docker run --name $Name --cpus 2 --memory 2g --pids-limit 256 `
            -v "${Repo}:/w:ro" -v "${Out}:/out" -w /w `
            $Image timeout --signal=KILL --kill-after=10s $InnerTimeout sh -lc $Script 2>&1
        "CONTAINER_EXIT=$LASTEXITCODE"
    } -ArgumentList $Image, $container, $dockerRepo, $dockerOut, $script, $innerTimeout

    $deadline = (Get-Date).AddSeconds([int]$innerTimeout + 60)
    while ($job.State -eq 'Running') {
        Receive-Job $job | Tee-Object -FilePath $logFile -Append
        if ((Get-Date) -gt $deadline) {
            Write-Output 'Outer deadline exceeded; killing container.'
            docker kill $container 2>$null | Out-Null
            break
        }
        Start-Sleep -Milliseconds 500
    }
    $raw = Receive-Job $job
    $raw | Tee-Object -FilePath $logFile -Append
    $marker = ($raw | Select-String -Pattern '^CONTAINER_EXIT=(\d+)$' | Select-Object -Last 1)
    if ($marker) { $exit = [int]$marker.Matches[0].Groups[1].Value } else { $exit = 1 }
}
catch {
    Write-Output "ERROR: $($_.Exception.Message)"
    $exit = 1
}
finally {
    Write-Section 'Cleanup'
    if ($job) {
        Stop-Job $job -ErrorAction SilentlyContinue | Out-Null
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
    $running = (docker ps -aq -f "name=^${container}$") -join ''
    if ($running) { docker rm -f $container | Out-Null }
    Write-Output "Elapsed: $([math]::Round($stopwatch.Elapsed.TotalSeconds,1)) s; exit=$exit; log=$logFile"
    Write-Output "No user/demo containers were touched."
}
exit $exit
