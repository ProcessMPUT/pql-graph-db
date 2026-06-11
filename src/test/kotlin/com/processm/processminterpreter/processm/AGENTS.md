# Ported ProcessM Tests Agent Guide

## Purpose

Tests in this subtree protect behavior ported from the original ProcessM
repository. They are high-value semantic regression tests, especially the
hierarchical PQL and XES suites.

The reference repository is available at:

```text
C:\Users\andre\AntigravityProjects\processm
```

Before changing a ported test, locate the original test and compare its setup,
query, complete assertions, fixtures, limits, and expected failure behavior.

## Assertion Policy

- An enabled ported test must preserve the full semantic assertion from the
  original test whenever the current domain model can express it.
- HTTP 200, `success == true`, non-empty output, or row count alone are not
  acceptable replacements for deep assertions.
- Assert relevant log, trace, event, metadata, attribute, type, ordering,
  grouping, aggregation, null, and cardinality behavior.
- When the representation differs but semantics are equivalent, document the
  adaptation and assert the equivalent domain value.
- Do not weaken assertions to make the implementation pass.
- Do not preserve assertions that only protect a removed compatibility wrapper
  or obsolete implementation detail. Replace them with stronger assertions on
  the current public/domain contract.

Comments such as `ADAPTED_PORT` must explain a real model difference, not hide
missing behavior.

## Fixtures And Datastores

- Use the same fixture and effective datastore/log scope as the original test.
- Tests must use datastore-aware execution when production does.
- Avoid relying on test order or data left by another class.
- Shared loaders may avoid duplicate imports, but must attach logs to the
  expected datastore and remain safe after database cleanup.
- Multi-log behavior requires explicit tests with at least two different logs.

If import/parser/persistence semantics change, ensure the test creates fresh
data. Existing manually loaded logs are not valid evidence after such a change.

## Failure Interpretation

When a ported test fails:

1. Verify that the assertion matches the original ProcessM test.
2. Verify fixture and query translation.
3. Inspect full local and reference hierarchy/output.
4. Decide whether the defect is parsing, semantics, Cypher, reconstruction, or
   serialization.
5. Fix production behavior rather than normalizing the expected result.

Treat differences in arbitrary UUID values or explicitly nondeterministic
windows narrowly. Missing attributes, different attribute names/types,
incorrect grouping, extra synthetic data, or lost metadata are real failures.

## Commands

```powershell
.\gradlew.bat test --tests com.processm.processminterpreter.processm.hierarchical.*
.\gradlew.bat test --tests com.processm.processminterpreter.processm.log.*
```

Run the relevant class while iterating, then both groups and the full suite
before a compatibility checkpoint.

