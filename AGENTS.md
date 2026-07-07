# Repository Agent Guide

## Project Goal

This repository implements a Neo4j-backed, ProcessM-compatible PQL interpreter
for hierarchical XES logs. The primary goals are:

- match original ProcessM query semantics and API contracts;
- preserve XES data through import, query reconstruction, and export;
- keep clear `domain`, `application`, and `infrastructure` boundaries;
- provide reproducible compatibility and performance evidence.

Do not optimize the implementation for one fixture such as teleclaims. Changes
must remain valid across JournalReview, Hospital, Sepsis, synthetic datasets,
and multi-log datastores.

## Source Of Truth

Use the current source tree, `build.gradle.kts`, tests, and generated reports as
the source of truth.

`CLAUDE.md` contains historical architecture and dependency information. Do not
copy class names, package paths, versions, commands, or design assumptions from
it without verifying them against the current repository.

The original ProcessM repository at
`C:\Users\andre\AntigravityProjects\processm` may be inspected when exact
reference semantics or test assertions are needed.

## Architecture

Package-by-feature under `com.processm.processminterpreter` (the former
`domain`/`application`/`infrastructure` layering was intentionally removed):

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
(HTTP). Everything else is a concrete class — do not reintroduce
single-implementation ports or layer ceremony. One top-level type per file;
sealed hierarchies keep variants nested in the sealed parent.

## Change Discipline

- Read the complete end-to-end flow before changing a local class.
- Prefer one unambiguous model over compatibility wrappers and duplicate DTOs.
- Keep controllers thin, use cases orchestration-focused, domain rules pure,
  and adapters technology-specific.
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

If XML parsing or persistence representation changes, previously imported logs
must be deleted and imported again before manual comparisons are meaningful.

## Verification

Compile before running broad tests:

```powershell
.\gradlew.bat compileKotlin compileTestKotlin compileBenchmarkKotlin
```

Run focused tests for the changed area, then the full suite before a checkpoint:

```powershell
.\gradlew.bat test
```

For compatibility-sensitive work, also run the repository compatibility report
and require zero strict problems. Do not treat HTTP 200 or equal payload sizes
as proof of semantic equality.

Generated outputs belong under `tmp/` and should not be committed unless a task
explicitly requests a result snapshot.

