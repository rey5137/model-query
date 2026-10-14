package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.MysqlStreamingMode;
import com.rey.modelquery.jpa.spi.WriteStatement;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * A to-one read by its id: a hand-written column of the target's id type over the association reads the foreign key
 * through a {@code LEFT} join in every clause of a read, so no row with a {@code NULL} foreign key is dropped, and as
 * the foreign-key column in a write, refused with {@code MQ1004} or {@code MQ1809} where that column is not the
 * target's primary key (spec api/10 R-COL-29, api/14 R-WRT-48, D-136). Every write is rolled back.
 */
class ToOneIdTest {

    /** An order and its referrer's id, {@code null} on the two thirds of the orders without one. */
    record Referred(Long id, Long referrerId) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<Referred, OrderEntity, Long> ID =
            ColumnField.of(Referred.class, ORDERS, "id", Long.class);
    private static final ColumnField<Referred, OrderEntity, Long> REFERRER =
            ColumnField.of(Referred.class, ORDERS, "referrer", Long.class);
    private static final ColumnField<Referred, OrderEntity, String> STATUS =
            ColumnField.of(Referred.class, ORDERS, "status", String.class);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS =
            TableField.join(ORDERS, "items", JoinType.INNER);
    private static final ColumnField<Referred, OrderItemEntity, String> ITEM_CODE =
            ColumnField.of(Referred.class, ORDER_ITEMS, "productCode", String.class);
    private static final ModelQuery.Builder<OrderEntity, Long, Referred> REFERRED =
            ModelQuery.builder(ORDERS, row -> new Referred(row.get(ID), row.get(REFERRER)))
                    .select(SelectSet.of(ID, REFERRER))
                    .primaryKey(PrimaryKey.of(ID));

    /** The orders' rows by referrer, {@code NULL} first, then by id. */
    private static final Comparator<Referred> NULLS_FIRST = Comparator.comparing(Referred::referrerId,
            Comparator.nullsFirst(Comparator.<Long>naturalOrder())).thenComparing(Referred::id);

    /** An order item and its order's referrer's id, read through the {@code @Join}ed order. */
    record ItemReferrer(Long id, Long referrerId) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final ColumnField<ItemReferrer, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(ItemReferrer.class, ITEMS, "id", Long.class);
    private static final TableField<OrderItemEntity, OrderEntity> ITEM_ORDER =
            TableField.join(ITEMS, "order", JoinType.INNER);
    private static final ColumnField<ItemReferrer, OrderEntity, Long> ITEM_REFERRER =
            ColumnField.of(ItemReferrer.class, ITEM_ORDER, "referrer", Long.class);

    /** A customer and the orders it referred, a child keyed by the order's referrer read by its id. */
    record Patron(Long id, List<Referred> referred) {}

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS =
            TableField.root(CustomerEntity.class);
    private static final ColumnField<Patron, CustomerEntity, Long> PATRON_ID =
            ColumnField.of(Patron.class, CUSTOMERS, "id", Long.class);
    private static final ChildField<Patron, Referred> REFERRED_ORDERS = new ChildField<>() {
        @Override
        public String name() {
            return "referred";
        }

        @Override
        public ColumnField<Patron, ?, ?> key() {
            return PATRON_ID;
        }

        @Override
        public Optional<ColumnField<Referred, ?, ?>> foreignKey() {
            return Optional.of(REFERRER);
        }

        @Override
        public Optional<TableField<?, ?>> through() {
            return Optional.empty();
        }

        @Override
        public boolean isToMany() {
            return true;
        }

        @Override
        public ModelQuery.Builder<?, ?, Referred> query() {
            return REFERRED;
        }

        @Override
        public Patron with(Patron parent, List<Referred> children) {
            return new Patron(parent.id(), List.copyOf(children));
        }
    };

    /** A card holder and its card's id, through the inverse {@code @OneToOne}. */
    record Holder(Long id, Long cardId) {}

    private static final TableField<CardHolderEntity, CardHolderEntity> HOLDERS =
            TableField.root(CardHolderEntity.class);
    private static final ColumnField<Holder, CardHolderEntity, Long> HOLDER_ID =
            ColumnField.of(Holder.class, HOLDERS, "id", Long.class);
    private static final ColumnField<Holder, CardHolderEntity, Long> HOLDER_CARD =
            ColumnField.of(Holder.class, HOLDERS, "card", Long.class);

