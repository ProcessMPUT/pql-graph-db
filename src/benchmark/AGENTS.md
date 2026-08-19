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
- `BenchmarkResultsWriter.kt` owns CSV, JSON, Markdown, and environment output.
- `resources/benchmark-datasets.json` defines scaling series and real datasets.
- `resources/benchmark-queries.json` defines the stable query workload.
  Its `scalingSeries` field is the predeclared query-axis interpretation
  contract; response-window class alone is not enough because lower-scope
  sorting, grouping, or aggregation can happen before a hierarchical limit.
- `scripts/benchmarks/plot-benchmark-results.py` is the plotting companion,
  even though it lives outside this source set.

Keep benchmark utilities independent from Spring application internals. The
runner must exercise both systems through HTTP as an external client would.

## Experimental Rules

1. Run local and reference ProcessM against equivalent datasets and queries.
2. Do not optimize, normalize, or special-case results for one known log.
3. Change one scaling dimension at a time:
   - trace series: vary trace count;
   - event series: vary events per trace;
   - attribute series: vary attributes per event.
   Interpret a query trend only for a series named in that query's
   `scalingSeries`; do not infer eligibility from `workload` or from a visually
   convincing plot after the measurement.
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

## Fair Local Runs

Before collecting thesis results:

- start both systems from a known, clean state;
- make sure no previous `bench-*` datastores remain;
- confirm both APIs are healthy;
- use the same input file bytes for both systems;
- avoid IDE indexing, builds, downloads, virus scans, and other heavy workloads;
- run systems sequentially or assign explicit CPU and memory limits when host
  contention would otherwise make the comparison asymmetric;
- repeat the full experiment more than once and retain every run directory;
- do not edit or regenerate CSV files manually after a run.

METODOLOGIA §5 requires **at least three** valid FULL runs on one clean Git
commit, with declared/reversed/random dataset order; the random block uses the
preregistered seed `20260728`. The preserved final series uses
`benchmarkProtocolVersion=10`; new collections use version 11. Versions before 2 have a weaker collection-time
parity check, while version 2 also accumulated every dataset in both databases
and can exhaust a shared Docker VM. Version 3 keeps only one measured dataset
pair live at a time. Version 4 additionally performs an unrecorded activation
warm-up after the intentional idle-memory baseline. Version 5 performs a fresh
throw-away import, the activation queries, and datastore deletion, so the first
measured dataset does not uniquely pay any part of the post-idle lifecycle.
Version 6 gates broad Q2 instability on the upper quartile of query/system
replicate spreads; the maximum for each query remains that query's practical
effect floor. Version 7 uses the benchmark Compose override to assign equal,
finite no-swap memory budgets to the complete applications, rejects OOM/restart/
missing-JVM state at the end of a run, and extends the FULL global warm-up to
200 rounds after a complete v6 block proved that 40 rounds did not reach the
LOCAL optimization horizon. Version 8 raises only Neo4j's transaction-memory
ceiling from 256 MiB to 512 MiB, within the same complete-application cgroup
budget, after the former ceiling rejected the Hospital round-trip export.
Version 9 extends the post-idle activation from 10 to 200 complete query rounds:
the first complete v8 block had broad LOCAL drift (Q3 ×1.36) because the first
replicate dataset remained slower after the 60-second idle window.
Version 10 builds LOCAL once for a final series and requires every later
fresh-volume block and isolated storage point to reuse that exact image ID.
Version 11 replaces the historical materializing `hoistedGroup` query with an
aggregate-only form. The former non-total ordering could select different valid
subsets at the default trace-limit boundary and invalidate timing comparison.
Do not rewrite version 10 evidence; its mismatches stay excluded and audited.
Rebuilding a report never upgrades a run's protocol. `compare-runs.py` accepts
version 10 and newer but never combines different versions.
A thesis-grade memory run
must contain `processm-interpreter`, `processm-neo4j`, and `processm-server`
from the same `docker stats` probe plus the per-timestamp aggregates
`local-total` and `reference-total`. The fallback `local-jvm` series is useful
only for development and invalidates Q3. Combine runs with
`scripts/benchmarks/compare-runs.py <runA> <runB> <runC>`; all runs are units
of evidence. Its median-metric anchor is only a presentation location, never a
replacement for cross-run estimation.

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

Run the full thesis profile:

```bash
./gradlew runBenchmarkFull
```

Remove leftover benchmark datastores:

```bash
./gradlew runBenchmarkCleanup
```

Generate SVG charts and embed them into the run's `thesis-report.md` and
`thesis-tables.tex` (standard post-run step; idempotent, safe to re-run), then
optionally fold the Markdown report + charts into one self-contained HTML file:

```bash
python3 scripts/benchmarks/plot-benchmark-results.py tmp/benchmark-results/<runId>
python3 scripts/benchmarks/render-report-html.py     tmp/benchmark-results/<runId>
```

The HTML (`thesis-report.html`) inlines every SVG, so it needs nothing else to
view or print to PDF. The `.tex` figure block uses extension-less
`\includegraphics{plots/<name>}`; pdflatex needs PDF (not SVG) art, so convert
once before compiling, e.g. `for f in plots/*.svg; do rsvg-convert -f pdf -o
"${f%.svg}.pdf" "$f"; done`, or load the `svg` package and use `\includesvg`.

Generated reports live under `tmp/` (gitignored) — do not commit run outputs;
only the generator scripts are version-controlled.

After three FULL blocks, validate and combine them before rendering HTML:

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
BENCHMARK_DATASET_FILTER=trace-100,trace-500
BENCHMARK_SYSTEM_FILTER=local,reference
BENCHMARK_KEEP_DATASTORES=false
```

Use filters only for diagnostics. Final results should use the complete,
version-controlled full profile.

## Result Contract

Each run must create an immutable directory under
`tmp/benchmark-results/<timestamp>/`. At minimum preserve:

- `summary.md`
- `datasets.csv`
- `import-results.csv`
- `query-results.csv`
- `query-summary.csv`
- `storage-results.csv`
- `roundtrip-results.csv`
- `memory-results.csv`
- `memory-summary.csv`
- `environment.json`
- `stack-preparation.json` (single-use proof of fresh Compose volumes and exact measured image IDs)
- `cleanup-results.csv`

Generated datasets and detailed mismatch files may also be retained when useful.
Do not commit generated benchmark results unless the task explicitly requests a
reviewable thesis result snapshot.

The per-run contract above intentionally does **not** include
`storage-scaling.csv`: only the median-metric anchor receives the isolated Q3
probe, and the series must be comparable before that anchor can be selected.
A final series directory at the anchor additionally preserves:

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

Interpret query performance primarily with median and Q1–Q3. Do not interpret
p95 below 200 samples; the FULL profile has 30. Keep mean, min, max, sample
count, status, and response size as supporting diagnostics. A speedup is valid
only when both systems completed the same workload, returned semantically
compatible data, and the direction repeats across the valid FULL blocks.

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
