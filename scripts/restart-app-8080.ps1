param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$TmpDir = Join-Path $RepoRoot "tmp"
$OutLog = Join-Path $TmpDir "bootRun-$Port.log"
$ErrLog = Join-Path $TmpDir "bootRun-$Port.err.log"
$Gradle = Join-Path $RepoRoot "gradlew.bat"
$Runner = Join-Path $RepoRoot "build\run-boot-$Port.cmd"
$PidFile = Join-Path $RepoRoot "build\bootrun-$Port.pid"

function Get-ListeningPid {
    param([int]$TargetPort)

    $lines = netstat -ano | Select-String ":$TargetPort"
    foreach ($line in $lines) {
        $parts = ($line.ToString().Trim() -split "\s+")
        if ($parts.Length -ge 5 -and $parts[1] -match ":$TargetPort$" -and $parts[3] -eq "LISTENING") {
            return [int]$parts[-1]
        }
    }

    return $null
}

Write-Host "Restarting ProcessM Interpreter on port $Port..."

$runnerPid = if (Test-Path $PidFile) {
    [int](Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
} else {
    $null
}
if ($runnerPid) {
    $runnerProcess = Get-Process -Id $runnerPid -ErrorAction SilentlyContinue
    if ($runnerProcess) {
        Write-Host "Stopping previous bootRun process tree, PID $runnerPid..."
        taskkill.exe /PID $runnerPid /T /F 2>$null | Out-Null
    } else {
        Write-Host "Ignoring stale bootRun PID file for missing PID $runnerPid."
    }
    Remove-Item -LiteralPath $PidFile -ErrorAction SilentlyContinue
}

$pidToStop = Get-ListeningPid -TargetPort $Port
if ($pidToStop) {
    Write-Host "Stopping process on port $Port, PID $pidToStop..."
    taskkill.exe /PID $pidToStop /F 2>$null | Out-Null

    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if (-not (Get-ListeningPid -TargetPort $Port)) {
            break
        }
    }
} else {
    Write-Host "No process is listening on port $Port."
}

Remove-Item -LiteralPath $OutLog, $ErrLog -ErrorAction SilentlyContinue

Write-Host "Starting Gradle bootRun..."
Write-Host "stdout: $OutLog"
Write-Host "stderr: $ErrLog"

New-Item -ItemType Directory -Path $TmpDir -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path $Runner -Parent) -Force | Out-Null
@"
@echo off
cd /d "$RepoRoot"
call "$Gradle" --no-daemon bootRun > "$OutLog" 2> "$ErrLog"
"@ | Set-Content -LiteralPath $Runner -Encoding ASCII

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = "$env:ComSpec"
$psi.Arguments = "/c `"`"$Runner`"`""
$psi.WorkingDirectory = $RepoRoot
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$process = [System.Diagnostics.Process]::Start($psi)
$process.Id | Set-Content -LiteralPath $PidFile -Encoding ASCII

Write-Host "Waiting for http://localhost:$Port ..."
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 1
    if (Get-ListeningPid -TargetPort $Port) {
        Write-Host "App is listening on http://localhost:$Port"
        exit 0
    }
}

Write-Warning "App did not start listening on port $Port within 60 seconds."
Write-Warning "Check logs:"
Write-Warning "  $OutLog"
Write-Warning "  $ErrLog"
if (Test-Path $ErrLog) {
    Write-Warning "stderr tail:"
    Get-Content -LiteralPath $ErrLog -Tail 40 | ForEach-Object { Write-Warning "  $_" }
}
if (Test-Path $OutLog) {
    Write-Warning "stdout tail:"
    Get-Content -LiteralPath $OutLog -Tail 80 | ForEach-Object { Write-Warning "  $_" }
}
exit 1
