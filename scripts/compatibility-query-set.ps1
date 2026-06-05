function Get-ProcessMCompatibilityQueries {
    param(
        [ValidateSet("quick", "extended")]
        [string]$Profile = "quick"
    )

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

    if ($Profile -eq "quick") {
        return $quickQueries
    }

    return $quickQueries + @(
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
}

function Get-ProcessMMultiLogCompatibilityQueries {
    return @(
        [pscustomobject]@{
            Label = "multiLogAllAttachedLogs"
            Query = "select l:name limit l:10"
            Comparison = "strict"
            Source = "multi-log"
        },
        [pscustomobject]@{
            Label = "multiLogHierarchyWindow"
            Query = "select l:name, t:name, e:name limit l:10, t:3, e:3"
            Comparison = "strict"
            Source = "multi-log"
        },
        [pscustomobject]@{
            Label = "multiLogPrimaryWhere"
            Query = "select l:name, t:name, e:name where l:name='{PrimaryLogName}' limit l:10, t:3, e:3"
            Comparison = "strict"
            Source = "multi-log"
        },
        [pscustomobject]@{
            Label = "multiLogSecondaryWhere"
            Query = "select l:name, t:name, e:name where l:name='{SecondaryLogName}' limit l:10, t:3, e:3"
            Comparison = "strict"
            Source = "multi-log"
        },
        [pscustomobject]@{
            Label = "multiLogEventWhereAcrossLogs"
            Query = "select l:name, t:name, e:name where e:name='ER Registration' or e:name='invite reviewers' limit l:10, t:3, e:3"
            Comparison = "strict"
            Source = "multi-log"
        },
        [pscustomobject]@{
            Label = "multiLogImplicitGroupFromSelect"
            Query = "select l:*, t:*, avg(e:total), min(e:timestamp), max(e:timestamp) limit l:1"
            Comparison = "strict"
            Source = "multi-log"
        }
    )
}

function Get-ProcessMCompareDropdownQueries {
    param(
        [string]$IndexHtmlPath = (Join-Path (Split-Path -Parent $PSScriptRoot) "src\main\resources\static\index.html")
    )

    if (-not (Test-Path -LiteralPath $IndexHtmlPath)) {
        throw "Missing index.html file: $IndexHtmlPath"
    }

    $html = Get-Content -LiteralPath $IndexHtmlPath -Raw
    $selectMatch = [regex]::Match(
        $html,
        '(?is)<select\b[^>]*\bid\s*=\s*["'']sampleQueriesSelect["''][^>]*>(?<body>.*?)</select>'
    )
    if (-not $selectMatch.Success) {
        throw "Could not find compare sample query dropdown: #sampleQueriesSelect"
    }

    $currentGroup = ""
    $queries = @()
    $optionIndex = 0
    foreach ($line in ($selectMatch.Groups["body"].Value -split "`r?`n")) {
        $groupMatch = [regex]::Match($line, '<optgroup\b[^>]*\blabel\s*=\s*"(?<label>[^"]*)"')
        if ($groupMatch.Success) {
            $currentGroup = [System.Net.WebUtility]::HtmlDecode($groupMatch.Groups["label"].Value).Trim()
            continue
        }

        $optionMatch = [regex]::Match($line, '<option\b[^>]*\bvalue\s*=\s*"(?<value>[^"]*)"[^>]*>(?<label>.*?)</option>')
        if (-not $optionMatch.Success) {
            continue
        }

        $query = [System.Net.WebUtility]::HtmlDecode($optionMatch.Groups["value"].Value).Trim()
        if ([string]::IsNullOrWhiteSpace($query)) {
            continue
        }

        $optionIndex++
        $label = [regex]::Replace($optionMatch.Groups["label"].Value, '<[^>]+>', '')
        $label = [System.Net.WebUtility]::HtmlDecode($label).Trim()
        if ([string]::IsNullOrWhiteSpace($label)) {
            $label = "dropdownQuery$optionIndex"
        }

        $queries += [pscustomobject]@{
            Label = $label
            Query = $query
            Comparison = "strict"
            Group = $currentGroup
            Source = "dropdown"
        }
    }

    if ($queries.Count -eq 0) {
        throw "Compare sample query dropdown does not contain executable queries."
    }

    return $queries
}
