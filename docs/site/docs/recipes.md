# Migration recipes

Recipes for moving an existing reporting service onto model-query, one adoption case at a time. Each recipe's code is
copied from a test that runs, named under the code.

## Replace a dynamic JPQL DTO query

A typical read before migration: a JPQL constructor expression, a query string that grows with each optional filter,
and every parameter bound a second time.

```java
var jpql = new StringBuilder("select new com.rey.modelquery.sample.plainjpa.OrderRow(o.id, o.status, o.total,"
        + " c.name) from OrderEntity o left join o.customer c where 1 = 1");
status.ifPresent(s -> jpql.append(" and o.status = :status"));
country.ifPresent(s -> jpql.append(" and c.country = :country"));
jpql.append(" order by o.id");
var query = em.createQuery(jpql.toString(), OrderRow.class);
status.ifPresent(s -> query.setParameter("status", s));
country.ifPresent(s -> query.setParameter("country", s));
return query.setMaxResults(100).getResultList();
```

Move the columns and the join into a model once. The joined model's columns become constants of the outer one
(`QOrderView.CUSTOMER_COUNTRY`), and an empty `Optional` skips its filter, so the query needs no branches:

```java
@QueryModel(root = OrderEntity.class)
public class OrderView {
    @PrimaryKey
    private Long id;
    private String status;
    private BigDecimal total;
    @Join
    private Optional<CustomerView> customer = Optional.empty();
    // getters and setters
}

@QueryModel(root = CustomerEntity.class)
public record CustomerView(@PrimaryKey Long id, String name, String country) {}
```

```java
var query = QOrderView.query()
        .select(QOrderView.ALL.with(QOrderView.CUSTOMER))
        .where(f -> f.eq(QOrderView.STATUS, status)
                .eq(QOrderView.CUSTOMER_COUNTRY, country))
        .orderBy(QOrderView.ID.asc())
        .build();
```

=== "Plain JPA"

    ```java
    return executor.list(query, Limit.of(100));
    ```

=== "Spring repository"

    ```java
    return orders.findAll(query, Limit.of(100));
    ```

A Querydsl query migrates the same way: the `Projections.constructor(...)` arguments become the model's fields, the
`leftJoin` becomes the `@Join`, and each `BooleanBuilder` branch becomes one filter call.

Tested by `MigrationRecipeTest` in `samples/plain-jpa`, which checks that both versions return the same rows for every
filter combination.

## Keep your own repository factory bean

The case in one line: an existing service that already sets its own `repositoryFactoryBeanClass` and
`repositoryBaseClass`, so the starter has to add the fragment without replacing either (R-SPR-02, D-113).

The sample's factory bean is a plain `JpaRepositoryFactoryBean` subclass; it does not extend
`ModelQueryRepositoryFactoryBean`:

```java
public class PlainJpaRepositoryFactoryBean<T extends Repository<S, I>, S, I>
        extends JpaRepositoryFactoryBean<T, S, I> {

    public PlainJpaRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }
}
```

Its base class adds one method and keeps `SimpleJpaRepository`:

```java
public class RefreshingJpaRepository<T, ID> extends SimpleJpaRepository<T, ID>
        implements RefreshingRepository<T, ID> {

    private final EntityManager entityManager;

    public RefreshingJpaRepository(JpaEntityInformation<T, ?> entityInformation, EntityManager entityManager) {
        super(entityInformation, entityManager);
        this.entityManager = entityManager;
    }

    @Override
    public T refreshAndGet(ID id) {
        T entity = findById(id).orElse(null);
        if (entity != null) {
            entityManager.refresh(entity);
        }
        return entity;
    }
}
```

The h2 datasource names both, so the starter keeps them and adds the fragment only where the repository extends
`ModelQueryRepository`:

```java
@EnableJpaRepositories(basePackageClasses = BookRepository.class, entityManagerFactoryRef = "h2EntityManagerFactory",
        transactionManagerRef = "h2TransactionManager",
        repositoryFactoryBeanClass = PlainJpaRepositoryFactoryBean.class,
        repositoryBaseClass = RefreshingJpaRepository.class)
```

One repository migrates, the other does not:

```java
public interface BookRepository extends JpaRepository<BookEntity, Long>, ModelQueryRepository<BookEntity>,
        RefreshingRepository<BookEntity, Long> {}
```

```java
public interface ReviewRepository extends JpaRepository<ReviewEntity, Long>,
        RefreshingRepository<ReviewEntity, Long> {}
```

`BookRepository` gets the model-query fragment; `ReviewRepository` does not, and both carry the base method. Migrate
one at a time: add `ModelQueryRepository<MyEntity>` to one interface and leave the rest for later. A definition that
already sets `customImplementation` fails with `MQ4008`: the starter cannot compose the fragment beside it, so leave
that implementation to provide the fragment methods or extend `ModelQueryRepositoryFactoryBean`; a repository whose
`ModelQueryRepository<E>` names another entity than its own domain type fails with `MQ4007`.

Tested by `SampleApplicationTest`.

If instead you can change the class, extending `ModelQueryRepositoryFactoryBean` is the simplest route; Spring
requires the one-argument constructor. The test's own factory bean and `@EnableJpaRepositories` name it and the custom
base class:

```java
public static class CustomJpaRepositoryFactoryBean<T extends Repository<S, I>, S, I>
        extends ModelQueryRepositoryFactoryBean<T, S, I> {
    public CustomJpaRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }
}
```

```java
@EnableJpaRepositories(basePackageClasses = CustomFactoryBeanTest.class, considerNestedRepositories = true,
        includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = { CustomerProfileRepository.class, PlainCustomerRepository.class }),
        repositoryFactoryBeanClass = CustomJpaRepositoryFactoryBean.class,
        repositoryBaseClass = RefreshingJpaRepository.class)
```

Tested by `CustomFactoryBeanTest` and `PlainFactoryBeanTest`.

## Filter by a sub-query

The case in one line: a filter whose values come from another query on an entity the model does not map, with no
association between the two roots (D-111 item 2).

The sample filters books by a sub-select over the unmapped `reviews` table; `mode=notIn` asks the mirror image, and
the test pins the exact ids, including a `NULL` review book:

