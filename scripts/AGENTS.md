# Scripts Agent Guide

## Scope

This directory contains operational scripts used to start the application,
seed original ProcessM, run compatibility reports, and produce benchmark
charts. Treat script behavior as part of the development workflow.

Every script here is Python 3 using the standard library only — no virtualenv
and no `pip install` step, so they run on a bare interpreter on macOS, Linux and
Windows. Do not introduce a third-party import; if something seems to need one,
it almost certainly belongs in `_common.py` instead.

`_common.py` is the shared foundation and the reason these scripts stay
consistent with each other. Use it rather than re-deriving:

- `repo_root()` / `tmp_dir()` — paths resolved from the script, never from the
  caller's current directory;
- `http_json()` / `http_post_file()` — JSON and multipart upload over `urllib`,
  raising `ScriptError` with the server's own message;
- `listening_pid()` / `terminate()` / `wait_for_port()` — port and process
  handling that behaves the same on every platform;
- `info()` / `warn()` / `main_guard()` — diagnostics on stderr, results on
  stdout, `ScriptError` turned into a clean message and exit code 1.

## Script Roles

- `_common.py`: shared helpers for every script here (see above). Not an entry
  point.
- `restart-app-8080.py`: stop the tracked/listening app and start Gradle
  `bootRun`, writing logs and PID files under `tmp/` and `build/`. `--port`
  selects the port; the name keeps the usual one.
- `stop-app-8080.py`: stop only the application process for the selected port.
- `init-processm-datastores.py`: create the reference user and, unless
  `PROCESSM_SEED_DATASETS=false`, upload the fixed compatibility fixtures.
- `compatibility_query_set.py`: version-controlled query definitions. Imported
  by the report, not run directly, hence the importable underscore name.
- `run-compatibility-report.py`: orchestrate matrix/dropdown/discovery reports.
- `benchmarks/plot-benchmark-results.py`: create SVG plots from benchmark
  artifacts and embed them into historical protocol-10/11 reports.
- `benchmarks/plot-readable-benchmark-results.py`: create ten linear-scale SVG
  figures from protocol-23 CSVs, including the BPI forest/heatmap, while
  retaining the historical eight-figure protocol-22 contract. It never changes
  raw data or the Markdown narrative.
- `benchmarks/render-report-html.py`: fold the current protocol's
  `benchmark-report.md`, or a historical `thesis-report-series.md`, and its
  charts into one self-contained HTML file.
- `benchmarks/prepare-benchmark-stack.py`: explicitly destructive setup for a
  clean symmetric benchmark stack; builds the current `bootJar` once per final
  series, then accepts only an exact commit-labelled LOCAL image ID for later
  fresh-volume blocks and isolated storage points; starts REFERENCE
  without fixture seeding through the benchmark Compose resource override, proves
  equal finite system-level memory budgets, equal aggregate effective JVM heap
  ceilings and zero datastores in both APIs, then
  verifies the live per-database and DBMS-wide Neo4j transaction-memory limits, and
  writes the single-use fresh-volume/image-ID/resource marker consumed by
  `BenchmarkRunner`.
- `benchmarks/compare-runs.py`: historical protocol-10/11 tool which validates
  at least three counterbalanced FULL runs and estimates conclusions from all
  blocks. The anchor run is only the
  output location for `repeatability.*`, `series-*.csv`, the combined report,
  `report-provenance.json`, and `thesis-tables-series.tex`. Descriptive
  magnitude-stability and non-preregistered scaling diagnostics must remain
  separate from the confirmatory direction verdicts.
- `benchmarks/verify-hoisted-group-mismatches.py`: reproduces the raw-XES,
  source-code and optional live evidence for the protocol-10 hoisted-group
  boundary tie. It must never turn the tie signature into a claim that either
  implementation is incorrect.
- `benchmarks/test_compare_runs.py`: standard-library end-to-end contract test
  for the three-block aggregator, including its rejection path.
- `benchmarks/test_plot_readable_results.py`: standard-library contract test
  for the current eight-figure generator.
- `benchmarks/measure-storage-scaling.py`: isolated storage probe with a fresh
  Compose stack per dataset (writes `storage-scaling.csv`).

Do not combine unrelated startup, destructive cleanup, seeding, comparison, and
plotting responsibilities into one script.

## Safety

- Resolve paths relative to the script or repository, not the caller's current
  directory.
- Put generated logs, reports, snapshots, and temporary files under `tmp/`.
- Make destructive actions explicit and narrowly scoped.
- Never delete arbitrary datastores or volumes based on a broad name match.
  Benchmark cleanup is limited to the `bench-` prefix.
- Startup scripts may replace the application process on their configured port;
  they must not kill unrelated processes on other ports.
- A stop script must not implicitly restart or rebuild the application.
- Initialization should be idempotent where the external API allows it.
- Use nonzero exit codes for failures and include actionable diagnostics.
- Do not print credentials or authorization tokens.

## Compatibility Reports

The compatibility report is evidence, not a mechanism for hiding differences.

- Keep dropdown, matrix, discovery, and multi-log workloads distinguishable.
- Store outputs in timestamped directories under `tmp/compatibility-reports`.
- Preserve full failure details when investigating mismatches.
- `MATCH` requires semantic comparison; HTTP success is insufficient.
- Accepted nondeterminism must be explicit, narrow, and supported by tests.
- Query text and case selection should remain version controlled.

The standard broad report is:

```bash
python3 scripts/run-compatibility-report.py --query-source dropdown --profile extended --include-multi-log-checks --multi-log-cases scripts/verify-compatibility.multi-log.cases.local.json --skip-failure-snapshots --measure-payload-size
```

Run `python3 scripts/run-compatibility-report.py --help` for the full flag list.
Exit code 1 means strict problems were found; a compatibility checkpoint
requires exit code 0.

Payload line and byte counts are produced by this script's own JSON
serialization, so compare them within a run. The deltas and percentages that
drive `payload-size-delta` are serializer-independent, because both sides go
through the same dump.

Case files ending in `.local.json` hold machine-specific datastore IDs and are
not committed. Generate them from the tracked templates
`verify-compatibility.cases.example.json` and
`verify-compatibility.multi-log.cases.example.json`, which document the expected
shape — a fresh clone has the examples but no `.local.json`, so create one
before running the multi-log checks.

## Validation

After changing a script:

- byte-compile it before execution (`python3 -m py_compile <script>`) and check
  `--help` still renders;
- run it against a small/non-destructive case first;
- verify exit status and generated artifacts;
- check failure handling, not only the happy path. An unreachable API, a
  missing cases file and a non-2xx response are the paths that actually break.

After changing the cross-run aggregator, run:

```bash
python3 -m unittest scripts/benchmarks/test_compare_runs.py -v
```

After changing the current protocol-aware chart or HTML path, run:

```bash
python3 -m unittest scripts/benchmarks/test_plot_readable_results.py -v
```

After changing ProcessM initialization, rebuild a fresh stack and verify every
expected datastore and uploaded log. After changing compatibility queries or
comparison orchestration, run a focused report and then the broad report.

Benchmark-specific methodology is documented in `src/benchmark/AGENTS.md`.
