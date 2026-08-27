# Benchmark Agent Guide

## Scope

These instructions apply to everything under `src/benchmark`.

This source set contains black-box benchmarks used to answer the thesis
questions about ProcessM compatibility, XES round-trip invariance, import and
query scalability, and storage overhead. Treat benchmark methodology as a
public contract. A faster result is not useful if the comparison is no longer
fair or reproducible.

## Responsibilities

- `BenchmarkRunner.kt` orchestrates runs and cleanup. Keep domain or production
  query logic out of the runner.
- `BenchmarkHttpClient.kt` contains API integration for both implementations.
  Prefer the common ProcessM-compatible endpoints over system-specific paths.
- `XesDatasetGenerator.kt` creates controlled synthetic datasets.
- `CanonicalXes.kt` verifies semantic XES round trips. Do not replace canonical
  comparison with byte-level XML or gzip comparison.
- `StorageMeasurement.kt` contains environment-specific storage probes.
- `BenchmarkRecords.kt` defines result records written to reports.
- `BenchmarkResultsWriter.kt` owns raw/derived CSV and environment output;
  `BenchmarkReportWriter.kt` owns the concise current report and appendix.
- `BenchmarkAnalysis.kt` owns paired effects, confidence intervals, Wilcoxon
  tests and the scope of Holm correction.
- `ContainerIoMeter.kt` owns Docker Block/Network I/O and cgroup operation
  counters. Probe outside timed intervals.
- `resources/benchmark-datasets.json` defines scaling series and real datasets.
- `resources/benchmark-queries.json` defines the stable query workload.
  Its `scalingSeries` field is the predeclared query-axis interpretation
  contract; response-window class alone is not enough because lower-scope
  sorting, grouping, or aggregation can happen before a hierarchical limit.
- `scripts/benchmarks/plot-readable-benchmark-results.py` is the current
  plotting companion. The older `plot-benchmark-results.py` is retained only
  for historical protocol-10/11 reports.

Keep benchmark utilities independent from Spring application internals. The
runner must exercise both systems through HTTP as an external client would.

## Experimental Rules

1. Run local and reference ProcessM against equivalent datasets and queries.
2. Do not optimize, normalize, or special-case results for one known log.
3. Keep the protocol-25 workload hypothesis-led:
   - `size-scaling` changes total events while holding 10 events/trace and five
     constant-valued custom event attributes fixed, from 1,000 through
     1,000,000 events;
   - `variant-scaling` holds 100,000 events, 2,000 traces, 50 events/trace,
     48 activities and five custom event attributes fixed while changing only
     the exact number of `concept:name` trace variants: 1, 100 and 2,000;
   - `real-validation` checks transfer to twelve published real-life logs with
     recorded DOI provenance; the hand-written
     `sample_process.xes` fixture belongs only to the SMOKE profile.
   Execute a query only on a series declared in its `measurementSeries` and
   interpret a trend only on a series declared in `scalingSeries`.
4. Keep fixed query text, warmup count, repetition count, limits, and response
   materialization rules across both systems.
5. Do not count dataset generation, authentication, report writing, or cleanup
   as import or query execution time.
6. Preserve raw repetitions. Charts and summaries may aggregate results, but
   the measured samples must remain available in CSV.
7. Report failures as failures. Never convert timeouts, HTTP errors, missing
   payloads, or unsupported queries into zero-duration successful samples.
8. Do not claim performance compatibility from the smoke profile. Smoke only
   verifies that the measurement pipeline works.
9. Do not claim semantic compatibility from similar response sizes or timings.
   Use the compatibility report and canonical XES comparison.
10. Record environment details needed to interpret results. Performance numbers
    without the runtime, hardware, system versions, and profile are incomplete.
11. Stock REFERENCE truncates the compressed XES stream at 5 MiB inside
    `LogsService`, even if the HTTP upload limit is raised. Every compared file
    must stay below that common-domain limit; do not patch REFERENCE or silently
    sample a published real log to make it pass.

## Fair Local Runs

Before collecting thesis results:

- start both systems from a known, clean state;
- make sure no previous `bench-*` datastores remain;
- confirm both APIs are healthy;
- use the same input file bytes for both systems;
- avoid IDE indexing, builds, downloads, virus scans, and other heavy workloads;
- run systems sequentially or assign explicit CPU and memory limits when host
  contention would otherwise make the comparison asymmetric;
- do not edit or regenerate CSV files manually after a run.