```java
SubSelect<ReviewView, Long> reviewed = SubSelect.of(QReviewView.BOOK_ID)
        .where(f -> f.gte(QReviewView.RATING, minRating));
var query = QBookView.query()
        .select(QBookView.ALL)
        .where(f -> "notIn".equals(mode) ? f.notIn(QBookView.ID, reviewed) : f.in(QBookView.ID, reviewed))
        .orderBy(QBookView.ID.asc())
        .build();
```

A `SubSelect` is one column of another root with its own filters. It is immutable, so it can be a constant:

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

`in` and `notIn` take it uncorrelated. A `NULL` column value never empties a `notIn`, and an empty sub-select keeps
its usual meaning — `in` matches nothing, `notIn` every row:

```java
results.add(ids(executor, f -> f.in(ID, P007_ITEMS)));
results.add(ids(executor, f -> f.notIn(REFERRER_ID, PAID_ORDERS)));
results.add(ids(executor, f -> f.notIn(ID, NO_PRODUCT)));
```

`exists` on such an unmapped root states the correlation explicitly, since there is no association to follow:

```java
results.add(ids(executor, f -> f.exists(P007_ITEMS,
        (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))));
```

Tested by `SampleApplicationTest` and `SubSelectTest`.

## Correlate an exists to the outer row

The case in one line: a correlated `exists` whose inner predicate compares an inner column to a column of the outer
root, so the sub-query reads the row being filtered (D-111 item 5).

The sample's `/books/with-review` does exactly that over the unmapped reviews:

```java
SubSelect<ReviewView, Long> reviewed = SubSelect.of(QReviewView.BOOK_ID)
        .where(f -> f.gte(QReviewView.RATING, minRating));
var query = QBookView.query()
        .select(QBookView.ALL)
        .where(f -> f.exists(reviewed,
                (inner, outer) -> inner.compare(QReviewView.BOOK_ID, Op.EQ, outer.column(QBookView.ID))))
        .orderBy(QBookView.ID.asc())
        .build();
```

`outer.column(...)` lifts an outer-root column into the sub-select's vocabulary. It goes anywhere an inner column
goes, so `compare` puts it on one side, and `or` can mix inner and lifted conditions:

```java
private static final ColumnField<O, OrderEntity, BigDecimal> TOTAL =
        ColumnField.of(O.class, ORDERS, "total", BigDecimal.class);
private static final ColumnField<I, PlainOrderItemEntity, Integer> ITEM_QUANTITY =
        ColumnField.of(I.class, PLAIN_ITEMS, "quantity", Integer.class);

results.add(ids(executor, f -> f.exists(P007_ITEMS,
        (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))));
results.add(ids(executor, f -> f.notExists(P007_ITEMS,
        (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID)))));
// An or mixing an inner condition and a lifted one matches through either branch.
results.add(ids(executor, f -> f.exists(P007_ITEMS, (s, outer) -> s
        .compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID))
        .or(a -> a.eq(ITEM_QUANTITY, 9),
                b -> b.lt(outer.column(TOTAL), new BigDecimal("100.00"))))));
```

The lifted column must sit on the outer query's root, so the outer query joins nothing; the sub-query reads the
outer row through a correlation. Tested by `SubSelectTest` and `SampleApplicationTest`.

## Enrich a joined model

The case in one line: fill a field of the model behind a `@Join` from your own lookup, once per page.

The nested plan carries the enricher, and `join(...)` applies it to the joined models, before the outer plan's
enrichers; `calls` is the test's bookkeeping, so drop it in your own code:

```java
FetchPlan<Patron> patron = FetchPlan.of(SelectSet.of(QPatron.ID))
        .child(QPatron.ORDERS, FetchPlan.of(QOrderRef.ALL))
        .enrich(Enricher.byKey(Patron::name, names -> {
            calls.lookups().add(Set.copyOf(names));
            return names.stream().collect(Collectors.toMap(Function.identity(), name -> name + "/"));
        }, (p, tag) -> p.withTag(tag + p.orders().size()), QPatron.NAME))
        .enrich(Enricher.of(page -> {
            calls.nested().add(page.size());
            return page;
        }));
FetchPlan<OrderPatrons> order = FetchPlan.of(SelectSet.of(QOrderPatrons.ID))
        .join(QOrderPatrons.CUSTOMER_JOIN, patron)
        .join(QOrderPatrons.REFERRER_JOIN, patron)
        .enrich(Enricher.of(page -> {
            calls.outer().add(page.size());
            return page.stream().map(o -> o.withNote(o.status() + " "
                    + o.customer().map(Patron::tag).orElseThrow())).toList();
        }, QOrderPatrons.STATUS));
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
    @JoinColumn(name = "product_sku", referencedColumnName = "sku")
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
    @JoinFormula("upper(product_code)")
    FormulaProductEntity product;
}
```

The `@Join` model has an `Optional` nested model per association, with no hint of how the join is written:

```java
@QueryModel(root = SkuOrderLineEntity.class)
public record SkuLineView(@PrimaryKey Long id, Integer quantity, @Join Optional<SkuProductView> product) {}
```

```java
@QueryModel(root = FormulaLineEntity.class)
public record FormulaLineView(@PrimaryKey Long id, Integer quantity, @Join Optional<FormulaProductView> product) {}
```

Selecting a nested column, filtering on it and sorting on it all use the mapped join:

```java
var filtered = QSkuLineView.query()
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

## A shared artist-profile enricher

The case in one line: an artist profile that lives on another datasource, keyed by a `(artistId, catalogId)` record, and
looked up once per chunk by one enricher declaration that is reused across models.

The sample's key is the pair a song carries, with a factory that hands a half-empty pair to the enricher as `null`, so
that row is skipped:

```java
public record ArtistRef(long artistId, int catalogId) {

