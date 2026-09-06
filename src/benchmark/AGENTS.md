# Benchmark Agent Guide

## Scope and entry point

This source set measures two complete ProcessM-compatible systems through HTTP.
Read `METHODOLOGY.md` and `../../docs/benchmark-study.md` before changing it.
The procedure is protocol 27, declared in `resources/study-plan.json`.
Maintain one collection procedure, one report pipeline and one statistical
implementation. Do not add parallel profiles, runners or compatibility wrappers.
Saved measurements and evidence manifests under `tmp/` are immutable.

The sole custom benchmark Gradle task is `prepareBenchmark`: it compiles the
collector and writes its classpath and Java executable, without contacting services.
`scripts/benchmarks/benchmark-study.py` owns plan, pilot, freeze, run, audit and
report. It invokes preparation and storage helpers; they are not alternate campaigns.
Live collection and destructive preparation require explicit user authorization.
Offline compilation, contract tests and report generation do not.

## Responsibilities

- `StudyJobRunner` loads one immutable job and calls `StudyCollector`.
- `StudyCollector` orchestrates import, warmup, query blocks, resource windows,
  XES verification and cleanup. Counts and selection come only from `StudyJob`.
- `BenchmarkSettings` holds HTTP connection settings; measurement parameters belong
  to `StudyJob`.
- `BenchmarkExecution` contains shared request recording and owned-store cleanup.
- `BenchmarkHttpClient` measures equivalent API operations on both systems.
- `XesDatasetGenerator` and `StudyDatasetCache` create and verify identical inputs.
- `CanonicalXesComparator` and `XesJsonSemanticParity` check semantic data.
- `BenchmarkResultsWriter` writes raw CSV and environment evidence only.
- `BenchmarkJournal` records request order, responses, checkpoints and failures.
- `MemorySampler` and `ContainerIoMeter` sample the three Docker containers.
- `study_contract.py` validates the plan, matrix, environment and raw evidence.
- `study_analysis.py` is the only statistical implementation.
- `study_report.py`, `study_figures.py` and `study_html.py` publish one document.

Keep production PQL logic out of the collector. No Spring internals, single-system
benchmark modes or host-process RSS fallback belong in this measurement path.

## Measurement contract

- The protocol requires exactly **30 measured LOCAL/REFERENCE pairs per final
  latency block**, in both coverage and primary preparations (15 LR and 15 RL).
  This is a fixed requirement, not a pilot-tuned parameter. Never reduce it to
  fit the execution budget. Pilot observations remain separate from final results.
- Six predeclared primary comparisons: three hierarchy queries on 100k/1m events,
  with twelve independent fresh preparations. The minimal response is descriptive.
- All 28 logs have a declared matrix of 168 descriptive latency blocks and XES
  roundtrip checks. Resources are a separate 55-block matrix on seven logs.
  Import uses five pairs on all seven sizes; storage also keeps all seven sizes.
  Four expensive size queries use 1k/100k/1m anchors; omitted cells are absent,
  not interpolated or substituted with a primary preparation.
- Technical HTTP repetitions are not independent preparations. Exact sign tests
  and Holm correction apply only to the six primary comparisons. Preserve the
  median estimand, conservative order-statistic interval and descriptive scopes.
- Sizes vary events at ten events/trace and five fixed custom attributes. Variants
  hold 100k events, 2000 traces, 50 events/trace, 48 activities and five custom
  attributes fixed, varying concept:name variants among 1, 100 and 2000.
- Controlled selectivity varies matching trace/event shares among 1%, 10% and
  100% at 100k events only. Both event-level and hoisted trace-level predicates
  return one trace count through `count(t:name)`; verify its trace attribute,
  not a log attribute. Hoisting the aggregate itself changes its input set.
  Trace length varies among
  10/100/1000 at 100k events, with ten activities and a ten-event cost cycle.
  Response windows return 20/200/600 events on the same variants-1 input.
  Validate declared counts and aggregate values independently of LOCAL/REFERENCE
  parity. These axes remain descriptive and do not expand the primary test family.
- Execute only declared `measurementSeries`; interpret scaling only where the
  query's declared `scalingSeries` permits it. Honor explicit dataset selections
  in each query definition; do not expand them to the Cartesian product of series.
  Keep real-log DOI provenance.
- REFERENCE sessions expire after three hours. Check both sessions before every
  adjacent request pair, not only before a block, which may itself exceed that
  lifetime. Renew proactively outside request timing, import timing and resource
  windows; ensure enough validity for the complete next pair/operation. Record
  renewal events without bearer tokens. Never replay a timed 401 as a successful
  sample. Authentication failures (401/403) are distinct from PQL rejection.
- Use identical bytes, PQL, limits and materialization rules on both systems.
  All compressed inputs must stay below stock REFERENCE's 5 MiB limit. Do not
  patch REFERENCE or silently sample a published log to evade it.
- Exclude generation, authentication, analysis, cleanup and disk writes from
  request timing. Alternate adjacent LOCAL/REFERENCE pairs in AB/BA order.
