# Sub-queries and expressions

Two incubating features let a query read another root it has no association to, and read a value the database
computes rather than one it stores. The [migration recipes](recipes.md) put each one in an adopter's case; this page
is the reference.

A `SubSelect` is one column of another root with its own filters; `Filters.in`/`notIn`/`exists`/`notExists` take it.
An `ExpressionField`, built by `Expr`, is a typed value over one vocabulary's columns; it fits wherever a `ColumnField`
fits. Neither changes the generated `Q<Model>`: a sub-select and an expression are built in your own code.

## Sub-selects

A `SubSelect<S, C>` names a second root and one of its columns, with filters of its own. It is immutable, so it can be
a constant. The entity it reads needs no association to the outer root:

```java
record I(Long orderId, String productCode, Integer quantity) {}

private static final TableField<PlainOrderItemEntity, PlainOrderItemEntity> PLAIN_ITEMS =
        TableField.root(PlainOrderItemEntity.class);
private static final ColumnField<I, PlainOrderItemEntity, Long> ITEM_ORDER_ID =
        ColumnField.of(I.class, PLAIN_ITEMS, "orderId", Long.class);
private static final ColumnField<I, PlainOrderItemEntity, String> ITEM_PRODUCT =
        ColumnField.of(I.class, PLAIN_ITEMS, "productCode", String.class);

private static final SubSelect<I, Long> P007_ITEMS =
        SubSelect.of(ITEM_ORDER_ID).where(f -> f.eq(ITEM_PRODUCT, "P007"));
private static final SubSelect<I, Long> NO_PRODUCT =
        SubSelect.of(ITEM_ORDER_ID).where(f -> f.in(ITEM_PRODUCT, List.of()));
```

### `in` and `notIn`

`in` and `notIn` take a `SubSelect` where they take a list, and the sub-select is not correlated to the outer row:

```java
results.add(ids(executor, f -> f.in(ID, P007_ITEMS)));
results.add(ids(executor, f -> f.in(ID, NO_PRODUCT)));
```

`notIn` is NULL-safe on both sides: a `NULL` in the sub-select's column adds an `IS NOT NULL` guard inside, so it
never empties the result, and an outer `NULL` is kept by an `OR col IS NULL` outside (R-FLT-16). An empty sub-select
keeps the empty-list rule: `in` matches nothing, `notIn` every row:

```java
results.add(ids(executor, f -> f.notIn(REFERRER_ID, PAID_ORDERS)));
results.add(ids(executor, f -> f.notIn(ID, REFERRER_IDS)));
results.add(ids(executor, f -> f.notIn(ID, NO_PRODUCT)));
```

Tested by `SubSelectTest`.

### `exists` and `notExists`

A sub-select is never correlated on its own; `exists` and `notExists` take a second lambda that states the
correlation, since there is no association to follow on an unmapped root:

```java
results.add(ids(executor, f -> f.exists(P007_ITEMS,
        (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))));
results.add(ids(executor, f -> f.notExists(P007_ITEMS,
        (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))));
```

`Outer.column(...)` lifts a column of the enclosing query's root into the sub-select's vocabulary, so it goes anywhere
an inner column goes. `or` may mix an inner condition and a lifted one, and one branch may read the outer row:

```java
private static final ColumnField<O, OrderEntity, BigDecimal> TOTAL =
        ColumnField.of(O.class, ORDERS, "total", BigDecimal.class);
private static final ColumnField<I, PlainOrderItemEntity, Integer> ITEM_QUANTITY =
        ColumnField.of(I.class, PLAIN_ITEMS, "quantity", Integer.class);

results.add(ids(executor, f -> f.exists(P007_ITEMS, (s, outer) -> s
        .compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID))
        .or(a -> a.eq(ITEM_QUANTITY, 9),
                b -> b.lt(outer.column(TOTAL), new BigDecimal("100.00"))))));
```

The lifted column must sit on the outer query's own root, so the outer query joins nothing for it.

Tested by `SubSelectTest`.

## Expressions