    /** The ref the pair names, or {@code null} when either half is missing, so the enricher skips the row. */
    static ArtistRef of(Long artistId, Integer catalogId) {
        return artistId == null || catalogId == null ? null : new ArtistRef(artistId, catalogId);
    }
}
```

The lookup is one model query on the profile repository, run once per chunk of `batchSize` distinct keys:

```java
Map<ArtistRef, String> findProfiles(Set<ArtistRef> keys) {
    counter.increment();
    List<Long> artistIds = keys.stream().map(ArtistRef::artistId).distinct().toList();
    List<Integer> catalogIds = keys.stream().map(ArtistRef::catalogId).distinct().toList();
    var query = QProfileView.query()
            .select(QProfileView.ALL)
            .where(f -> f.in(QProfileView.ARTIST_ID, artistIds).in(QProfileView.CATALOG_ID, catalogIds))
            .build();
    var found = new LinkedHashMap<ArtistRef, String>();
    for (ProfileView profile : profiles.findAll(query, Limit.unlimited())) {
        ArtistRef ref = new ArtistRef(profile.artistId(), profile.catalogId());
        if (keys.contains(ref)) {
            found.put(ref, profile.profile());
        }
    }
    return found;
}
```

One generic factory turns that one lookup into an enricher for whichever model it is given (R-FCH-15, R-FCH-16):

```java
@SafeVarargs
public final <M> Enricher<M> profileOf(Function<M, ArtistRef> key, BiFunction<M, String, M> with,
        ColumnField<M, ?, ?>... reading) {
    return Enricher.<M, ArtistRef, String>byKeys(this::findProfiles)
            .key(key, with)
            .batchSize(2)
            .reading(reading);
}
```

The second model differs only in shape, over the same `songs` root as `SongView` but without the release year:

```java
public record SongCreditView(@PrimaryKey Long id, String title, Long artistId, Integer catalogId,
        @Transient String profile) {

    SongCreditView withProfile(String value) {
        return new SongCreditView(id, title, artistId, catalogId, value);
    }
}
```

The sample calls the factory once per model and serves them at `/songs` and `/songs/credits`:

```java
public MusicService(SongRepository songs, ProfileEnrichers profiles) {
    this.songs = songs;
    this.songProfile = profiles.<SongView>profileOf(song -> ArtistRef.of(song.artistId(), song.catalogId()),
            SongView::withProfile, QSongView.ARTIST_ID, QSongView.CATALOG_ID);
    this.creditProfile = profiles.<SongCreditView>profileOf(credit -> ArtistRef.of(credit.artistId(),
            credit.catalogId()), SongCreditView::withProfile,
            QSongCreditView.ARTIST_ID, QSongCreditView.CATALOG_ID);
}
```

`SampleApplicationTest` checks each model's rows are filled, a song with no profile stays `null`, and each endpoint's
lookup runs once per chunk (four distinct keys at `batchSize(2)` are two calls). The rest of this recipe is the TCK's
`ByKeysEnricherTest` cases.

Tested by `SampleApplicationTest`.

### A row with several keys: a delivery's parties

The case in one line: a row carries more than one look-up key — a delivery's sender, recipient, courier and optional
approver, each a `(partyType, partyId)` pair — and each key fills its own field from one lookup.

The key is a record of the columns the plan selects, and `byKeys` takes one `key(...)` per field, all on the same
lookup, whose values share a type:

```java
record PartyKey(int partyType, long partyId) {}

private static Enricher.Keys<DeliveryView, PartyKey, String> partyKeys(SplitDirectory profiles) {
    return Enricher.<DeliveryView, PartyKey, String>byKeys(profiles::find)
            .key(DeliveryView::senderKey, DeliveryView::withSender)
            .key(DeliveryView::recipientKey, DeliveryView::withRecipient)
            .key(DeliveryView::courierKey, DeliveryView::withCourier)
            .key(DeliveryView::approverKey, DeliveryView::withApprover);
}
```

The plan selects the eight declared columns even though the model's own selection does not:

```java
private static ColumnField<DeliveryView, ?, ?>[] keyColumns() {
    return new ColumnField[] {QDeliveryView.SENDER_PARTY_TYPE, QDeliveryView.SENDER_PARTY_ID,
            QDeliveryView.RECIPIENT_PARTY_TYPE, QDeliveryView.RECIPIENT_PARTY_ID,
            QDeliveryView.COURIER_PARTY_TYPE, QDeliveryView.COURIER_PARTY_ID,
            QDeliveryView.APPROVER_PARTY_TYPE, QDeliveryView.APPROVER_PARTY_ID};
}
```

A party in two roles of one row, or in two rows, is looked up once, and the value is passed to every role's setter.
`batchSize(n)` splits the distinct keys into consecutive chunks of at most `n`, one lookup call per chunk. Tested by
`ByKeysEnricherTest`.

### A lookup that splits its own keys

The case in one line: the profiles live in one source per party type, so the lookup partitions each chunk by the type.
The library never partitions (R-FCH-16); because each chunk reaches the lookup once, each source is called at most
once per chunk:

```java
Map<PartyKey, String> find(Set<PartyKey> keys) {
    var grouped = new LinkedHashMap<Integer, Set<Long>>();
    for (PartyKey key : keys) {
        grouped.computeIfAbsent(key.partyType(), type -> new LinkedHashSet<>()).add(key.partyId());
    }
    var found = new LinkedHashMap<PartyKey, String>();
    grouped.forEach((type, ids) -> {
        Map<Long, String> source = byType.getOrDefault(type, Map.of());
        for (long id : ids) {
            String profile = source.get(id);
            if (profile != null) {
                found.put(new PartyKey(type, id), profile);
            }
        }
    });
    return found;
}
```

Set `batchSize(...)` to the smallest source's limit, so no source ever receives more keys than it accepts. Tested by
`ByKeysEnricherTest`.

### A cache shared with a child's enricher

The case in one line: the child's enricher loads a party, and the outer enricher must not load the same party again.
Per-call state belongs in the caller's code, captured when the plan is built (R-FCH-17). The lookup loads only the
keys no one has loaded yet, into a cache both enrichers read:

```java
private static Function<Set<PartyKey>, Map<PartyKey, String>> cached(SplitDirectory profiles,
        Map<PartyKey, String> cache, List<List<PartyKey>> loads) {
    return keys -> {
        List<PartyKey> missing = keys.stream().filter(key -> !cache.containsKey(key)).toList();
        if (!missing.isEmpty()) {
            loads.add(missing);
            cache.putAll(profiles.find(new LinkedHashSet<>(missing)));
        }
        var found = new LinkedHashMap<PartyKey, String>();
        for (PartyKey key : keys) {
            if (cache.containsKey(key)) {
                found.put(key, cache.get(key));
            }
        }
        return found;
    };
}

