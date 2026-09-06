# Scripts Agent Guide

## Scope

This directory contains operational scripts used to start the application,
seed original ProcessM, run compatibility reports, and produce benchmark
charts. Treat script behavior as part of the development workflow.

Every script here uses Python 3 and the standard library only, on macOS, Linux
and Windows. Do not introduce third-party imports or a virtual-environment
requirement. Reuse shared operational helpers from `_common.py`.

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

- `benchmarks/benchmark-study.py`: protocol-27 study controller for
  pilot forecasting, optional execution cap, progress reporting,
  immutable attempts, resume and offline
  publication. It delegates preparation and storage to their dedicated tools;
  only explicitly opted-in pilot/run commands may touch services.
- `benchmarks/study_contract.py`: pure schedule, matrix, resource coverage and
  provenance validation. Preserve six predeclared primary comparisons and
  the separate descriptive scopes; no result-driven selection or sample count.
- `benchmarks/study_analysis.py`: estimates and diagnostics from validated raw
  evidence. Memory totals use simultaneous active components. Latency diagnostics
  must not invent memory windows in tasks without resource collection.
- `benchmarks/study_report.py`: one report with primary p-values, descriptive
  scaling, resources, complete tables, SVG figures and LaTeX. Missing measurements
  cannot be filled from another campaign. Read the campaign's recorded definitions
  during offline replay rather than the repository's plan template.
  Maintain only the current study format. Shared prose/Markdown primitives live
  in `report_common.py`; `study_figures.py` draws linear effect charts with
  LOCAL/REFERENCE values and descriptive quartiles, plus the BPI summary, and
  `study_html.py` embeds them in the self-contained document.
  Preserve individual datasets and recorded PQL in the figures. Use linear axes;
  confidence intervals belong only to the independent-preparation comparisons,
  not to descriptive HTTP pairs. Keep memory, import and disk summaries visible.

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
- `thesis-compatibility-queries.json`: frozen 69 named thesis compatibility
  cases (68 distinct PQL texts), selected with `--query-source thesis`.
- `benchmarks/prepare-benchmark-stack.py`: explicitly destructive setup for a
  clean symmetric benchmark stack; builds the current `bootJar` once per final
  series, then accepts only an exact commit-labelled LOCAL image ID for later
  fresh-volume blocks and isolated storage points; starts REFERENCE
  without fixture seeding through the benchmark Compose resource override, proves
  equal finite system-level memory budgets, equal aggregate effective JVM heap
  ceilings and zero datastores in both APIs, then
  verifies the live per-database and DBMS-wide Neo4j transaction-memory limits, and
  writes the single-use fresh-volume/image-ID/resource marker consumed by
  `StudyCollector`.
- `benchmarks/measure-storage-scaling.py`: isolated storage probe with a fresh
  Compose stack for one explicitly selected dataset (writes `storage-scaling.csv`).
  It does not search for recent runs or resume its own campaign; the controller
  selects inputs and manages immutable attempts.

Do not combine unrelated startup, destructive cleanup, seeding, comparison, and
plotting responsibilities into one script.

## Safety

- Resolve paths relative to the script or repository, not the caller's current
  directory.
- Put generated logs, reports, snapshots, and temporary files under `tmp/`.
- Make destructive actions explicit and narrowly scoped.
- Never delete arbitrary datastores or volumes based on a broad name match.
  Task cleanup uses the recorded IDs of datastores created by that task.
  Full stack preparation removes the explicitly selected Compose stack and its
  volumes only after the required execution and destruction confirmations.
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
python3 scripts/run-compatibility-report.py --query-source dropdown --include-multi-log-checks --multi-log-cases scripts/verify-compatibility.multi-log.cases.local.json --skip-failure-snapshots --measure-payload-size
```

Run `python3 scripts/run-compatibility-report.py --help` for the full flag list.
Exit code 1 means strict problems were found; a compatibility checkpoint
requires exit code 0.

Payload line and byte counts use the same JSON serialization settings for both
systems. Compare them within a run: these counts, their deltas and percentages
depend on that serialization. They describe the normalized result representations,
not the original HTTP response sizes.

Positive multi-log cases also require the declared minimum number of logs on
both sides. Two empty responses cannot pass such a case. Preserve the endpoint
comparison separately from the case verdict. When recording full evidence,
reuse the measured response; label a subsequent diagnostic execution as a
replay, not the original response. Keep matching rejections and INFO separate
from strict successful responses in summaries.

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

After changing benchmark collection, analysis or rendering, run the offline tests:

```bash
python3 -m unittest discover -s scripts/benchmarks -p 'test_*.py' -v
```

After changing ProcessM initialization, rebuild a fresh stack and verify every
expected datastore and uploaded log. After changing compatibility queries or
comparison orchestration, run a focused report and then the broad report.

Benchmark-specific methodology is documented in `src/benchmark/AGENTS.md`.
The current plan's `budgetSeconds: null` permits an unlimited total runtime;
positive finite caps remain supported. Always record the forecast and elapsed
time, and enforce a cap only when configured. A heartbeat must not claim that
collection advanced: also report the last completed activity and its timestamp.
Individual request timeouts, strict semantic validation and immutable attempts
remain mandatory regardless of the total runtime policy.
