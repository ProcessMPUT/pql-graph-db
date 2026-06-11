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

Storage measurements require particular care. Database allocation, checkpoints,
WAL/transaction logs, page cache, and filesystem allocation granularity can
distort small deltas. Use a fresh stack for final storage runs and describe the
measurement method in the thesis.

## Commands

Compile the benchmark source set:

```powershell
.\gradlew.bat compileBenchmarkKotlin
```

Run the smoke profile:

```powershell
.\gradlew.bat runBenchmarkSmoke
```

Run the full thesis profile:

```powershell
.\gradlew.bat runBenchmarkFull
```

Remove leftover benchmark datastores:

```powershell
.\gradlew.bat runBenchmarkCleanup
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
- `environment.json`
- `cleanup-results.csv`

Generated datasets and detailed mismatch files may also be retained when useful.
Do not commit generated benchmark results unless the task explicitly requests a
reviewable thesis result snapshot.

Interpret query performance primarily with median and p95. Keep mean, min, max,
sample count, status, and response size as supporting diagnostics. A speedup is
valid only when both systems completed the same workload successfully and
returned semantically compatible data.

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

```powershell
.\gradlew.bat compileKotlin compileTestKotlin compileBenchmarkKotlin
.\gradlew.bat test --tests com.processm.processminterpreter.benchmark.*
.\gradlew.bat runBenchmarkSmoke
```

6. Inspect generated CSV rows for errors, missing samples, implausible zeroes,
   unequal repetition counts, and response-size anomalies.
7. Confirm benchmark datastores were removed even when a run failed.

Do not silently change the full dataset matrix, query workload, warmups,
repetitions, endpoint behavior, or storage measurement method after thesis data
collection has started. Such changes create a new experiment version and must
be documented explicitly.
