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
| `@QueryModel(root = ...)` | Marks a model and names its root entity. `prefix` and `suffix` rename the generated class; `singleGroup` is covered in [Grouped queries](grouped-queries.md), `generateChanges` in [Shared accessors across models](#shared-accessors-across-models). |
| `@PrimaryKey` | The entity id. Paging, export and nested-model presence read it. Required on a model without aggregates. |
| `@Column(attribute = ..., converter = ..., treat = ...)` | Maps a field to a differently named attribute, converts between types, or reads a subtype attribute in a polymorphic hierarchy; see [Inheritance roots](inheritance-roots.md). |
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

A `Date` field over an entity attribute that is itself a `java.util.Date`, with or without `@Temporal`, needs nothing
either. Hibernate reads such an attribute as a `java.sql.Date`, `Time` or `Timestamp`, so that is the class the field
holds; each is a `Date`. On a `@Temporal(DATE)` attribute, filter with a date at midnight: whether a time of day in the
value is kept or dropped before it is compared depends on the JPA provider. A `java.sql.Date` prints as `yyyy-MM-dd`,
and `Timestamp.equals` is false for a plain `Date`, so compare such values by `getTime()`. A `ColumnConverter` over one
of these attributes is given the `java.sql` instance; `toInstant()` throws on a `java.sql.Date` or `Time`, so convert
with `getTime()`.

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
- one `<SUBTYPE>_TREAT` constant per distinct `treat` target (R-GEN-41);
  see [Inheritance roots](inheritance-roots.md);
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

### Join filters

The incubating `ModelQuery.Builder#joinFilter` binds a request's filter into one LEFT join's `ON`. A row that
fails the filter keeps its outer row and reads the joined columns as null. This example is adapted from
`JoinFilterExecutionTest`, using its `Detail(Long id, String joined)` row:

```java
var orders = TableField.root(OrderEntity.class);
var id = ColumnField.of(Detail.class, orders, "id", Long.class).named("id");
var customer = TableField.join(orders, "customer", JoinType.LEFT);
var customerName = ColumnField.of(Detail.class, customer, "name", String.class).named("customer");
var query = ModelQuery.builder(orders, row -> new Detail(row.get(id), row.get(customerName)))
        .select(SelectSet.of(id, customerName)).primaryKey(PrimaryKey.of(id))
        .where(f -> f.gte(id, 990L).lte(id, 1030L))
        .joinFilter(customer, f -> f.lt(customerName, "Customer 0500"))
        .orderBy(id.asc()).build();
```

=== "Plain JPA"

    ```java
    List<Detail> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<Detail> rows = orderRows.findAll(query, Limit.unlimited());
    ```

The same call accepts an `EntityJoin`: `joinFilter(MANAGER, f -> f.lt(MANAGER_NAME, "Customer 1000"))`, as in
`JoinFilterExecutionTest`. See
[entity joins off a joined source](source-models.md#joining-an-entity-off-a-joined-source)
for that definition. The filter is ANDed with the entity join's key predicate or an association's static
`TableField.on(...)` in exactly one provider `on(...)` call. It follows every statement that renders the join,
including count, both primary-key-first steps, keyset and export. It also follows a joining `where` term moved
into the semi-join used for a to-many filter.

The lambda runs once when `joinFilter` is called; only recorded terms and values stay on the query. The shared
`TableField` or `EntityJoin` holds no request state. There is one filter per join key per query: a second call
**replaces** the first. Optional values and `when(...)` can skip terms. An all-skipped filter removes an earlier
filter and is absent from storage, inspection and query-condition equality, as tested by `JoinFilterTest`.

A `where` predicate on a column the join filter blanked can then drop the row. For example, adding
`isNotNull(customerName)` to the example's `where` removes every row whose customer failed the ON filter;
LEFT keeps the outer row with null joined values, and WHERE filters that row out. This is tested by
`JoinFilterExecutionTest.ac_col_55_where_filter_on_a_blanked_column_drops_the_row`.

Only the filtered join's own columns, values and constants are allowed. The refusals are tested by
`JoinFilterTest`, `JoinFilterValidationTest` and `JoinFilterExecutionTest`:

- At build, [`MQ1912`](diagnostics.md) rejects a root or INNER join, another table (including the root or a sibling),
  any descendant path (to-one or to-many), `exists`, an aggregate, window or correlated value, a sub-query term
  crossing the filtered join, or a filter on an entity join the query does not bind. A sub-query reading the value through `outer.column(...)` reuses the outer join
  and is allowed.
- At first resolution, before SQL, `MQ1912` rejects id-form access to the filtered join, a join key the content
  statement never applies (including a different type or alias), another join key resolving to that join, or a
  filter column creating a descendant join, including an id-form or whole-entity to-one column. For id-form
  access, map the foreign key as a basic column instead.
- A join that renders `INNER` (declared, and not turned `LEFT` by `or(...)`, `not` or an aggregate filter) below a
  filtered join, root or entity join, is `MQ1912` at the first statement that resolves it, since a join filter only blanks columns and never removes rows. Declare such a join
  `LEFT`. A declared `INNER` that `or(...)`, `not` or an aggregate filter renders `LEFT` is allowed.
- A filter alone creates no association join: select, filter or order on that join elsewhere in the query.
  A null value is `MQ1301` at the `joinFilter` call.

## Foreign key by id

A to-one association can be read as its foreign key, with no nested model, by declaring a column of the target's id
type over it:

```java
@QueryModel(root = OrderEntity.class, generateChanges = true, generateInserts = true)
public record OrderRow(
        @PrimaryKey Long id,
        String status,
        @Column(attribute = "customer") Long customerId,
        @Column(attribute = "referrer") Long referrerId) {}
```

The field's type, or its converter's attribute type (`ColumnConverter<CustomerId, Long>`), must be the target's single
basic id type; another type is `MQ3002`, which names the id type. The column reads through a `LEFT` join and the
target's id, so an order whose foreign key is `NULL` comes back with `null` rather than disappearing, in a select, a
filter, an order, a page, a `count`, a keyset or an export. A locked read of it is `MQ2211`. When you also need the
target's columns, add a `@Join` beside it; the constants must differ (`MQ3015`), which the field name settles:

```java
@Column(attribute = "customer") Long customerId,       // CUSTOMER_ID
@Join Optional<CustomerView> customer                  // CUSTOMER_TABLE, CUSTOMER_NAME, ...
```

Use the id form instead of mapping the foreign key twice in the entity (a basic `Long referrerId` beside the
`@ManyToOne`, as before): the entity keeps one mapping, and the processor writes the column too. `generateChanges` and
`generateInserts` include the id form like any scalar, and the generated `from` copies it. A whole-entity to-one
column (`CustomerEntity customer`) stays read-only in both, and `@GroupBy` on it is `MQ3028`: group by the id form.

The processor refuses, with `MQ3002` and the reason, the mappings whose foreign key is not the target's primary key or
that Hibernate joins: the inverse side (`mappedBy`), a `referencedColumnName`, `@JoinColumns`, `@JoinFormula`,
`@JoinColumnOrFormula`, `@NotFound`, `@JoinTable`, a `@JoinColumn(table = "...")`, a target with `@MapsId`, `@IdClass`
or `@EmbeddedId`, and a to-one inside an embedded value. Read those through a `@Join`. The dotted
`@Column(attribute = "referrer.id")` stays `MQ3001`. A bulk update or delete that uses the id form in its filter, its
set clause or an upsert's `doUpdate(...).where(...)` renders the foreign-key column and takes no join; a hand-written
column over a mapping the provider does not report as a column of the entity's own table referencing the target's
primary key (a formula, a key on a `@SecondaryTable`, a `@NotFound`) is `MQ1004` before any statement. See
[Migration recipes](recipes.md#read-and-write-a-foreign-key-by-id).

The id comes from the `LEFT`-joined row, so a target whose entity has `@SQLRestriction` or `@Where` would read `null`
for a foreign key that holds a value, and `generateChanges` with `from(row)` would then write `NULL` over it. The
processor refuses that with `MQ3029`; read such a to-one through a `@Join`, or map the foreign key as a basic column on
the entity. A runtime `@Filter` on the target has the same effect on a read and is not checked: the id reads `null`
while the filter is enabled.

The processor also refuses a `@JoinColumn(referencedColumnName = "id")` that names the primary key, since it can't tell
that from another column, although the runtime accepts it. For such a mapping, write the column by hand:

```java
static final OrderedColumnField<OrderRow, OrderEntity, Long> REFERRER_ID =
        ColumnField.of(OrderRow.class, QOrderRow.ROOT, "referrer", Long.class);
```

## Hand-written columns

You rarely need to, but a `ColumnField` can be written by hand, for example for a column the server sets:

```java
static final OrderedColumnField<OrderPatch, OrderEntity, Instant> UPDATED_AT =
        ColumnField.of(OrderPatch.class, QOrderPatch.ROOT, "updatedAt", Instant.class);
```

A column with no converter, or with an `OrderedColumnConverter`, is an `OrderedColumnField`, which `Agg.min`, `Agg.max`
and `Agg.countDistinct` take. A column with any other converter is a plain `ColumnField`, and those three do not
compile over it.

## Computed fields

A field whose value the database computes, rather than one it stores, is marked `@Computed` with a class implementing
`ExpressionDefinition<Model, FieldType>`:

```java
@QueryModel(root = OrderEntity.class)
public record OrderNet(@PrimaryKey Long id, @Computed(RowNet.class) BigDecimal net) {}
```

```java
/** {@code total * 2} over {@code orders}, typed to the record model {@link OrderNet} (R-PROC-21). */
public final class RowNet implements ExpressionDefinition<OrderNet, BigDecimal> {

    public static final RowNet INSTANCE = new RowNet();

    private RowNet() {}

    @Override
    public ExpressionField<OrderNet, BigDecimal> expression() {
        return Expr.times(ColumnField.of(OrderNet.class, TableField.root(OrderEntity.class), "total",
                BigDecimal.class), BigDecimal.valueOf(2));
    }
}
```

The generated constant is an `ExpressionField` named after the field, emitted after the root, joined and filter-only
column constants and after earlier `@Computed` constants — the `@Aggregate` constants come last — so a definition
reading `Q<Model>`'s own constants finds them set (R-GEN-27), and selected by `ALL` and `DEFAULT` unless
`@ExcludeFromDefaults`. The definition's type arguments must be exactly the model and the field's boxed type, and the
class must have a public `INSTANCE` field or a visible no-arg constructor, or the processor reports `MQ3018`;
`@Computed` can't share a field with another mapping annotation, or sit on a primitive field, and then reports
`MQ3019`. Build the expression inside `expression()`, or from `Q<Model>` constants, never from a static constant the
generated class initialises. The expression vocabulary, `@Aggregate(expression = ...)` and where an expression plugs
in are on [Sub-queries and expressions](subqueries-expressions.md).

Tested by `GeneratedModelTest`.

## Selected fields

A record gets `null` for a column the query did not select, and so for a selected column that is `NULL`; a class keeps
the field's initialiser in both cases. To tell them apart, declare one `@Selected SelectSet<Model>` field or record
component, `Model` being the model itself:

```java
@QueryModel(root = OrderEntity.class)
public record OrderView(
        @PrimaryKey Long id,
        String status,
        @Column(attribute = "referrerId") Long referrerKey,
        @Selected @JsonIgnore SelectSet<OrderView> selected) {}
```

The generated mapper fills it on every row, never with `null`, with the constants of `QOrderView` it reads that the
row selected: columns, `@Computed` and `@Aggregate` constants and joined columns of any depth. A selected column that is
`NULL` is in the set, and a column the query left out is not:

=== "Plain JPA"

    ```java
    OrderView order = executor.list(QOrderView.query().select(SelectSet.of(QOrderView.STATUS)).build(), Limit.of(1)).get(0);
    order.selected().contains(QOrderView.STATUS);        // true, whatever the status is
    order.selected().contains(QOrderView.REFERRER_KEY);  // false: never selected, so `null` means nothing
    ```

=== "Spring repository"

    ```java
    OrderView order = orders.findAll(QOrderView.query().select(SelectSet.of(QOrderView.STATUS)).build(), Limit.of(1)).get(0);
    order.selected().contains(QOrderView.STATUS);        // true, whatever the status is
    order.selected().contains(QOrderView.REFERRER_KEY);  // false: never selected, so `null` means nothing
    ```

What the set holds is what the row selected, so it can hold more than the caller's own `SelectSet`: the primary key and
the ordering, group, fetch-plan and join-presence columns the engine adds are in it, because the field holds them. A
column used only in a filter, or an `orderBy` on a filter-only column, is not, since no field of the model holds it. A
nested `@Join` or `@Child` model with its own `@Selected` field fills it from its own row, and a LEFT-join miss builds no
nested model, so there is nothing to fill. Every row of one query shares one set instance.

The field is no column and no constant, and cannot be combined with another field annotation (`MQ3021`); its type must be
exactly `SelectSet<Model>`, and a model has one (`MQ3020`). An update model and an insert model refuse it (`MQ3302`,
`MQ3502`). Put `@JsonIgnore` (or your serialiser's equivalent) on it: it is for server-side code that reads a model,
not for a response body. `@Selected`, `SelectSet.contains` and `SelectSet.selectedIn` are `@Incubating`. `contains` is
exact, as `Row.isSelected` is, and never looks through a converter.

A record `finisher` builds a copy through the canonical constructor, so it passes `selected()` along like any component
it does not change: `finisher(o -> new OrderView(o.id(), label(o), o.referrerKey(), o.selected()))`. The library does
not fill the set a second time, so a copy built with `null` holds `null` (R-GEN-31).

Tested by `SelectedFieldsTest` and `SelectedPersistTest`.

## Joined sources and report aggregates

Three additions serve report models. They are `@Incubating`.

`@JoinSource` (repeatable, on a `@QueryModel` rooted at an entity) joins a unique `@SourceModel` on its full key, and
`@SourceColumn` reads one of its fields into the annotated field:

```java
@QueryModel(root = DayEntity.class)
@JoinSource(name = "sales", source = DaySales.class, type = JoinKind.LEFT,
        on = @JoinSource.On(column = "businessDate", key = "businessDate"))
public record UnreconciledDay(
        @PrimaryKey Long id, BigDecimal countedCash,
        @SourceColumn(join = "sales", field = "cash") BigDecimal salesCash) {}
```

The `name` is a Java identifier unique in the model; it becomes the generated `SourceJoin` constant and the
parameter of `QUnreconciledDay.query(SourceQuery<DaySales> sales)`. `type` is `LEFT` (the default) or `INNER`, and one
`@On(column, key)` is needed for each key field of the source, once, with the same type on both sides. A
`@SourceColumn` has the source field's type, takes no `@Column` and no converter, and is no column of the root, so
`generateInserts` and `generateChanges` leave it out. [Joining a source](source-models.md#joining-a-source) covers
uniqueness, cost and the runtime rules.

`@Aggregate` takes three more members, described with the [aggregate functions](grouped-queries.md#first-value-in-a-group):

- `orderBy`, a `String[]` of `"path [asc|desc] [nulls first|nulls last]"` keys over basic attributes of the aggregated
  root, required by `fn = FIRST_BY` and refused on any other function;
- `filter`, a class implementing `FilterDefinition<Model>` with an `INSTANCE` or a visible no-arg constructor, which
  aggregates only the rows its condition matches;
- `scale`, `0` to `30` (`-1`, the default, is unset), which rounds a `BigDecimal` field. An `AVG` field typed
  `BigDecimal` is the exact average (`Agg.avgExact`).

The processor refuses a misdeclared `@JoinSource` or `@SourceColumn` with `MQ3044` (a mismatched type is `MQ3002`, a `@Column` beside it `MQ3014`; including a `@SourceColumn` beside
`@PrimaryKey`, and a nested `@Join` model that joins a source), a `@Child` model that joins a source with `MQ3039`, and a
misdeclared `orderBy`, `filter` or `scale` with `MQ3045`; see [Diagnostics](diagnostics.md).

## Shared accessors across models

A type carries one of `@QueryModel`, `@UpdateModel` and `@InsertModel` (`MQ3503` otherwise), and the processor reads
only the fields a model declares itself, never a superclass's: a field inherited from a base class is not a column.
When a read model and a write model over one entity share fields, declare them in each record and share the accessors
through an interface instead:

```java
public interface CustomerContact {
    String name();

    String country();

    default String label() {
        return name() + " (" + country() + ")";
    }
}

@QueryModel(root = CustomerEntity.class)
public record CustomerCard(@PrimaryKey Long id, String name, String country) implements CustomerContact {}

@UpdateModel(root = CustomerEntity.class)
public record CustomerContactPatch(@PrimaryKey Long id, String name, String country) implements CustomerContact {}
```

Code written against `CustomerContact` (a label, a validator, a mapper to a DTO) then takes either model, and each
model keeps its own generated class: `QCustomerCard` for queries, `QCustomerContactPatch` and its change set for
updates. Each record still lists its own components, which is what keeps an update model to the attributes its
endpoint may write. Tested by `SharedAccessorModelTest`.

For internal code that only needs to write back what it read, `@QueryModel(generateChanges = true)` gives one model a
change set over its root, non-key columns as well; don't bind that change set from a request, since it can write every
root column the model reads.

## One model for reading and creating

A screen that reads one row shape and creates it keeps one model with `@QueryModel(generateInserts = true)` (D-123,
`@Incubating`). The processor then generates `INSERT_COLUMNS`, `insert`, `insertFrom` and `persist` on the query model,
as an `@InsertModel` has them (see [Inserts](inserts.md#insert-models)), over the model's root columns:

```java
@QueryModel(root = Customer.class, generateInserts = true)
public record CustomerView(@PrimaryKey @Column(attribute = "id") Long id,
                           @Column(attribute = "name") String name,
                           @Column(attribute = "email") String email) {}
```

=== "Plain JPA"

    ```java
    executor.insert(QCustomerView.insert(rows).build());
    Long id = executor.persist(QCustomerView.persist(row));
    ```

=== "Spring repository"

    ```java
    customers.insert(QCustomerView.insert(rows).build());
    Long id = customers.persist(QCustomerView.persist(row));
    ```

The flag leaves these out, with no diagnostic:

- `@Join`, `@Child`, `@Computed`, `@Selected` and `@Transient` fields, and `@FilterColumn`s;
- a to-one column, whole-entity or through a converter (a query model cannot name a foreign key as a scalar);
- a generated id, the `@Version` column, and a column an insert cannot write (`insertable = false`);
- a non-id root column whose attribute carries Hibernate's `@Generated` with `writable = false`, an empty `sql` and
  `INSERT` among its events (D-125): the database fills it, so Hibernate never writes it either, and a computed
  (`GENERATED ALWAYS AS`) column would refuse a value. Every timestamp annotation (`@CreationTimestamp`,
  `@UpdateTimestamp`, `@CurrentTimestamp`) stays written whatever its `source`, since a Hibernate insert does not fill
  it: exclude the field below to let the server set it.

The Javadoc of `INSERT_COLUMNS` names each field left out and why, so a missing column is visible in the IDE.

A column whose default only the DDL declares — a plain `DEFAULT` clause or `@ColumnDefault` — looks like any other to
the processor, so mark the field `@ExcludeFromInserts` (`@Incubating`, D-125) to leave it out of `INSERT_COLUMNS`,
`insert`, `insertFrom` and `persist` too; `insert` then writes no value for it and the database default applies:

```java
@QueryModel(root = OrderEntity.class, generateInserts = true)
public record OrderView(
        @PrimaryKey Long id,
        String customer,
        @ExcludeFromInserts String status,        // DEFAULT 'NEW'
        @ExcludeFromInserts Instant createdAt) {} // DEFAULT now()
```

The field is still a column of the model and keeps its constant, so a caller can set it on the write; the recipe for a
server-set timestamp:

=== "Plain JPA"

    ```java
    Instant now = Instant.now();
    executor.insert(QOrderView.insert(rows).set(QOrderView.CREATED_AT, now).build());
    ```

=== "Spring repository"

    ```java
    Instant now = Instant.now();
    orders.insert(QOrderView.insert(rows).set(QOrderView.CREATED_AT, now).build());
    ```

`persist` takes no `set`, so it writes an excluded column as the no-arg constructor leaves it (R-WRT-25): `status
DEFAULT 'NEW'` reads back `NULL` unless the entity has a field initializer or `@DynamicInsert`. On a model without
`generateInserts`, on an `@UpdateModel` or `@InsertModel`, or on a field the flag already leaves out,
`@ExcludeFromInserts` is `MQ3506`; on a `@Selected` field it is `MQ3021`. `MQ3501` refuses an excluded assigned id,
and excluding every writable column is `MQ3505`.

Things to know:

- A grouped model (`@Aggregate`, `@GroupBy` or `singleGroup = true`), or one with no writable root column, is `MQ3505`.
- `@QueryModel` and `@InsertModel` on one type stay `MQ3503`; set `generateInserts` instead.
- `generateChanges` leaves a whole-entity to-one column out for the same reason (D-124), and the change set's Javadoc
  says so; a to-one column of the target's id is written (see [Foreign key by id](#foreign-key-by-id)). The column is
  still read: a whole-entity or converted to-one column reads through a `LEFT` join, so a row whose foreign
  key is `NULL` comes back with `null` rather than disappearing.
- A model that binds a request can write every listed column, so a public create endpoint keeps its own
  `@InsertModel` listing only what a caller may set (as with `generateChanges`).
- A foreign key is written by a column of the target's id type over the to-one (see
  [Foreign key by id](#foreign-key-by-id)), or by an `@InsertModel` (an `@UpdateModel` for an update). Mapping the
  column twice in the entity, a basic `Long customerId` with `insertable`/`updatable` on and the `@ManyToOne` read-only,
  still works.
- `addKey` follows the root's id, and `insert` and `persist` are typed by it. For a composite key the model's `KEY`
  is a `PrimaryKey<M, List<Object>>` while `persist` returns the `@IdClass` or `@EmbeddedId` type, so
  `one(q, QCustomerView.persist(row))` does not compile there; read it back with a filter on the key's columns.
  A `@PrimaryKey` on a non-id unique column is written with `add`. An assigned id must be written by some column, annotated
  `@PrimaryKey` or not (`MQ3501` otherwise); a plain column that writes it does not change the read key.

## Models in an API module

A model that is passed outward, to a client or another service, can live in a module that has no entity. That module
depends on `model-query-annotations` and `model-query-core` only; the persistence module, which has the entities and the
processor, depends on the API module and binds each model with `@BindQueryModel`. The processor reads the model from the
class path, checks it against the root entity as it would an annotated model, and generates its `QModel` in the
binding's package.

```text
shop-api          OrderView, OrderLine          (no JPA, no processor)
shop-persistence  OrderEntity, Models           (processor; depends on shop-api)
```

The model carries no `@QueryModel`; `root` moves to the binding:

```java
package shop;

public class OrderView {
    @PrimaryKey
    private Long id;
    private String status = "NEW";
    private BigDecimal total;
    @Column(attribute = "address.city")
    private String city;

    public void setId(Long id) { this.id = id; }
    public void setStatus(String status) { this.status = status; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public void setCity(String city) { this.city = city; }
}
```

```java
package persistence;

import shop.OrderView;

@BindQueryModel(model = OrderView.class, root = OrderEntity.class)
final class Models {}
```

`persistence.QOrderView` equals the `QOrderView` an annotated `OrderView` generates, apart from its package.
`@BindQueryModel` repeats on one type and goes on a `package-info` as well, and takes `generateSelectSets`, `prefix`,
`suffix` and `singleGroup` as `@QueryModel` does. Set `generateChanges` or `generateInserts` for the change set or the
insert builders of the same model, or bind an update or insert model of its own:

```java
@BindUpdateModel(model = OrderPatch.class, root = OrderEntity.class)
@BindInsertModel(model = NewOrder.class, root = OrderEntity.class)
final class WriteModels {}
```

A model is bound once for each kind. Two bindings, or a binding and an annotated model, that would generate one class
name are `MQ3023`; give one a `prefix` or another package. What the generated classes call must be public, since they
sit in another package: the model, its no-arg constructor, its setters, the getters that `from` and insert members read, its `@Join` getters, and
a nested model's type and the types enclosing it (`MQ3024`). A `@Join` or `@Child` model of a bound model is itself
annotated `@QueryModel`, on the classpath or in the compilation, or bound in the same compilation (`MQ3026`), and its
`QModel` lands in the annotated model's package or the nested binding's. An annotated model may nest a bound one too,
with `modelquery.aggregating` set (below). A binding whose root, or a nested model's root, names no class yet is retried by name
in each later round, and is `MQ3017` on the binding if the class is never generated:

```java
@BindQueryModel(model = CustomerView.class, root = CustomerEntity.class)
@BindQueryModel(model = InvoiceView.class, root = InvoiceEntity.class)
@BindQueryModel(model = CountryView.class, root = CountryEntity.class)
final class Models {}
```

### Computed fields and aggregates

The API module cannot compile an `ExpressionDefinition` over an entity it does not have, so `@Computed` may omit
`value` there and the binding supplies the definition. The same `@BindExpression` turns an `@Aggregate` into an
expression aggregate:

```java
public record OrderBand(
        @GroupBy @Computed String band,
        @Aggregate(fn = AggregateFunction.SUM) BigDecimal doubled,
        @Aggregate(fn = AggregateFunction.COUNT) Long id) {}
```

```java
@BindQueryModel(model = OrderBand.class, root = OrderEntity.class, expressions = {
        @BindExpression(field = "band", value = Band.class),
        @BindExpression(field = "doubled", value = BandDoubled.class)})
final class Models {}
```

A valueless `@Computed` with no `@BindExpression`, or on an annotated model, is `MQ3025`, as is a `@BindExpression`
naming no such field.

### Build setup

The processor runs in the persistence module only, and the API module is an ordinary dependency.

=== "Maven"

    ```xml
    <!-- shop-api/pom.xml -->
    <dependencies>
        <dependency>
            <groupId>io.github.rey5137</groupId>
            <artifactId>model-query-annotations</artifactId>
            <version>0.10.0</version>
        </dependency>
    </dependencies>

    <!-- shop-persistence/pom.xml -->
    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>shop-api</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>io.github.rey5137</groupId>
                            <artifactId>model-query-processor</artifactId>
                            <version>0.10.0</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
    ```

    Maven recompiles the persistence module when the API module changes, and regenerates the `QModel`s.

=== "Gradle"

    ```kotlin
    // shop-api/build.gradle.kts
    dependencies {
        api("io.github.rey5137:model-query-annotations:0.10.0")
    }

    // shop-persistence/build.gradle.kts
    dependencies {
        implementation(project(":shop-api"))
        annotationProcessor("io.github.rey5137:model-query-processor:0.10.0")
    }

    // Recompile when the API module's classes change in any way, annotations on private fields included.
    tasks.named<JavaCompile>("compileJava") {
        inputs.files(configurations.runtimeClasspath).withNormalizer(ClasspathNormalizer::class)
    }

    // Needed when a model nests a bound model whose binding is in another source file.
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-Amodelquery.aggregating=true")
    }
    ```

The `compileJava` input is a Gradle limit, not a library choice. The processor is `isolating`: each generated file has
the binding as its one originating element, and Gradle recompiles the binding when the bound model's public shape
changes. An edit that only changes annotations on the model's private fields or record components, such as a new
`@Column(attribute = ...)`, is outside Gradle's compile-avoidance ABI, so without the input Gradle skips the persistence
module's compile and its `QModel` stays stale until a clean build. With the input, any change to the API module's
classes recompiles the persistence module in full (D-140). A nested model bound in a different persistence module from
its outer model is not supported yet: bind both in one module.

Gradle recompiles each model on its own by default (`isolating`), and then misses a binding in another source file. Set
`modelquery.aggregating=true` when a model nests a bound model whose binding is in another file, as an annotated model
nesting a bound one always does; without it, a recompile of the outer model alone can report `MQ3005`, `MQ3401` or
`MQ3026` wrongly, or leave its `QModel` naming a removed class, until a clean build. Maven does not compile
incrementally and needs nothing.

The binding annotations are `@Incubating` (see [Stability](stability.md)). The checks above are
[`MQ3023` to `MQ3026`](diagnostics.md).

## Processor options

The processor reads four options, each passed to `javac` as `-Akey=value`:

| Option | Default | Effect |
|---|---|---|
| `modelquery.prefix` | `Q` | The prefix of a generated class name. |
| `modelquery.suffix` | empty | The suffix of a generated class name. |
| `modelquery.jspecify` | `false` | With `true`, the generated classes carry JSpecify nullness annotations. |
| `modelquery.aggregating` | `false` | With `true`, Gradle treats the processor as aggregating; needed when a model nests a bound model bound in another source file. |

`modelquery.jspecify` is new in 0.9.0 and incubating. With it on, every generated `Q` class and change set is
`@NullMarked`, and a change set's values, setter parameters and getters are `@Nullable`, since a change set holds
`null` to write a NULL. The annotations are named in the generated source, not depended on: add
`org.jspecify:jspecify` to the module that compiles the models. Without it on the classpath the processor reports
`MQ3027` once and generates no annotation.

### Checking nullness with NullAway

With the option on, [NullAway](https://github.com/uber/NullAway) in JSpecify mode checks your own code against the
generated API: dereferencing a change set's `@Nullable` getter is an error. Run NullAway over your packages and the
packages the models generate into, which are the same ones. JPA entities and query-model classes are filled by
reflection, so they are excluded from the field-initialization check.

=== "Maven"

    ```xml
    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
            <compilerArgs>
                <arg>-Amodelquery.jspecify=true</arg>
                <arg>-XDcompilePolicy=simple</arg>
                <arg>-XDaddTypeAnnotationsToSymbol=true</arg>
                <arg>--should-stop=ifError=FLOW</arg>
                <arg>-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=com.example -XepOpt:NullAway:JSpecifyMode=true -XepOpt:NullAway:ExcludedClassAnnotations=jakarta.persistence.Entity,jakarta.persistence.Embeddable,com.rey.modelquery.annotations.QueryModel</arg>
            </compilerArgs>
            <annotationProcessorPaths>
                <path>
                    <groupId>io.github.rey5137</groupId>
                    <artifactId>model-query-processor</artifactId>
                    <version>0.10.0</version>
                </path>
                <path>
                    <groupId>com.google.errorprone</groupId>
                    <artifactId>error_prone_core</artifactId>
                    <version>2.47.0</version>
                </path>
                <path>
                    <groupId>com.uber.nullaway</groupId>
                    <artifactId>nullaway</artifactId>
                    <version>0.13.1</version>
                </path>
            </annotationProcessorPaths>
        </configuration>
    </plugin>
    ```

    On JDK 17 and later, Error Prone needs the `jdk.compiler` exports in `.mvn/jvm.config`; see the
    [Error Prone installation guide](https://errorprone.info/docs/installation).

=== "Gradle"

    ```kotlin
    plugins {
        id("net.ltgt.errorprone") version "5.1.1"
    }

    dependencies {
        annotationProcessor("io.github.rey5137:model-query-processor:0.10.0")
        compileOnly("org.jspecify:jspecify:1.0.0")
        errorprone("com.google.errorprone:error_prone_core:2.47.0")
        errorprone("com.uber.nullaway:nullaway:0.13.1")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Amodelquery.jspecify=true")
        options.errorprone {
            disableAllChecks.set(true)
            error("NullAway")
            option("NullAway:AnnotatedPackages", "com.example")
            option("NullAway:JSpecifyMode", "true")
            option("NullAway:ExcludedClassAnnotations",
                "jakarta.persistence.Entity,jakarta.persistence.Embeddable,com.rey.modelquery.annotations.QueryModel")
        }
    }
    ```

The `net.ltgt.errorprone` plugin supplies the `-XDcompilePolicy` and `-XDaddTypeAnnotationsToSymbol` flags itself.
The Maven snippet carries the flags the processor's own NullAway test passes (`NullAwayTest`).

## Keep the prefix consistent

The default class name is `Q` plus the model name. If you change `prefix` or `suffix` through the
`-Amodelquery.prefix=` / `-Amodelquery.suffix=` processor options, use the same value in every module that reads a
nested model across module boundaries, or set it on the nested model's own annotation.

## Compile-time checks

The processor checks your models when you compile: an unknown attribute, a type that does not match the entity, a
missing primary key, a `@Join` that is not an `Optional` of a model, and so on. Failures are reported as `MQ3xxx`
errors; see [Diagnostics](diagnostics.md).
