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

$quickQueries = @(
    [pscustomobject]@{
        Label = "limitAll"
        Query = "limit e:3, t:2, l:1"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "limitZero"
        Query = "limit e:0"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "traceFilterPreservesEvents"
        Query = "where t:name is not null limit l:1, t:1"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "allStdAttributes"
        Query = "select l:name, t:name, e:name, e:timestamp limit l:1, t:2, e:3"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "whereIsNotNull"
        Query = "where e:name is not null limit l:1, t:2, e:3"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "selectAggregation"
        Query = "select min(e:timestamp), max(e:timestamp)"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "groupEventOrder"
        Query = "group by e:name order by e:name limit l:1, t:2, e:5"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "orderByExpression"
        Query = "select min(timestamp) group by ^e:name order by min(^e:timestamp)"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "timestampThenNameOrderWindow"
        Query = "order by e:timestamp, e:name limit l:1, t:3, e:5"
        Comparison = "strict"
    }
)

$extendedQueries = $quickQueries + @(
    [pscustomobject]@{
        Label = "limitSingle"
        Query = "limit l:1"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "limitPerTrace"
        Query = "limit l:1, t:2, e:3"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "multiScopeImplicitGroupBy"
        Query = "select count(l:name), count(^t:name), count(^^e:name)"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "groupByOuterScope"
        Query = "select t:min(l:name) limit l:3"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "groupImplicitFromSelect"
        Query = "select l:*, t:*, avg(e:total), min(e:timestamp), max(e:timestamp) limit l:1"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "selectNonStdAttrs"
        Query = "select [e:result], [e:time:timestamp], [e:concept:name] limit l:1, t:5, e:10"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "nonexistentCustomAttrs1"
        Query = "select [e:nonexistent_attribute_xyz] limit l:1, t:1, e:1"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "nonexistentCustomAttrs2"
        Query = "select [e:result] limit l:1, t:5, e:10"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "groupByImplicitScope"
        Query = "group by c:Resource"
        Comparison = "strict"
        Cases = @("teleclaims", "JournalReview")
    },
    [pscustomobject]@{
        Label = "whereDateComparison"
        Query = "where e:timestamp >= D2007-01-01 limit l:1, t:5, e:10"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "orderByMultiScope"
        Query = "order by e:timestamp, e:name, e:transition, t:total desc, t:name limit l:1, t:3, e:10"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "selectNow"
        Query = "select l:now() limit l:1"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "mixedTraceAndHoistedGroupBy"
        Query = "select l:name, count(t:name), e:name group by t:name, ^e:name order by count(t:name) desc, t:name limit l:1"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "hoistedVariantTop3WithEvents"
        Query = "select l:name, count(t:name), e:name group by ^e:name order by count(t:name) desc limit l:1, t:3"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "hoistedVariantTop3TraceCountOnly"
        Query = "select count(t:name) group by ^e:name order by count(t:name) desc limit l:1, t:3"
        Comparison = "strict"
    },
    [pscustomobject]@{
        Label = "hoistedVariantTop3TraceAndEventCount"
        Query = "select count(t:name), count(^e:name) group by ^e:name order by count(t:name) desc limit l:1, t:3"
        Comparison = "strict"
    }
)

$queries = if ($Profile -eq "extended") { $extendedQueries } else { $quickQueries }

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
            -TimeoutSec 180
        $stopwatch.Stop()

        $status = if ($response.match) {
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

Write-Host ""
Write-Host "Summary:"
Write-Host "  Cases: $(@($cases).Count)"
Write-Host "  Checks: $(@($results).Count)"
Write-Host "  Strict problems: $($strictProblems.Count)"
Write-Host "  Informational mismatches: $($informational.Count)"

if ($strictProblems.Count -gt 0) {
    Write-Host ""
    Write-Host "Strict problems:"
    $strictProblems | Format-Table -AutoSize
    exit 1
}