var cache = new HashMap<PartyKey, String>();
var childLoads = new ArrayList<List<PartyKey>>();
var orderLoads = new ArrayList<List<PartyKey>>();

FetchPlan<Line> items = FetchPlan.of(SelectSet.of(QLine.ID, QLine.QUANTITY))
        .enrich(Enricher.<Line, PartyKey, String>byKeys(cached(profiles, cache, childLoads))
                .key(line -> new PartyKey(requestedPartyType, line.id()), Line::withProfile).reading(QLine.ID));
FetchPlan<OrderLines> order = FetchPlan.of(SelectSet.of(QOrderLines.ID))
        .child(QOrderLines.ITEMS, items)
        .enrich(Enricher.<OrderLines, PartyKey, String>byKeys(cached(profiles, cache, orderLoads))
                .key(o -> new PartyKey(requestedPartyType, o.id()), OrderLines::withProfile)
                .reading(QOrderLines.ID));
```

A child plan runs whole before the outer plan's enrichers, so the outer enricher finds what the child loaded. Tested
by `ByKeysEnricherTest`.

### Request-time parameters

The case in one line: the plan depends on the call — a requested party type, a chosen column set, a cursor — so build
it per call and apply it with `withFetch`, which returns a copy of the query and runs no statement of its own
(R-FCH-13):

```java
int requestedPartyType = 1;
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

        public Key() {}
    }
}
```

The query states its primary key as usual; a String key is `PrimaryKey.of(code)`, an embedded one is
`PrimaryKey.composite(regionCode, seqNo)`:

```java
private static ModelQuery.Builder<StringKeyProductEntity, String, StringRow> stringRows() {
    return ModelQuery.builder(PRODUCTS, row -> new StringRow(row.get(CODE), row.get(NAME), row.get(CATEGORY)))
            .select(SelectSet.of(CODE, NAME, CATEGORY))
            .primaryKey(PrimaryKey.of(CODE));
}
```

```java
private static ModelQuery.Builder<EmbeddedKeyEntity, List<Object>, EmbeddedRow> embeddedRows() {
    return ModelQuery
            .builder(EMBEDDED, row -> new EmbeddedRow(row.get(REGION), row.get(SEQ), row.get(LABEL)))
            .select(SelectSet.of(REGION, SEQ, LABEL))
            .primaryKey(PrimaryKey.composite(REGION, SEQ));
}
```

The keyset page and its neighbour calls; the cursor is opaque, so pass it through rather than rebuilding it. The
test's `forward` walks one page at a time and `walkBack` walks the last page's cursor the other way:

```java
var q = stringRows().orderBy(CATEGORY.asc()).keyset().build();
var list = stringRows().orderBy(CATEGORY.asc()).build();
```

```java
private static <E, M> List<KeysetSlice<M>> forward(ModelQueryExecutor<E> executor, ModelQuery<E, ?, M> q,
        int size) {
    List<KeysetSlice<M>> pages = new ArrayList<>();
    KeysetSpec spec = KeysetSpec.first(size);
    while (true) {
        KeysetSlice<M> page = executor.page(q, spec);
        pages.add(page);
        if (!page.hasNext()) {
            return pages;
        }
        spec = KeysetSpec.after(page.nextCursor().orElseThrow(), size);
    }
}
```

```java
private static <E, M> List<M> walkBack(ModelQueryExecutor<E> executor, ModelQuery<E, ?, M> q, int size,
        List<KeysetSlice<M>> pages) {
    List<M> rows = new ArrayList<>(pages.get(pages.size() - 1).content());
    Optional<String> cursor = pages.get(pages.size() - 1).previousCursor();
    while (cursor.isPresent()) {
        KeysetSlice<M> page = executor.page(q, KeysetSpec.before(cursor.get(), size));
        rows.addAll(0, page.content());
        cursor = page.previousCursor();
    }
    return rows;
}
```

Primary-key-first paging works the same over the key:

```java
var plain = stringRows().orderBy(CATEGORY.asc()).build();
var twoStep = stringRows().orderBy(CATEGORY.asc())
        .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
        .build();
```

```java
var filtered = stringRows().where(f -> f.in(CATEGORY, List.of("cat-00", "cat-02"))).orderBy(CATEGORY.asc());
var keyset = filtered.keyset().build();
var plain = filtered.build();
var twoStep = filtered.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
```

The key's columns close the order, so tied sort values never skip or repeat a row.

Tested by `StringAndEmbeddedKeyTest`.

## An expression in a filter or an aggregate

The case in one line: a condition over a value the database computes, such as `total + 1` or `coalesce(status, 'NONE')`,
and an aggregate over an expression, with no extra column mapped for either (D-115, R-COL-17, R-FLT-18, R-AGG-13).

The sample's report filters films on `coalesce(genre, 'unknown')`, groups by a computed band, and sums the expression
`tickets * 2`; the test pins the exact rows:

```java
ExpressionField<FilmBand, String> genreText = Expr.coalesce(ColumnField.of(FilmBand.class,
        TableField.root(FilmEntity.class), "genre", String.class), "unknown");
var query = QFilmBand.query()
        .select(QFilmBand.GROUP_KEYS.with(QFilmBand.TICKETS))
        .where(f -> f.eq(genreText, genre))
        .orderBy(QFilmBand.BAND.asc())
        .build();
```

The grouping key and the aggregate are computed fields, so the model names their definitions:

```java
@QueryModel(root = FilmEntity.class)
public record FilmBand(
        @GroupBy @Computed(FilmBandKey.class) String band,
        @Aggregate(fn = AggregateFunction.SUM, expression = FilmTickets.class) Long tickets) {}
```

```java
public final class FilmBandKey implements ExpressionDefinition<FilmBand, String> {

    public static final FilmBandKey INSTANCE = new FilmBandKey();

    private FilmBandKey() {
    }