- Keep every measured response and compare every pair semantically after the timed
  block. Counts or payload sizes alone are insufficient. Failed requests remain
  failures, never zero-duration successes. Missing counterpart evidence fails.
- A small atomic live-progress update before/after each request is permitted
  outside its measured HTTP interval, identically for both systems. Full response
  persistence, checkpoints, semantic comparisons and Docker probes remain outside
  latency blocks. Do not move heavier work into gaps between paired requests.
  Required session renewal belongs before the first request of a pair and is an
  explicit exception to keeping all network work outside the complete block.
- Docker probes belong outside latency blocks. Pause and await sampler quiescence
  before timed requests. Record resource-window start/end and completed requests.
- Sum simultaneous active-system components before aggregating memory. Require
  complete in-window samples; late or missing samples cannot become zeros or peaks.
  An observed maximum is not a guaranteed peak. Short imports may have no samples.
- Block I/O bytes are required per component; network bytes at the application
  boundary. Missing cgroup operation counts may be PARTIAL, never fabricated.
  Preserve bounded retries of incomplete Docker snapshots outside timed blocks.
- Isolated disk points use fresh volumes and exact anchor images, one point per
  storage helper invocation. The controller alone handles retries and resumption.

## Runtime, provenance and recovery

Use `docker-compose.benchmark.yml`: equal whole-system memory budgets, no swap,
equal aggregate effective JVM heap ceilings and explicit Docker VM headroom.
Validate live limits, versions, health, image IDs and empty APIs; consume the
single-use fresh-volume proof. A clean Git revision must match measured images.

The final plan uses `budgetSeconds: null`, meaning no cumulative execution
deadline. The pilot has its own `pilot.budgetSeconds: 1800` cumulative cap,
including stack preparation and every attempt; never reset it on resume.
An incomplete or timed-out pilot cannot freeze the final plan. Keep recording
elapsed time and the pilot forecast with its reserve.
A positive finite `budgetSeconds` is an optional cap; enforce it during execution
and against the forecast only when configured. Do not freeze a plan without
compatible pilot evidence. Do not select
counts from final p-values or change the workload to obtain a desired verdict.
A source/configuration change requires new verification before final collection.
The ten-log pilot covers every query in each measured series, all three 100k
selectivity inputs, both trace-length endpoints and all response windows.
Its 47 cells have one preparatory invocation per system and two measured pairs
(one LR and one RL), totalling 282 block requests; two global warmup rounds
add 72 requests. Its only resource probe is `size-1k` / `hierarchyWindow`,
ten seconds per system with at least three samples per required component.
Pilot import sampling is limited to `size-1k`; short imports can lack samples.
The pilot checks procedure and rough cost, not warmup sufficiency or stationarity.
Final blocks use 40 warmups, 30 pairs and 200 global rounds; twelve primary
preparations have 96 blocks. Together with 168 descriptive blocks this is
36,960 block requests, excluding global warmups and other stages.
Forecast unknown cells only from the same query and series; missing
query/import/warmup evidence must fail, not borrow an unrelated case. Estimate
total repeated work from arithmetic means, separately for warmup and measured
pairs, while preserving the median estimand for performance results. Expose
forecast costs by task and component. A two-pair forecast is a rough estimate,
not a conservative upper bound or guarantee of completion time, even with its
25% reserve. Absence of a final total deadline does not remove individual request
timeouts or failure and completeness checks.

Report the current task, elapsed time and last completed activity during long
operations. Distinguish a controller heartbeat from actual collector progress:
a live supervising process is not evidence that an HTTP request or import has
finished. Preserve timestamps and logs so a stalled operation can be located.
The controller's `progress.json` includes the collector's `live-progress.json`;
the latter distinguishes `current.startedAt`, `completedOperations` and
`lastCompleted.completedAt` from its own `updatedAt` timestamp. Keep request
timeouts enabled so an unresponsive operation becomes a recorded failure, not an
endless heartbeat.

Attempts, inputs and completed-task manifests are immutable. An infrastructure
failure permits at most one documented replacement in a fresh environment.
Do not repeat semantic/control failures in pursuit of MATCH. Completed tasks remain
reusable after another fails, including resources and storage; elapsed time never
resets, and a configured cumulative budget includes previous attempts.
Cleanup targets only datastores created by the current task. Never delete arbitrary
stores by a broad prefix match.

Raw evidence includes job and definition snapshots, input hashes, import/query/
roundtrip/memory/I/O CSV, full measured responses, execution.jsonl, environment
before/after, preparation proof and cleanup outcome. Offline reports read recorded
plans and definitions, not the working draft, and publish to a new directory.
Incomplete results remain explicitly incomplete. Preserve hypothesis scope and
strict semantic failures; results from other campaigns cannot fill missing cells.

## Verification

After changes run compilation, focused benchmark unit tests and offline Python
contract tests. Use the commands in `docs/benchmark-study.md`. Check failure and
recovery paths and regenerate a report from saved evidence without changing it.
Do not trigger Docker or live measurements as part of this offline check.
Before final publication also require the full relevant application/ported test
suite, zero strict compatibility problems and XES MATCH for every assessed log.
Generated evidence stays under `tmp/` and is not committed by default.
