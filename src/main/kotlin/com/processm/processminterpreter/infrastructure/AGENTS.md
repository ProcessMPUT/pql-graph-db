# Infrastructure Layer Agent Guide

## Responsibility

Infrastructure implements application ports and exposes the application to the
outside world. Technology-specific decisions belong here.

- `config`: Spring wiring and external configuration.
- `parser`: ANTLR adapter producing domain raw syntax.
- `persistence`: Neo4j repositories, XES graph writes, Cypher generation, and
  result reconstruction.
- `processm`: remote ProcessM HTTP integration and JSON conversion.
- `web`: HTTP controllers, request/response DTOs, and response mapping.
- `xes`: XML/XES input, parsing, import, and output adapters.

Do not move technology-specific helpers into domain or application merely
because several adapters use them.

## Adapter Boundaries

- Controllers call application use cases; they do not call Neo4j or parsers.
- Repositories implement application persistence ports and map database data to
  domain/application models.
- Web DTOs stay in infrastructure and are mapped at the HTTP boundary.
- Remote ProcessM DTOs and transport details stay in the remote adapter.
- Parser adapters return domain syntax or structured compile errors.
- XES adapters preserve the complete semantic model, including metadata and
  nested/custom attributes.

Avoid adding an application port for a helper used only between infrastructure
classes. Use a port only where application owns the required capability.

## Neo4j

Keep these responsibilities separate:

- `repository`: application-facing metadata and datastore operations;
- `xes/mapping`: domain XES to persistence batches;
- `xes/writing`: transactions and batch writes;
- `xes/schema`: constraints and indexes;
- `xes/metadata`: metadata serialization;
- `property`: physical property names and nested attribute encoding;
- `query/cypher`: logical plan to parameterized Cypher;
- `query/result`: rows to hierarchical XES reconstruction.

All user values must be passed as Cypher parameters. Generated aliases and
property names require explicit controlled mapping.

Repository caches must:

- remain infrastructure details;
- have bounded lifetime or size;
- be invalidated by every write affecting cached data;
- be shared when multiple repositories mutate the same cached view;
- have focused invalidation tests.

Do not change graph shape without reviewing import, delete, query generation,
reconstruction, indexes, existing volumes, and re-import requirements.

## Web And ProcessM Compatibility

ProcessM-compatible endpoints should preserve the reference contract where that
contract is in scope. Authentication/session compatibility is not required
unless explicitly requested.

- Keep HTTP status and body handling explicit, including empty successful
  responses.
- Do not expose persistence IDs or synthetic attributes unless ProcessM does.
- Do not use response-size similarity as semantic comparison.
- Comparator exceptions for nondeterministic ordering or UUID values must be
  narrowly defined and tested.

## XES

Import/export changes must preserve:

- log attributes;
- extensions and classifiers;
- trace/event globals;
- trace and event attributes with their types;
- custom and nested attributes;
- valid uniqueness of attribute keys.

Canonical semantic equality is the contract; XML formatting and gzip bytes are
not. After parser or persistence changes, re-import test logs before manual
comparison.

## Verification

Run focused infrastructure tests first:

```powershell
.\gradlew.bat test --tests com.processm.processminterpreter.infrastructure.*
```

For Neo4j query/reconstruction changes, also run hierarchical tests. For XES
changes, run ProcessM log tests and round-trip certification. Use the full test
suite before committing cross-adapter changes.

