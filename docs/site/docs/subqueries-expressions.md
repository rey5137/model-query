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
- [Queries and Filters](queries.md): the `Filters` operators a `SubSelect` and an expression plug into.
- [Models and QModels](models.md): `@Computed`, `@GroupBy` and the generated `Q<Model>` constants.
