# Repository Agent Guide

## Project Goal

This repository implements a Neo4j-backed, ProcessM-compatible PQL interpreter
for hierarchical XES logs. The primary goals are:

- match original ProcessM query semantics and API contracts;
- preserve XES data through import, query reconstruction, and export;
- keep feature packages cohesive and their boundaries explicit (see
  *Architecture*);
- provide reproducible compatibility and performance evidence.

Do not optimize the implementation for one fixture such as teleclaims. Changes
must remain valid across JournalReview, Hospital, Sepsis, synthetic datasets,
and multi-log datastores.

## Source Of Truth

Use the current source tree, `build.gradle.kts`, tests, and generated reports as
the source of truth.

These `AGENTS.md` files are the only tracked agent instructions, and they are
tool-agnostic on purpose — the repository is worked on from more than one coding
assistant. Put durable project rules here, in the `AGENTS.md` closest to the code
they govern. Per-tool note files and directories are gitignored (see the
`### AI Agents ###` block in `.gitignore`), so they are machine-local and do not
survive a fresh clone: never record a rule only there, and never treat one as
authoritative if you find it.

Three nested guides add binding rules for their subtree; read the one covering
the code you are touching, in addition to this file:

| guide | applies to |
|---|---|
| `scripts/AGENTS.md` | operational, seeding, compatibility-report, plotting scripts |
| `src/benchmark/AGENTS.md` | thesis benchmark: methodology, result contract, commands |
| `src/test/kotlin/com/processm/processminterpreter/processm/AGENTS.md` | tests ported from original ProcessM |

### Reference ProcessM Repository

Exact reference semantics and test assertions come from the original ProcessM
repository. It is a separate checkout that is not present on every machine.
Resolve it in this order:

1. the `PROCESSM_REFERENCE_REPO` environment variable, if set;
2. a sibling checkout next to this repository (`../processm`);
3. otherwise read the relevant file on GitHub — `README.md` links the grammar,
   `Query.kt`, `TranslatedQuery.kt`, and the test suites.

If none is available, say so rather than guessing at reference behavior.

## Architecture

Package-by-feature under `com.processm.processminterpreter`:

- `pql`: the whole PQL feature — unified AST (`pql.ast.PqlExpression` /
  `PqlQuery`: parser emits surface nodes, the resolver rewrites them in place
  into resolved nodes), catalog, semantics (`Resolver`/`Validator`/`Planner`,
  constructed internally by `PqlCompiler`), logical plans, ANTLR adapter
  (`pql.parser`), Cypher code generation (`pql.cypher`), and `PqlQueryService`
  (execute / validate / export-as-XES / metadata).
- `neo4j`: persistence adapters — repositories, XES import writing, schema,
  `Neo4jQueryPlanExecutor`, hierarchy reconstruction (`neo4j.query.result`).
- `xes`: log/datastore model and services (`LogService`, `DataStoreService`),
  XES XML IO (`xes.io`).
- `processm`: remote ProcessM client, XES-JSON formatting/conversion
  (`processm.json`), comparison/verification (`processm.compat`).
- `web`: REST controllers and DTOs. The REST contract is a compatibility
  invariant — do not reshape mappings or DTOs casually.
- `src/benchmark`: black-box thesis benchmark source set.
- `src/test/.../processm`: semantic tests ported from original ProcessM
  (test-source package, distinct from the main `processm` feature package).
- `scripts`: operational, compatibility, initialization, and reporting tools.

Interfaces exist only at real substitution boundaries: `LogRepository`,
`DataStoreRepository` (Neo4j, faked in tests) and `RemoteProcessMGateway`
(HTTP). Use concrete classes elsewhere rather than single-implementation ports.
One top-level type per file;
sealed hierarchies keep variants nested in the sealed parent.

## Change Discipline

- Read the complete end-to-end flow before changing a local class.
- Prefer one unambiguous model over compatibility wrappers and duplicate DTOs.
- Keep controllers thin, services orchestration-focused, PQL/XES rules pure,
  and Neo4j/HTTP adapters technology-specific.
- Do not add interfaces for classes that have no meaningful substitution or
  architectural boundary.
- Do not remove a small port merely because its implementation is thin.
- Remove unused parameters and dead compatibility code instead of suppressing
  warnings.
- Do not use `@Suppress` to hide design problems.
- Preserve user changes in a dirty worktree and avoid unrelated refactors.

## Semantic Safety

For PQL, XES, hierarchy reconstruction, datastore scoping, or comparison
changes:

- compare behavior with original ProcessM code where possible;
- preserve exact attribute names and scopes;
- treat classifiers, extensions, globals, nested attributes, typed values, and
  duplicate XES keys as semantic data;
- do not normalize mismatches merely to make the comparator report `MATCH`;
- different generated identity UUID values may be nondeterministic, but missing
  identity attributes are not equivalent;
- verify single-log and multi-log datastores.