Protocol 25 retains the protocol-23 paired query design: one fixed dataset
order, 40 per-query warmups and 30 adjacent LOCAL/REFERENCE query pairs in
AB/BA order. `FULL` retains 10 paired imports into fresh datastores;
`BLOCK` is the atomic real-log campaign unit and performs one setup/import pair
because import inference belongs to the complete `size-scaling` run. A final
`BLOCK` must select exactly one non-scaling dataset and execute its complete
predeclared query family. It has no recorded cold sample, idle baseline, post-idle activation
or declared/reversed/random run variants. Query inference is performed per
dataset: a paired bootstrap interval for `REFERENCE / LOCAL`, a two-sided paired
Wilcoxon test and Holm correction across ten inferential queries (six primary
and four controls) for the size series, four primary queries for real datasets,
and two primary queries for variant datasets.
No Docker CLI command may run concurrently with or immediately before the
latency block. After latency collection, replay the same 30-pair system order as
a separate unmeasured resource block; memory sampling and query I/O snapshots
belong only to that replay. The runner must confirm that the sampler is quiescent
before any later timed block. Before inference, a temporal-stability diagnostic
computes the reported `median(REFERENCE) / median(LOCAL)` effect separately in
the first and last third of the 30 chronological pairs. A direction-independent
ratio above 1.10 is an explicit warning, not a hard validity gate: this fixed
cutoff is not a statistical test and must not discard otherwise complete paired
evidence. Do not substitute the median of individual pair ratios: it is a
different estimand and is sensitive to the two bands created by alternating
LR/RL order. Absolute LOCAL and REFERENCE drift and baseline drift also remain
visible diagnostically. The baseline remains descriptive and does not invalidate
otherwise sound hypothesis tests.
Import inference uses the seven `size-scaling`
datasets as one Holm family; variant and real imports are descriptive validation.

A protocol-25 thesis-grade memory/I/O run must contain
`processm-interpreter`, `processm-neo4j`, and `processm-server` from Docker.
LOCAL and REFERENCE receive equal 6 GiB whole-system cgroup budgets without
container swap and equal 3 GiB aggregate effective JVM heap ceilings; clean-stack
preparation must verify both from live containers rather than trusting Compose text.
Memory samples and summaries retain dataset and operation labels and include
per-timestamp `local-total` and `reference-total`. Campaign resource summaries
use medians of equal-sized blocks; never sum resource use merely because a
campaign contains more datasets.
`container-io.csv` retains component-level Block/Network byte deltas and cgroup
read/write operation deltas when available. A missing cgroup operation counter
may be marked `PARTIAL`. Block read/write bytes are required for every component;
Network I/O is required at the reported application boundary (`processm-interpreter`
for LOCAL and `processm-server` for REFERENCE). A missing internal Neo4j network
counter remains a visible diagnostic partial snapshot but must not discard exact
cgroup Block I/O from the same row. Because Docker can transiently omit one
container from a multi-container `stats --no-stream` response, the collector
retries an incomplete snapshot outside the timed interval before declaring it
unavailable.

Protocols 10 through 22 remain historical evidence. Do not rewrite or relabel
them as protocol 23. Protocol-12 reports already stored beside their raw run
must remain unchanged; current replay deliberately refuses to regenerate them.
`ThesisReportWriter`,
`plot-benchmark-results.py`, and `compare-runs.py` exist to reproduce those
artifacts; new runs use `BenchmarkReportWriter` and
`plot-readable-benchmark-results.py`.

Storage measurements require particular care. Database allocation, checkpoints,
WAL/transaction logs, page cache, and filesystem allocation granularity can
distort small deltas. Use a fresh stack for final storage runs and describe the
measurement method in the thesis.

The final expansion-factor probe
`scripts/benchmarks/measure-storage-scaling.py` recreates the whole stack for
every dataset and marks rows `measurementMode=isolated-fresh-stack`. Sequential
legacy files are not comparable and plotting must reject them as final Q3
evidence. The operation destroys Compose volumes and requires the explicit
`--confirm-destroy-volumes` flag.

## Commands

Commands use POSIX form; see *Platform And Commands* in the root `AGENTS.md`
for the Windows equivalents.

Compile the benchmark source set:

```bash
./gradlew compileBenchmarkKotlin
```

Before the first benchmark block, build the image and create the empty symmetric
stack (destructive):

```bash
python3 scripts/benchmarks/prepare-benchmark-stack.py --confirm-destroy-volumes
```

Before subsequent blocks in the same final series, recreate the volumes without
rebuilding LOCAL. Pass the exact LOCAL image ID recorded by the first block in
`environment.json`:

```bash
python3 scripts/benchmarks/prepare-benchmark-stack.py --confirm-destroy-volumes \
  --reuse-local-image-id sha256:...
```

The preparation script applies `docker-compose.benchmark.yml`; do not start a
thesis block with plain `docker compose up`. It verifies that the LOCAL app plus
Neo4j cgroup limits equal the single combined REFERENCE limit, that swap is not
available inside those budgets, and that the measured containers leave explicit
headroom in the Docker VM.

Run the smoke profile:

```bash
./gradlew runBenchmarkSmoke
```

Run a non-inferential feasibility check on datasets selected from the FULL
profile (one import, three query pairs):

```bash
BENCHMARK_DATASET_FILTER=size-1m,variants-2000,real-road-traffic ./gradlew runBenchmarkPilot
```

PILOT results may prove that the pipeline and resource budget can handle a
dataset. They must never be merged with or presented as FULL performance data.

Before a FULL run, verify small-dataset stationarity with the diagnostic profile
(1k, 5k and 20k; baseline plus hierarchy window; 40 warmups and 30 measured
pairs):

```bash
./gradlew runBenchmarkDiagnostic
```

Run the full thesis profile:

```bash
./gradlew runBenchmarkFull
```

Run one complete real-log block and later assemble compatible blocks. Each
block still requires a separately prepared fresh stack. Campaign assembly is
read-only with respect to the live systems and rejects dirty Git state,
different images/configuration, incomplete query families, failed parity,
missing resource totals, and duplicate datasets:

```bash
./gradlew runBenchmarkBlock -Pdataset=real-bpic12
./gradlew assembleBenchmarkCampaign \
  -PcampaignOut=tmp/benchmark-results/campaign-bpi \
  -PcampaignRuns=tmp/benchmark-results/<block1>,tmp/benchmark-results/<block2>
```

The assembled artifact uses the report-only `CAMPAIGN` profile. Never collect
measurements directly under that profile.

The controlled axes remain whole-run units:

```bash
./gradlew runBenchmarkSizeCampaign
./gradlew runBenchmarkVariantCampaign
```

The audited query workload is a separate whole-series unit. It keeps the full
40-warmup/30-pair design, imports each size dataset once, and must not be merged
silently into older CSV files:

```bash
./gradlew runBenchmarkQueryCampaign
```

`runBenchmarkControlCampaign` remains available for a focused diagnostic of
the four controls, but it is not a substitute for the complete protocol-25
query campaign.

Remove leftover benchmark datastores:

```bash
./gradlew runBenchmarkCleanup
```

Protocol 25 generates one independently scaled size-series effect figure per
query. Figures for variant, real-log, BPI and import series are generated only
when those series are present in the run; resource figures remain part of every
report.
Protocols 23 and 24 retain their ten-figure layout; protocol 22 retains eight.
The runner folds the
short Markdown report into one self-contained HTML file. Both steps are
idempotent and can also be rerun manually:

```bash
python3 scripts/benchmarks/plot-readable-benchmark-results.py tmp/benchmark-results/<runId>
python3 scripts/benchmarks/render-report-html.py     tmp/benchmark-results/<runId>
```

The HTML (`benchmark-report.html`) inlines every SVG, so it needs nothing else
to view or print to PDF. The main report is intentionally short; full tables
belong in `benchmark-appendix.md` and raw values in CSV.

Generated reports live under `tmp/` (gitignored) — do not commit run outputs;
only the generator scripts are version-controlled.

The following workflow applies only to reproduction of historical protocol
10/11 evidence, not to a new protocol-23 run:

```bash
python3 scripts/benchmarks/compare-runs.py <declared-run> <reversed-run> <random-run> \
  --compatibility-report tmp/compatibility-reports/<reportId>
# The first command prints <anchor-run>. Attach the isolated Q3 probe there:
python3 scripts/benchmarks/measure-storage-scaling.py \
  --datasets-dir <anchor-run>/generated-datasets \
  --out-csv <anchor-run>/storage-scaling.csv \
  --confirm-destroy-volumes
python3 scripts/benchmarks/plot-benchmark-results.py <anchor-run>
# For historical protocol-10 runs with hoistedGroup mismatches, attach the audit:
python3 scripts/benchmarks/verify-hoisted-group-mismatches.py \
  <declared-run> <reversed-run> <random-run> --out-dir <anchor-run>
# Refresh the combined report after the base report acquired figures and Q3:
python3 scripts/benchmarks/compare-runs.py <declared-run> <reversed-run> <random-run> \
  --compatibility-report tmp/compatibility-reports/<reportId>
python3 scripts/benchmarks/render-report-html.py <anchor-run>
```