`Expr` builds an `ExpressionField<Model, T>`: a typed, immutable value over one vocabulary's columns, equal by
structure. The factories are `coalesce`, `nullIf`, `cases`, `plus`, `minus`, `times`, `dividedBy`, `negate`, `concat`,
`function(name, type, args...)` and `constant(text, type)`. A `ScalarField` is a `ColumnField` or an expression, so
every `Filters` operator, `Filters.compare` and `groupBy` take either.

```java
ColumnField<FilmBand, FilmEntity, Integer> released =
        ColumnField.of(FilmBand.class, TableField.root(FilmEntity.class), "released", Integer.class);
return Expr.cases(FilmBand.class, String.class)
        .when(f -> f.lt(released, 1990), "classic")
        .otherwise("modern");
```

```java
ExpressionField<FilmBand, String> genreText = Expr.coalesce(ColumnField.of(FilmBand.class,
        TableField.root(FilmEntity.class), "genre", String.class), "unknown");
```

```java
ColumnField<FilmBand, FilmEntity, Long> tickets =
        ColumnField.of(FilmBand.class, TableField.root(FilmEntity.class), "tickets", Long.class);
return Expr.times(tickets, 2L);
```

Two expressions are equal when their structure is; `named(...)` gives one a name without changing it, so two equal
expressions under different names are one selection, each name reading the value:

```java
private static final ExpressionField<ExprView, Integer> EXPR_PLUS_ONE = Expr.plus(EXPR_QUANTITY, 1);
private static final ExpressionField<ExprView, Integer> EXPR_PLUS_ONE_NAMED = EXPR_PLUS_ONE.named("key");

var records = ModelQuery.builder(ITEMS, row -> new ExprView(row.get(EXPR_ID), row.get(EXPR_PLUS_ONE)))
        .select(SelectSet.of(EXPR_ID, EXPR_PLUS_ONE))
        .build();
```

The values of an expression always bind as parameters; they are never inlined into the SQL text. A value the factory
cannot bind, and a column with a `ColumnConverter` the database would compute over, are refused when the expression is
built, not when the query runs.

Tested by `ExpressionTest`, `ExprFactoryTest` and `SampleApplicationTest`.

### Where an expression fits

An expression stands wherever a `ColumnField` does. As a filter operand:

```java
.where(f -> f.eq(genreText, genre))
```

As an aggregate argument, `Agg.sum` reads the expression rather than a column:

```java
/** {@code unitPrice * 2}: a decimal expression. */
private static final ExpressionField<ItemTotals, BigDecimal> PRICE_DOUBLED =
        Expr.times(UNIT_PRICE, BigDecimal.valueOf(2));
private static final AggregateField<ItemTotals, BigDecimal> PRICE_SUM = Agg.sum(PRICE_DOUBLED);
```

```java
/** {@code quantity + 1}: a group key that binds a value, so PostgreSQL must match it to the select item. */
private static final ExpressionField<Group, Integer> KEY = Expr.plus(GROUP_QUANTITY, 1);
private static final AggregateField<Group, Long> GROUP_COUNT = Agg.count(GROUP_ID);

var query = GROUPS.select(SelectSet.of(KEY, GROUP_COUNT)).groupBy(KEY).orderBy(KEY.asc()).build();
```

```java
private static ExpressionField<FilmView, Long> ticketsPlusOne() {
    return Expr.plus(QFilmView.TICKETS, 1L);
}
```

A selected expression is read back by `Row.get`, exactly like a column, and a group key the database binds is
rendered once and reused across `select`, `group by` and `order by` (R-COL-19).

Tested by `ExpressionFilterTest`, `ExpressionAggregateTest`, `ExpressionTest` and `SampleApplicationTest`.

### Request-bound values

`QueryParam.of(name, type)` is an immutable, named and typed placeholder that can live in a `static final`.
`Expr.param(param)` reads it anywhere an `ExpressionField` goes: a select item, a `coalesce`, `nullIf`, arithmetic or
`concat` operand, a CASE condition or result, a `function` argument, a filter through `compare`, an order or group
key, and a `Correlated` correlation. Two params are equal only by identity, and `toString` is `name: Type`. A declared
type that cannot bind is `MQ1502` when `param` is called.