    /** A card, its holder's id through the owning {@code @OneToOne}, and its {@code @NotFound} issuer's id. */
    record Card(Long id, Long holderId, Long issuerId) {}

    private static final TableField<CardEntity, CardEntity> CARDS = TableField.root(CardEntity.class);
    private static final ColumnField<Card, CardEntity, Long> CARD_ID =
            ColumnField.of(Card.class, CARDS, "id", Long.class);
    private static final ColumnField<Card, CardEntity, Long> CARD_HOLDER =
            ColumnField.of(Card.class, CARDS, "holder", Long.class);
    private static final ColumnField<Card, CardEntity, Long> CARD_ISSUER =
            ColumnField.of(Card.class, CARDS, "issuer", Long.class);

    /** A line and its product's id, a to-one whose foreign key references the product's non-key {@code sku}. */
    record SkuLine(Long id, Long productId) {}

    private static final TableField<SkuOrderLineEntity, SkuOrderLineEntity> SKU_LINES =
            TableField.root(SkuOrderLineEntity.class);
    private static final ColumnField<SkuLine, SkuOrderLineEntity, Long> SKU_LINE_ID =
            ColumnField.of(SkuLine.class, SKU_LINES, "id", Long.class);
    private static final ColumnField<SkuLine, SkuOrderLineEntity, Long> SKU_LINE_PRODUCT =
            ColumnField.of(SkuLine.class, SKU_LINES, "product", Long.class);
    private static final ColumnField<SkuLine, SkuOrderLineEntity, Integer> SKU_LINE_QUANTITY =
            ColumnField.of(SkuLine.class, SKU_LINES, "quantity", Integer.class);

    /** A line and its product's code, a to-one joined through a {@code @JoinFormula}. */
    record FormulaLine(Long id, String productCode) {}

    private static final TableField<FormulaLineEntity, FormulaLineEntity> FORMULA_LINES =
            TableField.root(FormulaLineEntity.class);
    private static final ColumnField<FormulaLine, FormulaLineEntity, Long> FORMULA_LINE_ID =
            ColumnField.of(FormulaLine.class, FORMULA_LINES, "id", Long.class);
    private static final ColumnField<FormulaLine, FormulaLineEntity, String> FORMULA_LINE_PRODUCT =
            ColumnField.of(FormulaLine.class, FORMULA_LINES, "product", String.class);
    private static final ColumnField<FormulaLine, FormulaLineEntity, Integer> FORMULA_LINE_QUANTITY =
            ColumnField.of(FormulaLine.class, FORMULA_LINES, "quantity", Integer.class);

    /** An archived order's id, status and the referrer's id (the archive's {@code customer}) copied from its source. */
    record ArchivedReferrer(Long id, String status, Long customer) {}

    private static final TableField<OrderArchiveEntity, OrderArchiveEntity> ARCHIVE =
            TableField.root(OrderArchiveEntity.class);
    private static final ColumnField<ArchivedReferrer, OrderArchiveEntity, Long> ARCHIVE_ID =
            ColumnField.of(ArchivedReferrer.class, ARCHIVE, "id", Long.class);
    private static final ColumnField<ArchivedReferrer, OrderArchiveEntity, Long> ARCHIVE_ORDER_ID =
            ColumnField.of(ArchivedReferrer.class, ARCHIVE, "orderId", Long.class);
    private static final ColumnField<ArchivedReferrer, OrderArchiveEntity, String> ARCHIVE_STATUS =
            ColumnField.of(ArchivedReferrer.class, ARCHIVE, "status", String.class);
    private static final ColumnField<ArchivedReferrer, OrderArchiveEntity, Long> ARCHIVE_CUSTOMER =
            ColumnField.of(ArchivedReferrer.class, ARCHIVE, "customer", Long.class);

    /** The orders of one referrer, {@code NULL} a group of its own. */
    record ReferrerCount(Long referrerId, Long orders) {}

    private static final ColumnField<ReferrerCount, OrderEntity, Long> GROUP_ORDER_ID =
            ColumnField.of(ReferrerCount.class, ORDERS, "id", Long.class);
    private static final ColumnField<ReferrerCount, OrderEntity, Long> GROUP_REFERRER =
            ColumnField.of(ReferrerCount.class, ORDERS, "referrer", Long.class);
    private static final AggregateField<ReferrerCount, Long> GROUP_COUNT = Agg.count(ORDERS);

