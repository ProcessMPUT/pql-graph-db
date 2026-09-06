# Running the benchmark study

[`benchmark-study.py`](../scripts/benchmarks/benchmark-study.py) is the entry
point for planning, pilot collection, final collection, recovery and reporting.
The [methodology](../src/benchmark/METHODOLOGY.md) explains what is measured and
how to interpret the results. The checked-in
[`study-plan.json`](../src/benchmark/resources/study-plan.json) is a draft
template for protocol 27; each campaign preserves its own plan and evidence.

Run the commands below from the repository root. On Windows, replace `python3`
with `python` and `./gradlew` with `.\gradlew.bat`.

## Prepare without starting services

The tools need Python 3, the repository's Java/Gradle toolchain, and Docker
Compose for live collection. Python scripts use only the standard library.
On a fresh POSIX checkout, make the wrapper executable with `chmod +x gradlew`.

```sh
python3 scripts/benchmarks/benchmark-study.py plan
./gradlew prepareBenchmark compileKotlin compileTestKotlin
./gradlew test --tests 'com.processm.processminterpreter.benchmark.*' --offline
python3 -m unittest discover -s scripts/benchmarks -p 'test_*.py' -v
```

`plan` validates the definitions and prints the matrix without contacting Docker
or either API. `prepareBenchmark` compiles the collector and writes its Java
executable and classpath under `build/`; it does not start services. The first
Gradle invocation may download dependencies. The controller later invokes
`prepareBenchmark --offline`, so prepare the dependency cache before collection.

The final plan contains 41 tasks:

| Phase | Tasks | Scope |
|---|---:|---|
| Compatibility | 1 | 69 cases on each of four logs and six multi-log cases |
| Descriptive latency and XES preservation | 16 | 168 query–log blocks on 28 logs |
| Primary comparisons | 12 | Six comparisons plus two descriptive baseline blocks per preparation |
| Import | 1 | Five LOCAL/REFERENCE pairs on each of seven log sizes |
| Memory and I/O | 4 | 55 query–log blocks on seven logs |
| Storage | 7 | One isolated measurement per log size |

Every final latency block contains **30 measured LOCAL/REFERENCE pairs**,
with each system first in 15 pairs. This fixed count applies to both
descriptive and primary measurements. Including 40 warmup invocations per system
per block, the 168 descriptive and 96 primary blocks require 36,960 HTTP requests.
Global warmup and other phases are additional work. The resource windows alone
take at least 18 minutes 20 seconds; these counts are not a campaign duration estimate.

## Run the pilot and freeze the plan

Live collection recreates the measured Docker stack and **deletes its Compose
volumes**, including data used by the development stack. Both `--execute` and
`--confirm-destroy-volumes` are required to acknowledge collection and volume
removal. The commands can then run unattended, with progress and failures recorded.

The repository must be clean and committed before collection. The controller
checks this before preparing the stack; it does not commit changes. Do not run
another benchmark or competing host workload at the same time. The benchmark
Compose configuration assigns 6 GiB to each complete system with no swap and
equal aggregate JVM heap ceilings. The preparation check also requires these
12 GiB to occupy no more than 85% of the memory available to Docker.

```sh
python3 scripts/benchmarks/benchmark-study.py pilot \
  --out tmp/study-27-pilot --execute --confirm-destroy-volumes
python3 scripts/benchmarks/benchmark-study.py report --out tmp/study-27-pilot
python3 scripts/benchmarks/benchmark-study.py freeze \
  --pilot tmp/study-27-pilot --out tmp/study-27-frozen.json
```

The pilot checks ten logs, including the largest size, every selectivity level,
both trace-length endpoints and every response window. Its 47 latency blocks
contain one warmup invocation per system followed by two measured pairs, one LR
and one RL. Two global warmup rounds add 72 requests to the 282 block requests.
The pilot also checks full XES preservation, semantic response equality, declared
response counts and aggregate values. Its only resource probe is
`size-1k` / `hierarchyWindow`, with a ten-second window per system and at least
three samples per required component. Import sampling is limited to `size-1k`.

The pilot has a **30-minute cumulative cap** (`pilot.budgetSeconds: 1800`),
including stack preparation and any permitted replacement attempt. Exceeding it
leaves an incomplete pilot, which cannot be used to freeze a plan. Pilot data
never enter final result estimates.

`freeze` validates the completed pilot and produces a new file; it refuses to
overwrite an existing frozen plan. The file binds the measurement design to the
pilot evidence and a runtime forecast with a 25% reserve. The forecast uses
observed preparation, import, warmup and request costs, borrowing an unmeasured
cell only from the same query and dataset series. Missing required evidence
blocks freezing. Two diagnostic pairs provide a rough estimate, not a guarantee
of completion time or proof that final warmup reaches a stationary state.