    @Override
    public ExpressionField<FilmBand, String> expression() {
        ColumnField<FilmBand, FilmEntity, Integer> released =
                ColumnField.of(FilmBand.class, TableField.root(FilmEntity.class), "released", Integer.class);
        return Expr.cases(FilmBand.class, String.class)
                .when(f -> f.lt(released, 1990), "classic")
                .otherwise("modern");
    }
}
```

```java
public final class FilmTickets implements ExpressionDefinition<FilmBand, Long> {

    public static final FilmTickets INSTANCE = new FilmTickets();

    private FilmTickets() {
    }

    @Override
    public ExpressionField<FilmBand, Long> expression() {
        ColumnField<FilmBand, FilmEntity, Long> tickets =
                ColumnField.of(FilmBand.class, TableField.root(FilmEntity.class), "tickets", Long.class);
        return Expr.times(tickets, 2L);
    }
}
```

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

The expression's values bind as parameters. An expression also stands wherever a column does: as a selected column, a
group key or an order key. A selected expression is read back by `Row.get`, exactly like a column, and two equal
expressions under different `named` are one selection, each name reading the value:

```java
/** {@code quantity + 1}, named so a second, equal expression is one selection under a different name. */
private static final ExpressionField<ExprView, Integer> EXPR_PLUS_ONE = Expr.plus(EXPR_QUANTITY, 1);
private static final ExpressionField<ExprView, Integer> EXPR_PLUS_ONE_NAMED = EXPR_PLUS_ONE.named("key");

var records = ModelQuery.builder(ITEMS, row -> new ExprView(row.get(EXPR_ID), row.get(EXPR_PLUS_ONE)))
        .select(SelectSet.of(EXPR_ID, EXPR_PLUS_ONE))
        .build();
```

As a group key it groups over the value the database computes, and the aggregate reads each group:

```java
/** {@code quantity + 1}: a group key that binds a value, so PostgreSQL must match it to the select item. */
private static final ExpressionField<Group, Integer> KEY = Expr.plus(GROUP_QUANTITY, 1);
private static final AggregateField<Group, Long> GROUP_COUNT = Agg.count(GROUP_ID);

var query = GROUPS.select(SelectSet.of(KEY, GROUP_COUNT)).groupBy(KEY).orderBy(KEY.asc()).build();
```

A key the database binds is rendered once and reused across `select`, `group by` and `order by` (R-COL-19). `groupBy`
takes an expression directly or in a `SelectSet`; a selected or ordered scalar the group-by does not cover is refused
with `MQ1401` (selection) or `MQ1406` (order) — an expression fits when all of its columns are group-key columns, and
a sub-expression is never matched (R-AGG-14).

Tested by `SampleApplicationTest`, `ExpressionFilterTest`, `ExpressionAggregateTest`, `ExpressionTest`,
`ExpressionNullOrderingTest` and `ModelQueryTest`.

## Order by an expression

The case in one line: order a report by a value the database computes, such as `tickets + 1`, with no column mapped
for it (D-115, R-PAG-25). `orderBy` takes an expression, and `orderedBy(SortSpec)` reaches it by its `named(...)`
name.

The sample pages films in two offset pages ordered by `tickets + 1`, and the keyset request is refused with `MQ1208`:

```java
var query = QFilmView.query()
        .select(QFilmView.ALL)
        .orderBy(ticketsPlusOne().asc())
        .build();
ModelPage<FilmView> result = films.findPage(query, PageRequest.of(page, size), CountMode.COUNT);
```

```java
private static ExpressionField<FilmView, Long> ticketsPlusOne() {
    return Expr.plus(QFilmView.TICKETS, 1L);
}
```

```java
QFilmView.query()
        .select(QFilmView.ALL)
        .orderBy(ticketsPlusOne().asc())
        .keyset()
        .build();
```

Offset paging and offset export accept an expression key: the engine selects it (D-29) and appends the primary-key
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

Keep the function deterministic: a key the database computes differently on two runs cannot page stably. An expression
that reads a column through a to-many join is refused for offset export, keyset paging and `primaryKeyFirst` with
`MQ2204` before any query runs, and a `count` over such a query counts the rows rather than the keys (R-PAG-25,
R-PAG-13).

Tested by `SampleApplicationTest`, `PrimaryKeyFirstTest`, `OffsetExportTest` and `ModelQueryTest`.

## Client-chosen fields

The case in one line: a search endpoint whose clients pick the fields they get back (`?fields=id,status,referrer.name`),
without a hand-kept map from strings to constants (D-121, RFC 0006). The generated `Q<Model>.fields()` returns a
`FieldIndex<Model>` keyed by the names a sort already uses. The index resolves names and nothing else; the whitelist,
the response shape and the parsing of values stay in your application. The API is `@Incubating`.

Narrow the index once with `only`, so the endpoint exposes part of the model and keeps the rest private. A name that
isn't in the index, or a column set left empty, throws `MQ1105` at startup, because it is your own definition
(R-COL-24):

```java
private static final FieldIndex<SelOrder> API_FIELDS =
        QSelOrder.fields().only(List.of("id", "status", "referrer", "referrer.name", "referrerChild"));