`bind(param, value)` on `ModelQuery.Builder` or `SourceQuery.Builder` holds the value on the built query, never on the
`QueryParam`, the expression or the model's constants (INV-9). A second `bind` of one param replaces the first; a
`null` value, a value of the wrong type and a value that cannot bind are `MQ1502`. There is no optional bind: a caller
with an optional value binds a default.

```java
static final QueryParam<Long> MIN_ID = QueryParam.of("minId", Long.class);
static final QueryParam<String> TAG = QueryParam.of("tag", String.class);

/** Select-only: the count statements render it not, so they bind no tag. */
private static final ExpressionField<Row, String> LABEL =
        Expr.<Row>concat(STATUS, Expr.param(TAG)).named("label");

/** One shared definition (INV-9); each request binds it. */
private static final ModelQuery.Builder<OrderEntity, Long, Row> ROWS = ModelQuery
        .builder(ROOT, row -> new Row(row.get(ID), row.get(STATUS), row.get(LABEL)))
        .select(SelectSet.of(ID, STATUS, LABEL)).primaryKey(PrimaryKey.of(ID))
        .where(f -> f.compare(ID, Op.GTE, Expr.param(MIN_ID)).lte(ID, LAST)).orderBy(ID.asc());
```

With `ROOT`, `ID`, `STATUS` and `LAST` defined as in `QueryParamStatementsTest`, each request builds its own query:

```java
var query = ROWS.bind(MIN_ID, minId).bind(TAG, tag).build();
```

=== "Plain JPA"

    ```java
    List<Row> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<Row> rows = orders.findAll(query, Limit.unlimited());
    ```

A param that an expression the query renders reads and the query does not bind is `MQ1512` at `build()`, naming the
param and where it is read, before any statement. So is one name shared by two different params, even when both are
bound; a bound param that nothing reads is allowed. Every statement binds each param it renders once, so a param only
a select column reads is absent from the count and is not bound there. Under inline value handling it stays a bound
`?`, with the same SQL text and binds as the default mode.

Where no operand gives the type (a bare select item, an arithmetic or `concat` operand, a CASE result, a group key),
the param renders `cast(? as <type>)`; as a comparison operand, in `coalesce` beside a column and in a correlation it
renders bare. A group and an order key reading one param are one node, so PostgreSQL matches the group key.

A write binds no param. A bulk update, delete or insert whose `where` or conflict update's `where` reads one is
`MQ1512` at its build; a conflict arbiter's is `MQ1804`, as any expression value in an arbiter is; `setExpression`
with an expression that reads one is `MQ1613` at the call. A write that reads a caller's `ModelQuery`
(`ModelInsert.select`) uses that query's bindings.

Inspection names each param and its type, never its value. A keyset order key cannot be an expression, so a param
never reaches a cursor; a cursor reused under different bound values continues with the new values, as one reused
under a different `where` does.

Tested by `QueryParamTest`, `QueryParamPositionsTest` and `QueryParamStatementsTest`.

### Paging with an expression order key

Offset paging and offset export accept an expression key: the engine selects it and appends the primary-key
tie-breaker, so ties still page and export each row once. `primaryKeyFirst` accepts it too, on both its steps:

```java
private static final ColumnField<KeyRow, OrderItemEntity, Long> KEY_ID =
        ColumnField.of(KeyRow.class, ITEMS, "id", Long.class);
private static final ExpressionField<KeyRow, Integer> ITEM_KEY = Expr.plus(
        ColumnField.of(KeyRow.class, ITEMS, "quantity", Integer.class), 1);
private static final ModelQuery.Builder<OrderItemEntity, Long, KeyRow> BY_KEY = ModelQuery
        .builder(ITEMS, row -> new KeyRow(row.get(KEY_ID), row.get(ITEM_KEY)))
        .select(SelectSet.of(KEY_ID, ITEM_KEY))
        .primaryKey(PrimaryKey.of(KEY_ID));

var plain = BY_KEY.orderBy(ITEM_KEY.asc()).build();
var twoStep = BY_KEY.orderBy(ITEM_KEY.asc()).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(7_000)).build();
```