The final plan uses `budgetSeconds: null`, so it has **no cumulative runtime cap**.
Individual request timeouts and completeness checks apply. A positive
integer in this field enables an optional cap: the forecast must fit within it,
and elapsed collection time, including prior attempts, cannot exceed it. Changing
the frozen design invalidates its link to the pilot; final repetition counts
must not be reduced to meet a runtime target.

## Collect the final campaign

```sh
python3 scripts/benchmarks/benchmark-study.py run \
  --plan tmp/study-27-frozen.json --out tmp/study-27-final \
  --execute --confirm-destroy-volumes
```

The controller runs the phases in the table above. It validates each task before
accepting it, including response evidence, input hashes, environment identity,
required samples and cleanup. A successful collector exit alone is insufficient.
The final campaign requires the same Git revision, images and recorded hardware
configuration as the pilot. Keep the pilot directory available: the frozen plan
refers to its path and manifest. The first pilot task builds LOCAL; subsequent
preparations reuse its exact image rather than rebuilding it.

Input files are cached under the campaign's `inputs/` directory and checked by
SHA-256 before reuse. Hard links avoid copying large files when possible. Reusing
input bytes does not reuse a database: each task starts with fresh processes and
volumes, and imports its data again. The storage tasks use the same size-series
inputs as the latency tasks.

Open `progress.html` in the campaign directory for live status. The controller
updates `progress.json` about every five seconds with the task, attempt, phase,
elapsed time and collector status. Each task's `result/live-progress.json` records:

- `current.startedAt`: when the current operation started;
- `completedOperations`: how many operations finished;
- `lastCompleted.completedAt`: when the last operation finished.

`updatedAt` is a heartbeat, not proof that a query or import has completed. When
the current operation appears stuck, inspect the active task's `collector.log`,
`preparation.log` or `build.log` and its `result/execution.jsonl`. A long interval
without completion does not justify discarding a result or restarting a sample.

REFERENCE sessions normally expire after three hours. The collector checks
session validity before every adjacent request pair and renews outside the timed
interval. The same check precedes imports, exports and cleanup. It reserves at
least 35 minutes of validity, plus the declared duration before a resource window.
Renewals are journalled without tokens. HTTP 401/403 remains an authentication
failure; the timed request is not silently replayed or counted as a PQL rejection.

## Resume after an interruption

Repeating the final `run` command verifies and reuses completed tasks. A failed
or interrupted task requires an explicit infrastructure failure reason:

```sh
python3 scripts/benchmarks/benchmark-study.py run \
  --plan tmp/study-27-frozen.json --out tmp/study-27-final \
  --execute --confirm-destroy-volumes \
  --retry-failed 'Docker stopped; restored without changing code or configuration'
```

The controller restarts the **whole task** in a fresh environment, at most once.
It preserves the first attempt and its elapsed time. There is no resumption from
an individual HTTP pair, no selection of the fastest attempt, and no need to repeat
completed latency tasks after a later resource or storage failure. The same retry
option applies to `pilot`, within its cumulative cap.

Semantic and declared-control failures cannot be retried until they happen to
pass. Investigate the cause; an implementation or workload change requires a new
campaign and pilot. A controller lock prevents concurrent campaigns using this
tool, but does not prevent unrelated programs from loading the host.

## Audit and rebuild the report offline

```sh
python3 scripts/benchmarks/benchmark-study.py audit --out tmp/study-27-final
python3 scripts/benchmarks/benchmark-study.py report --out tmp/study-27-final
```

These commands use the campaign's recorded plan and definitions, not the working
draft, and do not contact databases. `audit` requires a complete matrix and valid,
unchanged evidence. `report` can also publish an explicitly incomplete preview;
it does not fill missing cells with other runs or claim a final result from them.

Each publication uses a separate directory under `reports/`; published files are
not overwritten. It contains a self-contained `benchmark-report.html`, Markdown,
SVG figures, CSV summaries and LaTeX tables. The main CSV exports are:

| File | Content |
|---|---|
| `primary-comparisons.csv` | Six primary effects, intervals, p-values and conclusions |
| `descriptive-comparisons.csv` | Pair counts, medians, quartiles and time ratios |
| `latency-diagnostics.csv` | Request-order and within-block drift diagnostics |
| `resource-observations.csv` | Active-system memory observations and sample coverage |

Keep the **whole campaign directory**, rather than only the HTML file: it holds
the immutable attempts, input and response data, environment records and manifests
needed to reproduce the report. Keep the pilot package alongside it to preserve the
evidence used to freeze the plan. Report generation records the generator identity
separately from the measured source revision. Text and figure edits require only
`report`; they do not require repeating measurements or changing raw evidence.

Use a single completed campaign for thesis numbers, tables, figures and
conclusions. The six primary comparisons support statistical conclusions;
the wider matrix remains descriptive. Plot axes are linear, and unmeasured cells
remain absent.
