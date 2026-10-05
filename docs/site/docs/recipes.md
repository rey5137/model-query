# Migration recipes

Recipes for moving an existing reporting service onto model-query, one adoption case at a time. Each recipe's code is
copied from a test that runs, named under the code.

## Keep your own repository factory bean

The case in one line: an existing service that already sets its own `repositoryFactoryBeanClass` and
`repositoryBaseClass`, so the starter has to add the fragment without replacing either (R-SPR-02, D-113).

The starter keeps a plain `JpaRepositoryFactoryBean` subclass — its class, its override and your `repositoryBaseClass`
— and re-registers the repository with the fragment as its `customImplementation`, but only where the repository
extends `ModelQueryRepository`:

```java
public class PlainJpaRepositoryFactoryBean<T extends Repository<S, I>, S, I>
        extends JpaRepositoryFactoryBean<T, S, I> {
    public PlainJpaRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }
    // your own override, and a custom repositoryBaseClass, keep working
}
```

```java
@EnableJpaRepositories(repositoryFactoryBeanClass = PlainJpaRepositoryFactoryBean.class,
        repositoryBaseClass = RefreshingJpaRepository.class)
```

If instead you can change the class, extending `ModelQueryRepositoryFactoryBean` is the simplest route; Spring
requires the one-argument constructor:

```java
public class CustomJpaRepositoryFactoryBean<T extends Repository<S, I>, S, I>
        extends ModelQueryRepositoryFactoryBean<T, S, I> {
    public CustomJpaRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }
}
```

```java
@EnableJpaRepositories(repositoryFactoryBeanClass = CustomJpaRepositoryFactoryBean.class,
        repositoryBaseClass = RefreshingJpaRepository.class)
```

Either way the fragment is added only to the repositories that extend `ModelQueryRepository`, and your
`repositoryBaseClass` keeps working for all of them, so migrate one at a time: add `ModelQueryRepository<MyEntity>` to
one interface and leave the rest for later. A definition that already sets `customImplementation` fails with `MQ4008`:
the starter cannot compose the fragment beside it, so leave that implementation to provide the fragment methods or
extend `ModelQueryRepositoryFactoryBean`; a repository whose `ModelQueryRepository<E>` names another entity than its
own domain type fails with `MQ4007`.

Tested by `CustomFactoryBeanTest` and `PlainFactoryBeanTest`.

## Filter by a sub-query

The case in one line: a filter whose values come from another query, or a row that exists only when another root has
a matching row, with no association mapped between the two roots (D-111 item 2).

A `SubSelect` is one column of another root with its own filters. It is immutable, so it can be a constant:

```java
record I(Long orderId, String productCode, Integer quantity) {}

private static final TableField<PlainOrderItemEntity, PlainOrderItemEntity> PLAIN_ITEMS =
        TableField.root(PlainOrderItemEntity.class);
private static final ColumnField<I, PlainOrderItemEntity, Long> ITEM_ORDER_ID =
        ColumnField.of(I.class, PLAIN_ITEMS, "orderId", Long.class);
private static final ColumnField<I, PlainOrderItemEntity, String> ITEM_PRODUCT =
        ColumnField.of(I.class, PLAIN_ITEMS, "productCode", String.class);

// The items of product P007. PlainOrderItemEntity has no association to orders.
private static final SubSelect<I, Long> P007_ITEMS =
        SubSelect.of(ITEM_ORDER_ID).where(f -> f.eq(ITEM_PRODUCT, "P007"));
```

`in` and `notIn` take it uncorrelated. A `NULL` column value never empties a `notIn`, and an empty sub-select keeps
its usual meaning — `in` matches nothing, `notIn` every row:

```java
ORDER_QUERY.where(f -> f.in(ID, P007_ITEMS)).build();      // orders with a P007 item
ORDER_QUERY.where(f -> f.notIn(ID, P007_ITEMS)).build();   // orders without one, NULL columns kept (R-FLT-16)
```

`exists` on such an unmapped root states the correlation explicitly, since there is no association to follow:

```java
ORDER_QUERY.where(f -> f.exists(P007_ITEMS,
        (inner, outer) -> inner.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))).build();
```

Tested by `SubSelectTest`.

## Correlate an exists to the outer row

The case in one line: a correlated `exists` whose inner predicate compares an inner column to a column of the outer
root, so the sub-query reads the row being filtered (D-111 item 5).