    /** The customer a few orders name as referrer: order 3's, so ids divisible by 3 reach it. */
    private static final long REFERRER_34 = 34L;

    // ---- AC-COL-34: reads

    @TckTest
    void ac_col_34_the_id_keeps_every_order_in_a_select_a_filter_an_order_an_offset_page_and_a_count(
            TckDatabase db) {
        List<Referred> all = orders(db, "");
        List<Referred> none = all.stream().filter(row -> row.referrerId() == null).toList();
        List<Referred> sorted = all.stream().sorted(NULLS_FIRST).toList();
        int boundary = none.size() - 2; // a page holding the last NULLs and the first referrers
        var listed = new ArrayList<Referred>();
        var withoutReferrer = new ArrayList<Referred>();
        var ofReferrer = new ArrayList<Referred>();
        var page = new ArrayList<Referred>();
        long[] counted = new long[2];
        List<String> sql = SqlSnapshots.assertMatches(db, "col-34-reads", ds -> withExecutor(ds,
                OrderEntity.class, executor -> {
                    listed.addAll(executor.list(REFERRED.orderBy(ID.asc()).build(), Limit.unlimited()));
                    withoutReferrer.addAll(executor.list(REFERRED.where(f -> f.isNull(REFERRER))
                            .orderBy(ID.asc()).build(), Limit.unlimited()));
                    ofReferrer.addAll(executor.list(REFERRED.where(f -> f.eq(REFERRER, REFERRER_34))
                            .orderBy(ID.asc()).build(), Limit.unlimited()));
                    page.addAll(executor.page(REFERRED.orderBy(REFERRER.asc().nullsFirst()).build(),
                            PageSpec.of(boundary / 4, 4), CountMode.NO_COUNT).content());
                    counted[0] = executor.count(REFERRED.build());
                    counted[1] = executor.count(REFERRED.where(f -> f.isNull(REFERRER)).build());
                }));
        assertThat(none).isNotEmpty().hasSizeLessThan(all.size());
        assertThat(listed).hasSize(TckFixture.ORDERS).isEqualTo(all);
        assertThat(withoutReferrer).isEqualTo(none);
        assertThat(ofReferrer).isNotEmpty().isEqualTo(all.stream()
                .filter(row -> Objects.equals(row.referrerId(), REFERRER_34)).toList());
        int from = boundary / 4 * 4;
        assertThat(page).isEqualTo(sorted.subList(from, from + 4));
        assertThat(page).extracting(Referred::referrerId).containsNull().anyMatch(Objects::nonNull);
        assertThat(counted).containsExactly(TckFixture.ORDERS, none.size());
        // The count of every order needs no join; each statement reading the id joins LEFT, never inner
        assertThat(sql).filteredOn(statement -> statement.contains("customers")).hasSize(sql.size() - 1)
                .allSatisfy(statement -> assertThat(statement).contains("left join customers")
                        .doesNotContainPattern("(?<!left) join customers"));
    }

