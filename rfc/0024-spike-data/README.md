# RFC 0024 spike data

Raw data of the RFC 0024 (nested models over declared joins) acceptance spike, read by
[`../0024-spike-results.md`](../0024-spike-results.md). The spike is one test-source module, `spike-rfc0024/`, behind
the root `pom.xml` profile `spike-rfc0024` (off by default). Production sources, SQL snapshots and `docs/spec/` are
unchanged.

## What runs where

The queries run the production engine (`ModelQuery`, `ModelQueryExecutor`, `model-query-hibernate`'s renderers) over
hand-written "generated" classes (`PartA`, `PartB`, `PartC`): what the processor would emit for the RFC's examples. The
core changes the RFC asks for don't exist yet and are emulated in the spike module only:

| RFC change | Emulation |
|---|---|
| Part A: `TableField#presentBy` on a joined-entity root, keeping the join name | `SpikeCore.presentBy` builds that node through `TableField`'s private constructor (reflection) |
| Part B: `Row.scoped` stops at joined roots and maps them through a name map | `SpikeCore.nameMap` (built once per `@Join` field, checked) and `SpikeCore.scoped`, a `Row` wrapper over the production `Row.scoped` |
| Part B: nested joins registered under the field's prefix | the outer classes declare `customer_region`, `referrer_totals`, ... by hand, anchored on the `@Join` path |
| Part C: R-SRC-25 / `MQ1911` relaxed for a column anchor on another entity join | a test-classpath shadow of `com.rey.modelquery.core.EntityJoin`, a verbatim copy whose `checkColumnAnchor` skips the entity-join refusal while `SpikeCore.chains(true)` is set; `setup` asserts the shadow is the class loaded |

## Data set

`Entities.seed`, the same on every vendor (schema by `hbm2ddl=create`, no foreign key constraints): 12 orders, 6
customers, 4 regions, 3 countries, 9 payments. Orders keep `customer_id` and `referrer_id` as basic columns and also map
them as read-only `@ManyToOne`s. Edge rows: order 2's customer (999) is missing, order 3 has none, customer 11 has every
column NULL but its id, customer 13's region (999) is missing, region 102's country (99) is missing, country 3's name is
NULL, order 9 has the same person as customer and referrer, and payment 9 belongs to the missing customer 999.

## Files

- `rfc0024-<line>.tsv`: one row per cell: `vendor`, `item` (`0` setup, `1` to `4` the RFC's spike items), `case`,
  `status` (`PASS` or `FAIL`), `value`. A cell compares the engine's rows with a native SQL query on the same
  connection, unless its value says otherwise. Cells whose value contains `FINDING` pass because they reproduce a
  problem the results file reports.
- `sql/<line>/<vendor>-<cell>.sql`: the statements a cell's engine run sent, as captured by `datasource-proxy`, with
  their binds. `*-count.sql` is the `count` of the same query.

`<line>` is `6.6.58.Final` or `7.4.14.Final`.

## Reproduce

From the repository root, with Docker running (Testcontainers: `postgres:16-alpine`, `mysql:8.0.31`). Each run
appends to its line's TSV, so delete the data first. One Hibernate line at a time:

```sh
rm -f rfc/0024-spike-data/*.tsv; rm -rf rfc/0024-spike-data/sql
./mvnw -q -P spike-rfc0024 -pl spike-rfc0024 -am test -Dtest='Rfc0024*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -P spike-rfc0024 -pl spike-rfc0024 -am test -Dtest='Rfc0024*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dhibernate.version=7.4.14.Final -Djakarta-persistence.version=3.2.0
```

A test fails when any of its cells fails. A run takes about a minute per line.