`outer.column(...)` lifts an outer-root column into the sub-select's vocabulary. It goes anywhere an inner column
goes, so `compare` puts it on one side, and `or` can mix inner and lifted conditions:

```java
private static final ColumnField<O, OrderEntity, BigDecimal> TOTAL =
        ColumnField.of(O.class, ORDERS, "total", BigDecimal.class);
private static final ColumnField<I, PlainOrderItemEntity, Integer> ITEM_QUANTITY =
        ColumnField.of(I.class, PLAIN_ITEMS, "quantity", Integer.class);

ORDER_QUERY.where(f -> f.exists(P007_ITEMS,
        (inner, outer) -> inner.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))).build();

// notExists is the mirror image, and one branch of an or may read the outer row too.
ORDER_QUERY.where(f -> f.notExists(P007_ITEMS,
        (inner, outer) -> inner.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))).build();
ORDER_QUERY.where(f -> f.exists(P007_ITEMS, (inner, outer) -> inner
        .compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID))
        .or(a -> a.eq(ITEM_QUANTITY, 9),
                b -> b.lt(outer.column(TOTAL), new BigDecimal("100.00"))))).build();
```

The lifted column must sit on the outer query's root, so the outer query joins nothing; the sub-query reads the
outer row through a correlation. Tested by `SubSelectTest`.

## Enrich a joined model

The case in one line: fill a field of the model behind a `@Join` from your own lookup, once per page.

The nested plan carries the enricher, and `join(...)` applies it to the joined models, before the outer plan's
enrichers:

```java
FetchPlan<Patron> patron = FetchPlan.of(SelectSet.of(QPatron.ID))
        .child(QPatron.ORDERS, FetchPlan.of(QOrderRef.ALL))
        .enrich(Enricher.byKey(Patron::name,
                names -> names.stream().collect(Collectors.toMap(Function.identity(), name -> name + "/")),
                (p, tag) -> p.withTag(tag + p.orders().size()),
                QPatron.NAME));
FetchPlan<OrderPatrons> order = FetchPlan.of(SelectSet.of(QOrderPatrons.ID))
        .join(QOrderPatrons.CUSTOMER_JOIN, patron)                    // the same plan under both joins
        .join(QOrderPatrons.REFERRER_JOIN, patron);
```

The lookup runs once per page for each join, with that page's distinct names, and every joined model is filled.
Tested by `EnricherTest`.

## Join on a non-key column or a formula

The case in one line: an association that joins on something other than the target's primary key, or on a computed
value, and an ad-hoc join that needs an extra `ON` condition.

The mapping states the association as JPA or Hibernate already does; the generated `@Join` follows it.

```java
@Entity
@Table(name = "sku_order_lines")
public class SkuOrderLineEntity {
    @Id
    Long id;

    int quantity;

    @ManyToOne
    @JoinColumn(name = "product_sku", referencedColumnName = "sku")   // the product's unique non-key sku
    SkuProductEntity product;
}
```

```java
@Entity
@Table(name = "formula_lines")
public class FormulaLineEntity {
    @Id
    Long id;

    @Column(name = "product_code")
    String productCode;

    int quantity;

    @ManyToOne
    @JoinFormula("upper(product_code)")   // joins the product whose code is upper(product_code)
    FormulaProductEntity product;
}
```

The `@Join` model has an `Optional` nested model per association, with no hint of how the join is written:

```java
@QueryModel(root = SkuOrderLineEntity.class)
public record SkuLineView(@PrimaryKey Long id, Integer quantity, @Join Optional<SkuProductView> product) {}
```

Selecting a nested column, filtering on it and sorting on it all use the mapped join:

```java
var q = QSkuLineView.query()
        .select(SelectSet.of(QSkuLineView.ID, QSkuLineView.QUANTITY, QSkuLineView.PRODUCT_ID,
                QSkuLineView.PRODUCT_SKU, QSkuLineView.PRODUCT_NAME, QSkuLineView.PRODUCT_PRICE))
        .where(f -> f.eq(QSkuLineView.PRODUCT_NAME, Optional.of("Sku 03")))
        .orderBy(QSkuLineView.ID.asc())
        .build();
```

The SQL it renders, abbreviated:

```sql
select l.id, l.quantity, p.id, p.sku, p.name, p.price
from sku_order_lines l
left join sku_products p on p.sku = l.product_sku
where p.name = ?
order by l.id asc
```

