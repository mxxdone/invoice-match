#requires -Version 7
# One deadline covers image preparation, installation, tests and CLI.
# Cleanup owns only the recorded Docker client PIDs and exact container name.
[CmdletBinding()]
param(
    [string]$Image = 'python:3.12-slim',
    [ValidateRange(1, 3600)][int]$TimeoutSeconds = 900,
    [string]$OutputRoot = 'D:/workspace/invoice-match/output/p2-04'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dockerRepo = (Join-Path $repo 'ai-worker') -replace '\\', '/'
$dockerOut = [System.IO.Path]::GetFullPath($OutputRoot) -replace '\\', '/'
$container = "invoice-match-p2-04-parser-$PID"
$logs = Join-Path $OutputRoot 'logs'
New-Item -ItemType Directory -Force -Path $logs, (Join-Path $OutputRoot 'cache/pip') | Out-Null
$logFile = Join-Path $logs "linux-verify-$PID.log"
Set-Content -LiteralPath $logFile -Value '' -Encoding utf8
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$exit = 1

function Invoke-DockerBounded {
    param([string[]]$Arguments, [double]$Deadline = $TimeoutSeconds)
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo.FileName = 'docker'
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $process.StartInfo.ArgumentList.Add($argument) }
    $capture = [System.Text.StringBuilder]::new()
    $started = $false
    try {
        if ($watch.Elapsed.TotalSeconds -ge $Deadline) { throw 'Verification deadline exceeded.' }
        $started = $process.Start()
        if (-not $started) { throw 'Could not start Docker client.' }
        Write-Host "Docker client PID=$($process.Id); container=$container"
        $stdout = $process.StandardOutput.ReadLineAsync()
        $stderr = $process.StandardError.ReadLineAsync()
        while ($null -ne $stdout -or $null -ne $stderr -or -not $process.HasExited) {
            if ($watch.Elapsed.TotalSeconds -ge $Deadline) { throw 'Verification deadline exceeded.' }
            foreach ($stream in @('stdout', 'stderr')) {
                $task = Get-Variable -Name $stream -ValueOnly
                if ($null -eq $task -or -not $task.IsCompleted) { continue }
                $line = $task.GetAwaiter().GetResult()
                if ($null -eq $line) { Set-Variable -Name $stream -Value $null; continue }
                Write-Host $line
                Add-Content -LiteralPath $logFile -Value $line -Encoding utf8
                if ($capture.Length -lt 4096) { [void]$capture.AppendLine($line) }
                $next = if ($stream -eq 'stdout') { $process.StandardOutput.ReadLineAsync() } else { $process.StandardError.ReadLineAsync() }
                Set-Variable -Name $stream -Value $next
            }
            Start-Sleep -Milliseconds 50
        }
        return [PSCustomObject]@{ ExitCode = $process.ExitCode; Output = $capture.ToString() }
    }
    finally {
        if ($started -and -not $process.HasExited) {
            $process.Kill($true)
            if (-not $process.WaitForExit(5000)) { throw 'Owned Docker client did not exit.' }
        }
        $process.Dispose()
    }
}

try {
    $cFree = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
    $dFree = [math]::Round((Get-PSDrive D).Free / 1GB, 2)
    Write-Host "C free=$cFree GiB; D free=$dFree GiB; timeout=$TimeoutSeconds seconds; log=$logFile"
    $inspect = Invoke-DockerBounded -Arguments @('image', 'inspect', $Image, '--format', '{{index .RepoDigests 0}}')
    if ($inspect.ExitCode -ne 0) {
        if ($cFree -lt 2) { throw 'Insufficient C space for a small runtime pull.' }
        $pull = Invoke-DockerBounded -Arguments @('pull', $Image)
        if ($pull.ExitCode -ne 0) { throw 'Python runtime pull failed.' }
    }
    $script = @'
set -e
cd /w
python -m venv /runtime/venv
V=/runtime/venv/bin
$V/python -m pip install --disable-pip-version-check --cache-dir /out/cache/pip -r requirements-dev.txt
PKG=$(mktemp -d /runtime/pkg-src-XXXXXX)
trap 'rm -rf "$PKG"' EXIT
tar --exclude='__pycache__' --exclude='*.pyc' -cf - src pyproject.toml README.md | tar -xf - -C "$PKG"
$V/python -m pip install --disable-pip-version-check --no-build-isolation --no-deps --cache-dir /out/cache/pip "$PKG"
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
    # Git may check this PowerShell file out with CRLF on Windows. sh requires LF.
    $script = $script.Replace("`r`n", "`n")
    $run = Invoke-DockerBounded -Arguments @('run', '--name', $container, '--cpus', '2', '--memory', '2g', '--pids-limit', '256', '--tmpfs', '/runtime:rw,exec,size=256m', '-v', ($dockerRepo + ':/w:ro'), '-v', ($dockerOut + ':/out'), '-w', '/w', $Image, 'sh', '-lc', $script)
    $exit = $run.ExitCode
}
catch { Write-Host "ERROR: $($_.Exception.Message)"; $exit = 1 }
finally {
    $cleanupDeadline = $watch.Elapsed.TotalSeconds + 15
    try {
        $owned = Invoke-DockerBounded -Arguments @('container', 'inspect', $container, '--format', '{{.Id}}') -Deadline $cleanupDeadline
        if ($owned.ExitCode -eq 0) {
            $removed = Invoke-DockerBounded -Arguments @('rm', '-f', $container) -Deadline $cleanupDeadline
            if ($removed.ExitCode -ne 0) { throw 'Owned container cleanup failed.' }
        }
        elseif ($owned.Output -notmatch 'No such (object|container)') { throw 'Could not verify container cleanup.' }
    }
    catch { Write-Host "CLEANUP ERROR: $($_.Exception.Message)"; $exit = 1 }
    Write-Host "Elapsed=$([math]::Round($watch.Elapsed.TotalSeconds,1))s; exit=$exit; log=$logFile"
}
exit $exit
