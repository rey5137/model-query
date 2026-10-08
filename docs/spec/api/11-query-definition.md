# 11 — Query Definition

**Covers:** the `ModelQuery` builder, primary keys, `afterMap`, `QueryCustomizer`, and the `ModelQueryExecutor`
signatures a query is handed to.
**Read when:** adding a builder method, changing what a query carries, or deciding where something belongs that the
`Filters` DSL cannot express.
**Owns:** `R-QRY-*`, `AC-QRY-*`. Execution behaviour is `engine/20..21`; filters are `api/12`.

---

## 1. The builder

```java
ModelQuery<OrderEntity, Long, OrderView> q = ModelQuery.builder(QOrderView.ROOT, QOrderView.MAPPER)
        .primaryKey(PrimaryKey.of(QOrderView.ID))     // composite: PrimaryKey.composite(A, B)
        .select(QOrderView.DEFAULT)
        .where(f -> f.eq(QOrderView.STATUS, status))
        .groupBy(...)                                 // optional; api/13
        .having(h -> ...)                             // optional, aggregates only; api/13
        .orderBy(QOrderView.CREATED_AT.desc())
        .keyset()                                     // optional: allow keyset paging
        .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(10_000))   // optional: two-step deep paging
        .afterMap((model, row) -> ...)                // optional: derived fields
        .customize(QueryCustomizer)                   // escape hatch: raw CriteriaBuilder access
        .build();
```

**R-QRY-01** `build()` returns an immutable `ModelQuery` (INV-9). A builder holds no per-query state; nothing is
resolved against a `CriteriaBuilder` until the query is executed.

**R-QRY-02** `ModelQuery.builder(root, mapper)` is the only required input besides `select`. Everything else is
optional, and every optional part has a defined behaviour when absent, listed in §5. `build()` without `select`, or a
fetch plan that carries one (`api/15` R-FCH-02), throws `MQ1202`.

## 2. Primary keys

**R-QRY-03** `primaryKey(...)` is required for `keyset()`, `primaryKeyFirst(...)`, offset `export` and `@Join` presence
(`processor/31`). It is optional for `list`, `page` and `count`, and optional for every grouped query
(`api/13` R-AGG-07). `keyset()` without a primary key is a build-time error, `MQ1201`. A keyset page
(`engine/21` R-PAG-16) also needs `keyset()`: `page(query, KeysetSpec)` on a query without it throws `MQ2207` before
any query runs, while `keyset()` puts `export` into keyset mode as well (`engine/21` R-PAG-14).

**R-QRY-04** The primary-key columns are added to the selection automatically whenever they are needed. A caller never
has to put them in a `SelectSet` to make paging work. `MODEL` and `MODEL_BY_KEYS` therefore select the key of every
ungrouped query that defines one, and every ordering and group key, whether or not the `SelectSet` names them (D-29). On an ungrouped query they also select
the presence key of each `presentBy` join a selected column is read through, and of every such join above it
(`api/10` R-COL-15, D-38).

**R-QRY-12** A primary-key column cannot have an array type (`byte[]`, for one). An array equals only itself, so the
key read from one row never equals the same key read from another: export's page-boundary dedupe and primary-key-first
paging, which place rows by key, would drop every row without a word. `build()` with such a key column throws
`MQ1206` naming the column.

**R-QRY-13** `keyset()` refuses a `Float` or `Double` keyset column: an order column, or a primary-key column appended
as the tie-breaker (`engine/21` R-PAG-04). A cursor on a binary floating-point value need not compare equal to the
stored one once bound (MySQL binds a `Float` as a decimal literal): a value stored above its decimal form makes the
next page repeat its tie group, and one stored below it makes the next page skip the rest of the group without a word.
`build()` throws `MQ1207` naming the column. Order by an exact type such as `BigDecimal`, or export by offset.

**R-QRY-16** *(D-115)* `keyset()` refuses an expression order key. Its cursor, fingerprint and bind budget are defined
over attribute values (`engine/21` R-PAG-17, R-PAG-19, D-82). `build()` throws `MQ1208` naming it, and an `orderedBy`
copy rethrows it as `MQ2301`'s cause. Offset paging, export and `primaryKeyFirst` accept it (`engine/21` R-PAG-25).

## 3. `afterMap` and derived fields

**R-QRY-05** `afterMap(BiConsumer<M, Row>)` runs once per row, after the `RowMapper`, and may read any selected column
from the `Row`. It is the documented home for a field computed from other mapped values — an order status, a
percentage, a label. Records use `finisher(UnaryOperator<M>)` instead, since their components are final.
A model that must tell an unselected column from a selected `NULL` one declares a `@Selected SelectSet<M>` field the
mapper fills (D-120, `processor/30` R-PROC-25); `afterMap` and a `finisher` see it already filled, and a record's
`finisher` passes it along (`processor/31` R-GEN-31).