For grouped queries, preserve ProcessM's two-stage evaluation: `WHERE` selects
component identities at the aggregate's effective scope, then aggregate arguments
read those components and their descendants. A lower-scope filter does not prune
the descendants read by a hoisted aggregate. For example, `count(^t:name)` and
`count(t:name)` can count different populations under the same event predicate.
Implicit log aggregation combines matching logs before applying the log window.
Grouped `ORDER BY` expressions use the membership-selection rows, so a hoisted
aggregate used for ordering cannot blindly reuse its projected aggregate value.
Regression coverage must check aggregate values and descendant placeholder shape.

ProcessM streams XES XML as JSON and can repeat child-element fields such as
`float` when attributes of another type occur between them. Use the shared
`ProcessMXesJsonParser` for response bodies: ordinary map/tree parsing silently keeps
only the last field. Preserve every attribute, including duplicate XES keys;
do not emulate the parser's data loss in LOCAL's JSON converter.

Grouped REFERENCE metadata has a separate limitation: `TranslatedQuery` assigns
ordinal IDs to result log groups but returns physical `log_id` values for
extensions/classifiers. `DBHierarchicalXESInputStream` compares those values
directly, so metadata can disappear depending on member order and physical IDs.
Keep such a response a strict MISMATCH; do not erase LOCAL metadata or relax the
comparator to manufacture compatibility. Matching aggregate values alone do not
make the full response eligible for timing comparisons.

If XML parsing or persistence representation changes, previously imported logs
must be deleted and imported again before manual comparisons are meaningful.

### Source Order And Hoisted-Variant Boundary Ties

Events inside a trace are ordered by `importOrder`, i.e. the order they appear in
the XES file. This is deliberate and spec-mandated: the PQL specification states
that "[b]y omitting the `order by` clause, the components are returned in the same
order as provided by the data source"
(`docs/pql.md`, https://github.com/ProcessMPUT/processm/blob/master/docs/pql.md).
Do not replace this with timestamp order.

For `group by ^e:name order by count(t:name) desc`, the Hospital,
JournalReview and Sepsis fixtures have ties at the trace-group window boundary.
Differences in the returned hierarchy must not be attributed to timestamp order:

- Hospital, JournalReview and Sepsis are already nondecreasing by timestamp inside
  every trace; stable timestamp sorting changes none of their event-name sequences;
- the reference `TranslatedQuery` orders the hoisted event array by the event ID
  when event-scope `order by` is absent, not by `time:timestamp`;
- the REST trace limit is 30, while `count(t:name) desc` is not a total order. At
  the 30th variant Hospital has 13 variants tied for 5 places, JournalReview 93
  tied for 27 places, and Sepsis 35 tied for 3 places;
- selecting different members of those tied sets can produce different event totals.

Such pairs are strict-response MISMATCHes and are excluded from timing comparisons;
they do not establish a query-semantics defect in either system. Benchmark queries
must project only values invariant under the boundary tie (for example
`select count(t:name) ...`) or otherwise define a total trace-group order.

## Platform And Commands

The repository is developed on more than one machine and operating system. All
commands in these guides are written in POSIX form and every path uses forward
slashes. Translate per host instead of hardcoding one platform:

| | macOS / Linux | Windows |
|---|---|---|
| Gradle | `./gradlew <task>` | `.\gradlew.bat <task>` |
| Python | `python3 <script>` | `python <script>` |

Gradle task names are identical everywhere; read them from `build.gradle.kts`
rather than assuming.

Everything under `scripts/` uses Python 3 and the standard library only.
Keep scripts runnable without third-party dependencies or a virtual environment.
`scripts/_common.py` holds shared repository-path, HTTP, process, port and output
helpers; reuse them instead of duplicating this functionality.

One host prerequisite is easy to miss: `gradlew` must be executable
(`chmod +x gradlew` on a fresh POSIX clone).

## Thesis publication

The thesis and its final report describe one completed study based on one
final evidence package. Describe the measured method, results and limitations.
Preserve execution provenance in the evidence metadata, and keep incomplete
previews explicitly incomplete. Update methodology, numbers, tables, figures
and conclusions together from the same final report. Every numerical claim must
be supported by the recorded evidence for that study.

## Verification

Compile before running broad tests:

```bash
./gradlew compileKotlin compileTestKotlin compileBenchmarkKotlin
```

Run focused tests for the changed area, then the full suite before a checkpoint:

```bash
./gradlew test
```

A change is not finished while the suite is red. If a test fails, fix the cause
or state plainly that it fails and why — do not weaken the assertion, do not
mark it `@Disabled`, and do not report the work as complete. Pre-existing
skips must be checked in the test sources; new ones need an explicit reason.

For compatibility-sensitive work, also run the repository compatibility report
and require zero strict problems. Do not treat HTTP 200 or equal payload sizes
as proof of semantic equality.

Generated outputs belong under `tmp/` and should not be committed unless a task
explicitly requests a result snapshot.
