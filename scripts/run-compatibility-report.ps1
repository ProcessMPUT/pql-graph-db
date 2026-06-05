param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$CasesPath = (Join-Path $PSScriptRoot "verify-compatibility.cases.local.json"),
    [ValidateSet("dropdown", "matrix")]
    [string]$QuerySource = "dropdown",
    [ValidateSet("quick", "extended")]
    [string]$Profile = "extended",
    [string]$IndexHtmlPath = (Join-Path (Split-Path -Parent $PSScriptRoot) "src\main\resources\static\index.html"),
    [string]$OutputRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) "tmp\compatibility-reports"),
    [int]$TimeoutSec = 420,
    [switch]$SaveFullSnapshots,
    [switch]$SkipFailureSnapshots,
    [switch]$IncludeMultiLogChecks,
    [string]$MultiLogCasesPath = (Join-Path $PSScriptRoot "verify-compatibility.multi-log.cases.local.json")
)

$ErrorActionPreference = "Stop"

$querySetPath = Join-Path $PSScriptRoot "compatibility-query-set.ps1"
if (-not (Test-Path -LiteralPath $querySetPath)) {
    throw "Missing compatibility query set: $querySetPath"
}
. $querySetPath

if (-not (Test-Path -LiteralPath $CasesPath)) {
    throw "Missing compatibility cases file: $CasesPath"
}

$cases = @(Get-Content -LiteralPath $CasesPath -Raw | ConvertFrom-Json | ForEach-Object { $_ })
if ($cases.Count -eq 0) {
    throw "Compatibility cases file is empty: $CasesPath"
}

$queries = if ($QuerySource -eq "dropdown") {
    @(Get-ProcessMCompareDropdownQueries -IndexHtmlPath $IndexHtmlPath)
} else {
    @(Get-ProcessMCompatibilityQueries -Profile $Profile)
}

$multiLogCases = @()
$multiLogQueries = @()
if ($IncludeMultiLogChecks) {
    if (-not (Test-Path -LiteralPath $MultiLogCasesPath)) {
        throw "Missing multi-log compatibility cases file: $MultiLogCasesPath"
    }

    $multiLogCases = @(Get-Content -LiteralPath $MultiLogCasesPath -Raw | ConvertFrom-Json | ForEach-Object { $_ })
    if ($multiLogCases.Count -eq 0) {
        throw "Multi-log compatibility cases file is empty: $MultiLogCasesPath"
    }

    $multiLogQueries = @(Get-ProcessMMultiLogCompatibilityQueries)
}
$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDirectory = Join-Path $OutputRoot $runId
$failureDirectory = Join-Path $outputDirectory "snapshots"
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

function Test-QueryAppliesToCase {
    param(
        [pscustomobject]$Case,
        [pscustomobject]$Query
    )

    $casesProperty = $Query.PSObject.Properties["Cases"]
    if ($null -eq $casesProperty) {
        return $true
    }

    return $Case.name -in @($casesProperty.Value)
}

function Get-FirstComparisonLine {
    param([string]$Details)

    if ([string]::IsNullOrWhiteSpace($Details)) {
        return ""
    }

    $lines = $Details -split "`r?`n"
    return ($lines | Where-Object { $_ -like "Comparison:*" } | Select-Object -First 1)
}

function Get-PropertyValue {
    param(
        [object]$Value,
        [string]$Name
    )

    if ($null -eq $Value) {
        return $null
    }

    $property = $Value.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Format-PqlStringLiteralContent {
    param([string]$Value)

    if ($null -eq $Value) {
        return ""
    }

    return $Value.Replace("'", "''")
}

function Resolve-QueryForCase {
    param(
        [pscustomobject]$Case,
        [pscustomobject]$Query
    )

    $resolved = $Query.Query
    foreach ($placeholder in @("PrimaryLogName", "SecondaryLogName")) {
        $token = "{$placeholder}"
        if (-not $resolved.Contains($token)) {
            continue
        }

        $propertyName = $placeholder.Substring(0, 1).ToLowerInvariant() + $placeholder.Substring(1)
        $value = Get-PropertyValue -Value $Case -Name $propertyName
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "Multi-log case '$($Case.name)' must define '$propertyName' for query '$($Query.Label)'."
        }

        $resolved = $resolved.Replace($token, (Format-PqlStringLiteralContent -Value $value))
    }

    if ($resolved -match "\{[A-Za-z0-9_]+\}") {
        throw "Unresolved query placeholder in '$($Query.Label)': $resolved"
    }

    $copy = $Query.PSObject.Copy()
    $copy.Query = $resolved
    return $copy
}

