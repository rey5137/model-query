# How it's built

Model Query is developed mostly by AI coding assistants working under the direction of a human maintainer. The
maintainer specifies requirements, makes architectural choices, reviews each increment, and approves every milestone.

The project relies on public, reproducible evidence rather than manual claims to ensure software quality. This page
documents that methodology and links to the artifacts that back it up.

## Spec-first development

Every capability starts in the specification under
[`docs/spec/`](https://github.com/rey5137/model-query/tree/main/docs/spec).
Requirements are organized into numbered rules (`R-*`) and explicit acceptance criteria (`AC-*`).

At the time of writing (version 0.11.0), the specification defines 514 acceptance criteria. CI's AC audit
([`.github/scripts/ac_audit.py`](https://github.com/rey5137/model-query/blob/main/.github/scripts/ac_audit.py),
R-QA-11) fails the build when an in-scope acceptance criterion has no test method named `ac_xxx_nn_`.

## RFCs and the decision log

A public signature change, a rule's meaning, or an `INV-*` invariant modification requires an accepted Request for
Comments in [`rfc/`](https://github.com/rey5137/model-query/tree/main/rfc).

Architectural decisions and answers to design questions are recorded in the
[decision log](https://github.com/rey5137/model-query/blob/main/docs/spec/reference/92-decisions-questions.md). At the
time of writing, 181 decisions (`D-1` through `D-181`) are logged, documenting why each alternative was chosen or
rejected.

## Invariant enforcement with ArchUnit

Architectural layering invariants are verified on every build by ArchUnit tests in
[`LayeringRules`](https://github.com/rey5137/model-query/blob/main/model-query-tck/src/test/java/com/rey/modelquery/tck/arch/LayeringRules.java)
and
[`LayeringTest`](https://github.com/rey5137/model-query/blob/main/model-query-tck/src/test/java/com/rey/modelquery/tck/arch/LayeringTest.java).
For example, `model-query-core` may depend only on the JDK and Jakarta Persistence, `model-query-jpa` cannot import
Hibernate classes directly, and database vendor-specific behavior must remain encapsulated behind SPI profiles rather
than scattered across execution paths.

## Multi-database TCK and Hibernate matrix

The Technology Compatibility Kit (TCK) in `model-query-tck` contains 1,144 test methods (955 `@TckTest`, each run
per database, and 189 `@Test`).

Tests run against in-memory H2, and against real database instances via Testcontainers:
- PostgreSQL 17
- MySQL 8.4

All tests run across both supported Hibernate lines:
- Hibernate 6.6 (`6.6.58.Final` in [`pom.xml`](https://github.com/rey5137/model-query/blob/main/pom.xml))
- Hibernate 7.4
  (`7.4.11.Final` in [CI workflow](https://github.com/rey5137/model-query/blob/main/.github/workflows/ci.yml))

## SQL snapshots

To prevent silent query regressions and dialect drift, the TCK maintains 644 committed SQL snapshots under
[`model-query-tck/src/test/resources/sql`](https://github.com/rey5137/model-query/tree/main/model-query-tck/src/test/resources/sql).
A test execution captures every statement sent to the database proxy and compares it after whitespace normalization
against the pinned snapshot. Any statement variation, unexpected bind placeholder, or missing clause fails the test
suite.

## Empirical spikes before performance decisions

Performance optimizations and planner trade-offs are evaluated with isolated benchmarks before adoption. For instance,
[`rfc/0018-spike-results.md`](https://github.com/rey5137/model-query/blob/main/rfc/0018-spike-results.md) records
measured execution times and query plan changes for windowed count queries versus separate count queries across varying
table sizes and filter selectivities.

## Incremental slices and verified milestone tags

Work is implemented in small, single-purpose slices. Each slice compiles, passes targeted tests, and is independently
verified before being merged. Milestone releases require passing the full multi-database test suite and CI matrix,
recorded as a [`mN-verified`](https://github.com/rey5137/model-query/tags) tag per milestone gate.

## Stable diagnostics

Exceptions emitted by the library carry stable diagnostic codes in the format `MQnnnn` documented in
[Diagnostics](diagnostics.md). Each diagnostic code identifies the exact rule or condition that failed, making errors
deterministic and traceable to the specification.

## What this process does and does not guarantee

Each acceptance criterion in scope has a named test, and each build checks layering, SQL snapshots and the TCK on
both Hibernate lines.

It does not guarantee that the database query planner will always choose an optimal index, nor does it eliminate the
need for applications to monitor production query performance. Known database-specific limitations, planner behaviors,
and performance considerations are documented on each relevant documentation page.
