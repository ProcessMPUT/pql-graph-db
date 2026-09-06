# PQL benchmark methodology

This document describes protocol 27, implemented by
[`study-plan.json`](resources/study-plan.json), the
[dataset definitions](resources/benchmark-datasets.json) and the
[query definitions](resources/benchmark-queries.json). For commands, prerequisites,
progress and recovery, see the [execution guide](../../docs/benchmark-study.md).
The repository plan is a draft template. A campaign records the frozen plan,
definition hashes and measured environment that govern its results.

## Aim and experimental units

The primary hypothesis is that the graph-backed interpreter gives shorter
response times for queries that traverse the XES hierarchy. LOCAL comprises the
interpreter and Neo4j; REFERENCE comprises ProcessM and PostgreSQL. The comparison
measures complete HTTP operations, including server execution, hierarchy
reconstruction and response receipt. It does not isolate the database model.

The report covers PQL compatibility, XES preservation, query latency, the effects
of input and result characteristics, import time, memory, I/O and persistent
storage. These measurements are not combined into a single ranking.

| Term | Meaning |
|---|---|
| Log | One input XES dataset |
| Series | Logs varying a declared factor, such as event count |
| Block | One query on one log within a task |
| Pair | Adjacent LOCAL and REFERENCE invocations of that query |
| Preparation | Fresh processes and database volumes before a task |
| Task | The indivisible collection and recovery unit, described by `job.json` |
| Campaign | The complete set of planned tasks |
| Process variant | The sequence of event names in a trace |

Repeated HTTP pairs within a preparation are technical repetitions. They are
not independent preparations for statistical inference.

## Measurement design

| Part | Scope | Repetitions and interpretation |
|---|---|---|
| Primary hypothesis | Three queries on 100k and 1m events | 12 independent preparations; six tests with Holm correction |
| Descriptive latency | 168 blocks on 28 logs | One preparation per coverage task; 30 pairs per block |
| Import | All seven synthetic log sizes | Five import pairs per size; descriptive estimates |
| Memory and I/O | 55 blocks on seven logs | Separate resource tasks; at least ten seconds per system per block |
| Storage | All seven synthetic log sizes | One isolated measurement per size |
| Compatibility | 69 cases on each of four logs, plus six multi-log cases | Separate task with strict and informational outcomes |

The primary queries are `hierarchyWindow`, `hoistedPositive` and
`hierarchyCardinality`: retrieving a hierarchy window, selecting traces through
an event condition, and counting hierarchy components. These queries represent
the hierarchy operations addressed by the hypothesis, rather than a random or
representative sample of all PQL queries. Each primary preparation measures
`minimalWindow` on both sizes as a descriptive baseline. Thus, twelve preparations
contain 96 blocks, but the statistical family contains only six comparisons.

Each final latency block has exactly **30 measured pairs**. Neither the pilot
forecast nor observed p-values may change this count. Variant grouping, global
event aggregation, other controls and the wider dataset matrix receive descriptive
estimates without additional p-values from
a single preparation. Support for the hypothesis is limited to the scope of the
six primary comparisons.

The matrix is defined by each query's `measurementSeries` and, where present,
`measurementDatasets`. It is not the Cartesian product of logs and queries.
Scaling claims additionally follow `scalingSeries`. In the size series,
`variantGroupCount`, `genericVariantGroup`, `globalEventAggregation` and
`hierarchyCardinality` use 1k, 100k and 1m events. Other size queries use all seven
sizes. Missing intermediate cells are not interpolated or replaced by results
from primary preparations. All seven sizes have XES checks, import and storage
measurements.

## Datasets and controlled factors

Both systems receive identical input bytes. Synthetic generation is deterministic;
file and definition hashes are recorded and verified on reuse. A custom attribute
count of five means five additional event attributes, beyond the standard XES
attributes generated for every event.

| Series | Dataset configuration | Factor varied |
|---|---|---|
| Size | 1k, 5k, 20k, 100k, 200k, 500k and 1m events; ten events per trace, one variant, five custom attributes | Number of events |
| Variants | 100k events, 2,000 traces of 50 events, 48 activities, five custom attributes | 1, 100 or 2,000 event-name sequences |
| Selectivity | 100k events, 10,000 traces of ten events, ten activities | Matching trace and event shares of 1%, 10% or 100% |
| Trace length | 100k events, ten activities and a ten-position cost cycle | 10, 100 or 1,000 events per trace |
| Response size | The same `variants-1` input; unsorted windows of 20 events per returned trace | 20, 200 or 600 returned events |
| Real logs | Twelve complete XES inputs with DOI provenance in the dataset definitions | Observed differences between real processes |

The real inputs are Sepsis, Hospital, BPIC12, the five BPIC15 municipality logs,
three BPIC13 logs and Road Traffic. They are not a random sample of processes.
Compressed inputs must fit the stock REFERENCE upload limit of 5 MiB; do not
modify REFERENCE or silently sample a published log to bypass it.

