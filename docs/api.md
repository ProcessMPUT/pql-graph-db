# HTTP API

The default base URL is `http://localhost:8080`. JSON request bodies use
`Content-Type: application/json`. The local API does not implement authentication
or sessions. Remote comparison uses the configured ProcessM account; see
[configuration in the README](../README.md#configuration).

There are two query interfaces: `/api/data-stores` provides the ProcessM-compatible
datastore contract, while `/api/query` exposes local execution, validation, XML
export, and comparison tools.

## Datastores

Use a datastore to query a particular collection of imported logs. A log uploaded
through `/api/logs/upload` is not attached to a datastore; use the datastore upload
route when that scope is needed.

| Method | Route | Request and result |
|---|---|---|
| `POST` | `/api/data-stores` | JSON `{"name":"Study"}`; `201` with `id`, `name`, `createdAt`, and nullable `propertySize`. |
| `GET` | `/api/data-stores` | List datastore metadata. |
| `GET` | `/api/data-stores/{id}` | Retrieve datastore metadata. |
| `PATCH` | `/api/data-stores/{id}` | JSON `{"name":"New name"}`; `204`. |
| `DELETE` | `/api/data-stores/{id}` | Delete the datastore and its logs, traces, and events; `204`, or `404` if absent. |
| `GET` | `/api/data-stores/{id}/log-summaries` | List `logId`, `name`, `createdAt`, and `updatedAt` for its logs. |
| `POST` | `/api/data-stores/{id}/logs` | Multipart XES file; `201` with no body on success. |
| `GET` | `/api/data-stores/{id}/logs` | Execute PQL and return XES JSON, or a ZIP containing XES XML. |
| `DELETE` | `/api/data-stores/{id}/logs/{logId}` | Delete the specified log and its hierarchy; `204`, or `404` if it is not in this datastore. |

Import accepts plain or gzip-compressed XES containing one log. The first multipart
file is used; `file` is the conventional field name. List log summaries after
import to obtain the generated `logId`.

```bash
curl -X POST http://localhost:8080/api/data-stores \
  -H 'Content-Type: application/json' \
  -d '{"name":"Study"}'

# Replace DATASTORE_ID with the returned id.
curl -X POST http://localhost:8080/api/data-stores/DATASTORE_ID/logs \
  -F 'file=@src/main/resources/logs/sample_process.xes'

curl --get http://localhost:8080/api/data-stores/DATASTORE_ID/logs \
  --data-urlencode 'query=select e:name limit l:1, t:2, e:3' \
  -H 'Accept: application/json'
```

The query route accepts these parameters:

| Parameter | Default | Meaning |
|---|---|---|
| `query` | Empty string | PQL query within this datastore; an empty query retrieves the hierarchy subject to the configured limits. |
| `includeTraces` | `true` | Include trace data in JSON. |
| `includeEvents` | `true` | Include event data in JSON; enabling events also enables traces. |

Set **both** inclusion flags to `false` for log-only JSON. These flags control
materialization of the response, not the scope available to PQL expressions.

The default response is a JSON array of logs in ProcessM's XES JSON format, without
the `/api/query/execute` response envelope. `Accept: application/zip` instead
returns a download named `xes.zip`, containing `xes.xml`. The inclusion flags do
not restrict XML export. PQL `DELETE` is rejected on this route.

### Hierarchical limits

The datastore query route and local side of `/api/query/verify` use
`processm.compatibility.default-limits`. The configured caps are **10 logs,
30 traces per log, and 90 events per trace**. The ordinary query path fills in
missing PQL limits and caps larger explicit limits. These are per-parent windows, not
one flat row limit: `limit l:1, t:2, e:3` can return up to two traces, each with
three events. Scoped `offset` clauses skip elements at their corresponding level.

There is an implementation exception for classifier queries spanning multiple
logs: the combined result uses only the explicit PQL log limit, bypassing the
configured outer log cap. Include `limit l:10` or a smaller log limit in such
queries when using the default compatibility configuration.

The local `/api/query/execute` and `/api/query/execute-xes` routes do not apply these
compatibility defaults. Specify PQL limits explicitly when using them for a
bounded query, or omit them for a complete export.

## Local query tools

| Method | Route | Request and result |
|---|---|---|
| `POST` | `/api/query/execute` | JSON query request; result envelope described below. Optional `format=json` or `format=xes`. |
| `POST` | `/api/query/execute-xes` | Same query and scope fields; XES XML download. Optional `compress=false` and `logName=Query Result Log`. |
| `POST` | `/api/query/validate` | JSON `query` with optional `logId` or `dataStoreId`; validation result. |
| `GET` | `/api/query/features` | Static lists of clauses, operators, attributes, examples, and limitations. |
| `GET` | `/api/query/statistics` | Reserved metrics fields; all values are currently zero. |

Provide `query` and optionally **either** `dataStoreId` or `logId`:

```json
{
  "query": "select e:name, e:timestamp limit l:1, t:2, e:3",
  "dataStoreId": "DATASTORE_ID"
}
```

With neither scope field, execution can inspect all local logs, including logs
outside datastores. If both are supplied, `logId` takes precedence; the datastore
is not an additional membership check. The request also accepts `timeout` and
`maxResults`, but neither is forwarded to execution. Use PQL `limit` to control
the returned hierarchy.

`POST /api/query/execute` returns `success`, `query`, `cypherQuery`, `results`,
`resultCount`, `executionTimeMs`, `error`, and `timestamp`:

- `format=json` returns backend result rows in `results`.
- `format=xes` returns reconstructed XES JSON in `results`; it does **not** return
  an XML file.
- For a read query, `resultCount` is the backend row count, not a general count
  of returned events. For PQL `DELETE`, it counts deleted nodes, including the
  descendants removed with their parent.
- `executionTimeMs` is currently zero, not a measured duration.
- Query compilation or execution failure returns HTTP `400` with `success=false`
  and an error message.

This local execution route supports PQL `DELETE`, which modifies stored data.
The datastore `GET` route and XML export reject deletion queries.

Validation uses the compiler's preparation path, including syntax and semantic
checks and classifier metadata resolution. It does not execute the query or
prove that execution will succeed. Expected compiler errors return HTTP `200`
with `valid=false`; inspect the JSON fields `valid`, `message`, and `error`.
The `cypherQuery` field is currently `null` in validation responses.

To export a complete local log, replace `LOG_ID` with its identifier:

```bash
curl -X POST http://localhost:8080/api/query/execute-xes \
  -H 'Content-Type: application/json' \
  -d '{"query":"","logId":"LOG_ID"}' \
  --output result.xes
```

`compress=true` produces gzip-compressed XML (`application/gzip`); otherwise the
response is `application/xml`. `logName` supplies a fallback name for an unnamed
or empty result; it does not overwrite existing log names.

## Log management

| Method | Route | Request and result |
|---|---|---|
| `POST` | `/api/logs/upload` | Multipart `file`, optional `logId`; import a standalone log and return import status and counts. |
| `POST` | `/api/logs/load-sample` | Parameters `resourcePath=logs/sample_process.xes`, optional `logId` and `dataStoreId`; import a bundled resource. |
| `GET` | `/api/logs/samples` | List bundled `.xes.gz` resources as `name` and `resourcePath`. The plain default sample is not included in this list. |
| `POST` | `/api/logs` | JSON `logId`, `name`, optional `attributes` object; create empty log metadata (`201`). |
| `GET` | `/api/logs` | List log metadata; `includeStatistics=true` adds trace and event counts. |
| `GET` | `/api/logs/{logId}` | Retrieve metadata, or `404`. |
| `GET` | `/api/logs/{logId}/statistics` | Retrieve metadata with `statistics.traceCount` and `statistics.eventCount`. |
| `POST` | `/api/logs/search` | JSON search criteria described below; list matching log metadata. |
| `PUT` | `/api/logs/{logId}` | JSON with optional `name` and `attributes`; replace the supplied metadata fields. |
| `DELETE` | `/api/logs/{logId}` | Delete the log, traces, and events; return `success`, `message`, and `deletedLogId`. |
| `HEAD` | `/api/logs/{logId}` | `200` if present, `404` otherwise. |
| `GET` | `/api/logs/generate-id` | Return a suggested `logId`; this does not create or reserve a log. |

Upload accepts filenames ending in `.xes`, `.xes.gz`, or `.xes.gzip`; the default
multipart file and request limit is 500 MB. A supplied existing `logId` is rejected
instead of overwritten. Import responses include `success`, `logId`, `tracesCount`,
`eventsCount`, `message`, and optional `error` and `filename`.

Log metadata contains `logId`, `name`, `createdAt`, `updatedAt`, and `attributes`.
Date fields and search timestamps use `yyyy-MM-dd'T'HH:mm:ss`.

Search criteria are **not combined**. The implementation uses the first applicable
criterion: nonblank `name`, then `attributeKey` plus `attributeValue`, then a range
with `createdAfter` and `createdBefore`, then `createdAfter` alone. An empty request
or `createdBefore` alone lists all logs.

The delete route accepts `deleteAllData` (default `true`), but the Neo4j
implementation removes the whole hierarchy for either value. Setting it to
`false` does not preserve traces or events.

## Remote comparison

These tools require a running reference ProcessM instance configured with
`processm.api.url`, `processm.login`, and `processm.password`.

| Method | Route | Request and result |
|---|---|---|
| `GET` | `/api/query/processm/data-stores` | List the remote datastores as `id` and `name`. |
| `POST` | `/api/query/processm/upload` | Multipart `file` and `logName`; create a new remote datastore and import the file. |
| `POST` | `/api/query/verify` | Execute and compare a query locally and remotely; optional `format=full` or `format=light`. |

Example verification request:

```json
{
  "query": "select e:name limit l:1, t:2, e:3",
  "dataStoreId": "LOCAL_DATASTORE_ID",
  "remoteDataStoreId": "REFERENCE_DATASTORE_ID",
  "includeTraces": true,
  "includeEvents": true
}
```

The two datastores must contain corresponding input data. Local `logId` can be used
instead of `dataStoreId`. Specify `remoteDataStoreId` explicitly: omission selects
the first remote datastore. The accepted `logName` verification field does not
select or filter a log. Inclusion flags default to `false` here, unlike the
datastore query route; set both to `true` to compare the complete returned
hierarchy. Use read queries for comparison: this endpoint does not guard the
local execution path against PQL `DELETE`.

The response includes `comparisonStatus`, `match`, both execution success flags,
log counts, diagnostic `details`, and the remote request information:

- `MATCH`: responses match under the comparator's rules, or both systems reject
  the query with the same recognized error category. Check `localSuccess` and
  `remoteSuccess` to distinguish these outcomes.
- `MISMATCH`: responses or execution outcomes differ.
- `NONDETERMINISTIC_MATCH`: an informational result for recognized ordering or
  boundary-window differences. It keeps `match=false` and is not strict equality.

`format=light` omits the returned local and remote result arrays; comparison still
runs. This endpoint is for compatibility checks, not latency measurement.
