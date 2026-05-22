param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
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

$runnerPid = if (Test-Path $PidFile) {
    [int](Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
} else {
    $null
}
if ($runnerPid) {
    Write-Host "Stopping bootRun process tree, PID $runnerPid..."
    taskkill.exe /PID $runnerPid /T /F 2>$null | Out-Null
    Remove-Item -LiteralPath $PidFile -ErrorAction SilentlyContinue
}

$pidToStop = Get-ListeningPid -TargetPort $Port
if (-not $pidToStop) {
    Write-Host "No app is listening on port $Port."
    exit 0
}

Write-Host "Stopping app on port $Port, PID $pidToStop..."
taskkill.exe /PID $pidToStop /F 2>$null | Out-Null

for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 500
    if (-not (Get-ListeningPid -TargetPort $Port)) {
        Write-Host "App on port $Port stopped."
        exit 0
    }
}

Write-Warning "App on port $Port did not stop within 10 seconds."
exit 1