**R-QRY-06** `afterMap` must not query, mutate shared state or throw for ordinary data. It runs inside the export loop,
so work proportional to anything but the single row belongs in the caller's `pageTransformer` (`engine/21`).

## 4. `QueryCustomizer` — the escape hatch

```java
public interface QueryCustomizer {
    void customize(QuerySpec spec, JoinContext joins, CriteriaQuery<?> query, CriteriaBuilder cb, Phase phase);
}

public enum Phase { MODEL, PRIMARY_KEY, MODEL_BY_KEYS }
```

**R-QRY-07** `QueryCustomizer` replaces subclassing hooks. It can add predicates, joins and selections for each phase
the engine runs (P-6). It cannot change the ordering or the grouping (R-QRY-11).

**R-QRY-08** A selection added by a customizer has no `SelectField` key, so it cannot be read back through `Row`. A
value that must reach the model goes through a `ColumnField`, an `ExpressionField` (`api/10` R-COL-17) or an
`AggregateField`. `Agg.of` remains for aggregate expressions `Agg` over an `ExpressionField` cannot express, such as a
ratio of two aggregates. A value derived in Java belongs in `afterMap` (R-QRY-05). A customizer-added selection still
cannot be read through `Row` (D-115 reverses D-27). The Javadoc says so explicitly, because this is the trap the escape
hatch sets.

**R-QRY-09** The three phases must stay consistent: a predicate that narrows `MODEL` but not `PRIMARY_KEY` makes
primary-key-first paging return rows the caller filtered out. The engine builds every phase with the same joins,
predicate, grouping and ordering, and only the SELECT list differs (D-26). A customizer that adds a predicate or an
INNER join in only some phases logs a warning naming the phases, once per `ModelQuery`, when it is first executed
(D-21). On a query with `primaryKeyFirst(...)` the mismatch skips rows, so it throws `MQ2206` instead
(`engine/21` R-PAG-15).

**R-QRY-11** A customizer never changes the ordering or the grouping. Paging and export order, dedupe and read cursors
by the definition's `orderBy` and `groupBy` (`engine/21` R-PAG-01, R-PAG-04, R-PAG-11), so a group-by the customizer
adds makes grouped export drop rows, and an order it changes makes keyset and offset pages skip rows. Every statement
the engine builds, in every phase and for grouped queries too, compares the `ORDER BY` and `GROUP BY` lists
(`CriteriaQuery.getOrderList()`, `getGroupList()`) before and after the customizer runs, and throws `MQ1205` naming the
model and the phase when either differs (D-33). The engine's own tie-breakers are appended after the customizer, so
they do not count.

## 5. Defaults when a part is absent

| Absent | Behaviour |
|---|---|
| `orderBy` | Unordered for `list` and `count`. Offset paging and export append the primary key (or the group keys) to get a stable order (`engine/21` R-PAG-01). |
| `primaryKey` | Allowed except where R-QRY-03 requires one. |
| `groupBy` | The query is not grouped; selecting an `AggregateField` makes it a single-group query (`api/13` R-AGG-07). |
| `keyset()` | Paging and export use offset mode; `page(query, KeysetSpec)` throws `MQ2207` (`engine/21` R-PAG-16). |
| `primaryKeyFirst` | Never used, whatever the offset. |
| `afterMap` | The `RowMapper`'s result is returned as is. |
| `where` | No predicate. `count` then counts the whole table. |

**R-QRY-14** `ModelQuery.orderedBy(SortSpec)` returns a copy of the definition ordered by the spec's keys, each a
property name, a direction and a `NullPrecedence`; an empty spec returns the definition unchanged. A property names
one of the query's selected columns, expressions or aggregates, never an attribute the query doesn't select: first by
the column's property path, the model field names from the root model (`customer.name` for field `name` of the nested
model under the `@Join` field `customer`), then by its attribute path from the root; an aggregate matches by its
`named` property first, then by its name (D-121), an expression by its `named` property. A bare
attribute name never matches a joined column. A column without a property (hand-written, not given one with
`named(String)`) matches by attribute path only (D-55). Matching is exact and case-sensitive, and every tier is
tried: a property matching no column, or different columns on one tier or on different tiers, throws `MQ2301` naming it
and every candidate with the tier it matched (INV-5); one column matched on two tiers is not ambiguous. The engine
still appends the primary key or the group keys (`engine/21` R-PAG-01), so an ungrouped definition without a primary
key, which has no tie-breaker, refuses a non-empty spec with `MQ2301`. The copy passes the same checks as `build()`;
a failure there (`MQ1207`) is rethrown as `MQ2301` with it as the cause, because the sort comes from the request
(D-58). This is how a sort chosen per request reaches a `static final` definition (INV-9, D-52).

