# Application Layer Agent Guide

## Responsibility

The application layer exposes use cases and ports. It decides what operation is
performed and in what order, but not how HTTP, Neo4j, ANTLR, XML, or remote
ProcessM implement it.

Allowed dependencies:

- domain models and domain services;
- application request/result models;
- application ports.

Forbidden dependencies:

- Spring MVC controllers or HTTP DTOs;
- Neo4j driver types, Cypher, persistence rows;
- ANTLR-generated types;
- XML parsers/writers and filesystem details;
- concrete remote ProcessM clients.

Spring `@Component` is accepted for use-case wiring, but application behavior
must remain testable without starting Spring.

## Package Roles

- `query`: compile, validate, execute, and export PQL use cases.
- `log`: log CRUD and XES import orchestration.
- `datastore`: datastore lifecycle and log membership operations.
- `compatibility`: orchestration and semantic comparison against ProcessM.
- `ports`: contracts implemented by infrastructure adapters.

Do not organize application code by technology. A ProcessM HTTP client belongs
in infrastructure; only its required gateway contract belongs here.

## Use Cases

- A use case may validate input, invoke domain logic, coordinate multiple
  ports, and translate expected failures into application-level results.
- A use case must not generate Cypher, parse XML, shape HTTP responses, or know
  database node properties.
- Keep trivial related CRUD use cases grouped when that reduces file noise.
  Give complex orchestration its own file.
- Do not create pass-through services that only rename another use-case method.
- Request and result types should describe the application operation, not a
  controller payload or persistence record.

## Ports

Add a port when application logic needs a capability owned outside the layer,
not merely to increase interface count.

- Name ports by capability: `PqlParser`, `QueryPlanExecutor`, `XesWriter`.
- Keep technology names out of port names.
- Place types that form the port contract next to that port when their role is
  unambiguous.
- Do not expose Neo4j records, HTTP responses, streams owned by web frameworks,
  or implementation exceptions through ports.
- Expected not-found and validation failures belong to application/domain
  semantics, not to a concrete adapter.

Before adding a port, verify that no existing capability already represents the
same boundary.

## Query Pipeline

The intended flow is:

```text
PQL text -> PqlParser port -> raw syntax -> resolver -> validator
         -> planner -> QueryPlanExecutor port -> XES hierarchy/result
```

`PqlCompiler` owns consistent orchestration of the domain compilation phases.
Execute, validate, export, and compatibility paths must not implement divergent
copies of this pipeline.

Classifier metadata should be loaded only when classifier semantics require it.
Datastore scoping must remain correct for zero, one, and multiple attached logs.

## Tests

Use fake ports for application unit tests. Assert orchestration and complete
results, not Spring wiring or implementation call trivia unless the call is the
contract being protected.

Run:

```powershell
.\gradlew.bat test --tests com.processm.processminterpreter.application.*
```

For query changes, also run hierarchical ProcessM tests and relevant
compatibility checks.

