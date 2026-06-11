# Domain Layer Agent Guide

## Responsibility

The domain layer defines the meaning of XES data and PQL queries. It must remain
pure Kotlin and independent from Spring, HTTP, Neo4j, ANTLR, XML, and Jackson.

Domain code may contain:

- XES log, trace, event, classifier, extension, and global attribute concepts;
- PQL syntax-independent catalogs and types;
- raw syntax models produced by a parser adapter;
- resolution, typing, validation, and planning rules;
- structured domain errors and source locations.

Physical storage encodings, JSON field names, XML mechanics, and controller
response shapes do not belong here.

## PQL Pipeline Packages

- `catalog`: stable language vocabulary, scopes, operators, standard/system
  attributes, and types shared by pipeline phases.
- `syntax`: parser output. It describes what was written without resolving its
  semantic meaning.
- `resolved`: expressions and queries after names, scopes, and types are
  resolved.
- `semantics`: pure transformations and validation between pipeline stages.
- `plan`: backend-independent logical execution intent.
- `common`: genuinely shared value objects used across pipeline stages.
- `error`: structured PQL problems and source-aware compile errors.

Maintain the dependency direction:

```text
syntax -> catalog/common
resolved -> catalog/common
semantics -> syntax/resolved/plan
plan -> catalog/common/resolved
```

Do not make resolved or plan models depend on parser implementation classes.
Move a type to `common` or `catalog` only when it is semantically identical
across stages, not merely structurally similar.

## Modeling Rules

- Prefer immutable data classes and exhaustive sealed types.
- Keep one representation for one semantic concept where practical.
- Do not add convenience fields that duplicate attributes unless the typed XES
  contract clearly owns them.
- Preserve original XES attribute names and types.
- Keep persistence separators, encoded property paths, Cypher aliases, and
  generated IDs outside the domain.
- Use structured `PQLCompileError`/problem types instead of leaking generic
  `IllegalArgumentException` for user query errors.
- Source locations should remain available through parsing, resolution, and
  validation so callers can report actionable errors.

## Semantic Changes

Do not infer PQL behavior from a single query or log. Confirm semantics using:

- the PQL specification;
- original ProcessM implementation;
- original ProcessM assertions;
- datasets with different metadata and attribute shapes.

Changes to scope, hoisting, aggregation, grouping, ordering, limits, offsets,
classifier resolution, null handling, or attribute typing require regression
tests at the pure domain level and hierarchical integration level.

## Tests

Keep domain tests deterministic and free from Spring or Testcontainers:

```powershell
.\gradlew.bat test --tests com.processm.processminterpreter.domain.pql.*
```

For semantic changes, additionally run:

```powershell
.\gradlew.bat test --tests com.processm.processminterpreter.processm.hierarchical.*
```