```

`resolve` turns the client's names into a `SelectSet`, the children they named and the names it didn't know. It never
throws for a name, so the endpoint decides what an unknown one means, usually a `400`. With no select field named, the
`select` is empty and the query falls back to the model's default set (R-COL-23):

```java
var resolved = API_FIELDS.resolve(requested);
if (!resolved.unknown().isEmpty()) {
    throw new BadRequest("Unknown fields: " + resolved.unknown() + ", expected one of " + API_FIELDS.names());
}
SelectSet<SelOrder> select = resolved.select().isEmpty() ? QSelOrder.DEFAULT : resolved.select();
var query = QSelOrder.query().select(select).orderBy(QSelOrder.ID.asc()).build();
```

The joins follow the selection: asking for `referrer.name` adds the `customers` join, and asking for `id` and `status`
adds none. A model with a `@Selected` set (see [Selected fields](models.md#selected-fields)) lets the response writer
leave out what the client didn't ask for, instead of writing it as `null`:

```java
static Map<String, Object> write(SelOrder order) {
    var out = new LinkedHashMap<String, Object>();
    if (order.selected().contains(QSelOrder.ID)) {
        out.put("id", order.id());
    }
    if (order.selected().contains(QSelOrder.STATUS)) {
        out.put("status", order.status());
    }
    return out;
}
```

A resolved child is matched by identity, because `FetchPlan.child` needs the child's typed model, which a
`ChildField<M, ?>` doesn't carry:

```java
FetchPlan<SelOrder> plan = FetchPlan.of(select);
if (resolved.children().contains(QSelOrder.REFERRER_CHILD)) {
    plan = plan.child(QSelOrder.REFERRER_CHILD, FetchPlan.of(QSelCustomer.DEFAULT));
}
var withChildren = query.withFetch(plan);
```

Filter values need the same care. `fields()` also holds the model's filter-only columns (`filter(name)`), and a filter
takes a typed column, so an application that converts a client's value writes a two-line capture helper. The library
adds no untyped `eq(field, Object)`, which would let a wrongly typed value compile:

```java
static <M, C> Filters<M> eqValue(Filters<M> f, ScalarField<M, C> column, Object value) {
    return f.eq(column, column.type().cast(value));
}
```

Aliases such as `customer_name` for `referrer.name` are an application choice. Keep the map from alias to index name in
the application and pass the index name to `resolve`: the index keys stay the ones `orderedBy(SortSpec)` accepts, so
`sort=referrer.name` and `fields=referrer.name` mean one field.

Tested by `ClientChosenFieldsTest` and `GeneratedFieldIndexTest`.

## Query a database view

The case in one line: a report over a database view, or a Hibernate `@Subselect`, read through the same model and
query API as a table (AC-COL-29).

A view is a read-only root, so map it with Hibernate's `@Immutable` over the view's name. It needs an `@Id`, and
every other column is a plain attribute:

```java
@Entity
@Immutable
@Table(name = "order_view")
public class ViewOrderEntity {
    @Id
    Long id;

    @Column(name = "customer_id")
    Long customerId;

    String status;

