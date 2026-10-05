# Migration recipes

Recipes for moving an existing reporting service onto model-query, one adoption case at a time. Each recipe's code is
copied from a test that runs, named under the code.

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

## A shared user-profile enricher

The case in one line: a user profile that lives on another datasource, keyed by a `(userId, userTypeId)` record, and
looked up once per chunk by one enricher declaration that is reused across models.

The sample's key is the pair a song carries, with a factory that hands a half-empty pair to the enricher as `null`, so
that row is skipped:

```java
public record UserRef(long userId, int userTypeId) {

    /** The ref the pair names, or {@code null} when either half is missing, so the enricher skips the row. */
    static UserRef of(Long userId, Integer userTypeId) {
        return userId == null || userTypeId == null ? null : new UserRef(userId, userTypeId);
    }
}
```

The lookup is one model query on the profile repository, run once per chunk of `batchSize` distinct keys:

```java
Map<UserRef, String> findProfiles(Set<UserRef> keys) {
    counter.increment();
    List<Long> userIds = keys.stream().map(UserRef::userId).distinct().toList();
    List<Integer> userTypeIds = keys.stream().map(UserRef::userTypeId).distinct().toList();
    var query = QProfileView.query()
            .select(QProfileView.ALL)
            .where(f -> f.in(QProfileView.USER_ID, userIds).in(QProfileView.USER_TYPE_ID, userTypeIds))
            .build();
    var found = new LinkedHashMap<UserRef, String>();
    for (ProfileView profile : profiles.findAll(query, Limit.unlimited())) {
        UserRef ref = new UserRef(profile.userId(), profile.userTypeId());
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
public final <M> Enricher<M> profileOf(Function<M, UserRef> key, BiFunction<M, String, M> with,
        ColumnField<M, ?, ?>... reading) {
    return Enricher.<M, UserRef, String>byKeys(this::findProfiles)
            .key(key, with)
            .batchSize(2)
            .reading(reading);
}
```

The second model differs only in shape, over the same `songs` root as `SongView` but without the release year:

```java
public record SongCreditView(@PrimaryKey Long id, String title, Long userId, Integer userTypeId,
        @Transient String profile) {

    SongCreditView withProfile(String value) {
        return new SongCreditView(id, title, userId, userTypeId, value);
    }
}
```

The sample calls the factory once per model and serves them at `/songs` and `/songs/credits`:

```java
public MusicService(SongRepository songs, ProfileEnrichers profiles) {
    this.songs = songs;
    this.songProfile = profiles.<SongView>profileOf(song -> UserRef.of(song.userId(), song.userTypeId()),
            SongView::withProfile, QSongView.USER_ID, QSongView.USER_TYPE_ID);
    this.creditProfile = profiles.<SongCreditView>profileOf(credit -> UserRef.of(credit.userId(),
            credit.userTypeId()), SongCreditView::withProfile,
            QSongCreditView.USER_ID, QSongCreditView.USER_TYPE_ID);
}
```

`SampleApplicationTest` checks each model's rows are filled, a song with no profile stays `null`, and each endpoint's
lookup runs once per chunk (four distinct keys at `batchSize(2)` are two calls). The rest of this recipe is the TCK's
`ByKeysEnricherTest` cases.

Tested by `SampleApplicationTest`.

### A row with several keys: a payment order's actors

The case in one line: a row carries more than one look-up key — a payment order's payer, payee, initiator and optional
requestor, each a `(userType, userId)` pair — and each key fills its own field from one lookup.

The key is a record of the columns the plan selects, and `byKeys` takes one `key(...)` per field, all on the same
lookup, whose values share a type:

```java
record ActorKey(int userType, long userId) {}

private static Enricher.Keys<PaymentOrderView, ActorKey, String> actorKeys(SplitProfiles profiles) {
    return Enricher.<PaymentOrderView, ActorKey, String>byKeys(profiles::find)
            .key(PaymentOrderView::payerKey, PaymentOrderView::withPayer)
            .key(PaymentOrderView::payeeKey, PaymentOrderView::withPayee)
            .key(PaymentOrderView::initiatorKey, PaymentOrderView::withInitiator)
            .key(PaymentOrderView::requestorKey, PaymentOrderView::withRequestor);
}
```

The plan selects the eight declared columns even though the model's own selection does not:

```java
private static ColumnField<PaymentOrderView, ?, ?>[] keyColumns() {
    return new ColumnField[] {QPaymentOrderView.PAYER_USER_TYPE, QPaymentOrderView.PAYER_USER_ID,
            QPaymentOrderView.PAYEE_USER_TYPE, QPaymentOrderView.PAYEE_USER_ID,
            QPaymentOrderView.INITIATOR_USER_TYPE, QPaymentOrderView.INITIATOR_USER_ID,
            QPaymentOrderView.REQUESTOR_USER_TYPE, QPaymentOrderView.REQUESTOR_USER_ID};
}
```

A user in two roles of one row, or in two rows, is looked up once, and the value is passed to every role's setter.
`batchSize(n)` splits the distinct keys into consecutive chunks of at most `n`, one lookup call per chunk. Tested by
`ByKeysEnricherTest`.

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
private static Function<Set<ActorKey>, Map<ActorKey, String>> cached(SplitProfiles profiles,
        Map<ActorKey, String> cache, List<List<ActorKey>> loads) {
    return keys -> {
        List<ActorKey> missing = keys.stream().filter(key -> !cache.containsKey(key)).toList();
        if (!missing.isEmpty()) {
            loads.add(missing);
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

var cache = new HashMap<ActorKey, String>();
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
int requestedUserType = 1;
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
