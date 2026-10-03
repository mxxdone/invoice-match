#requires -Version 7
<#
.SYNOPSIS
  Bounded Linux verification of the P2-04 parser inside a small official
  Python 3.12 runtime container.

.DESCRIPTION
  Runs the full parser pytest suite (including the real OS-level child
  timeout/memory/output/cleanup tests) and the CLI smoke on Linux. Downloads
  no large images: only the official python:3.12-slim runtime. All generated
  material stays on D and the container is removed in `finally`, including
  interrupted runs. Existing user/demo services are never touched.
#>
[CmdletBinding()]
param(
    [string]$Image = 'python:3.12-slim',
    [int]$TimeoutSeconds = 900,
    [string]$OutputRoot = 'D:/workspace/invoice-match/output/p2-04',
    [switch]$CleanRuntime
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$aiWorker = Join-Path $repo 'ai-worker'
$container = "invoice-match-p2-04-parser-$PID"
$logs = Join-Path $OutputRoot 'logs'
$runtime = Join-Path $OutputRoot 'venv-linux'
$cache = Join-Path $OutputRoot 'cache/pip'
$dockerOut = ($OutputRoot -replace '\\', '/')
$dockerRepo = ($aiWorker -replace '\\', '/')

New-Item -ItemType Directory -Force -Path $logs, $cache | Out-Null

function Write-Section([string]$text) { Write-Output "== $text ==" }

Write-Section 'Preflight'
$cFree = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
$dFree = [math]::Round((Get-PSDrive D).Free / 1GB, 2)
Write-Output "C free: $cFree GiB; D free: $dFree GiB"
if ($cFree -lt 2) { throw "Not enough C free space ($cFree GiB) for a runtime pull." }

$imageDigest = (docker image inspect $Image --format '{{index .RepoDigests 0}}' 2>$null) -join ''
if (-not $imageDigest) {
    Write-Output "Pulling small runtime image $Image ..."
    docker pull $Image
    $imageDigest = (docker image inspect $Image --format '{{index .RepoDigests 0}}').Trim()
}
Write-Output "Runtime image: $Image digest=$imageDigest"

$script = @'
set -e
if [ ! -d /out/venv-linux ]; then
  python -m venv /out/venv-linux
fi
/out/venv-linux/bin/python -m pip install --disable-pip-version-check \
  --cache-dir /out/cache/pip -r requirements-dev.txt
cd /w
export PYTHONPATH=src
/out/venv-linux/bin/python -m pytest tests -v
python -c "import sys; sys.path.insert(0, 'tests'); from fixtures import build_pdf; open('/out/smoke.pdf','wb').write(build_pdf(['가나다', None]))"
/out/venv-linux/bin/python -m ai_worker.api.cli version
/out/venv-linux/bin/python -m ai_worker.api.cli parse /out/smoke.pdf --document-id smoke --media-type application/pdf > /out/isolated.json
/out/venv-linux/bin/python -m ai_worker.api.cli parse-in-process /out/smoke.pdf --document-id smoke --media-type application/pdf > /out/inprocess.json
/out/venv-linux/bin/python -c "import json; a=json.load(open('/out/isolated.json')); b=json.load(open('/out/inprocess.json')); assert a==b; print('cli smoke ok')"
'@

$logFile = Join-Path $logs 'linux-verify.log'
Write-Section "Run (timeout ${TimeoutSeconds}s, container $container)"
$started = Get-Date
$exit = 1
try {
    docker run --rm --name $container `
        --cpus 2 --memory 2g --pids-limit 256 `
        -v "${dockerRepo}:/w:ro" `
        -v "${dockerOut}:/out" `
        -w /w `
        $Image sh -lc $script 2>&1 | Tee-Object -FilePath $logFile
    $exit = $LASTEXITCODE
}
finally {
    Write-Section 'Cleanup'
    $running = (docker ps -q -f "name=^${container}$") -join ''
    if ($running) { docker rm -f $container | Out-Null }
    # The Linux venv stays on the ignored D path so later runs reuse it and the
    # pip cache instead of re-downloading.
    if ($CleanRuntime) { Remove-Item -Recurse -Force $runtime -ErrorAction SilentlyContinue }
    $elapsed = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
    Write-Output "Elapsed: $elapsed s; exit=$exit; log=$logFile"
    Write-Output "No user/demo containers were touched."
}
exit $exit
