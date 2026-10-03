# Migration recipes

Recipes for moving an existing reporting service onto model-query, one adoption case at a time. Each recipe's code is
copied from a test that runs, named under the code.

## Keep your own repository factory bean

The case in one line: an existing service that already sets its own `repositoryFactoryBeanClass` and
`repositoryBaseClass`, so the starter leaves its repositories alone (R-SPR-02).

Extend `ModelQueryRepositoryFactoryBean` instead of `JpaRepositoryFactoryBean`; Spring requires the one-argument
constructor:

```java
public class CustomJpaRepositoryFactoryBean<T extends Repository<S, I>, S, I>
        extends ModelQueryRepositoryFactoryBean<T, S, I> {
    public CustomJpaRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }
}
```

Set it as before, next to your own base class:

```java
@EnableJpaRepositories(repositoryFactoryBeanClass = CustomJpaRepositoryFactoryBean.class,
        repositoryBaseClass = RefreshingJpaRepository.class)
```

The subclass adds the fragment only to the repositories that extend `ModelQueryRepository`, and your
`repositoryBaseClass` keeps working for all of them. Because it is added per repository, migrate one at a time: add
`ModelQueryRepository<MyEntity>` to one interface and leave the rest for later.

Tested by `CustomFactoryBeanTest`.

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

Very large key sets will be chunked in a later release (M9.12); until then the lookup receives all of a page's
distinct keys in one call. Tested by `EnricherTest`.

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