**R-QRY-15** `ExportOptions` carries an optional page size: `ExportOptions.defaults()` leaves it to
`ModelQueryConfig.exportPageSize()` (default 1000), `ExportOptions.of(int)` sets it. `stream` always uses
`ModelQueryConfig.streamFetchSize()` (default 500). Either config value below 1 is `MQ4003` (D-53).

## 6. Executor surface

```java
public interface ModelQueryExecutor<E> {
    <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit);
    <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode);        // COUNT, NO_COUNT, ONLY_COUNT
    <M> KeysetSlice<M> page(ModelQuery<E, ?, M> q, KeysetSpec keyset);              // keyset page, @Incubating
    long count(ModelQuery<E, ?, ?> q);
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body);
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
                       Function<List<M>, List<S>> pageTransformer, Consumer<S> sink);
}
```

**R-QRY-10** `ModelQueryExecutor.create(EntityManager, Class<E>, ModelQueryConfig)`, both in `com.rey.modelquery.jpa`,
is enough to use the library without Spring (INV-8). The executor resolves the factory's `VendorProfile` once and passes
its facts to every build as `RenderOptions` (D-34). Semantics of each method are `engine/20`. The bulk `update` and
`delete` methods are `api/14` §8 (`Future`, M6). A query that carries a `FetchPlan` (`api/15`) loads its children, join
plans and enrichers on `list`, `page` and `export`, and `stream` refuses it (`api/15` R-FCH-09).

## 7. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-QRY-01 | A built `ModelQuery` exposes no mutator; two queries built from one builder instance are independent (R-QRY-01). |
| AC-QRY-02 | `keyset()` without `primaryKey` throws `MQ1201` naming the model (R-QRY-03). |
| AC-QRY-03 | A `SelectSet` omitting the primary key still pages and exports correctly (R-QRY-04). |
| AC-QRY-04 | `afterMap` runs exactly once per row, sees every selected column, and its effect survives `export` (R-QRY-05). |
| AC-QRY-05 | A customizer-added selection is not readable through `Row`; the Javadoc example uses `afterMap` instead (R-QRY-08). |
| AC-QRY-06 | A customizer adding a predicate only in `Phase.MODEL` logs a warning naming the phase (R-QRY-09). |
| AC-QRY-07 | Every row of §5 is covered by a test that omits exactly that part (R-QRY-02). |
| AC-QRY-08 | `builder` on a join throws `MQ1203`; `whenOffsetAbove` with a negative offset throws `MQ1204`; the `PRIMARY_KEY` phase of a query without a primary key throws `MQ2203` (R-QRY-02, R-QRY-03). |
| AC-QRY-09 | Every phase of a query with a primary key renders the same joins, predicate and ordering, including a nullable join made only by the selection, and returns the same keys in the same order (R-QRY-09, D-26). |
| AC-QRY-10 | A customizer that adds a `GROUP BY` or changes the `ORDER BY` throws `MQ1205` naming the model and the phase, on a grouped query and on one without a primary key too (R-QRY-11). |
| AC-QRY-11 | `build()` with a primary-key column of array type throws `MQ1206` naming the column (R-QRY-12). |
| AC-QRY-12 | `build()` of a `keyset()` query ordered by a `Float` or `Double` column, or keyed by one, throws `MQ1207` naming the column; the same query without `keyset()` builds (R-QRY-13). |
| AC-QRY-13 | `orderedBy` sorts by a selected column's property path, by its attribute path and by an aggregate's name, including a renamed nested field and two `@Join`s on one attribute; a bare name of a joined column, an unknown or an ambiguous property, a property naming different columns on two tiers, a sort on an ungrouped query without a primary key, and a sorted copy `build()` refuses (as the cause) throw `MQ2301` (R-QRY-14). |
| AC-QRY-14 | `ExportOptions.defaults()` exports with the config's page size, and `stream` uses the config's fetch size (R-QRY-15). |
| AC-QRY-15 | `build()` of a `keyset()` query ordered by an expression throws `MQ1208` naming it, and the same query without `keyset()` builds. `orderedBy` naming an expression's property sorts by it, and on a `keyset()` query throws `MQ2301` caused by `MQ1208` (R-QRY-16, R-QRY-14). |
