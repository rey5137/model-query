# 13 — Aggregates and Grouped Queries

**Covers:** `Agg`, `AggregateField`, `groupBy`, `having`, aggregate result types, and every rule that applies once a
query is grouped.
**Read when:** building a report summary, adding an aggregate function, or deciding what a grouped query may and may
not do.
**Owns:** `R-AGG-*`, `AC-AGG-*`. `SelectField` is `api/10` §2; grouped export is `engine/21` §5.

---

Report summaries are projection-first reads too: `SELECT sku, operator, sum(price), count(*) … GROUP BY …` mapped into
a summary model. `api/10`'s `ColumnField` is a table attribute and resolves to a `Path`; an aggregate resolves to an
`Expression`. `SelectField` (`api/10` R-COL-06) is what lets both be selected, ordered and read from a `Row`.

## 1. `Agg`

```java
public final class AggregateField<M, C> implements SelectField<M, C> { /* … */ }

public final class Agg {
    public static <M> AggregateField<M, Long> count(TableField<?, ?> table);
    public static <M> AggregateField<M, Long> countDistinct(ColumnField<M, ?, ?> column);

    public static <M, C extends Number> AggregateField<M, C>      sum(ColumnField<M, ?, C> column);
    public static <M>                   AggregateField<M, Long>   sumAsLong(ColumnField<M, ?, ? extends Number> column);
    public static <M, C extends Number> AggregateField<M, Double> avg(ColumnField<M, ?, C> column);

    public static <M, C extends Comparable<? super C>> AggregateField<M, C> min(ColumnField<M, ?, C> column);
    public static <M, C extends Comparable<? super C>> AggregateField<M, C> max(ColumnField<M, ?, C> column);

    public static <M, C> AggregateField<M, C> of(String name, Class<C> type,
                                                BiFunction<JoinContext, CriteriaBuilder, Expression<C>> expression);
}
```

```java
public static final AggregateField<StockSummary, Long>       CLOSING_QUANTITY = Agg.count(QStockSummary.ROOT);
public static final AggregateField<StockSummary, BigDecimal> CLOSING_STOCK    = Agg.sum(QStockSummary.ORIGINAL_UNIT_PRICE);
```

**R-AGG-01** An `AggregateField` is equal to another with the same function, source column (by the column's own join
key and attribute) and alias — never by object identity, the same rule joins follow (`api/10` R-COL-02). Two
`Agg.sum(ORIGINAL_UNIT_PRICE)` calls are therefore one selection and one `Row` key. `as("…")` gives an aggregate its own
key when the same function over the same column is needed twice.

**R-AGG-02** `Agg.of(...)` is keyed by its `name`, because a lambda cannot be compared. Two `Agg.of` fields sharing a
name with different expression instances throw `MQ1103` when the query is built.

## 2. Result types

**R-AGG-03** An aggregate's Java type is what the database returns, not the source column's type:

| Expression | Source type | Result type |
|---|---|---|
| `count` / `countDistinct` | any | `Long` |
| `sum` | `BigDecimal` / `Double` / `Long` | same type |
| `sum` | `Integer` / `Short` | `Long` — `Agg.sum` on a 32-bit column throws `MQ1403` when it is called; use `sumAsLong` |
| `avg` | any numeric | `Double` |
| `min` / `max` | any comparable | same type |

*(was R16)* Checked by the processor for generated models (`processor/32`) and at build time for hand-written ones.
`Agg.sum` accepts `BigDecimal`, `Double` and `Long` columns; `Agg.sumAsLong` accepts `Integer`, `Short`, `Long` and
`Byte` columns. Any other column throws `MQ1403` when the factory is called, since Java cannot overload on a type
argument (D-25). For generated columns the processor (M4) reports the same mistake as a compile error
(`processor/32` `MQ3205`).

**R-AGG-04** `sum` over zero rows is `NULL`, not `0`. `Row.get` returns `null`, and a mapped field must be a boxed
type; a primitive field for a `sum` column is a processor error `MQ3201`. A caller that wants `0` uses a
`ColumnConverter` or `Objects.requireNonNullElse` in the mapper — the engine never invents a value (INV-5).

## 3. Grouping and `having`