    @TckTest
    void ac_col_34_a_keyset_order_on_the_id_needs_nulls_first_or_last_and_exports_each_order_once(TckDatabase db) {
        List<Referred> all = orders(db, "");
        var last = new ArrayList<Referred>();
        var first = new ArrayList<Referred>();
        var unordered = new ArrayList<Referred>();
        SqlSnapshots.assertMatches(db, "col-34-keyset", ds -> withExecutor(ds, OrderEntity.class, executor -> {
            assertThat(executor.export(REFERRED.orderBy(REFERRER.asc().nullsLast()).keyset().build(),
                    ExportOptions.of(1500), rows -> rows, last::add)).isEqualTo(TckFixture.ORDERS);
            assertThat(executor.export(REFERRED.orderBy(REFERRER.desc().nullsFirst()).keyset().build(),
                    ExportOptions.of(1500), rows -> rows, first::add)).isEqualTo(TckFixture.ORDERS);
        }));
        Comparator<Long> ascending = Comparator.naturalOrder();
        assertThat(last).isEqualTo(all.stream().sorted(Comparator.comparing(Referred::referrerId,
                Comparator.nullsLast(ascending)).thenComparing(Referred::id)).toList());
        // The primary-key tie-breaker follows the last order's direction (R-PAG-01)
        assertThat(first).isEqualTo(all.stream().sorted(Comparator.comparing(Referred::referrerId,
                Comparator.nullsFirst(ascending.reversed())).thenComparing(Referred::id, ascending.reversed()))
                .toList());
        // Without a null precedence the first NULL a page reads is refused, not skipped
        withExecutor(JoinTestSupport.dataSource(db), OrderEntity.class, executor -> assertThatThrownBy(
                () -> executor.export(REFERRED.orderBy(REFERRER.asc()).keyset().build(), ExportOptions.of(1500),
                        rows -> rows, unordered::add))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2202)));
        assertThat(unordered).extracting(Referred::referrerId).doesNotContainNull();
    }

    @TckTest
    void ac_col_34_primary_key_first_with_a_collection_filter_keeps_the_orders_without_a_referrer(TckDatabase db) {
        List<Referred> matching = orders(db, "where exists (select 1 from OrderItemEntity i where i.order = o "
                + "and i.productCode = 'P001')").stream().sorted(NULLS_FIRST).toList();
        var page = new ArrayList<Referred>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-34-primary-key-first", ds -> withExecutor(ds,
                OrderEntity.class, executor -> page.addAll(executor.page(REFERRED
                        .where(f -> f.exists(ORDER_ITEMS, items -> items.eq(ITEM_CODE, "P001")))
                        .orderBy(REFERRER.asc().nullsFirst())
                        .primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build(), PageSpec.of(1, 5),
                        CountMode.NO_COUNT).content())));
        assertThat(matching).hasSizeGreaterThan(10);
        assertThat(page).isEqualTo(matching.subList(5, 10));
        assertThat(sql).hasSize(2);
    }

    @TckTest
    void ac_col_34_the_id_reads_through_a_joined_table_a_child_root_an_inverse_and_a_not_found_to_one(
            TckDatabase db) {
        Map<Long, Long> orderReferrers = new HashMap<>();
        orders(db, "").forEach(row -> orderReferrers.put(row.id(), row.referrerId()));
        List<ItemReferrer> expectedItems = rows(db, "select i.id, i.order.id from OrderItemEntity i where i.id <= 60 "
                + "order by i.id").stream().map(r -> new ItemReferrer((Long) r[0], orderReferrers.get((Long) r[1])))
                .toList();
        Map<Long, List<Long>> expectedReferred = new TreeMap<>();
        for (long customer = 1; customer <= 40; customer++) {
            expectedReferred.put(customer, new ArrayList<>());
        }
        orderReferrers.forEach((order, referrer) -> {
            if (referrer != null && referrer <= 40) {
                expectedReferred.get(referrer).add(order);
            }
        });
        expectedReferred.values().forEach(ids -> ids.sort(Comparator.naturalOrder()));
        var items = new ArrayList<ItemReferrer>();
        var patrons = new ArrayList<Patron>();
        var holders = new ArrayList<Holder>();
        var cards = new ArrayList<Card>();
        SqlSnapshots.assertMatches(db, "col-34-joined-child-inverse-not-found", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    items.addAll(executor(em, OrderItemEntity.class).list(ModelQuery
                            .builder(ITEMS, row -> new ItemReferrer(row.get(ITEM_ID), row.get(ITEM_REFERRER)))
                            .select(SelectSet.of(ITEM_ID, ITEM_REFERRER)).primaryKey(PrimaryKey.of(ITEM_ID))
                            .where(f -> f.lte(ITEM_ID, 60L)).orderBy(ITEM_ID.asc()).build(), Limit.unlimited()));
                    patrons.addAll(executor(em, CustomerEntity.class).list(ModelQuery
                            .builder(CUSTOMERS, row -> new Patron(row.get(PATRON_ID), List.of()))
                            .primaryKey(PrimaryKey.of(PATRON_ID)).where(f -> f.lte(PATRON_ID, 40L))
                            .orderBy(PATRON_ID.asc())
                            .fetch(FetchPlan.of(SelectSet.of(PATRON_ID)).child(REFERRED_ORDERS,
                                    FetchPlan.of(SelectSet.of(ID, REFERRER))))
                            .build(), Limit.unlimited()));
                    holders.addAll(executor(em, CardHolderEntity.class).list(ModelQuery
                            .builder(HOLDERS, row -> new Holder(row.get(HOLDER_ID), row.get(HOLDER_CARD)))
                            .select(SelectSet.of(HOLDER_ID, HOLDER_CARD)).primaryKey(PrimaryKey.of(HOLDER_ID))
                            .orderBy(HOLDER_CARD.asc().nullsLast(), HOLDER_ID.asc()).build(), Limit.unlimited()));
                    cards.addAll(executor(em, CardEntity.class).list(ModelQuery
                            .builder(CARDS, row -> new Card(row.get(CARD_ID), row.get(CARD_HOLDER),
                                    row.get(CARD_ISSUER)))
                            .select(SelectSet.of(CARD_ID, CARD_HOLDER, CARD_ISSUER))
                            .primaryKey(PrimaryKey.of(CARD_ID)).orderBy(CARD_ID.asc()).build(), Limit.unlimited()));
                });
            }
        });
        assertThat(items).hasSize(60).isEqualTo(expectedItems).extracting(ItemReferrer::referrerId).containsNull();
        assertThat(patrons).extracting(Patron::id).containsExactlyElementsOf(expectedReferred.keySet());
        assertThat(patrons).allSatisfy(patron -> assertThat(patron.referred()).extracting(Referred::id)
                .containsExactlyInAnyOrderElementsOf(expectedReferred.get(patron.id())));
        // Card i belongs to holder i + 4 up to card 6: holders 1-4, 11 and 12 have none and read null, last
        assertThat(holders).containsExactly(new Holder(5L, 1L), new Holder(6L, 2L), new Holder(7L, 3L),
                new Holder(8L, 4L), new Holder(9L, 5L), new Holder(10L, 6L), new Holder(1L, null),
                new Holder(2L, null), new Holder(3L, null), new Holder(4L, null), new Holder(11L, null),
                new Holder(12L, null));
        // The issuer is NULL on cards 4 and 8 and names no customer on cards 1 and 5: each reads null, none dropped
        assertThat(cards).containsExactly(new Card(1L, 5L, null), new Card(2L, 6L, 2L), new Card(3L, 7L, 3L),
                new Card(4L, 8L, null), new Card(5L, 9L, null), new Card(6L, 10L, 6L), new Card(7L, null, 7L),
                new Card(8L, null, null));
    }

    @TckTest
    void ac_col_34_a_locked_read_of_the_id_is_refused_with_mq2211_before_any_statement(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class,
                executor -> assertThatThrownBy(() -> executor.list(REFERRED.build().withLock(RowLock.exclusive()),
                        Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ2211))));
        assertThat(sql).isEmpty();
    }

    // ---- AC-COL-34: writes

    @TckTest
    void ac_col_34_a_bulk_write_filters_by_the_foreign_key_column_with_no_join(TckDatabase db) {
        long expected = orders(db, "where o.id < 30").stream()
                .filter(row -> row.referrerId() == null || row.referrerId() == REFERRER_34).count();
        var update = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED")
                .where(f -> f.lt(ID, 30L).or(a -> a.isNull(REFERRER), b -> b.eq(REFERRER, REFERRER_34)))
                .build();
        var delete = ModelDelete.builder(CARDS).primaryKey(PrimaryKey.of(CARD_ID))
                .where(f -> f.eq(CARD_HOLDER, 5L)).build();
        long[] written = new long[3];
        var marked = new ArrayList<Long>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-34-writes", ds -> inRolledBackTransaction(ds, em -> {
            written[0] = executor(em, OrderEntity.class).update(update);
            marked.addAll(em.createQuery("select o.id from OrderEntity o where o.status = 'MARKED'", Long.class)
                    .getResultList());
            written[1] = executor(em, CardEntity.class).delete(delete);
        }));
        assertThat(expected).isGreaterThan(1);
        assertThat(written[0]).isEqualTo(expected);
        assertThat(marked).hasSize((int) expected);
        assertThat(written[1]).isEqualTo(1);
        assertThat(sql).filteredOn(statement -> statement.startsWith("update")).singleElement()
                .satisfies(statement -> assertThat(statement).doesNotContain("join").doesNotContain("customers"));
        assertThat(sql).filteredOn(statement -> statement.startsWith("delete")).singleElement()
                .satisfies(statement -> assertThat(statement).doesNotContain("join").doesNotContain("card_holders"));
        // throughEntities loads the entities by the same filter
        inRolledBackTransaction(JoinTestSupport.dataSource(db), em -> written[2] = executor(em, OrderEntity.class)
                .update(ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED")
                        .where(f -> f.lt(ID, 30L).isNull(REFERRER)).throughEntities().build()));
        assertThat(written[2]).isEqualTo(orders(db, "where o.id < 30 and o.referrerId is null").size());
    }

    @TckTest
    void ac_col_34_a_returning_write_reads_the_id_from_the_foreign_key_column(TckDatabase db) {
        List<Referred> expected = orders(db, "where o.id < 10");
        boolean returnsRows = returnsRows(db);
        var update = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ID)).set(STATUS, "MARKED")
                .where(f -> f.lt(ID, 10L)).build();
        var returned = new ArrayList<Referred>();
        var refusals = new ArrayList<ModelQueryException>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-34-returning", ds -> inRolledBackTransaction(ds,
                em -> {
                    var executor = executor(em, OrderEntity.class);
                    if (returnsRows) {
                        returned.addAll(executor.updateReturning(update, REFERRED.build()));
                    } else {
                        refusals.add(catchRefusal(() -> executor.updateReturning(update, REFERRED.build())));
                    }
                }));
        if (returnsRows) {
            assertThat(returned).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(returned).extracting(Referred::referrerId).containsNull().anyMatch(Objects::nonNull);
        } else {
            assertThat(sql).isEmpty();
            assertThat(refusals).singleElement().satisfies(e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011));
        }
    }

    @TckTest
    void ac_col_34_a_write_filter_or_a_returning_write_over_a_non_key_reference_is_refused_before_any_statement(
            TckDatabase db) {
        var byProduct = ModelUpdate.builder(SKU_LINES).primaryKey(PrimaryKey.of(SKU_LINE_ID))
                .set(SKU_LINE_QUANTITY, 9).where(f -> f.eq(SKU_LINE_PRODUCT, 1L));
        var returning = ModelQuery.builder(SKU_LINES, row -> new SkuLine(row.get(SKU_LINE_ID),
                row.get(SKU_LINE_PRODUCT))).select(SelectSet.of(SKU_LINE_ID, SKU_LINE_PRODUCT))
                .primaryKey(PrimaryKey.of(SKU_LINE_ID)).build();
        var refusals = new ArrayList<ModelQueryException>();
        boolean returnsRows = returnsRows(db);
        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            var executor = executor(em, SkuOrderLineEntity.class);
            refusals.add(catchRefusal(() -> executor.update(byProduct.build())));
            refusals.add(catchRefusal(() -> executor.update(byProduct.throughEntities().build())));
            refusals.add(catchRefusal(() -> executor.delete(ModelDelete.builder(SKU_LINES)
                    .primaryKey(PrimaryKey.of(SKU_LINE_ID)).where(f -> f.isNull(SKU_LINE_PRODUCT)).build())));
            refusals.add(catchRefusal(() -> executor.updateReturning(ModelUpdate.builder(SKU_LINES)
                    .primaryKey(PrimaryKey.of(SKU_LINE_ID)).set(SKU_LINE_QUANTITY, 9)
                    .where(f -> f.eq(SKU_LINE_ID, 1L)).build(), returning)));
            // A @NotFound to-one and the inverse side are refused as well: Hibernate joins both
            refusals.add(catchRefusal(() -> executor(em, CardEntity.class).delete(ModelDelete.builder(CARDS)
                    .primaryKey(PrimaryKey.of(CARD_ID)).where(f -> f.eq(CARD_ISSUER, 2L)).build())));
            refusals.add(catchRefusal(() -> executor(em, CardHolderEntity.class).delete(ModelDelete.builder(HOLDERS)
                    .primaryKey(PrimaryKey.of(HOLDER_ID)).where(f -> f.isNull(HOLDER_CARD)).build())));
        }));
        assertThat(sql).isEmpty();
        assertThat(refusals).hasSize(6);
        assertThat(refusals.subList(4, 6)).extracting(ModelQueryException::code)
                .containsExactly(MqCode.MQ1004, MqCode.MQ1004);
        assertThat(refusals.get(4)).hasMessageContaining("MQ1004: Card.issuer: ");
        assertThat(refusals.get(5)).hasMessageContaining("MQ1004: Holder.card: ");
        assertThat(refusals.subList(0, 3)).allSatisfy(e -> assertThat(e)
                .isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("MQ1004: SkuLine.product: ")
                .satisfies(refused -> assertThat(refused.code()).isEqualTo(MqCode.MQ1004)));
        assertThat(refusals.get(3).code()).isEqualTo(returnsRows ? MqCode.MQ1809 : MqCode.MQ4011);
    }

    @TckTest
    void ac_col_34_a_non_key_reference_and_a_join_formula_read_through_the_left_join(TckDatabase db) {
        var skuLines = new ArrayList<SkuLine>();
        var formulaLines = new ArrayList<FormulaLine>();
        long[] written = new long[1];
        List<String> sql = SqlSnapshots.assertMatches(db, "col-34-non-key-and-formula", ds ->
                inRolledBackTransaction(ds, em -> {
                    skuLines.addAll(executor(em, SkuOrderLineEntity.class).list(ModelQuery
                            .builder(SKU_LINES, row -> new SkuLine(row.get(SKU_LINE_ID), row.get(SKU_LINE_PRODUCT)))
                            .select(SelectSet.of(SKU_LINE_ID, SKU_LINE_PRODUCT))
                            .primaryKey(PrimaryKey.of(SKU_LINE_ID)).where(f -> f.lte(SKU_LINE_ID, 6L))
                            .orderBy(SKU_LINE_ID.asc()).build(), Limit.unlimited()));
                    var formula = executor(em, FormulaLineEntity.class);
                    formulaLines.addAll(formula.list(ModelQuery.builder(FORMULA_LINES,
                                    row -> new FormulaLine(row.get(FORMULA_LINE_ID), row.get(FORMULA_LINE_PRODUCT)))
                            .select(SelectSet.of(FORMULA_LINE_ID, FORMULA_LINE_PRODUCT))
                            .primaryKey(PrimaryKey.of(FORMULA_LINE_ID)).where(f -> f.lte(FORMULA_LINE_ID, 4L))
                            .orderBy(FORMULA_LINE_ID.asc()).build(), Limit.unlimited()));
                    written[0] = formula.update(ModelUpdate.builder(FORMULA_LINES)
                            .primaryKey(PrimaryKey.of(FORMULA_LINE_ID)).set(FORMULA_LINE_QUANTITY, 9)
                            .where(f -> f.eq(FORMULA_LINE_PRODUCT, "F-04")).build());
                }));
        List<SkuLine> expectedSku = rows(db, "select l.id, l.product.id from SkuOrderLineEntity l where l.id <= 6 "
                + "order by l.id").stream().map(r -> new SkuLine((Long) r[0], (Long) r[1])).toList();
        assertThat(skuLines).hasSize(6).isEqualTo(expectedSku);
        assertThat(formulaLines).extracting(FormulaLine::productCode).containsExactly("F-04", "F-07", "F-02", "F-05");
        assertThat(written[0]).isEqualTo(rows(db, "select l.id from FormulaLineEntity l "
                + "where upper(l.productCode) = 'F-04'").size());
        assertThat(sql.get(0)).contains("left join");
    }

    @TckTest
    void ac_col_34_an_insert_select_source_reads_the_id_through_the_left_join_and_keeps_a_null_foreign_key(
            TckDatabase db) {
        var insert = ModelInsert.select(InsertColumns.<ArchivedReferrer, OrderArchiveEntity>of(ARCHIVE)
                        .addKey(ARCHIVE_ID, ArchivedReferrer::id).add(ARCHIVE_ORDER_ID, ArchivedReferrer::id)
                        .add(ARCHIVE_STATUS, ArchivedReferrer::status)
                        .add(ARCHIVE_CUSTOMER, ArchivedReferrer::customer), ORDERS)
                .map(ARCHIVE_ID, ID).map(ARCHIVE_ORDER_ID, ID).map(ARCHIVE_STATUS, STATUS)
                .map(ARCHIVE_CUSTOMER, REFERRER)
                .where(f -> f.lte(ID, 9L)).build();
        long[] written = new long[1];
        var archived = new ArrayList<ArchivedReferrer>();

        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = executor(em, OrderArchiveEntity.class).insert(insert);
            em.createQuery("select a.id, a.status, a.customer.id from OrderArchiveEntity a "
                    + "order by a.id", Object[].class).getResultList()
                    .forEach(r -> archived.add(new ArchivedReferrer((Long) r[0], (String) r[1], (Long) r[2])));
        }));
        List<ArchivedReferrer> expected = rows(db, "select o.id, o.status, o.referrer.id from OrderEntity o "
                + "where o.id <= 9 order by o.id").stream()
                .map(r -> new ArchivedReferrer((Long) r[0], (String) r[1], (Long) r[2])).toList();

        assertThat(written[0]).isEqualTo(9);
        assertThat(archived).isEqualTo(expected);
        assertThat(archived).extracting(ArchivedReferrer::customer).containsNull();
        String insertSql = sql.stream().filter(s -> s.startsWith("insert")).findFirst().orElseThrow();
        assertThat(insertSql).contains("left join");
        SqlSnapshots.assertMatches(db, "col-35-insert-from-select", List.of(insertSql));
    }

    // ---- AC-COL-35: grouping by the id

    @TckTest
    void ac_col_35_a_runtime_group_by_on_the_id_keeps_the_null_group_in_a_list_and_a_grouped_export(TckDatabase db) {
        Map<Long, Long> expected = new HashMap<>();
        orders(db, "where o.id <= 300").forEach(row -> expected.merge(row.referrerId(), 1L, Long::sum));
        var grouped = ModelQuery.builder(ORDERS, row -> new ReferrerCount(row.get(GROUP_REFERRER),
                        row.get(GROUP_COUNT)))
                .select(SelectSet.of(GROUP_REFERRER, GROUP_COUNT))
                .where(f -> f.lte(GROUP_ORDER_ID, 300L))
                .groupBy(GROUP_REFERRER)
                .orderBy(GROUP_REFERRER.asc().nullsFirst());
        var listed = new ArrayList<ReferrerCount>();
        var exported = new ArrayList<ReferrerCount>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-35-group-by-id", ds -> withExecutor(ds,
                OrderEntity.class, executor -> {
                    listed.addAll(executor.list(grouped.build(), Limit.unlimited()));
                    assertThat(executor.export(grouped.build(), ExportOptions.of(40), rows -> rows, exported::add))
                            .isEqualTo(expected.size());
                }));
        assertThat(expected).containsKey(null);
        assertThat(listed.get(0)).isEqualTo(new ReferrerCount(null, expected.get(null)));
        assertThat(listed).hasSize(expected.size()).allSatisfy(group -> assertThat(group.orders())
                .isEqualTo(expected.get(group.referrerId())));
        assertThat(exported).isEqualTo(listed);
        assertThat(sql).allSatisfy(statement -> assertThat(statement).contains("left join").contains("group by"));
    }

    // ---- support

    /** The orders {@code where} (JPQL over {@code o}) chooses, by id, with the plain {@code referrerId} column. */
    private static List<Referred> orders(TckDatabase db, String where) {
        return rows(db, "select o.id, o.referrerId from OrderEntity o " + where + " order by o.id").stream()
                .map(r -> new Referred((Long) r[0], (Long) r[1])).toList();
    }

    private static List<Object[]> rows(TckDatabase db, String jpql) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            var out = new ArrayList<Object[]>();
            sf.inSession(em -> out.addAll(em.createQuery(jpql, Object[].class).getResultList()));
            return out;
        }
    }

    /** Whether {@code db}'s profile returns the rows an update changed (R-WRT-50), else {@code MQ4011}. */
    private static boolean returnsRows(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            return VendorResolver.resolve(sf, Optional.empty(), MysqlStreamingMode.ROW_BY_ROW).profile()
                    .returning(WriteStatement.UPDATE).isPresent();
        }
    }

    private static ModelQueryException catchRefusal(Runnable call) {
        try {
            call.run();
        } catch (ModelQueryException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    private static <E> ModelQueryExecutor<E> executor(EntityManager em, Class<E> root) {
        return ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults());
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(executor(em, root)));
        }
    }

    /** Runs {@code work} in a transaction over {@code ds} and rolls it back, so the fixture stays as seeded. */
    private static void inRolledBackTransaction(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    work.accept(em);
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }
}