    BigDecimal total;
}
```

The model is an ordinary one over that entity:

```java
@QueryModel(root = ViewOrderEntity.class)
public record ViewOrderRow(@PrimaryKey Long id, Long customerId, String status, BigDecimal total) {}
```

From there it is a root like any table: filter on a view column, order by one, and read the rows:

```java
var query = QViewOrderRow.query()
        .select(SelectSet.of(QViewOrderRow.ID, QViewOrderRow.CUSTOMER_ID, QViewOrderRow.STATUS,
                QViewOrderRow.TOTAL))
        .where(f -> f.lte(QViewOrderRow.ID, 200L).eq(QViewOrderRow.STATUS, "PAID"))
        .orderBy(QViewOrderRow.ID.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<ViewOrderRow> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<ViewOrderRow> rows = orders.findAll(query, Limit.unlimited());
    ```

Hibernate's `@Subselect` defines the view from a select, for a database with no view to name. Add `@Synchronize`
so Hibernate flushes the table the sub-select reads before running it:

```java
@Entity
@Immutable
@Subselect("select id, name, country from customers")
@Synchronize("customers")
public class SubselectCustomerEntity {
    @Id
    Long id;

    String name;

    String country;
}
```

Both forms are read-only: there is no insert or update through them, and both group like any root, so a view can
back a report model the way a table does.

Tested by `ViewRootTest` in the TCK.

## Call a function with literal arguments

The case in one line: a selected or grouped value from a database function whose arguments include a value fixed in
code, such as `date_trunc('day', placed_at)` (AC-COL-30, D-115, R-COL-18).

```java
/** One truncated-timestamp group and its order count. */
record DayGroup(Object day, Long orders) {}

private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
private static final ColumnField<DayGroup, OrderEntity, LocalDateTime> DAY_PLACED_AT =
        ColumnField.of(DayGroup.class, ORDERS, "placedAt", LocalDateTime.class);
private static final AggregateField<DayGroup, Long> DAY_ORDERS = Agg.count(ORDERS);
```

`Expr.function(name, resultType, args...)` builds a call to any SQL function, and an argument may be an
`Expr.constant`, which renders into the statement as SQL text. On PostgreSQL and H2 a day key truncates with
`date_trunc`:

```java
ExpressionField<DayGroup, ?> day =
        Expr.function("date_trunc", LocalDateTime.class, Expr.constant("day"), DAY_PLACED_AT);
```

MySQL has no `date_trunc`; the same key uses `date_format` with a format literal:

```java
ExpressionField<DayGroup, ?> day =
        Expr.function("date_format", String.class, DAY_PLACED_AT, Expr.constant("%Y-%m-%d"));
```

The expression is a group key like any other, and the aggregate reads each group:

```java
var query = ModelQuery.builder(ORDERS, row -> new DayGroup(row.get(day), row.get(DAY_ORDERS)))
        .select(SelectSet.of(day, DAY_ORDERS))
        .groupBy(day)
        .orderBy(day.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<DayGroup> groups = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<DayGroup> groups = orders.findAll(query, Limit.unlimited());
    ```

PostgreSQL's `timezone(text, timestamp)` reads a timestamp in a zone. Nesting it with a fixed zone makes the result
independent of the JVM zone and the session zone:

```java
var zoned = Expr.function("timezone", LocalDateTime.class, Expr.constant("UTC"),
        Expr.function("timezone", LocalDateTime.class, Expr.constant("Asia/Ho_Chi_Minh"), DAY_PLACED_AT));
```

`Expr.constant` renders SQL text, so it is only safe for a value defined in code. Never pass request input through
it: the value would go into the statement as SQL (R-COL-18). A bound, per-request argument in these positions is
not supported yet, so the argument must be an `Expr.constant`.

Tested by `FunctionArgumentTest` in the TCK.

## Increment a counter

The case in one line: bump a column by a fixed step on exactly the rows a filter picks, without reading them first
(AC-WRT-41).

`setExpression(column, (path, cb) -> cb.sum(path, 1))` writes `column = column + 1` in the update's own statement.
The update's columns are hand-written constants over the item entity, as in
[An expression in a filter or an aggregate](#an-expression-in-a-filter-or-an-aggregate):

```java
/** The patch the counter update writes through. */
record ItemRowPatch(Long id) {}

private static final TableField<OrderItemEntity, OrderItemEntity> ITEM_ROWS =
        TableField.root(OrderItemEntity.class);
private static final ColumnField<ItemRowPatch, OrderItemEntity, Long> ITEM_ROW_ID =
        ColumnField.of(ItemRowPatch.class, ITEM_ROWS, "id", Long.class);
private static final ColumnField<ItemRowPatch, OrderItemEntity, Integer> ITEM_ROW_QUANTITY =
        ColumnField.of(ItemRowPatch.class, ITEM_ROWS, "quantity", Integer.class);

var update = ModelUpdate.builder(ITEM_ROWS)
        .primaryKey(PrimaryKey.of(ITEM_ROW_ID))
        .setExpression(ITEM_ROW_QUANTITY, (quantity, cb) -> cb.sum(quantity, 1))
        .where(f -> f.lte(ITEM_ROW_ID, 10L))
        .build();
```

=== "Plain JPA"

    ```java
    long written = executor.update(update);
    ```

=== "Spring repository"

    ```java
    long written = items.update(update);
    ```

The new value is computed by the database, so two concurrent increments both land. See [bulk writes](bulk-writes.md) for the rules
`setExpression` follows, including the vendor difference when one expression reads a column another writes.

Tested by `BulkWriteTest` in the TCK.

## Number rows from a sequence

The case in one line: read a database sequence's next value next to each row, without a function call that names the
dialect (`Expr.nextval`, R-COL-28, `@Incubating`; see [Sequence values](queries.md#sequence-values)).

```java
private static final ExpressionField<Numbered, Long> NEXT = Expr.nextval("tck_seq");

var query = ModelQuery.builder(ROWS, row -> new Numbered(row.get(ID), row.get(NEXT)))
        .select(SelectSet.of(ID, NEXT))
        .orderBy(ID.asc())
        .build();
```

=== "Plain JPA"

    ```java
    List<Numbered> rows = executor.list(query, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<Numbered> rows = rowsRepository.findAll(query, Limit.unlimited());
    ```

A value is consumed on every evaluation, so a re-run of the query and `one(q)`'s second row each use one. To write
one instead, use `setExpression(column, Expr.nextval(...))` on the update or insert (see
[Inserts](inserts.md#sequence-values)). MySQL has no sequences, so this is `MQ1509` there. Tested by
`SequenceNextvalTest` in the TCK (AC-COL-33).

## Claim the next job

A pool of workers pulls work from a table: each takes the next pending rows, marks them claimed and commits, and a
second worker must not take the same rows. Claim in two steps in one transaction, so the rows stay locked from the
read until the commit (`RowLock`, D-127, and returning writes, R-WRT-50, both `@Incubating`; see
[Row locks](queries.md#row-locks) and [Returning the changed rows](bulk-writes.md#returning-the-changed-rows)):

1. read the next pending rows' keys with `RowLock.exclusive(SKIP_LOCKED)`, an order and a limit, so a row another
   worker holds is passed over rather than waited for;
2. claim exactly those rows with `whereKeys(ids)`, and read the claim back: `updateReturning` does it in the same
   statement on PostgreSQL and H2; MySQL returns no rows of a write (`MQ4011`), so write with `update` and read the
   rows back with `byKeys`.

```java
private static final ModelQuery<OrderEntity, Long, OrderLines> NEXT_PENDING = QOrderLines.query()
        .select(QOrderLines.ALL)
        .where(f -> f.eq(QOrderLines.STATUS, "PENDING"))
        .orderBy(QOrderLines.ID.asc())
        .build()
        .withLock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED));
```

=== "Plain JPA"

    ```java
    // In one transaction: the read locks each row it returns, and the commit releases it.
    List<Long> ids = executor.list(NEXT_PENDING, Limit.of(2)).stream().map(OrderLines::id).toList();
    var claim = ModelUpdate.builder(QOrderLines.ROOT).primaryKey(QOrderLines.KEY)
            .set(QOrderLines.STATUS, "CLAIMED").whereKeys(ids).build();
    var claimedRows = QOrderLines.query().select(QOrderLines.ALL).build();
    // PostgreSQL and H2: one statement writes the claim and returns the rows.
    List<OrderLines> claimed = executor.updateReturning(claim, claimedRows);
    // MySQL: write, then read the rows back by key.
    executor.update(claim);
    Map<Long, OrderLines> byId = executor.byKeys(claimedRows, ids);
    ```

=== "Spring repository"

    ```java
    // In one transaction (a TransactionTemplate or @Transactional), as with plain JPA.
    List<Long> ids = orders.findAll(NEXT_PENDING, Limit.of(2)).stream().map(OrderLines::id).toList();
    var claim = ModelUpdate.builder(QOrderLines.ROOT).primaryKey(QOrderLines.KEY)
            .set(QOrderLines.STATUS, "CLAIMED").whereKeys(ids).build();
    var claimedRows = QOrderLines.query().select(QOrderLines.ALL).build();
    // PostgreSQL and H2: one statement writes the claim and returns the rows.
    List<OrderLines> claimed = orders.updateReturning(claim, claimedRows);
    // MySQL: write, then read the rows back by key.
    orders.update(claim);
    Map<Long, OrderLines> byId = orders.findAllByKeys(claimedRows, ids);
    ```

Two workers running this at once claim disjoint rows: the second one's read passes the first one's locked rows over.
There is no one-statement form that writes over a locked sub-select (D-131): a Criteria sub-query carries no lock,
and on PostgreSQL `IN (SELECT … LIMIT n FOR UPDATE SKIP LOCKED)` can claim more rows than the limit.

Caveats:

- `SKIP_LOCKED` can return the pending rows out of the query's order, because the database skips whatever another
  worker holds. So `first(q)` gives *a* next row, not *the* next; give the query an explicit order and claim the whole
  page in one transaction, as above.
- `one(q)` refuses `SKIP_LOCKED` with `MQ2211`: it reads two rows to check uniqueness, and a locked second row would
  be invisible, so the check would pass when it should fail.
- On PostgreSQL a lock error (`LockTimeoutException`, `PessimisticLockException`) aborts the whole transaction, so the
  claim is lost; retry the transaction, not the statement.
- A read without an active transaction is `MQ2102`. On Spring do not wrap the claim in
  `@Transactional(readOnly = true)`: PostgreSQL and MySQL reject the locking clause.
- On PostgreSQL a `WAIT` claim can return fewer rows than the limit, or no row while a match exists, because a locked
  row that no longer matches is dropped after `LIMIT`; `SKIP_LOCKED`, as here, avoids it.
- H2 renders `SKIP_LOCKED` only on Hibernate 7; on Hibernate 6.6 the locked read is `MQ4010` before any statement.
- To filter by a related table's rows, use [`Filters.exists(...)`](queries.md#exists), not a join: a locked query whose
  statement joins is `MQ2211`.

Tested by `ac_wrt_47_two_concurrent_workers_claim_disjoint_jobs_and_read_their_claims_back` and
`ac_exe_17_claim_the_next_job_skips_locked_rows_and_updates_its_claim` in `RowLockExecutionTest`, and by
`ac_wrt_47_the_claim_recipe_runs_through_the_repository_in_one_transaction` in `ModelQueryRepositoryTest` (AC-WRT-47).

## Filter by a fixed set of enum states

The case in one line: a filter that matches a small, fixed set of enum states, inlined as literals so the planner can
use the column's index (`Expr.constant`, D-128, R-FLT-19, R-COL-27, `@Incubating`; see
[Literal filters](queries.md#literal-filters)).

Wrap each state in an `Expr.constant` typed by the column, and pass them to the fixed-arity `Filters#in` field form.
An `@Enumerated(STRING)` column inlines the member's name; an `ORDINAL` one its ordinal:

```java
/** The rows of constant_rows this recipe reads, and its status column. */
record Row(Long id, ConstantStatus status) {}

private static final TableField<ConstantRowEntity, ConstantRowEntity> ROWS =
        TableField.root(ConstantRowEntity.class);
private static final ColumnField<Row, ConstantRowEntity, Long> ID =
        ColumnField.of(Row.class, ROWS, "id", Long.class);
private static final ColumnField<Row, ConstantRowEntity, ConstantStatus> STATUS =
        ColumnField.of(Row.class, ROWS, "status", ConstantStatus.class);

/** The two states to keep, as column-typed constants, inlined as their names (R-COL-27). */
private static final ExpressionField<Row, ConstantStatus> PENDING = Expr.constant(STATUS, ConstantStatus.PENDING);
private static final ExpressionField<Row, ConstantStatus> FAILED = Expr.constant(STATUS, ConstantStatus.FAILED);

private static final ModelQuery<ConstantRowEntity, Object, Row> OPEN_ROWS =
        ModelQuery.builder(ROWS, row -> new Row(row.get(ID), row.get(STATUS)))
                .select(SelectSet.of(ID, STATUS))
                .where(f -> f.in(STATUS, PENDING, FAILED))
                .orderBy(ID.asc())
                .build();
```

The statement inlines `status in ('PENDING','FAILED')`. To keep the rows whose status is *not* one of the states,
`f.notIn(STATUS, PENDING, FAILED)` renders `status not in ('PENDING','FAILED') or status is null`, so a row whose
status is NULL still matches.

=== "Plain JPA"

    ```java
    List<Row> rows = executor.list(OPEN_ROWS, Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<Row> rows = constantRows.findAll(OPEN_ROWS, Limit.unlimited());
    ```

Because the literals are part of the statement text, this form suits a fixed set — enum states, a status a partial
index covers. For a set the caller chooses per request, use the value form `in(STATUS, List.of(a, b))`, which binds
each value. Never build an `Expr.constant` from request input (R-COL-18).

Tested by `ac_flt_20_in_and_not_in_over_operand_fields_inline_the_literals_and_keep_null_rows` in
`TypedConstantTest`.

## Group by a time zone the caller picks

The case in one line: group rows by a timestamp shifted into a zone the caller chooses per request, with the zone
bound as a parameter so one statement serves every zone (`Expr.value`, D-128, R-COL-26, `@Incubating`; see
[Bound function arguments](queries.md#bound-function-arguments)). PostgreSQL only.

`timezone(text, timestamp)` returns a timestamp without a zone, so nesting it under `timezone('UTC', …)` makes the
group key independent of the JVM and session zone. The zone itself is an `Expr.value`, so it renders as `?` and one
statement covers every zone:

```java
/** One customer id and its created_at read in the chosen zone, for the group key. */
record Hour(Long id, LocalDateTime at) {}

private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS =
        TableField.root(CustomerEntity.class);
private static final ColumnField<Hour, CustomerEntity, Long> ID =
        ColumnField.of(Hour.class, CUSTOMERS, "id", Long.class);
private static final ColumnField<Hour, CustomerEntity, LocalDateTime> CREATED_AT =
        ColumnField.of(Hour.class, CUSTOMERS, "createdAt", LocalDateTime.class);

/** The grouping query for one zone, built per request. */
static ModelQuery<CustomerEntity, Object, Hour> byZone(String zone) {
    ExpressionField<Hour, LocalDateTime> at = Expr.function("timezone", LocalDateTime.class, Expr.constant("UTC"),
            Expr.function("timezone", LocalDateTime.class, Expr.value(zone), CREATED_AT));
    return ModelQuery.builder(CUSTOMERS, row -> new Hour(row.get(ID), row.get(at)))
            .select(SelectSet.of(ID, at))
            .where(f -> f.lte(ID, 20L))
            .groupBy(ID, at)
            .orderBy(ID.asc())
            .build();
}
```

=== "Plain JPA"

    ```java
    List<Hour> hours = executor.list(byZone(zone), Limit.unlimited());
    ```

=== "Spring repository"

    ```java
    List<Hour> hours = customers.findAll(byZone(zone), Limit.unlimited());
    ```

The value binds on the statement, so each distinct zone does not create a new statement text (the plan-cache note on
[Literal filters](queries.md#literal-filters)). A function that reads a value changing between statements — `now()`,
say — is evaluated by each statement that renders it, so the pages of a paged read may disagree; read such a value
from a column when it must be stable (INV-4).

Tested by `ac_col_31_timezone_with_a_bound_zone_groups_a_timestamp_on_postgresql` in `BoundValueFunctionTest`.
