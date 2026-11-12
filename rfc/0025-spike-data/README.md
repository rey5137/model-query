# RFC 0025 spike data

Raw data of the RFC 0025 (portability from recorded calls) acceptance spike, read by
[`../0025-spike-results.md`](../0025-spike-results.md). The spike is one test-source module, `spike-rfc0025/`, behind
the root `pom.xml` profile `spike-rfc0025` (off by default). Production sources, SQL snapshots and `docs/spec/` are
unchanged.

## What runs where

No database. Part C's cells boot `model-query-portability`'s offline factories (`OfflineFactory`, `OfflineUnit`) on
the stub data source (`StubDataSource`, which throws at `prepareStatement`) for H2 2.3.232, PostgreSQL 16.4, MySQL
8.0.36 and MariaDB 11.4, each under bound values and under `hibernate.criteria.value_handling_mode=inline` (D-177).
Every call runs on its own `JdbcRecording` and is classified by production's `DryRun.classify`, through
`portability.internal.SpikeDry`, a test-classpath copy of `DryRun.run(emf, ...)` that keeps the whole recording.

| RFC change | Emulation |
|---|---|
| Part C: a not-API `jpa` entry running one child load on given keys | `Engine.childrenOf`: production's private `DefaultModelQueryExecutor#childrenOf(load, load.query(), keys)`, by reflection |
| Part C: key groups run on the catch-all's values, else on the placeholder's group asked with no owner | `Engine.oneRun`: production's `checkFirstRun(q)`, then a fresh private `ChildRounds` and `KeyRun` of those values (`size`, then `read`), by reflection |
| Part C: the harness walks a plan's child loads depth first on two placeholders of each key's type | `Probes.walk` over `FetchPlan.childLoads()` and `joinPlans()`, key type `load.field().key().attributeType()`, placeholders from `Placeholders` |

No class of the engine is shadowed. The query models are processor-generated (`@QueryModel`, `@SourceModel`); the two
children the processor refuses (MQ3404: a `byte[]` key and a whole `@EmbeddedId`) are hand-written `ChildField`s in
`HandChildren`, in the generated shape.

The R-INS-16 item boots an in-memory H2 unit (no statement run) twice: through
`HibernatePersistenceProvider#createContainerEntityManagerFactory(PersistenceUnitInfo, Map)`, and through spring-orm's
`LocalContainerEntityManagerFactoryBean` over the spike's package and one empty mapping file.

## Files

- `rfc0025-<line>.tsv`: one row per cell: `vendor` (`H2`, `POSTGRESQL`, `MYSQL`, `MARIADB`, with `/inline` for the
  inline unit, or `ANY`), `item` (`0` setup, `1` to `3` Part C's questions, `4` the R-INS-16 naming item), `case`,
  `status` (`PASS` or `FAIL`), `value`. A Part C value is `key <type>: <outcome> <first statement>; accesses=<n>,
  last=<the last JDBC call the stub saw>`; `no placeholder (failing line)` is the line the RFC's harness reports for
  a key type it has no placeholder for. A cell passes when its outcome is the one the case names or expects; cells
  named `hand-made.*`, `wrong-java-type`, `one-placeholder-shape` and `factory-bean-instance-valued-keys` only
  record.

`<line>` is `6.6.58.Final` or `7.4.14.Final`. No statement files: each child statement is the value's text.

## Reproduce

From the repository root; no Docker. Each run rewrites its line's TSV. One Hibernate line at a time (7.4 was run in an
`rsync` copy of the working tree, so the two lines' `target/` dirs don't mix):

```sh
./mvnw -q -P spike-rfc0025 -pl spike-rfc0025 -am test -Dtest='Rfc0025*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -P spike-rfc0025 -pl spike-rfc0025 -am test -Dtest='Rfc0025*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dhibernate.version=7.4.14.Final -Djakarta-persistence.version=3.2.0
```

A test fails when any of its cells fails. A run takes a few seconds once compiled.
