"""Version-controlled PQL query definitions for the compatibility report.

This is the query workload, not a place for orchestration: keep HTTP calls,
reporting and comparison logic in `run-compatibility-report.py`. Query text is
version controlled on purpose — a report is only comparable across runs when
the queries that produced it are.

`cases=None` means the query applies to every case in the cases file;
a tuple restricts it to the named cases.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from pathlib import Path
import html
import json
import re

from _common import ScriptError, repo_root


@dataclass(frozen=True)
class Query:
    label: str
    query: str
    comparison: str = "strict"
    group: str = ""
    source: str = ""
    cases: tuple[str, ...] | None = None
    minimum_logs: int = 0

    def applies_to(self, case_name: str) -> bool:
        return self.cases is None or case_name in self.cases

    def with_query(self, text: str) -> "Query":
        return replace(self, query=text)

    @property
    def case_scope(self) -> str:
        return "all" if self.cases is None else ", ".join(self.cases)


def compatibility_queries(profile: str = "quick") -> list[Query]:
    """The matrix workload. `extended` is `quick` plus the harder cases."""
    if profile not in ("quick", "extended"):
        raise ScriptError(f"unknown profile '{profile}'; expected quick or extended")

    quick = [
        Query("limitAll", "limit e:3, t:2, l:1"),
        Query("limitZero", "limit e:0"),
        Query("traceFilterPreservesEvents", "where t:name is not null limit l:1, t:1"),
        Query("allStdAttributes", "select l:name, t:name, e:name, e:timestamp limit l:1, t:2, e:3"),
        Query("whereIsNotNull", "where e:name is not null limit l:1, t:2, e:3"),
        Query("selectAggregation", "select min(e:timestamp), max(e:timestamp)"),
        Query("groupEventOrder", "group by e:name order by e:name limit l:1, t:2, e:5"),
        Query("orderByExpression", "select min(timestamp) group by ^e:name order by min(^e:timestamp)"),
        Query("timestampThenNameOrderWindow", "order by e:timestamp, e:name limit l:1, t:3, e:5"),
    ]
    if profile == "quick":
        return quick

    return quick + [
        Query("limitSingle", "limit l:1"),
        Query("limitPerTrace", "limit l:1, t:2, e:3"),
        Query("multiScopeImplicitGroupBy", "select count(l:name), count(^t:name), count(^^e:name)"),
        Query("groupByOuterScope", "select t:min(l:name) limit l:3"),
        Query(
            "groupImplicitFromSelect",
            "select l:*, t:*, avg(e:total), min(e:timestamp), max(e:timestamp) limit l:1",
        ),
        Query(
            "selectNonStdAttrs",
            "select [e:result], [e:time:timestamp], [e:concept:name] limit l:1, t:5, e:10",
        ),
        Query("nonexistentCustomAttrs1", "select [e:nonexistent_attribute_xyz] limit l:1, t:1, e:1"),
        Query("nonexistentCustomAttrs2", "select [e:result] limit l:1, t:5, e:10"),
        Query("groupByImplicitScope", "group by c:Resource", cases=("teleclaims", "JournalReview")),
        Query("whereDateComparison", "where e:timestamp >= D2007-01-01 limit l:1, t:5, e:10"),
        Query(
            "orderByMultiScope",
            "order by e:timestamp, e:name, e:transition, t:total desc, t:name limit l:1, t:3, e:10",
        ),
        Query("selectNow", "select l:now() limit l:1"),
        Query(
            "mixedTraceAndHoistedGroupBy",
            "select l:name, count(t:name), e:name group by t:name, ^e:name "
            "order by count(t:name) desc, t:name limit l:1",
        ),
        Query(
            "hoistedVariantTop3WithEvents",
            "select l:name, count(t:name), e:name group by ^e:name "
            "order by count(t:name) desc limit l:1, t:3",
        ),
        Query(
            "hoistedVariantTop3TraceCountOnly",
            "select count(t:name) group by ^e:name order by count(t:name) desc limit l:1, t:3",
        ),
        Query(
            "hoistedVariantTop3TraceAndEventCount",
            "select count(t:name), count(^e:name) group by ^e:name "
            "order by count(t:name) desc limit l:1, t:3",
        ),
    ]


def multi_log_compatibility_queries() -> list[Query]:
    source = "multi-log"
    return [
        Query("multiLogAllAttachedLogs", "select l:name limit l:10", source=source, minimum_logs=2),
        Query(
            "multiLogHierarchyWindow",
            "select l:name, t:name, e:name limit l:10, t:3, e:3",
            source=source, minimum_logs=2,
        ),
        Query(
            "multiLogPrimaryWhere",
            "select l:name, t:name, e:name where l:name='{PrimaryLogName}' limit l:10, t:3, e:3",
            source=source, minimum_logs=1,
        ),
        Query(
            "multiLogSecondaryWhere",
            "select l:name, t:name, e:name where l:name='{SecondaryLogName}' limit l:10, t:3, e:3",
            source=source, minimum_logs=1,
        ),
        Query(
            "multiLogEventWhereAcrossLogs",
            "select l:name, t:name, e:name where e:name='ER Registration' "
            "or e:name='invite reviewers' limit l:10, t:3, e:3",
            source=source, minimum_logs=2,
        ),
        Query(
            "multiLogImplicitGroupFromSelect",
            "select l:*, t:*, avg(e:total), min(e:timestamp), max(e:timestamp) limit l:1",
            source=source, minimum_logs=1,
        ),
    ]


def thesis_queries() -> list[Query]:
    """Frozen 69 named cases used in the thesis; independent of UI changes."""
    path = Path(__file__).with_name("thesis-compatibility-queries.json")
    definitions = json.loads(path.read_text(encoding="utf-8"))
    return [Query(source="thesis-v1", **definition) for definition in definitions]


def discovery_queries() -> list[Query]:
    source = "discovery"
    wildcard = "Discovery: wildcard reconstruction"
    metadata = "Discovery: log custom metadata"
    custom = "Discovery: custom attributes"
    grouping = "Discovery: grouping"
    custom_grouping = "Discovery: custom grouping"
    predicates = "Discovery: predicates"
    offsets = "Discovery: limit offset"

    return [
        Query(
            "wildcardFullHierarchySmallWindow",
            "select l:*, t:*, e:* limit l:1, t:2, e:3",
            group=wildcard,
            source=source,
        ),
        Query(
            "wildcardTraceEventWithoutLog",
            "select t:*, e:* limit l:1, t:3, e:2",
            group=wildcard,
            source=source,
        ),
        Query(
            "logMetadata3TuFields",
            "select [l:meta_3TU:language], [l:meta_3TU:log_type], [l:meta_3TU:process_type] limit l:1",
            group=metadata,
            source=source,
            cases=("Hospital_log", "Sepsis"),
        ),
        Query(
            "logTimingMetadataFields",
            "select [l:meta_time:log_start_time], [l:meta_time:log_end_time], "
            "[l:meta_time:duration_total] limit l:1",
            group=metadata,
            source=source,
            cases=("Hospital_log", "Sepsis"),
        ),
        Query(
            "teleclaimsCustomAttributes",
            "select [l:source], [l:description], [t:description], [e:call centre], "
            "[e:location] limit l:1, t:5, e:5",
            group=custom,
            source=source,
            cases=("teleclaims",),
        ),
        Query(
            "hospitalTraceCustomAttributes",
            "select [t:Specialism code], [t:Treatment code], [t:Start date], "
            "e:timestamp limit l:1, t:5, e:1",
            group=custom,
            source=source,
            cases=("Hospital_log",),
        ),
        Query(
            "journalReviewResultFilter",
            "select e:name, e:timestamp, [e:result] where [e:result] is not null "
            "order by e:timestamp, e:name limit l:1, t:10, e:10",
            group=custom,
            source=source,
            cases=("JournalReview",),
        ),
        Query(
            "sepsisClinicalTraceAttributes",
            "select [t:Age], [t:DisfuncOrg], [t:Hypoxie], [t:SIRSCriteria2OrMore] limit l:1, t:10, e:1",
            group=custom,
            source=source,
            cases=("Sepsis",),
        ),
        Query(
            "sepsisClinicalEventAttributes",
            "select e:name, [e:CRP], [e:Leucocytes], [e:LacticAcid] where [e:CRP] is not null "
            "or [e:Leucocytes] is not null or [e:LacticAcid] is not null limit l:1, t:10, e:10",
            group=custom,
            source=source,
            cases=("Sepsis",),
        ),
        Query(
            "eventNameGroupCountOrder",
            "select e:name, count(e:name) group by e:name order by count(e:name) desc, "
            "e:name limit l:1, t:10",
            group=grouping,
            source=source,
        ),
        Query(
            "eventResourceTransitionGroup",
            "select e:resource, e:transition, count(e:name) group by e:resource, e:transition "
            "order by count(e:name) desc, e:resource, e:transition limit l:1, t:10",
            group=grouping,
            source=source,
        ),
        Query(
            "traceNameWithHoistedEventDuration",
            "select t:name, min(^e:timestamp), max(^e:timestamp), "
            "max(^e:timestamp)-min(^e:timestamp) group by t:name order by t:name limit l:1, t:10",
            group=grouping,
            source=source,
        ),
        Query(
            "journalReviewResultGroup",
            "select [e:result], count(e:name) group by [e:result] order by count(e:name) desc limit l:1, t:10",
            group=custom_grouping,
            source=source,
            cases=("JournalReview",),
        ),
        Query(
            "sepsisOrgGroupEventNameGroup",
            "select e:group, e:name, count(e:name) group by e:group, e:name "
            "order by e:group, count(e:name) desc limit l:1, t:10",
            group=custom_grouping,
            source=source,
            cases=("Sepsis",),
        ),
        Query(
            "timestampWhereBetween",
            "where e:timestamp >= D2006-01-01 and e:timestamp < D2007-01-01 "
            "order by e:timestamp, e:name limit l:1, t:10, e:10",
            group=predicates,
            source=source,
        ),
        Query(
            "stringLikePredicate",
            "where e:name like '%e%' order by e:name, e:timestamp limit l:1, t:10, e:10",
            group=predicates,
            source=source,
        ),
        Query(
            "journalReviewResultInPredicate",
            "where [e:result] in ('accept', 'reject') order by [e:result], e:timestamp limit l:1, t:10, e:10",
            group=predicates,
            source=source,
            cases=("JournalReview",),
        ),
        Query(
            "sepsisBooleanLikePredicate",
            "where [t:InfectionSuspected] is not null or [t:SIRSCriteria2OrMore] is not null "
            "limit l:1, t:10, e:2",
            group=predicates,
            source=source,
            cases=("Sepsis",),
        ),
        Query(
            "offsetWithOrderedWindow",
            "order by e:timestamp, e:name, e:transition limit l:1, t:5, e:5 offset t:2, e:2",
            group=offsets,
            source=source,
            cases=("teleclaims", "JournalReview", "Sepsis"),
        ),
        Query(
            "offsetLogAndTrace",
            "order by t:name limit l:1, t:5 offset l:0, t:3",
            group=offsets,
            source=source,
        ),
    ]


def multi_log_discovery_queries() -> list[Query]:
    source = "multi-log-discovery"
    return [
        Query(
            "multiLogCountsByLogName",
            "select l:name, count(t:name), count(^^e:name) group by l:name order by l:name limit e:1",
            source=source,
        ),
        Query(
            "multiLogEventCountsByLog",
            "select l:name, e:name, count(e:name) group by l:name, e:name "
            "order by l:name, count(e:name) desc, e:name limit l:10, t:5",
            source=source,
        ),
        Query(
            "multiLogExplicitBothNames",
            "select l:name, t:name, e:name where l:name='{PrimaryLogName}' "
            "or l:name='{SecondaryLogName}' order by l:name, t:name, e:name limit l:10, t:5, e:5",
            source=source,
        ),
        Query(
            "multiLogMixedEventPredicates",
            "select l:name, t:name, e:name where e:name='ER Registration' or [e:result]='accept' "
            "order by l:name, t:name, e:timestamp limit l:10, t:5, e:5",
            source=source,
        ),
        Query(
            "multiLogLogWildcardForPrimary",
            "select l:*, t:* where l:name='{PrimaryLogName}' limit l:1, t:5",
            source=source,
        ),
        Query(
            "multiLogOrderedTimestampWindow",
            "select l:name, t:name, e:name, e:timestamp, e:transition "
            "order by l:name, e:timestamp, e:name, e:transition limit l:10, t:3, e:3",
            source=source,
        ),
    ]


def default_index_html() -> Path:
    return repo_root() / "src" / "main" / "resources" / "static" / "index.html"


_SELECT_RE = re.compile(
    r"""<select\b[^>]*\bid\s*=\s*["']sampleQueriesSelect["'][^>]*>(?P<body>.*?)</select>""",
    re.IGNORECASE | re.DOTALL,
)
_OPTGROUP_RE = re.compile(r"""<optgroup\b[^>]*\blabel\s*=\s*"(?P<label>[^"]*)\"""", re.IGNORECASE)
_OPTION_RE = re.compile(
    r"""<option\b[^>]*\bvalue\s*=\s*"(?P<value>[^"]*)"[^>]*>(?P<label>.*?)</option>""",
    re.IGNORECASE | re.DOTALL,
)
_TAG_RE = re.compile(r"<[^>]+>")


