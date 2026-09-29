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
        .columns(QOrderView.DEFAULT)
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

**R-QRY-02** `ModelQuery.builder(root, mapper)` is the only required input besides `columns`. Everything else is
optional, and every optional part has a defined behaviour when absent, listed in §5. `build()` without `columns`
throws `MQ1202`.

## 2. Primary keys

**R-QRY-03** `primaryKey(...)` is required for `keyset()`, `primaryKeyFirst(...)`, offset `export` and `@Join` presence
(`processor/31`). It is optional for `list`, `page` and `count`, and optional for every grouped query
(`api/13` R-AGG-07). `keyset()` without a primary key is a build-time error, `MQ1201`.

**R-QRY-04** The primary-key columns are added to the selection automatically whenever they are needed. A caller never
has to put them in a `ColumnSet` to make paging work.

## 3. `afterMap` and derived fields

**R-QRY-05** `afterMap(BiConsumer<M, Row>)` runs once per row, after the `RowMapper`, and may read any selected column
from the `Row`. It is the documented home for a field computed from other mapped values — an order status, a
percentage, a label. Records use `finisher(UnaryOperator<M>)` instead, since their components are final.

**R-QRY-06** `afterMap` must not query, mutate shared state or throw for ordinary data. It runs inside the export loop,
so work proportional to anything but the single row belongs in the caller's `pageTransformer` (`engine/21`).

## 4. `QueryCustomizer` — the escape hatch

```java
public interface QueryCustomizer {
    void customize(QuerySpec spec, JoinContext joins, CriteriaQuery<?> query, CriteriaBuilder cb, Phase phase);
}

public enum Phase { MODEL, PRIMARY_KEY, MODEL_BY_KEYS }
```

**R-QRY-07** `QueryCustomizer` replaces subclassing hooks. It can add predicates, selections and group-by expressions
for each phase the engine runs (P-6).

**R-QRY-08** A selection added by a customizer has no `SelectField` key, so it cannot be read back through `Row`. A
value that must reach the model goes through a `ColumnField` or an `AggregateField` instead (`api/13` §2, including
`Agg.of` for an arbitrary expression). The Javadoc says so explicitly, because this is the trap the escape hatch sets.

**R-QRY-09** The three phases must stay consistent: a predicate that narrows `MODEL` but not `PRIMARY_KEY` makes
primary-key-first paging return rows the caller filtered out. A customizer that adds a predicate in only one phase logs
a warning naming the phase, once per `ModelQuery`, when it is first executed (D-21).

## 5. Defaults when a part is absent

| Absent | Behaviour |
|---|---|
| `orderBy` | Unordered for `list` and `count`. Offset paging and export append the primary key (or the group keys) to get a stable order (`engine/21` R-PAG-01). |
| `primaryKey` | Allowed except where R-QRY-03 requires one. |
| `groupBy` | The query is not grouped; selecting an `AggregateField` makes it a single-group query (`api/13` R-AGG-04). |
| `keyset()` | Paging and export use offset mode. |
| `primaryKeyFirst` | Never used, whatever the offset. |
| `afterMap` | The `RowMapper`'s result is returned as is. |
| `where` | No predicate. `count` then counts the whole table. |

## 6. Executor surface

```java
public interface ModelQueryExecutor<E> {
    <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit);
    <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode);        // COUNT, NO_COUNT, ONLY_COUNT
    long count(ModelQuery<E, ?, ?> q);
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body);
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
                       Function<List<M>, List<S>> pageTransformer, Consumer<S> sink);
}
```

**R-QRY-10** `ModelQueryExecutor.create(EntityManager, Class<E>, ModelQueryConfig)` is enough to use the library
without Spring (INV-8). Semantics of each method are `engine/20`. The bulk `update` and `delete` methods are
`api/14` §8 (`Future`, M8).

## 7. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-QRY-01 | A built `ModelQuery` exposes no mutator; two queries built from one builder instance are independent (R-QRY-01). |
| AC-QRY-02 | `keyset()` without `primaryKey` throws `MQ1201` naming the model (R-QRY-03). |
| AC-QRY-03 | A `ColumnSet` omitting the primary key still pages and exports correctly (R-QRY-04). |
| AC-QRY-04 | `afterMap` runs exactly once per row, sees every selected column, and its effect survives `export` (R-QRY-05). |
| AC-QRY-05 | A customizer-added selection is not readable through `Row`; the Javadoc example uses `Agg.of` instead (R-QRY-08). |
| AC-QRY-06 | A customizer adding a predicate only in `Phase.MODEL` logs a warning naming the phase (R-QRY-09). |
| AC-QRY-07 | Every row of §5 is covered by a test that omits exactly that part (R-QRY-02). |
