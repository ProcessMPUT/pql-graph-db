# ProcessM Interpreter — Neo4j

A standalone REST service that executes ProcessM's Process Query Language (PQL)
over XES event logs stored in Neo4j. It translates PQL into Cypher, reconstructs
the log–trace–event hierarchy, and exposes XES JSON and XML results.

The project investigates an alternative to ProcessM's PostgreSQL implementation.
Compatibility and performance are checked against the reference system; a graph
representation does not imply that every query is faster.

- [API reference](docs/api.md): requests, formats, limits and implementation caveats.
- [Benchmark guide](docs/benchmark-study.md): preparation, collection, recovery and reports.
- [Measurement methodology](src/benchmark/METHODOLOGY.md): workloads, metrics and inference.

## Requirements

- **JDK 26**, as declared in [build.gradle.kts](build.gradle.kts). The configured
  toolchain resolver can download it if another suitable JVM is available to
  start Gradle; the wrapper still requires Java to start.
- **Docker with Compose v2** for Neo4j, reference ProcessM and integration tests.
- **Python 3.10 or newer** for the scripts, which use the standard library only.
- No global Gradle installation is needed: the wrapper pins **Gradle 9.5.0**.

Commands run from the repository root. On Windows, replace `./gradlew` with
`.\gradlew.bat` and `python3` with `python`. Shell examples use POSIX syntax.
If needed on macOS/Linux, make the wrapper executable with `chmod +x gradlew`.

## Run locally

For development, start Neo4j in Docker and the application on the host:

```bash
docker compose up -d --wait neo4j
./gradlew bootRun
```