Selectivity inputs distribute matching traces evenly through source order.
Every event in a matching trace has `attr_1=hit`; every other event has
`attr_1=out`, so trace and event selectivity coincide. `selectivityEventFilter`
uses `[e:attr_1] = 'hit'`, while `selectivityTraceFilter` uses
`[^e:attr_1] = 'hit'`. The first filters events; the second selects whole traces
through their events. On these inputs, both select the same trace population.
Both return `count(t:name)` as an attribute of one result trace. The `t:1`
window does not cap the number of traces counted. The expected values are 100,
1,000 and 10,000; their type, key `count(trace:concept:name)`, scope and result
cardinalities are checked independently of agreement between the systems.

The trace-length series keeps the event count and cost distribution fixed while
changing the number of traces. The response-size series measures the full query
and reconstruction path, rather than isolating serialization or transmission.
Its 200-event point is `hierarchyWindow`; the other two windows provide the
endpoints. Synthetic names, timestamps and attribute values have regular
distributions. In particular, `hoistedPositive` matches every trace of the size
series, and `hierarchyWindow` returns 100 events there. The separate selectivity
and response-size series are needed to study those factors.

Memory and I/O use `size-1k`, `size-100k`, `size-1m`, `variants-1`,
`variants-2000`, `real-sepsis` and `real-hospital`. These cover size anchors,
variant endpoints and two real logs. Resource observations are not interpolated
for the remaining inputs.

## Preparation, pilot and collection

The Compose benchmark configuration gives each complete system 6 GiB of memory,
3 GiB of aggregate maximum JVM heap and no swap. Preparation verifies effective
limits, runtime health, exact image identities and empty APIs. It also records a
single-use proof of fresh volumes. The final campaign must match the clean source
revision, images and recorded hardware configuration of its pilot. Compilation,
input generation and unrelated host work must not overlap measured query blocks.

The pilot is a separate diagnostic campaign on ten logs: `size-1k`, `size-1m`,
`variants-1`, `variants-2000`, `real-road-traffic`, all three selectivity inputs,
`trace-length-10` and `trace-length-1000`. It covers 47 latency blocks, using two
global warmup rounds, one block warmup invocation per system and two measured
pairs per block. It checks XES before timing, then compares measured responses
and declared expected values. A single resource probe uses `hierarchyWindow` on
`size-1k`; only that input's import is sampled. An additional stack preparation
measures the cost of reusing the LOCAL image.

The pilot has a cumulative 1,800-second cap, including preparation and attempts.
Incomplete evidence blocks plan freezing. The final plan has no cumulative cap
(`budgetSeconds: null`), while individual requests have timeouts. A forecast
with a 25% reserve is required before freezing. It uses arithmetic mean pair
costs separately for warmup and measurement, observed stack preparation, imports,
global warmup and resource overheads. Missing query costs may use only the same
query and series; size import costs are interpolated between pilot endpoints,
and other import costs use the slowest observed input in their series. Fixed
allowances include ten minutes for compatibility. These forecast assumptions do
not change the median estimand for results. Two pilot pairs cannot establish
stationarity or provide a reliable upper bound on final runtime.

The final campaign executes compatibility, descriptive latency and XES checks,
primary preparations, repeated imports, resource tasks and isolated storage, in
that order. Within a task, a recorded seed determines dataset and query order.
Each latency task starts with 200 global warmup rounds on a separate log of
100 traces and ten events per trace. A round executes each task query once per
system. The warmup stores are then removed. Each measured block has 40 warmup
invocations per system followed by 30 adjacent pairs, alternating LR/RL. Each
system runs first in 15 measured pairs. XES preservation checks follow latency
collection for each coverage input, so they do not precede its timed blocks.

The query timer includes request construction, HTTP execution, receipt of the
complete response and its byte count. Response cardinality parsing follows the
timer. Authentication, data generation, file persistence, full semantic comparison
and Docker probes are excluded. Every measured response is retained in memory
until the block ends, then persisted and compared with its counterpart. A small
atomic progress update occurs before and after each request, outside its timed
interval. Missing responses and failures remain failures, never zero durations.

Before each adjacent pair, the collector checks session validity and renews if
needed, outside timing. It reserves at least 35 minutes, covering two ten-minute
query timeouts or a fifteen-minute upload followed by fifteen minutes of readiness
polling. The same check precedes import, export and cleanup; a resource window
adds its declared duration to the reserve. Renewal events contain no tokens.
HTTP 401/403 is an authentication failure, not a PQL rejection or a request to
silently repeat a timed sample.

## Correctness requirements

The separate compatibility task exercises 69 named cases on Hospital,
JournalReview, Sepsis and teleclaims, plus six cases on a JournalReview + Sepsis
datastore. Cases include expected responses and expected rejections. Equal error
categories are reported separately from equal returned responses. Informational
cases, such as nondeterministic boundary selection, retain their explanation and
are not presented as strict response equality. Any strict problem fails the task.

Every measured latency pair must pass full semantic comparison, including XES
attributes and metadata; equal counts or response sizes are insufficient. Declared
cardinalities and aggregate values are an additional, independent control. Missing
or unequal evidence prevents acceptance of a block. A semantic mismatch cannot
be converted into compatibility by dropping attributes or selecting another run.
LOCAL also undergoes a complete import/export XES comparison on each of the 28
coverage logs. These checks support preservation for the tested files, not a
proof for every possible XES document.

