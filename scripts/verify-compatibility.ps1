param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$CasesPath = (Join-Path $PSScriptRoot "verify-compatibility.cases.local.json"),
    [ValidateSet("quick", "extended")]
    [string]$Profile = "quick"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $CasesPath)) {
    throw "Missing compatibility cases file: $CasesPath"
}

$cases = Get-Content -LiteralPath $CasesPath -Raw | ConvertFrom-Json
if (-not $cases) {
    throw "Compatibility cases file is empty: $CasesPath"
}

$querySetPath = Join-Path $PSScriptRoot "compatibility-query-set.ps1"
if (-not (Test-Path -LiteralPath $querySetPath)) {
    throw "Missing compatibility query set: $querySetPath"
}
. $querySetPath

$queries = @(Get-ProcessMCompatibilityQueries -Profile $Profile)

function Get-FirstComparisonLine {
    param([string]$Details)

    $lines = $Details -split "`r?`n"
    return ($lines | Where-Object { $_ -like "Comparison:*" } | Select-Object -First 1)
}

function Invoke-CompatibilityCheck {
    param(
        [pscustomobject]$Case,
        [pscustomobject]$Query
    )

    $body = @{
        query = $Query.Query
        dataStoreId = $Case.localDataStoreId
        remoteDataStoreId = $Case.remoteDataStoreId
        includeTraces = $true
        includeEvents = $true
    } | ConvertTo-Json -Depth 6

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-RestMethod `
            -Uri "$BaseUrl/api/query/verify?format=light" `
            -Method Post `
            -ContentType "application/json" `
            -Body $body `
            -TimeoutSec 420
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

        [pscustomobject]@{
            Log = $Case.name
            Query = $Query.Label
            Status = $status
            Seconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 2)
            Details = Get-FirstComparisonLine -Details $response.details
        }
    } catch {
        $stopwatch.Stop()
        [pscustomobject]@{
            Log = $Case.name
            Query = $Query.Label
            Status = "ERROR"
            Seconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 2)
            Details = $_.Exception.Message
        }
    }
}

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

$results = foreach ($case in $cases) {
    foreach ($query in $queries) {
        if (Test-QueryAppliesToCase -Case $case -Query $query) {
            Invoke-CompatibilityCheck -Case $case -Query $query
        }
    }
}

$results | Format-Table -AutoSize

$strictProblems = @($results | Where-Object { $_.Status -in @("MISMATCH", "ERROR") })
$informational = @($results | Where-Object { $_.Status -eq "INFO" })
$matches = @($results | Where-Object { $_.Status -eq "MATCH" })
$accepted = @($results | Where-Object { $_.Status -in @("MATCH", "INFO") })

Write-Host ""
Write-Host "Summary:"
Write-Host "  Cases: $(@($cases).Count)"
Write-Host "  Checks: $(@($results).Count)"
Write-Host "  Matches: $($matches.Count)"
Write-Host "  Accepted compatibility checks: $($accepted.Count)"
Write-Host "  Strict problems: $($strictProblems.Count)"
Write-Host "  Informational mismatches: $($informational.Count)"

if ($strictProblems.Count -gt 0) {
    Write-Host ""
    Write-Host "Strict problems:"
    $strictProblems | Format-Table -AutoSize
    exit 1
}