For an extra `ON` condition the mapping does not carry, write a hand-written `TableField` and add it with `on(...)`,
which requires the alias from `as(...)`; D-111 declines `@Join(on = ...)` in favour of this:

```java
private record CheapLine(Long id, String productName) {}
private static final TableField<SkuOrderLineEntity, SkuOrderLineEntity> LINES =
        TableField.root(SkuOrderLineEntity.class);
private static final TableField<SkuOrderLineEntity, SkuProductEntity> CHEAP =
        TableField.<SkuOrderLineEntity, SkuProductEntity>join(LINES, "product", LEFT).as("cheap")
                .on((p, cb) -> cb.le(p.<BigDecimal>get("price"), new BigDecimal("1.50")));
private static final ColumnField<CheapLine, SkuOrderLineEntity, Long> LINE_ID =
        ColumnField.of(CheapLine.class, LINES, "id", Long.class);
private static final ColumnField<CheapLine, SkuProductEntity, String> CHEAP_NAME =
        ColumnField.of(CheapLine.class, CHEAP, "name", String.class);

ModelQuery<SkuOrderLineEntity, Long, CheapLine> q = ModelQuery
        .builder(LINES, row -> new CheapLine(row.get(LINE_ID), row.get(CHEAP_NAME)))
        .select(SelectSet.of(LINE_ID, CHEAP_NAME))
        .primaryKey(PrimaryKey.of(LINE_ID))
        .orderBy(LINE_ID.asc())
        .build();
```

Because the condition goes through `Join#on`, a line whose product is not cheap keeps its row with a `NULL` name.

Tested by `NonKeyJoinTest`.

## A shared user-profile enricher

The case in one line: a user profile that lives on another datasource, keyed by a `(userId, userTypeId)` record, and
looked up once per page by an enricher declared once and reused on several models.

The key is a record of the columns the plan selects, and the lookup is one call with the page's distinct keys:

```java
record UserRef(long userId, int userTypeId) {}

private static final Enricher<Line> PROFILE_ENRICHER = Enricher.byKey(
        line -> new UserRef(line.id(), line.quantity()),          // the key of one model
        PROFILES::find,                                           // one call per page: Map<UserRef, String>
        (line, profile) -> line.withProfile(profile),             // a key absent from the map leaves the model as is
        QLine.ID, QLine.QUANTITY);                                 // the columns the key reads
```

The same constant goes on a root plan and on a nested one:

```java
FetchPlan<Line> items = FetchPlan.of(SelectSet.of(QLine.ID, QLine.QUANTITY)).enrich(PROFILE_ENRICHER);
FetchPlan<OrderLines> orders = FetchPlan.of(QOrderLines.ALL)
        .child(QOrderLines.ITEMS, FetchPlan.of(SelectSet.of(QLine.ID, QLine.QUANTITY)).enrich(PROFILE_ENRICHER));
```

Tested by `EnricherTest`.

### A row with several keys: a payment order's actors

The case in one line: a row carries more than one look-up key — a payment order's payer, payee, initiator and optional
requestor, each a `(userType, userId)` pair — and each key fills its own field from one lookup.

The key is a record of the columns the plan selects, and `byKeys` takes one `key(...)` per field, all on the same
lookup, whose values share a type:

```java
record ActorKey(int userType, long userId) {}

Enricher<PaymentOrderView> actors = Enricher.<PaymentOrderView, ActorKey, String>byKeys(
                profiles::find)                                          // one call per run: Map<ActorKey, String>
        .key(PaymentOrderView::payerKey, PaymentOrderView::withPayer)
        .key(PaymentOrderView::payeeKey, PaymentOrderView::withPayee)
        .key(PaymentOrderView::initiatorKey, PaymentOrderView::withInitiator)
        .key(PaymentOrderView::requestorKey, PaymentOrderView::withRequestor)  // null when absent: skipped
        .reading(QPaymentOrderView.PAYER_USER_TYPE, QPaymentOrderView.PAYER_USER_ID,
                QPaymentOrderView.PAYEE_USER_TYPE, QPaymentOrderView.PAYEE_USER_ID,
                QPaymentOrderView.INITIATOR_USER_TYPE, QPaymentOrderView.INITIATOR_USER_ID,
                QPaymentOrderView.REQUESTOR_USER_TYPE, QPaymentOrderView.REQUESTOR_USER_ID);
```