A keyset cursor binds the key's value to a column, and an expression key has no column to bind, so `keyset()` refuses
it with `MQ1208`, naming the key:

```java
var base = ModelQuery.builder(ROOT, VIEW_MAPPER).select(DEFAULT).primaryKey(PrimaryKey.of(ID))
        .orderBy(TOTAL_PLUS_ONE.asc());
assertThatThrownBy(() -> base.keyset().build())
        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1208))
        .hasMessageStartingWith("MQ1208: " + TOTAL_PLUS_ONE.name()
                + ": keyset() cannot order by an expression");
```

An expression that reads a column through a to-many join is refused before any query runs, with `MQ2204`, for offset
export, keyset paging and `primaryKeyFirst`; a `count` over such a query counts the rows rather than the keys
(R-PAG-25, R-PAG-13). Keep the function deterministic: a key the database computes differently on two runs cannot
page stably.

Tested by `PrimaryKeyFirstTest`, `OffsetExportTest` and `ModelQueryTest`.

## Correlated columns

The `@Incubating` `Correlated` API builds a scalar value for each outer row from a `SubSelect` and a correlation
lambda. Its result is an `ExpressionField`: select it, filter it, order by it, or compose it with `Expr.coalesce`
and arithmetic. The sub-select's own filters and the correlation are combined with AND.

| Terminal | Value when no rows match | Meaning |
|---|---|---|
| `count` | `0L` | Count non-NULL values of the selected column, returning `Long`. |
| `exists` | `false` | Whether a row matches, ignoring the selected column; never NULL. |
| `min`, `max` | NULL | Least or greatest non-NULL value; requires a comparable column. |
| `sum` | NULL | Sum non-NULL numeric values, using the usual aggregate SUM types. |
| `first` | NULL | Selected value of the first matching row in the required order. |

`min`, `max` and `sum` also return NULL when all selected values are NULL. `first` can return NULL from the chosen
row. To filter on existence alone, prefer `Filters.exists`; `Correlated.exists` produces a Boolean column using
`CASE WHEN EXISTS`, which can obstruct the semi-join plan when used in WHERE.

For example, adapt the `OrderEntity` status correlation from `CorrelatedExecutionTest` into a computed field.
These declarations go in their respective Java files, with the usual Model Query imports:

```java
@QueryModel(root = OrderEntity.class)
public record OrderCounts(@PrimaryKey Long id, @Computed(SameStatusCount.class) Long count) {}
```

```java
public final class SameStatusCount implements ExpressionDefinition<OrderCounts, Long> {
    public static final SameStatusCount INSTANCE = new SameStatusCount();
    private static final TableField<OrderEntity, OrderEntity> ROOT = TableField.root(OrderEntity.class);
    private static final ColumnField<OrderCounts, OrderEntity, String> STATUS =
            ColumnField.of(OrderCounts.class, ROOT, "status", String.class);
    private static final ColumnField<Inner, OrderEntity, Long> INNER_ID =
            ColumnField.of(Inner.class, ROOT, "id", Long.class);
    private static final ColumnField<Inner, OrderEntity, String> INNER_STATUS =
            ColumnField.of(Inner.class, ROOT, "status", String.class);

    private static final class Inner {}

    @Override
    public ExpressionField<OrderCounts, Long> expression() {
        return Correlated.count(OrderCounts.class, SubSelect.of(INNER_ID),
                (f, outer) -> f.compare(INNER_STATUS, Op.EQ, outer.column(STATUS)));
    }
}
```

Or build the query by hand using that same expression, without selecting the generated computed constant:

```java
var root = TableField.root(OrderEntity.class);
var id = ColumnField.of(OrderCounts.class, root, "id", Long.class);
var count = SameStatusCount.INSTANCE.expression();
var query = ModelQuery.builder(root, row -> new OrderCounts(row.get(id), row.get(count)))
        .select(SelectSet.of(id, count)).primaryKey(PrimaryKey.of(id))
        .orderBy(count.desc(), id.asc()).build();
```

=== "Plain JPA"

    ```java
    List<OrderCounts> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<OrderCounts> rows = orders.findAll(query, Limit.unlimited());
    ```

The lambda must lift an outer column with `outer.column(...)`. A lifted column may follow a to-one path on the
outer root, including a joined column with an `on(...)` condition; the outer join context preserves that condition.
A lifted to-many path is refused with `MQ1311`. A source may be the outer root, but cannot root the sub-select.

### First value and refusals

`Correlated.first(model, rows, correlation, order, more...)` requires at least one inner order key. The engine
appends the inner root's id ascending as a tie-breaker (every composite-id component, by attribute name).
On a nullable order key, set `nullsFirst()` or `nullsLast()` explicitly for the same value on every vendor;
`DEFAULT` renders no null clause, so the configured default null precedence (if any) or the vendor's own ordering
applies. `first` requires the Hibernate integration's ordered sub-query
renderer; without it, or with `hibernate.jpa.compliance.query=true`, statement construction fails with
[`MQ4014`](diagnostics.md#mq4xxx-configuration). Other terminals do not need this renderer.

The refusals to know are:

- `MQ1505`: a correlated value inside a CASE condition; CASE results may use one.
- `MQ1510`: an aggregate over a correlated value, including `Agg.of` and `@Aggregate(expression = ...)`.
- `MQ1314`: a correlated group key, or a grouped selection/order lifting a column that is not a group key.
  WHERE is evaluated before grouping and may lift a non-group-key column.
- `MQ1511`: `count`, `sum` or `first` crossing a to-many join in the inner selected column, filters, correlation,
  or `first` order. Use `Filters.exists(path, inner)` for the child condition instead. `min`, `max` and `exists`
  are unaffected by duplicate inner rows.
- `MQ1208`: keyset ordering by a correlated expression. Offset paging, offset export and `primaryKeyFirst` accept it.
- `MQ1908`: a correlated expression in a source body's fields.

### Cost and concurrent writes

Every position uses a scalar sub-query, including SELECT, WHERE and ORDER BY. There is no join lowering or vendor
switch. A small page needs an index probe per outer row and correlated column; a whole-table filter or export
needs a probe per parent. An offset export ordered or filtered by the value repeatedly evaluates it for rows it
sorts past: roughly N sub-queries per page, for P pages. An index cannot remove that repeated work.

**Index the sub-select's correlated columns.** PostgreSQL does not automatically index a foreign key's child
column. The RFC 0015 §6 measurements below used 100,000 parents with ten children each on Hibernate 6.6 and 7.4;
"join" means a comparison query joining a grouped child table, not a rendering the engine selects.

<table>
<thead><tr>
<th>Child FK indexed?</th><th>Page of 20 / 100</th><th>Export</th>
<th>Export ordered by it</th><th>WHERE on it</th>
</tr></thead>
<tbody>
<tr>
<td>Yes</td>
<td>Scalar ~1–3 ms on PG/MySQL; join 1–2 ms on PG, ~105 ms on MySQL.</td>
<td>PG: join 1.5× faster; MySQL: scalar 4× faster; H2: scalar 6× faster.</td>
<td>PG/MySQL: join ~1.3–1.4× faster; H2: scalar faster.</td>
<td>PG/MySQL: join ~1.6× faster.</td>
</tr>
<tr>
<td>No</td>
<td>Scalar 0.5–6 s; join 40–180 ms on PG/MySQL; H2 the same both ways.</td>
<td>Scalar over 10 s on every vendor; join 0.6–2.3 s on PG/MySQL; H2 over 10 s both ways.</td>
<td>As export.</td><td>As export.</td>
</tr>
</tbody>
</table>

An export ordered by a correlated value can silently skip or repeat rows under concurrent writes as rows move between
offset pages. The value can change when **another table** is written: keeping parents unchanged is insufficient.
Use REPEATABLE READ or a consistent snapshot across the export for the full guarantee. The engine does not check
for this movement or warn about it (R-PAG-25).

A correlated column is one way to put a child's aggregate beside each parent. The other is to
[join a source](source-models.md#joining-a-source) that groups the child once. A later measurement
(RFC 0020) found that once the page **filters or sorts on the value**, a joined source won on every vendor, because a
filter on a correlated column re-evaluates its sub-select. Keep
`Correlated.*` for **unfiltered** pages of a few rows over an indexed child, and join a source for a page, export or
report that filters or sorts on the aggregate. [What a joined source costs](source-models.md#what-a-joined-source-costs)
covers the count.

Behavior is covered by `CorrelatedExecutionTest`, `CorrelatedFirstTest`, `CorrelatedResolutionTest` and
`LiftedColumnTest`; the computed declaration follows `OrderNet` / `RowNet`.

## Computed fields

`@Computed` maps a field to an `ExpressionDefinition<Model, FieldType>`, a class with one
`ExpressionField<Model, FieldType> expression()` method, reached through a public `INSTANCE` constant or a visible
no-arg constructor. `@Aggregate(expression = ...)` sums or counts the expression that definition builds rather than a
mapped attribute. A `@GroupBy` on a `@Computed` field selects the expression as the group key:

```java
@QueryModel(root = OrderEntity.class)
public record OrderBand(
        @GroupBy @Computed(Band.class) String band,
        @Aggregate(fn = AggregateFunction.SUM, expression = BandDoubled.class) BigDecimal doubled) {}
```

The definition is ordinary code, and its `expression()` may build on `Q<Model>`'s own constants:

```java
public final class Band implements ExpressionDefinition<OrderBand, String> {
    public static final Band INSTANCE = new Band();

    @Override
    public ExpressionField<OrderBand, String> expression() {
        return Expr.coalesce(ColumnField.of(OrderBand.class, TableField.root(OrderEntity.class), "status",
                String.class), "none");
    }
}
```

The generated constant is an `ExpressionField` named after the field, so a field named `band` becomes `BAND`, and a
`@Computed` field joins `ALL` and `DEFAULT` unless it carries `@ExcludeFromDefaults`. The processor refuses a class
that is not a usable `ExpressionDefinition<Model, FieldType>`, or that has neither `INSTANCE` nor a visible no-arg
constructor, with `MQ3018`; a `@Computed` field that shares its field with an annotation it can't (`@PrimaryKey`,
`@Column`, `@Transient`, `@Aggregate`, `@Join`, `@Child`) or that is primitive, with `MQ3019`; and an `@Aggregate`
that takes both `attribute` and `expression`, with `MQ3208`. A `@Join` model with a `@Computed` field reports
`MQ3005`.

Tested by `ComputedModelTest`, `DiagnosticMatrixTest`, `ComputedModelQueryTest` and `SampleApplicationTest`.

## Factory diagnostics

The `Expr` factories check their operands where they are called. `MQ1501` is a column carrying a `ColumnConverter`
the expression computes over without applying it; `MQ1502` a null, array, enum, `Date`, `Calendar` or entity value a
factory cannot bind; `MQ1503` a `dividedBy` over two integral operands, whose division truncates on some vendors;
`MQ1504` a CASE condition left with no filter; `MQ1505` a CASE condition using `add`, `exists` or a sub-select;
`MQ1506` a `function` name that is not a plain SQL identifier or that is a built-in aggregate. A declared type the
provider does not resolve is refused when the query is first built, as `MQ1507`.

Tested by `ExprFactoryTest`.

## See also

- [Migration recipes](recipes.md): the adoption cases, each with its own recipe.
- [Filters](filters.md): the `Filters` operators a `SubSelect` and an expression plug into.
- [Models and QModels](models.md): `@Computed`, `@GroupBy` and the generated `Q<Model>` constants.