## Statistical analysis and interpretation

For primary comparison `c` and independent preparation `r`, let `L_r` and `R_r`
be the 30 LOCAL and REFERENCE times:

```text
E_r = median(R_r) / median(L_r)
X_r = log(E_r)
E   = exp(median(X_1, ..., X_12))
```

`E > 1` indicates shorter LOCAL times. `X_r` is one independent observation.
The summary table's LOCAL and REFERENCE columns are medians of preparation
medians, so their quotient need not equal `E`.

The hypotheses are `H0: median(X) = 0` and `H1: median(X) != 0`. The exact
two-sided sign test uses binomial sign counts across preparations. Zero effects
are assigned conservatively against the stronger observed direction. The median
interval uses order statistics with at least 95% coverage, transformed back to
the ratio scale. It is a pointwise interval, not a simultaneous family interval.

Holm correction covers the six predeclared comparisons. An incomplete comparison
keeps its place in the correction as `p = 1` and receives no final verdict. A
corrected p-value below 0.05 and the effect's direction determine the reported
advantage. Failure to reject `H0` does not establish equivalence.

Twelve preparations permit rejection with eleven effects in one direction and
one in the other: the two-sided p-value is `26/4096`, below 0.05 after
multiplication by six. This explains the attainable test resolution, not power
or a guarantee of detecting a difference. Sample size is not selected using
final p-values.

The descriptive matrix and five import pairs report medians, inclusive quartiles,
raw observations and ratios. Diagnostics compare the first and last thirds of
final latency blocks and LR/RL order. Their 1.10 ratio threshold is descriptive,
not a test or a reason to exclude unfavourable observations. Two pilot pairs do
not separate order effects from ordinary variation or establish within-block drift.
The calculations are implemented once, in
[`study_analysis.py`](../../scripts/benchmarks/study_analysis.py).

Reported differences concern complete implementations under the recorded
conditions. Import metadata may accelerate some grouping queries. Without query
plan evidence, a timing difference cannot be attributed to a specific operator,
index or the database model alone.

## Memory, I/O, import and storage

Resource tasks are separate from latency tasks. They use 20 global warmup rounds,
five preparatory query invocations per system and repeated requests in a window
of at least ten seconds per system. The final request is allowed to complete,
so actual windows can be longer. The collector records their boundaries and
completed request counts. The sampler is paused, and its in-flight probe awaited,
before latency requests.

Memory comes from Docker container statistics, which exclude part of inactive
cache rather than measuring JVM heap alone. Sampling targets a one-second
interval, but a slow probe increases the gap. Each window requires at least three
distinct in-window samples per component. LOCAL's interpreter and Neo4j samples
from the same poll are summed **before** calculating their median and observed
maximum. This is not an atomic snapshot of both processes. Samples for the inactive
system remain in the evidence but are excluded from the active-system summary.
The maximum observed sample is not a guaranteed peak.

Resource-task imports are also sampled. A short import may finish without a
sample; this remains missing data, not zero usage or a sample taken after the
operation. These observations are not a cold-start measurement or resource
coverage for all 28 logs.

I/O is the difference between counters surrounding a window. Block I/O byte
counters are required for every component; network byte counters are required
at the application boundary. Missing cgroup operation counts may be marked
`PARTIAL`. Counter resets invalidate a window. Since a faster system can complete
more requests, total window bytes must be interpreted alongside request count
and duration rather than treated as a per-query cost.

Import time includes upload and API-observed readiness. After a negative status
poll, the collector waits 100 ms in addition to the poll's own cost. This measures
observed completion, not an exact internal server timestamp. Each of five import
pairs uses fresh datastores in running processes. Preparatory imports in other
tasks do not increase that sample.

Each storage task invokes the storage helper for one log size with fresh volumes
and the same input bytes and image identities as the coverage measurements. The
reported quantity is the increase in database-file size. Storage identities and
input hashes are checked again during publication.

## Evidence and recovery

Tasks preserve the evidence required for their phase: `job.json`, definition
snapshots, dataset hashes, measurement CSV files, full measured responses,
`execution.jsonl`, environment records before and after collection, fresh-volume
proof and cleanup results. Compatibility and storage tasks preserve their
respective reports and input identities. The controller validates the required
files and records a SHA-256 manifest before accepting a task. It verifies the
manifest before reuse.

Recovery restarts a failed task, not an individual pair, and retains every attempt.
At most one replacement is allowed, with a recorded infrastructure reason. A
synthetic-series task is repeated as its whole declared series. Already accepted
tasks survive a later failure. Elapsed time never resets on resume. Semantic or
control failures require diagnosis; they cannot be retried in pursuit of `MATCH`.
Changes to implementation, inputs or design require a new campaign.

The campaign directory contains its recorded plan, state, inputs, task attempts
and report publications. Offline report generation reads these saved definitions
and evidence, validates their identity and creates a new publication directory.
An incomplete preview remains visibly incomplete. Preserve measured provenance;
editing the report must not attribute measurements to a different source revision.