```java
ModelQuery<StockEntity, ?, StockSummary> q = QStockSummary.query()
        .columns(QStockSummary.GROUP_KEYS.with(CLOSING_QUANTITY, CLOSING_STOCK))
        .where(f -> f.lte(QStockSummary.IMPORTED_TIMESTAMP, dateTo)
                     .or(g -> g.isNull(QStockSummary.SOLD_TIMESTAMP),
                         g -> g.gt(QStockSummary.SOLD_TIMESTAMP, dateTo))
                     .apply(QStockSummary::notDeleted))
        .groupBy(QStockSummary.GROUP_KEYS)
        .having(h -> h.gt(CLOSING_QUANTITY, 0L))
        .orderBy(QStockSummary.SKU_CODE.asc(), QStockSummary.OPERATOR.asc())
        .afterMap((model, row) -> model.setOrderStatus(orderStatus(model)))
        .build();
```

**R-AGG-05** `groupBy` accepts a `ColumnSet<M>` or explicit `ColumnField`s. Passing a `ColumnSet` keeps the group-by
list and the selection in step from one constant, which is where hand-written builders drift (`guide/70`).

**R-AGG-06** `having(UnaryOperator<Having<M>>)` is the `Filters` DSL (`api/12`) over `AggregateField` instead of
`ColumnField`, with the same skip and `Optional` semantics. Aggregates cannot appear in `where` and plain columns cannot
appear in `having`: both are compile errors, because `Filters` takes `ColumnField` and `Having` takes `AggregateField`
(P-2).

## 4. Rules for a grouped query

**R-AGG-07** A query is **grouped** when `groupBy` is non-empty. Selecting an `AggregateField` with no `groupBy` is a
grouped query with one group — what `SELECT count(*) FROM …` means — and returns exactly one row.

**R-AGG-08** Every selected non-aggregate column must be in the group-by. Checked when the query is built, throwing
`MQ1401` with the offending column's name. MySQL would accept it and return an arbitrary value; PostgreSQL would fail at
execution time with a message naming neither the model nor the column.

**R-AGG-09** A grouped query needs no primary key (`api/11` R-QRY-03). The `engine/21` R-PAG-03 "primary key not
selected" check is skipped, because a group has no row identity. When `primaryKey` is set on a grouped query it is
ignored and logged once at `DEBUG`.

**R-AGG-10** *(was R17)* A grouped query refuses keyset paging. `.keyset()` and `primaryKeyFirst(...)` throw `MQ1402`
at build time naming the model: both need a unique per-row key, and R-PAG-04/05 cannot hold without one. Grouped reads
use `list`, `stream` or offset `export`.

**R-AGG-11** `count` over a grouped query counts groups, not rows (`engine/20` R-EXE-03).

**R-AGG-12** Generated `ColumnSet`s (`DEFAULT`, `ALL`) never include aggregates, since including one would turn every
plain query into a grouped one. Aggregates are added explicitly with `with(...)`.

## 5. Generated support

`processor/30` defines `@Aggregate` and `@GroupBy`; `processor/31` defines what they generate. In short: an
`@Aggregate` field yields one `AggregateField` constant and is mapped like any other column, `@GroupBy` fields form the
generated `GROUP_KEYS` `ColumnSet`, and `QStockSummary.query()` comes pre-configured with `groupBy(GROUP_KEYS)`.

## 6. Acceptance criteria

| ID | Criterion |
|---|---|
| AC-AGG-01 | Every `Agg` function returns the R-AGG-03 type on every Tier-1 vendor. |
| AC-AGG-02 | `Agg.sum` over an `Integer` column throws `MQ1403` when called; `sumAsLong` accepts it (R-AGG-03). |
| AC-AGG-03 | `sum` over zero matching rows reaches the model as `null`, not `0` (R-AGG-04). |
| AC-AGG-04 | Two identical `Agg.sum(...)` constants render one selection and one `Row` key (R-AGG-01). |
| AC-AGG-05 | Two `Agg.of` fields with the same name and different expressions throw `MQ1103` (R-AGG-02). |
| AC-AGG-06 | An aggregate in `where` and a plain column in `having` fail to compile (compile-testing) (R-AGG-06). |
| AC-AGG-07 | `having` skip semantics match `api/12` AC-FLT-03 for aggregates (R-AGG-06). |
| AC-AGG-08 | A selected column missing from the group-by throws `MQ1401` naming the column (R-AGG-08). |
| AC-AGG-09 | `.keyset()` on a grouped query throws `MQ1402`; a grouped query with no `primaryKey` exports successfully (R-AGG-09, R-AGG-10). |
| AC-AGG-10 | An aggregate selection with no `groupBy` returns exactly one row (R-AGG-07). |
| AC-AGG-11 | `afterMap` on a grouped query runs once per group and sees every selected aggregate (`api/11` R-QRY-05). |