function New-SafeFileName {
    param([string]$Value)

    $safe = ($Value -replace "[^a-zA-Z0-9._-]+", "_").Trim("_")
    if ([string]::IsNullOrWhiteSpace($safe)) {
        return "value"
    }

    return $safe
}

function Format-MarkdownTableCell {
    param([object]$Value)

    if ($null -eq $Value) {
        return ""
    }

    $text = $Value.ToString().Replace("|", "\|")
    $text = $text -replace "`r?`n", " "
    $text = $text -replace "\s+", " "
    return $text.Trim()
}

function Invoke-VerifyEndpoint {
    param(
        [hashtable]$Request,
        [string]$Format
    )

    $body = $Request | ConvertTo-Json -Depth 8
    return Invoke-RestMethod `
        -Uri "$BaseUrl/api/query/verify?format=$Format" `
        -Method Post `
        -ContentType "application/json" `
        -Body $body `
        -TimeoutSec $TimeoutSec
}

function Save-FullSnapshot {
    param(
        [pscustomobject]$Case,
        [pscustomobject]$Query,
        [hashtable]$Request
    )

    New-Item -ItemType Directory -Force -Path $failureDirectory | Out-Null
    $fileName = "{0}__{1}.json" -f (New-SafeFileName -Value $Case.name), (New-SafeFileName -Value $Query.Label)
    $path = Join-Path $failureDirectory $fileName

    try {
        $snapshot = Invoke-VerifyEndpoint -Request $Request -Format "full"
        $snapshot | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $path -Encoding UTF8
        return $path
    } catch {
        $errorPath = [System.IO.Path]::ChangeExtension($path, ".error.txt")
        $_.Exception.Message | Set-Content -LiteralPath $errorPath -Encoding UTF8
        return $errorPath
    }
}

function Invoke-CompatibilityCheck {
    param(
        [pscustomobject]$Case,
        [pscustomobject]$Query
    )

    $request = @{
        query = $Query.Query
        dataStoreId = $Case.localDataStoreId
        remoteDataStoreId = $Case.remoteDataStoreId
        includeTraces = $true
        includeEvents = $true
    }

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $snapshotPath = ""

    try {
        $response = Invoke-VerifyEndpoint -Request $request -Format "light"
        $stopwatch.Stop()

        $status = if ($response.comparisonStatus -eq "NONDETERMINISTIC_MATCH") {
            "INFO"
        } elseif ($response.match) {
            "MATCH"
        } elseif (-not $response.localSuccess -or -not $response.remoteSuccess) {
            "ERROR"
        } elseif ($Query.Comparison -eq "informational") {
            "INFO"
        } else {
            "MISMATCH"
        }

        if ($SaveFullSnapshots -or (($status -in @("MISMATCH", "ERROR")) -and -not $SkipFailureSnapshots)) {
            $snapshotPath = Save-FullSnapshot -Case $Case -Query $Query -Request $request
        }

        return [pscustomobject]@{
            Log = $Case.name
            LocalDataStoreId = $Case.localDataStoreId
            RemoteDataStoreId = $Case.remoteDataStoreId
            Query = $Query.Label
            QueryText = $Query.Query
            Comparison = $Query.Comparison
            Group = Get-PropertyValue -Value $Query -Name "Group"
            Source = Get-PropertyValue -Value $Query -Name "Source"
            Status = $status
            Seconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 2)
            Details = Get-FirstComparisonLine -Details $response.details
            LocalSuccess = Get-PropertyValue -Value $response -Name "localSuccess"
            RemoteSuccess = Get-PropertyValue -Value $response -Name "remoteSuccess"
            Match = Get-PropertyValue -Value $response -Name "match"
            SnapshotPath = $snapshotPath
        }
    } catch {
        $stopwatch.Stop()
        if (-not $SkipFailureSnapshots) {
            $snapshotPath = Save-FullSnapshot -Case $Case -Query $Query -Request $request
        }

        return [pscustomobject]@{
            Log = $Case.name
            LocalDataStoreId = $Case.localDataStoreId
            RemoteDataStoreId = $Case.remoteDataStoreId
            Query = $Query.Label
            QueryText = $Query.Query
            Comparison = $Query.Comparison
            Group = Get-PropertyValue -Value $Query -Name "Group"
            Source = Get-PropertyValue -Value $Query -Name "Source"
            Status = "ERROR"
            Seconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 2)
            Details = $_.Exception.Message
            LocalSuccess = $false
            RemoteSuccess = $false
            Match = $false
            SnapshotPath = $snapshotPath
        }
    }
}

$effectiveChecks = @()
foreach ($case in $cases) {
    foreach ($query in $queries) {
        if (Test-QueryAppliesToCase -Case $case -Query $query) {
            $effectiveChecks += [pscustomobject]@{
                Case = $case
                Query = $query
            }
        }
    }
}

foreach ($case in $multiLogCases) {
    foreach ($query in $multiLogQueries) {
        $effectiveChecks += [pscustomobject]@{
            Case = $case
            Query = (Resolve-QueryForCase -Case $case -Query $query)
        }
    }
}

$results = @()
for ($index = 0; $index -lt $effectiveChecks.Count; $index++) {
    $check = $effectiveChecks[$index]
    $activity = "ProcessM compatibility report"
    $status = "{0}/{1}: {2} / {3}" -f ($index + 1), $effectiveChecks.Count, $check.Case.name, $check.Query.Label
    Write-Progress -Activity $activity -Status $status -PercentComplete ((($index + 1) / $effectiveChecks.Count) * 100)
    Write-Host ("[{0}/{1}] {2} :: {3}" -f ($index + 1), $effectiveChecks.Count, $check.Case.name, $check.Query.Label)
    $results += Invoke-CompatibilityCheck -Case $check.Case -Query $check.Query
}
Write-Progress -Activity "ProcessM compatibility report" -Completed

$strictProblems = @($results | Where-Object { $_.Status -in @("MISMATCH", "ERROR") })
$informational = @($results | Where-Object { $_.Status -eq "INFO" })
$matches = @($results | Where-Object { $_.Status -eq "MATCH" })
$accepted = @($results | Where-Object { $_.Status -in @("MATCH", "INFO") })

$jsonPath = Join-Path $outputDirectory "results.json"
$csvPath = Join-Path $outputDirectory "results.csv"
$markdownPath = Join-Path $outputDirectory "summary.md"

$results | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
$results | Export-Csv -LiteralPath $csvPath -NoTypeInformation -Encoding UTF8

$markdown = @()
$markdown += "# ProcessM Compatibility Report"
$markdown += ""
$markdown += "- Generated: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz")"
$markdown += "- Base URL: $BaseUrl"
$markdown += "- Query source: $QuerySource"
if ($QuerySource -eq "dropdown") {
    $markdown += "- Dropdown source file: $IndexHtmlPath"
} else {
    $markdown += "- Matrix profile: $Profile"
}
$markdown += "- Cases file: $CasesPath"
$markdown += "- Logs: $($cases.Count)"
if ($IncludeMultiLogChecks) {
    $markdown += "- Multi-log cases file: $MultiLogCasesPath"
    $markdown += "- Multi-log cases: $($multiLogCases.Count)"
    $markdown += "- Multi-log queries: $($multiLogQueries.Count)"
} else {
    $markdown += "- Multi-log checks: disabled"
}
$markdown += "- Checks: $($results.Count)"
$markdown += "- Matches: $($matches.Count)"
$markdown += "- Accepted compatibility checks: $($accepted.Count)"
$markdown += "- Strict problems: $($strictProblems.Count)"
$markdown += "- Informational mismatches: $($informational.Count)"
$markdown += ""

if ($strictProblems.Count -eq 0) {
    $markdown += "Result: all strict compatibility checks matched ProcessM."
} else {
    $markdown += "Result: strict compatibility problems were found."
}

$markdown += ""
$markdown += "## Data stores"
$markdown += ""
$markdown += "| Log | Local datastore | Remote datastore |"
$markdown += "|---|---|---|"
foreach ($case in $cases) {
    $markdown += "| {0} | {1} | {2} |" -f `
        (Format-MarkdownTableCell $case.name), `
        (Format-MarkdownTableCell $case.localDataStoreId), `
        (Format-MarkdownTableCell $case.remoteDataStoreId)
}