The plan selects the eight declared columns even though the model's own selection does not. A user in two roles of one
row, or in two rows, is looked up once, and the value is passed to every role's setter. `batchSize(n)` splits the
distinct keys into consecutive chunks of at most `n`, one lookup call per chunk. Tested by `ByKeysEnricherTest`.

### A lookup that splits its own keys

The case in one line: the profiles live in one source per user type, so the lookup partitions each chunk by the type.
The library never partitions (R-FCH-16); because each chunk reaches the lookup once, each source is called at most
once per chunk:

```java
Map<ActorKey, String> find(Set<ActorKey> keys) {
    var grouped = new LinkedHashMap<Integer, Set<Long>>();
    for (ActorKey key : keys) {
        grouped.computeIfAbsent(key.userType(), type -> new LinkedHashSet<>()).add(key.userId());
    }
    var found = new LinkedHashMap<ActorKey, String>();
    grouped.forEach((type, ids) -> {
        Map<Long, String> source = byType.getOrDefault(type, Map.of());
        for (long id : ids) {
            String profile = source.get(id);
            if (profile != null) {
                found.put(new ActorKey(type, id), profile);
            }
        }
    });
    return found;
}
```

Set `batchSize(...)` to the smallest source's limit, so no source ever receives more keys than it accepts. Tested by
`ByKeysEnricherTest`.

### A cache shared with a child's enricher

The case in one line: the child's enricher loads a user, and the outer enricher must not load the same user again.
Per-call state belongs in the caller's code, captured when the plan is built (R-FCH-17). The lookup loads only the
keys no one has loaded yet, into a cache both enrichers read:

```java
Function<Set<ActorKey>, Map<ActorKey, String>> cached(SplitProfiles profiles,
        Map<ActorKey, String> cache, List<List<ActorKey>> loads) {
    return keys -> {
        List<ActorKey> missing = keys.stream().filter(key -> !cache.containsKey(key)).toList();
        if (!missing.isEmpty()) {
            loads.add(missing);                                      // the test's bookkeeping; drop it in your own code
            cache.putAll(profiles.find(new LinkedHashSet<>(missing)));
        }
        var found = new LinkedHashMap<ActorKey, String>();
        for (ActorKey key : keys) {
            if (cache.containsKey(key)) {
                found.put(key, cache.get(key));
            }
        }
        return found;
    };
}

var cache = new HashMap<ActorKey, String>();                     // per call, the caller's memory
var childLoads = new ArrayList<List<ActorKey>>();
var orderLoads = new ArrayList<List<ActorKey>>();

FetchPlan<Line> items = FetchPlan.of(SelectSet.of(QLine.ID, QLine.QUANTITY))
        .enrich(Enricher.<Line, ActorKey, String>byKeys(cached(profiles, cache, childLoads))
                .key(line -> new ActorKey(requestedUserType, line.id()), Line::withProfile).reading(QLine.ID));
FetchPlan<OrderLines> order = FetchPlan.of(SelectSet.of(QOrderLines.ID))
        .child(QOrderLines.ITEMS, items)
        .enrich(Enricher.<OrderLines, ActorKey, String>byKeys(cached(profiles, cache, orderLoads))
                .key(o -> new ActorKey(requestedUserType, o.id()), OrderLines::withProfile)
                .reading(QOrderLines.ID));
```

A child plan runs whole before the outer plan's enrichers, so the outer enricher finds what the child loaded. Tested
by `ByKeysEnricherTest`.

### Request-time parameters

The case in one line: the plan depends on the call — a requested user type, a chosen column set, a cursor — so build
it per call and apply it with `withFetch`, which returns a copy of the query and runs no statement of its own
(R-FCH-13):

```java
int requestedUserType = 1;                                    // captured when the plan is built
ModelQuery<OrderEntity, Long, OrderLines> perCall = base.withFetch(order);
```

Such a plan holds caller state and must not be shared across calls; a cache it captures spans export batches and is
the caller's memory (R-FCH-17, INV-9). Tested by `ByKeysEnricherTest`.

## Keyset paging with a String or embedded key

The case in one line: a table whose primary key is a String, or an `@EmbeddedId` of two columns, paged by keyset and by
primary-key-first just like a numeric key.

```java
@Entity
@Table(name = "string_key_products")
public class StringKeyProductEntity {
    @Id
    String code;

    String name;

    String category;

    BigDecimal price;
}
```

