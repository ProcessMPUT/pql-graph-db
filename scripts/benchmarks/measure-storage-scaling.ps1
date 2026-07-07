# Sequential storage-scaling probe (thesis question 5).
#
# Imports the synthetic scaling datasets one by one into BOTH systems WITHOUT
# deleting anything in between, checkpointing and measuring the database size
# after each import. Because neither store ever shrinks mid-run, successive
# deltas are attributable per dataset — this sidesteps the page-reuse effect
# that makes per-dataset deltas unmeasurable in the benchmark's
# import-measure-cleanup protocol (METODOLOGIA §Q3).
#
# MUST run on a fresh stack (docker-compose down -v && up -d, app restarted,
# no imports or deletions since start) or the first deltas absorb reused pages.
param(
    [string]$DatasetsDir = "",
    [string]$OutCsv = "tmp\storage-scaling.csv",
    [string]$LocalApi = "http://localhost:8080/api",
    [string]$ReferenceApi = "http://localhost:80/api",
    [string]$ProcessMLogin = "admin@example.com",
    [string]$ProcessMPassword = "Admin1234"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")

if (-not $DatasetsDir) {
    $latestRun = Get-ChildItem (Join-Path $RepoRoot "tmp\benchmark-results") -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName "generated-datasets") } |
        Sort-Object Name | Select-Object -Last 1
    if (-not $latestRun) { throw "No generated-datasets found; pass -DatasetsDir." }
    $DatasetsDir = Join-Path $latestRun.FullName "generated-datasets"
}
Write-Host "Datasets: $DatasetsDir"

$datasets = @(
    "trace-100", "trace-500", "trace-2000", "trace-10000",
    "event-5", "event-10", "event-50", "event-200",
    "attr-1", "attr-5", "attr-20"
)

# Neo4j 5.26 community exposes NO manual checkpoint procedure, so the only
# reliable flush is a clean shutdown (checkpoint on stop). Sizes count the
# store files only (/data/databases); transaction logs are the WAL equivalent
# and are excluded on both sides so the expansion factor compares durable data.
function Measure-LocalBytes {
    docker restart processm-neo4j | Out-Null
    $deadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 3
        $status = docker ps --filter "name=processm-neo4j" --format "{{.Status}}"
    } while ((Get-Date) -lt $deadline -and $status -notmatch '\(healthy\)')
    if ($status -notmatch '\(healthy\)') { throw "neo4j did not become healthy after restart" }
    $cmd = 'total=0; for f in $(find /data/databases -type f 2>/dev/null); do size=$(stat -c %s "$f" 2>/dev/null || echo 0); total=$((total + size)); done; echo $total'
    $out = docker exec processm-neo4j sh -c $cmd
    return [long]($out | Select-Object -First 1)
}

# pg_database_size sums relation files (no WAL), so recycled 16 MiB WAL
# segments stop polluting per-dataset deltas; CHECKPOINT first flushes dirty
# shared buffers into those relation files.
function Measure-ReferenceBytes {
    docker exec processm-server sh -c "psql -U postgres -c 'CHECKPOINT;'" | Out-Null
    $out = docker exec processm-server sh -c "psql -U postgres -t -A -c 'SELECT sum(pg_database_size(datname)) FROM pg_database;'"
    return [long](($out | Select-Object -First 1).Trim())
}

$login = Invoke-RestMethod -Uri "$ReferenceApi/users/session" -Method Post -ContentType 'application/json' `
    -Body (@{login = $ProcessMLogin; password = $ProcessMPassword } | ConvertTo-Json)
$token = $login.authorizationToken

function Import-Dataset([string]$name, [string]$gzPath) {
    $localStore = (Invoke-RestMethod -Uri "$LocalApi/data-stores" -Method Post -ContentType 'application/json' `
        -Body (@{name = "storage-scaling-$name" } | ConvertTo-Json)).id
    $code = curl.exe -s -o NUL -w "%{http_code}" --max-time 900 -X POST "$LocalApi/data-stores/$localStore/logs" -F "file=@$gzPath"
    if ($code -ne '201') { throw "local import $name failed: HTTP $code" }

    $refStore = (Invoke-RestMethod -Uri "$ReferenceApi/data-stores" -Method Post -ContentType 'application/json' `
        -Headers @{Authorization = "Bearer $token" } -Body (@{name = "storage-scaling-$name" } | ConvertTo-Json)).id
    $code = curl.exe -s -o NUL -w "%{http_code}" --max-time 900 -X POST "$ReferenceApi/data-stores/$refStore/logs" `
        -H "Authorization: Bearer $token" -F "file=@$gzPath"
    if ($code -notmatch '^2') { throw "reference import $name failed: HTTP $code" }
}

$outPath = Join-Path $RepoRoot $OutCsv
"datasetName,system,beforeBytes,afterBytes,deltaBytes,xesBytes,xesGzBytes,deltaToXesRatio,deltaToGzipRatio" |
    Set-Content -LiteralPath $outPath -Encoding ASCII

$localBefore = Measure-LocalBytes
$refBefore = Measure-ReferenceBytes
Write-Host ("baseline  local={0:N0} B  reference={1:N0} B" -f $localBefore, $refBefore)

foreach ($name in $datasets) {
    $xes = Join-Path $DatasetsDir "$name.xes"
    $gz = Join-Path $DatasetsDir "$name.xes.gz"
    if (-not (Test-Path $gz)) { throw "missing $gz" }
    $xesBytes = (Get-Item $xes).Length
    $gzBytes = (Get-Item $gz).Length

    Import-Dataset $name $gz

    $localAfter = Measure-LocalBytes
    $refAfter = Measure-ReferenceBytes
    $localDelta = $localAfter - $localBefore
    $refDelta = $refAfter - $refBefore
    $inv = [System.Globalization.CultureInfo]::InvariantCulture
    "$name,local,$localBefore,$localAfter,$localDelta,$xesBytes,$gzBytes," +
        ($localDelta / $xesBytes).ToString('0.####', $inv) + "," + ($localDelta / $gzBytes).ToString('0.####', $inv) |
        Add-Content -LiteralPath $outPath -Encoding ASCII
    "$name,reference,$refBefore,$refAfter,$refDelta,$xesBytes,$gzBytes," +
        ($refDelta / $xesBytes).ToString('0.####', $inv) + "," + ($refDelta / $gzBytes).ToString('0.####', $inv) |
        Add-Content -LiteralPath $outPath -Encoding ASCII
    Write-Host ("{0,-12} local +{1,12:N0} B ({2:N2}x XES)   reference +{3,12:N0} B ({4:N2}x XES)" -f `
        $name, $localDelta, ($localDelta / $xesBytes), $refDelta, ($refDelta / $xesBytes))

    $localBefore = $localAfter
    $refBefore = $refAfter
}
Write-Host "Wrote $outPath"