def compare_dropdown_queries(index_html_path: Path | None = None) -> list[Query]:
    """Scrape the compare page's sample-query dropdown.

    Keeping this as the default query source means the report always exercises
    exactly the queries a user can pick in the UI: adding one to `index.html`
    automatically puts it under compatibility test.
    """
    path = Path(index_html_path) if index_html_path else default_index_html()
    if not path.is_file():
        raise ScriptError(f"missing index.html file: {path}")

    select_match = _SELECT_RE.search(path.read_text(encoding="utf-8"))
    if not select_match:
        raise ScriptError("could not find compare sample query dropdown: #sampleQueriesSelect")

    queries: list[Query] = []
    current_group = ""
    option_index = 0
    for line in select_match.group("body").splitlines():
        group_match = _OPTGROUP_RE.search(line)
        if group_match:
            current_group = html.unescape(group_match.group("label")).strip()
            continue

        option_match = _OPTION_RE.search(line)
        if not option_match:
            continue

        query_text = html.unescape(option_match.group("value")).strip()
        if not query_text:
            continue

        option_index += 1
        label = html.unescape(_TAG_RE.sub("", option_match.group("label"))).strip()
        if not label:
            label = f"dropdownQuery{option_index}"

        queries.append(
            Query(label=label, query=query_text, group=current_group, source="dropdown")
        )

    if not queries:
        raise ScriptError("compare sample query dropdown does not contain executable queries")
    return queries