Open the comparison UI at [localhost:8080](http://localhost:8080/).
REST endpoints are under `http://localhost:8080/api`; see the
[API reference](docs/api.md) for a create–import–query example.

To enable comparisons with ProcessM, also start the reference and its initializer:

```bash
docker compose up -d processm processm-init
docker compose logs -f processm-init
```

The initializer creates the reference account and imports Hospital, JournalReview,
Sepsis and teleclaims, plus a JournalReview + Sepsis datastore. Wait for it to
finish before comparing results. It seeds **REFERENCE only**: import the same
files into LOCAL through the UI or API.

| Service | Address | Development credentials |
|---|---|---|
| Interpreter UI / API | `http://localhost:8080/` / `/api` | No local login required |
| Neo4j Browser / Bolt | `http://localhost:7474/` / `bolt://localhost:7687` | `neo4j` / `password123` |
| Reference ProcessM | `http://localhost:80/` | `admin@example.com` / `Admin1234` |

### Run the application in Docker

Stop any host application using port 8080 first, then build the JAR before
building the application image:

```bash
./gradlew bootJar
docker compose up -d --build
docker compose ps
```

The Dockerfile expects `build/libs/processm-interpreter.jar`. Rebuild the JAR
and image after changing application code. The application reaches Neo4j and
ProcessM by their Compose service names inside the Docker network.

This Compose setup is for development. Use the
[benchmark controller](docs/benchmark-study.md) for measured runs: it prepares
the resource limits, verifies image identities and controls seeding.

### Stop and inspect services

```bash
docker compose logs --tail=100 neo4j
docker compose logs --tail=100 app
docker compose stop
```

Use `Ctrl+C` to stop a foreground `bootRun`. `docker compose stop` retains the
containers; `docker compose start` starts them again. `docker compose down`
removes containers, and adding `-v` also removes the declared volumes, including
Neo4j data. The reference service has no explicitly configured persistent data
volume in this Compose file; do not rely on its datastores surviving recreation.

If startup fails, check Docker health, available memory, occupied ports and the
configured Neo4j credentials. Avoid running the host application and the Compose
`app` service on port 8080 at the same time.

## PQL examples

A request supplies the datastore or log to query. Within PQL, `l:`, `t:` and
`e:` refer to log, trace and event attributes. Standard shorthand such as
`e:name` resolves to `event:concept:name`; custom attributes use brackets,
for example `[e:attr_1]`.

```sql
-- At most one log, ten traces per log and twenty events per trace
select e:name limit l:1, t:10, e:20

-- Filter events by their activity name
where e:name = 'Task A' limit l:1, t:10, e:20

-- Count named events at log scope, with timestamp bounds
select count(^^e:name), min(^^e:timestamp), max(^^e:timestamp) limit l:1, t:1

-- Group traces by their event-name sequence and return group counts
select count(t:name) group by ^e:name order by count(t:name) desc limit l:1, t:3
```

The `^` operator lifts an attribute by one level in the hierarchy;
`^^e:name` lifts it to log scope. Hoisting changes the scope of filters,
grouping and aggregates. `count(attribute)` counts non-null values, so counting
names equals counting components only when each component has a name.

Limits apply separately at each hierarchy level. In particular, `e:20` means
twenty events **per returned trace**, not twenty events in the entire response.
The ProcessM-compatible datastore endpoint also applies default upper limits;
the local execution endpoints have different defaults. See the
[API reference](docs/api.md#hierarchical-limits).

## Configuration

Defaults are in [application.yml](src/main/resources/application.yml).
Use environment variables to override them:

| Setting | Default | Environment variable |
|---|---|---|
| HTTP port | `8080` | `SERVER_PORT` |
| Neo4j URI | `bolt://localhost:7687` | `SPRING_NEO4J_URI` |
| Neo4j username | `neo4j` | `SPRING_NEO4J_AUTHENTICATION_USERNAME` |
| Neo4j password | `password123` | `SPRING_NEO4J_AUTHENTICATION_PASSWORD` |
| Reference API | `http://localhost:80/api` | `PROCESSM_API_URL` |
| Reference login / password | `admin@example.com` / `Admin1234` | `PROCESSM_LOGIN` / `PROCESSM_PASSWORD` |

The application accepts `.xes` and `.xes.gz` uploads. Spring's multipart file
and request limits default to 500 MB. The reference
system's upload limit is separate; the benchmark verifies that its inputs fit
the stock reference limit.

Compatibility limits default to 10 logs, 30 traces per log and 90 events per
trace. Request fields named `timeout` and `maxResults` are currently not enforced
by the local execution service; use PQL limits to bound results. The API reference
documents these and other placeholder fields.

## Architecture and storage

Source packages under `com.processm.processminterpreter` group code by feature:

| Package | Responsibility |
|---|---|
| `pql` | AST, attribute/function catalog, parser, semantic analysis, logical plans, Cypher generation and query service |
| `neo4j` | Schema, repositories, XES import, query execution and hierarchy reconstruction |
| `xes` | Datastore/log model, services and XES XML input/output |
| `processm` | Reference HTTP client, XES JSON conversion and semantic comparison |
| `web` | REST controllers and request/response DTOs |

The main query path is:

```text
PQL → ANTLR parser → Resolver → Validator → Planner
    → CypherCodegen → Neo4j driver → HierarchyReconstructor → JSON or XES XML
```

`PqlCompiler` coordinates parsing and semantic preparation. Neo4j access uses
the driver directly. The repositories and remote ProcessM gateway are the
substitution boundaries used by services and tests.

The persistent hierarchy is:

```text
(:DataStore)-[:CONTAINS_LOG]->(:Log)-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->(:Event)
```

A datastore can contain multiple logs. Node identifiers and parent identifiers
support scoping; `importOrder` preserves source order. Standard XES attributes
map to physical properties such as `Event.activity` and `Event.timestamp`.
The persistence layer also keeps custom/nested attributes and log metadata for
reconstruction and export.

An optional `FOLLOWS` relationship can be written during import, but it is
disabled by default (`processm.neo4j.persist-follows=false`) and is not used
to execute PQL. It does not determine PQL's default event order.

Other source locations:

- `src/main/resources/static/`: comparison UI.
- `src/main/resources/logs/`: bundled XES fixtures, mostly gzip-compressed.
- `src/test/`: unit and integration tests, including tests ported from ProcessM.
- `src/benchmark/`: HTTP measurement collector and declared workloads.
- `scripts/`: development, compatibility and benchmark tools.
- `tmp/`: generated reports, evidence and local working files; ignored by Git.

## Verification and development

Compile application, test and benchmark sources before running a broad suite:

```bash
./gradlew compileKotlin compileTestKotlin compileBenchmarkKotlin
./gradlew test
```

The test task includes Neo4j integration tests that start containers through
Testcontainers, so Docker is required. It does not run the full thesis study.
Benchmark analysis and reporting have a separate offline test suite:

```bash
python3 -m unittest discover -s scripts/benchmarks -p 'test_*.py' -v
```

### Compare with reference ProcessM

Start both systems and import identical input files into corresponding
datastores. Copy the tracked case templates to machine-local files:

```bash
cp scripts/verify-compatibility.cases.example.json scripts/verify-compatibility.cases.local.json
cp scripts/verify-compatibility.multi-log.cases.example.json scripts/verify-compatibility.multi-log.cases.local.json
```

Replace the placeholder IDs with the real LOCAL and REFERENCE datastore IDs.
The multi-log case also needs the actual log names. Then run:

```bash
python3 scripts/run-compatibility-report.py \
  --query-source dropdown \
  --include-multi-log-checks \
  --measure-payload-size
```

The default query source is the UI's query menu. `--query-source thesis` uses
the frozen thesis compatibility cases instead. Reports are written under
`tmp/compatibility-reports/`; exit code 1 means strict problems were found.
`INFO` cases and matching rejections are reported separately from matching
successful responses. Equal status codes or payload sizes do not prove
semantic equality.

### Contributing

Read [AGENTS.md](AGENTS.md) and the guide for the affected subtree:
[scripts](scripts/AGENTS.md), [benchmark](src/benchmark/AGENTS.md), or
[ported ProcessM tests](src/test/kotlin/com/processm/processminterpreter/processm/AGENTS.md).
They describe semantic invariants, ownership and verification requirements.

Preserve the reference semantics when changing parsing, scope, grouping,
ordering, XES data or response reconstruction. Use parametrized Cypher and
keep REST controllers focused on mapping requests to services. Changes to
persistence or XML parsing require freshly imported data for meaningful
compatibility checks.

Shared project instructions live in the tracked `AGENTS.md` files.
Tool-specific settings, including `.codex/`, remain local and ignored.

## Reference sources

- [ProcessM project](https://processm.cs.put.poznan.pl) and
  [source repository](https://github.com/ProcessMPUT/processm).
- [PQL specification](https://github.com/ProcessMPUT/processm/blob/master/docs/pql.md),
  [ANTLR grammar](https://github.com/ProcessMPUT/processm/tree/master/processm.core/src/main/antlr4/processm/core/querylanguage),
  [Query.kt](https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/main/kotlin/processm/core/querylanguage/Query.kt)
  and [TranslatedQuery.kt](https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/main/kotlin/processm/core/log/hierarchical/TranslatedQuery.kt).
- Reference [parser tests](https://github.com/ProcessMPUT/processm/tree/master/processm.core/src/test/kotlin/processm/core/querylanguage),
  [hierarchical interpreter tests](https://github.com/ProcessMPUT/processm/tree/master/processm.core/src/test/kotlin/processm/core/log/hierarchical)
  and [XES logs](https://github.com/ProcessMPUT/processm/tree/master/xes-logs).

For a local reference checkout, set `PROCESSM_REFERENCE_REPO` or place the
ProcessM repository at `../processm`. Otherwise use the linked upstream sources.