```java
@Entity
@Table(name = "embedded_key_items")
public class EmbeddedKeyEntity {
    @EmbeddedId
    Key key;

    String label;

    BigDecimal amount;

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "region_code")
        String regionCode;

        @Column(name = "seq_no")
        int seqNo;
        // equals and hashCode omitted
    }
}
```

The query states its primary key as usual; a String key is `PrimaryKey.of(code)`, an embedded one is
`PrimaryKey.composite(regionCode, seqNo)`:

```java
ModelQuery.Builder<StringKeyProductEntity, String, StringRow> rows = ModelQuery
        .builder(PRODUCTS, row -> new StringRow(row.get(CODE), row.get(NAME), row.get(CATEGORY)))
        .select(SelectSet.of(CODE, NAME, CATEGORY))
        .primaryKey(PrimaryKey.of(CODE));
```

```java
ModelQuery.Builder<EmbeddedKeyEntity, List<Object>, EmbeddedRow> rows = ModelQuery
        .builder(EMBEDDED, row -> new EmbeddedRow(row.get(REGION), row.get(SEQ), row.get(LABEL)))
        .select(SelectSet.of(REGION, SEQ, LABEL))
        .primaryKey(PrimaryKey.composite(REGION, SEQ));
```

The keyset page and its neighbour calls; the cursor is opaque, so pass it through rather than rebuilding it:

```java
var q = rows.orderBy(CATEGORY.asc()).keyset().build();

KeysetSpec spec = KeysetSpec.first(size);
KeysetSlice<StringRow> page = executor.page(q, spec);
while (page.hasNext()) {
    spec = KeysetSpec.after(page.nextCursor().orElseThrow(), size);   // the cursor the page handed back
    page = executor.page(q, spec);
}
// the last page's cursor, walked the other way
KeysetSlice<StringRow> back = executor.page(q, KeysetSpec.before(page.previousCursor().orElseThrow(), size));
```

Primary-key-first paging works the same over the key:

```java
var twoStep = rows.orderBy(CATEGORY.asc())
        .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
        .build();
Slice<StringRow> page = executor.page(twoStep, PageSpec.of(0, 500), CountMode.NO_COUNT);
```

The key's columns close the order, so tied sort values never skip or repeat a row.

Tested by `StringAndEmbeddedKeyTest`.

## An expression in a filter or an aggregate

The case in one line: a condition over a value the database computes, such as `total + 1` or `coalesce(status, 'NONE')`,
and a conditional count, with no extra column mapped for either (D-115, R-COL-17, R-FLT-18, R-AGG-13).

`Expr` builds a typed, immutable expression over one vocabulary's columns, equal by structure. `ExpressionFilterTest`
declares the two this recipe uses as filter operands:

```java
/** {@code total + 1}: a non-null numeric expression over a root column. */
private static final ExpressionField<O, BigDecimal> TOTAL_PLUS = Expr.plus(FiltersTest.TOTAL, BigDecimal.ONE);
/** {@code coalesce(status, 'NONE')}: a non-null text expression over a root column. */
private static final ExpressionField<O, String> STATUS_TEXT = Expr.coalesce(FiltersTest.STATUS, "NONE");
```

`Filters` takes either wherever it takes a column; the suite covers every operator and `Optional` form over them,
for example `f -> f.like(STATUS_TEXT, "AID", LikeMode.CONTAINS)`.

A conditional count is a `cases` expression the aggregate reads, so it counts only the rows each `when` matches:

```java
/** The rows whose quantity is above 4, NULL everywhere else: a conditional {@code count}. */
private static final ExpressionField<ItemTotals, Integer> MANY_ITEMS =
        Expr.cases(ItemTotals.class, Integer.class).when(f -> f.gt(QUANTITY, 4), 1).orNull();

private static final AggregateField<ItemTotals, Long> CONDITIONAL_COUNT = Agg.count(MANY_ITEMS);
```

The expression's values bind as parameters. A filter and an aggregate argument take an expression now; `groupBy`'s
parameter is widened to `ScalarField` for source compatibility, but an expression as a group key, a selected column or
an order key is refused until M9.14.

<!-- M9.14: an expression as a selected column, a group key or an order key, and the paging rules over one. -->

Tested by `ExpressionFilterTest` and `ExpressionAggregateTest`.