$markdown += ""
$markdown += "## Queries"
$markdown += ""
$markdown += "| Group | Label | Query | Comparison | Cases |"
$markdown += "|---|---|---|---|---|"
foreach ($query in $queries) {
    $casesProperty = $query.PSObject.Properties["Cases"]
    $caseScope = if ($null -eq $casesProperty) { "all" } else { (@($casesProperty.Value) -join ", ") }
    $markdown += "| {0} | {1} | ``{2}`` | {3} | {4} |" -f `
        (Format-MarkdownTableCell (Get-PropertyValue -Value $query -Name "Group")), `
        (Format-MarkdownTableCell $query.Label), `
        (Format-MarkdownTableCell $query.Query), `
        (Format-MarkdownTableCell $query.Comparison), `
        (Format-MarkdownTableCell $caseScope)
}

$markdown += ""
$markdown += "## Results"
$markdown += ""
$markdown += "| Log | Query | Status | Seconds | Details | Snapshot |"
$markdown += "|---|---|---:|---:|---|---|"
foreach ($result in $results) {
    $snapshot = if ([string]::IsNullOrWhiteSpace($result.SnapshotPath)) { "" } else { $result.SnapshotPath }
    $markdown += "| {0} | {1} | {2} | {3} | {4} | {5} |" -f `
        (Format-MarkdownTableCell $result.Log), `
        (Format-MarkdownTableCell $result.Query), `
        (Format-MarkdownTableCell $result.Status), `
        (Format-MarkdownTableCell $result.Seconds), `
        (Format-MarkdownTableCell $result.Details), `
        (Format-MarkdownTableCell $snapshot)
}

$markdown | Set-Content -LiteralPath $markdownPath -Encoding UTF8

$results | Format-Table -AutoSize Log, Query, Status, Seconds, Details

Write-Host ""
Write-Host "Report written to:"
Write-Host "  $markdownPath"
Write-Host "  $csvPath"
Write-Host "  $jsonPath"
if (Test-Path -LiteralPath $failureDirectory) {
    Write-Host "  $failureDirectory"
}

Write-Host ""
Write-Host "Summary:"
Write-Host "  Query source: $QuerySource"
Write-Host "  Logs: $($cases.Count)"
if ($IncludeMultiLogChecks) {
    Write-Host "  Multi-log cases: $($multiLogCases.Count)"
    Write-Host "  Multi-log queries: $($multiLogQueries.Count)"
}
Write-Host "  Checks: $($results.Count)"
Write-Host "  Matches: $($matches.Count)"
Write-Host "  Accepted compatibility checks: $($accepted.Count)"
Write-Host "  Strict problems: $($strictProblems.Count)"
Write-Host "  Informational mismatches: $($informational.Count)"

if ($strictProblems.Count -gt 0) {
    exit 1
}