Relevant configuration:

```text
LOCAL_PROCESSM_API=http://localhost:8080/api
REFERENCE_PROCESSM_API=http://localhost:80/api
PROCESSM_LOGIN=admin@example.com
PROCESSM_PASSWORD=Admin1234
BENCHMARK_OUTPUT_DIR=tmp/benchmark-results
BENCHMARK_DATASET_FILTER=size-1k,size-5k
BENCHMARK_SERIES_FILTER=size-scaling
BENCHMARK_SYSTEM_FILTER=local,reference
BENCHMARK_KEEP_DATASTORES=false
```

Use arbitrary filters only for diagnostics. Final controlled-axis results use
the complete version-controlled series; final real-log filters are accepted
only through the one-dataset `BLOCK` profile and campaign assembler.

## Result Contract

Each run must create an immutable directory under
`tmp/benchmark-results/<timestamp>/`. At minimum preserve:

- `summary.md`
- `datasets.csv`
- `import-results.csv`
- `query-results.csv`
- `query-summary.csv`
- `comparison-results.csv`
- `container-io.csv`
- `storage-results.csv`
- `roundtrip-results.csv`
- `memory-results.csv`
- `memory-summary.csv`
- `environment.json`
- `stack-preparation.json` (single-use proof of fresh Compose volumes and exact measured image IDs)
- `cleanup-results.csv`
- `benchmark-report.md` / `benchmark-appendix.md`
- `benchmark-report.html`
- exactly ten SVG files under `figures/` for protocol 23 (eight for historical protocol 22)

Generated datasets and detailed mismatch files may also be retained when useful.
Do not commit generated benchmark results unless the task explicitly requests a
reviewable thesis result snapshot.

Historical protocol-10/11 final series additionally preserve:

- `repeatability.csv` / `repeatability.md`
- `series-comparison.csv` / `series-cell-stability.csv`
- `series-import.csv` / `series-import-comparison.csv`
- `series-scaling.csv` / `series-scaling-exploratory.csv`
- `report-provenance.json`
- `storage-scaling.csv`
- `thesis-report-series.md` / `thesis-tables-series.tex`
- `thesis-report.html` after the final renderer step

`render-report-html.py` must reject a purported final series if any of these
analysis/provenance artifacts or the isolated storage probe is absent. Do not
add `storage-scaling.csv` to `compare-runs.py`'s per-run `REQUIRED_FILES`: doing
so would make the two non-anchor runs invalid and prevent selecting the anchor.

Interpret protocol-25 query performance with paired medians, the
`REFERENCE / LOCAL` effect, its paired-bootstrap 95% interval and the
within-dataset Holm-adjusted paired Wilcoxon p-value. Keep raw times, status and
response size as diagnostics. A directional verdict requires both adjusted
`p < 0.05` and an interval excluding 1, after semantic parity has passed.

## Correctness Boundaries

The benchmark suite is not the primary source of truth for PQL compatibility.
Before publishing performance conclusions, require:

- the ProcessM compatibility report to have zero strict problems;
- relevant ported hierarchical tests to pass;
- round-trip comparisons to report `MATCH` for datasets under evaluation;
- response payload size differences to be investigated rather than ignored.

Different generated identity UUID values are acceptable only where the
compatibility contract explicitly treats values as nondeterministic. Missing
identity attributes, classifiers, extensions, globals, nested attributes, or
custom attributes remain mismatches.

## Change Checklist

When modifying benchmark code:

1. Explain which thesis question the change supports.
2. Check whether it changes the measured interval or workload.
3. Apply equivalent behavior to local and reference clients.
4. Add or update focused tests under
   `src/test/kotlin/com/processm/processminterpreter/benchmark`.
5. Run:

```bash
./gradlew compileKotlin compileTestKotlin compileBenchmarkKotlin
./gradlew test --tests 'com.processm.processminterpreter.benchmark.*'
python3 -m unittest scripts/benchmarks/test_plot_readable_results.py -v
python3 scripts/benchmarks/prepare-benchmark-stack.py --confirm-destroy-volumes
./gradlew runBenchmarkSmoke
```

6. Inspect generated CSV rows for errors, missing samples, implausible zeroes,
   unequal repetition counts, and response-size anomalies.
7. Confirm benchmark datastores were removed even when a run failed.

Do not silently change the full dataset matrix, query workload, warmups,
repetitions, endpoint behavior, or storage measurement method after thesis data
collection has started. Such changes create a new experiment version and must
be documented explicitly.
