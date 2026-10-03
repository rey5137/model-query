# Models and QModels

A **model** is the shape of a result: a plain class or a record you write. A **QModel** is the class the annotation
processor generates from it (`QOrderView` for `OrderView`), holding typed constants you build queries from.

## Writing a model

Annotate the type with `@QueryModel` and name the JPA entity it reads from. A field maps to the entity attribute of
the same name.

```java
@QueryModel(root = OrderEntity.class)
public record OrderView(
        @PrimaryKey Long id,
        String status,
        BigDecimal total,
        @Join Optional<CustomerView> customer) {}
```

| Annotation | Use |
|---|---|
| `@QueryModel(root = ...)` | Marks a model and names its root entity. `prefix` and `suffix` rename the generated class; `singleGroup` and `generateChanges` are covered below. |
| `@PrimaryKey` | The entity id. Paging, export and nested-model presence read it. Required on a model without aggregates. |
| `@Column(attribute = ..., converter = ...)` | Maps a field to a differently named attribute, or one inside an embedded value, or converts between the model type and the attribute type. |
| `@Join` | A nested model read through a to-one association. The field must be an `Optional` of another `@QueryModel`; it is empty when a LEFT join found nothing. |
| `@FilterColumn(name, path, ...)` | Adds a filter-only constant for an attribute path (`customer.country`) that the model does not select. |
| `@ExcludeFromDefaults` | Leaves a heavy column (a BLOB, a long text) out of the generated default column set. |
| `@Transient` | Keeps a field out of the mapping. |
| `@GroupBy`, `@Aggregate` | Turn a model into a grouped result; see [Grouped queries](grouped-queries.md). |
| `@UpdateModel(root = ...)` | Declares the attributes a bulk update may write; see [Bulk writes](bulk-writes.md). |

A field of type `Instant` or `Date` over a `java.sql.Timestamp` attribute needs no `converter`: the processor gives it
the built-in `InstantTimestampConverter` or `DateTimestampConverter`, so you filter it with values of the field's type:
a `Date placedAt` field takes `f.gte(QOrderView.PLACED_AT, Optional.of(since))` with `since` a `Date`. A `Date` read
this way is the `Timestamp` itself, so comparing it back to the stored value is exact. A plain `java.util.Date` binds
at whole milliseconds, so write an inclusive upper bound as half-open, `lt(nextDayStart)`: `lte(23:59:59.999)` excludes
a stored `23:59:59.999500`. An `Instant` beyond the range of `Timestamp` is refused with `MQ1308`. A converter you
name takes precedence.

A class model needs a no-argument constructor visible from its package and setters. Record components that are
primitive are only allowed on the primary key of a plain model; use the boxed type elsewhere, because a column can be
NULL.

## What the processor generates

For `OrderView` you get `QOrderView` with:

- a `ColumnField` constant per field (`ID`, `STATUS`, `TOTAL`), and per joined column (`CUSTOMER_NAME`);
- a `SelectSet` named `ALL` with the model's own columns, `DEFAULT` (the same minus `@ExcludeFromDefaults`), and one set
  per `@Join` (`CUSTOMER`);
- a constant per `@FilterColumn`, and a `TableField` for every collection association on the root (`ITEMS_TABLE`),
  which you use with `Filters.exists`;
- `ROOT`, `KEY` (the primary key) and `MAPPER`;
- `query()`, a pre-configured `ModelQuery` builder, and `delete()`.

```java
var q = QOrderView.query()
        .select(QOrderView.ALL.with(QOrderView.CUSTOMER))
        .where(f -> f.eq(QOrderView.CUSTOMER_COUNTRY, "DE"))
        .orderBy(QOrderView.CUSTOMER_NAME.asc().nullsFirst(), QOrderView.ID.desc())
        .build();
```

A `SelectSet` is immutable: `with(...)` and `without(...)` return copies, so a shared constant cannot be changed by
one caller and affect another.

## Joins

A `@Join` nested model is read through a join the library creates for you. Selecting, filtering and ordering on the
same path use exactly one join. Two `@Join`s on the same attribute become two joins, each aliased by its field name.

A filter-only column that crosses a collection needs an explicit `joinType` on its `@FilterColumn`, because joining a
collection multiplies rows; prefer `Filters.exists` for "has a child matching X".

A generated `@Join` follows the mapped association, whatever that association joins on. A `@ManyToOne` with
`@JoinColumn(referencedColumnName = "sku")` joins through that unique non-key column instead of the target's key, and a
Hibernate `@JoinFormula` joins through its computed expression; selecting, filtering and sorting on the nested model's
columns use the join the mapping implies. When a join needs an extra `ON` condition that is not part of the mapping —
the case `@Join(on = ...)` would cover — write a hand-written `TableField` and add it with `as(...).on(...)`. The
condition goes through `Join#on`, so a `LEFT` join still keeps the rows that miss it. See
[Migration recipes](recipes.md) for both.

## Hand-written columns

You rarely need to, but a `ColumnField` can be written by hand, for example for a column the server sets:

```java
static final OrderedColumnField<OrderPatch, OrderEntity, Instant> UPDATED_AT =
        ColumnField.of(OrderPatch.class, QOrderPatch.ROOT, "updatedAt", Instant.class);
```

A column with no converter, or with an `OrderedColumnConverter`, is an `OrderedColumnField`, which `Agg.min`, `Agg.max`
and `Agg.countDistinct` take. A column with any other converter is a plain `ColumnField`, and those three do not
compile over it.

## Keep the prefix consistent

The default class name is `Q` plus the model name. If you change `prefix` or `suffix` through the
`-Amodelquery.prefix=` / `-Amodelquery.suffix=` processor options, use the same value in every module that reads a
nested model across module boundaries, or set it on the nested model's own annotation.

## Compile-time checks

The processor checks your models when you compile: an unknown attribute, a type that does not match the entity, a
missing primary key, a `@Join` that is not an `Optional` of a model, and so on. Failures are reported as `MQ3xxx`
errors; see [Diagnostics](diagnostics.md).
