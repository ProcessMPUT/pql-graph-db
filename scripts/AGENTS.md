# Scripts Agent Guide

## Scope

This directory contains operational scripts used to start the application,
seed original ProcessM, run compatibility reports, and produce benchmark
charts. Treat script behavior as part of the development workflow.

## Script Roles

- `restart-app-8080.ps1`: stop the tracked/listening app and start Gradle
  `bootRun`, writing logs and PID files under `tmp/` and `build/`.
- `stop-app-8080.ps1`: stop only the application process for the selected port.
- `init-processm-datastores.py`: create the reference user/datastores and upload
  the fixed XES fixtures used for compatibility work.
- `compatibility-query-set.ps1`: version-controlled query definitions.
- `run-compatibility-report.ps1`: orchestrate matrix/dropdown/discovery reports.
- `benchmarks/plot-benchmark-results.py`: create SVG plots from benchmark
  artifacts and embed them into the run's `thesis-report.md` and `.tex`.
- `benchmarks/render-report-html.py`: fold `thesis-report.md` and its charts
  into one self-contained HTML file (browser-viewable, print-to-PDF).
- `benchmarks/measure-storage-scaling.ps1`: sequential no-cleanup storage
  probe answering the disk-scaling question (writes `storage-scaling.csv`).

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

```powershell
.\scripts\run-compatibility-report.ps1 `
  -QuerySource dropdown `
  -Profile extended `
  -IncludeMultiLogChecks `
  -MultiLogCasesPath .\scripts\verify-compatibility.multi-log.cases.local.json `
  -SkipFailureSnapshots `
  -MeasurePayloadSize
```

Local case files containing machine-specific datastore IDs should not be
committed.

## Validation

After changing a PowerShell script:

- parse it before execution;
- run it against a small/non-destructive case first;
- verify exit status and generated artifacts;
- check failure handling, not only the happy path.

After changing ProcessM initialization, rebuild a fresh stack and verify every
expected datastore and uploaded log. After changing compatibility queries or
comparison orchestration, run a focused report and then the broad report.

Benchmark-specific methodology is documented in `src/benchmark/AGENTS.md`.
