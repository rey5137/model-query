package com.rey.modelquery.tck.flt;

import static com.rey.modelquery.core.RenderOptions.portable;
import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.flt.FiltersTest.Fixture;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** {@code or}, {@code not}, {@code when}, {@code apply}, {@code exists} and {@code add} (api/12 §1-3, §6-7). */
class FilterCompositionTest {

    // ---- orders, with their customer and items

    record O(Long id) {}

    static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS = TableField.join(ORDERS, "items", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS_B = ITEMS.as("b");
    private static final TableField<OrderEntity, OrderItemEntity> ITEMS_BIG =
            ITEMS.as("big").on((i, cb) -> cb.ge(i.<Integer>get("quantity"), 8));

    static final ColumnField<O, OrderEntity, Long> ID = ColumnField.of(O.class, ORDERS, "id", Long.class);
    static final ColumnField<O, OrderEntity, String> STATUS =
            ColumnField.of(O.class, ORDERS, "status", String.class);
    private static final ColumnField<O, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(O.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<O, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(O.class, CUSTOMER, "name", String.class);
    private static final ColumnField<O, OrderItemEntity, String> PRODUCT =
            ColumnField.of(O.class, ITEMS, "productCode", String.class);
    private static final ColumnField<O, OrderItemEntity, Integer> QUANTITY =
            ColumnField.of(O.class, ITEMS, "quantity", Integer.class);
    private static final ColumnField<O, OrderItemEntity, String> PRODUCT_B =
            ColumnField.of(O.class, ITEMS_B, "productCode", String.class);
    private static final ColumnField<O, OrderItemEntity, Integer> QUANTITY_B =
            ColumnField.of(O.class, ITEMS_B, "quantity", Integer.class);
    private static final ColumnField<O, OrderItemEntity, String> PRODUCT_BIG =
            ColumnField.of(O.class, ITEMS_BIG, "productCode", String.class);

    static final ModelQuery.Builder<OrderEntity, Object, O> ORDER_QUERY =
            ModelQuery.builder(ORDERS, row -> new O(row.get(ID))).columns(ColumnSet.of(ID)).orderBy(ID.asc());

    /** A shared fragment, as a caller would keep one. */
    private static final UnaryOperator<Filters<O>> NOT_CANCELLED = g -> g.ne(STATUS, "CANCELLED");

    // ---- customers, with their orders joined LEFT or INNER

    record C(Long id) {}

    static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final TableField<CustomerEntity, OrderEntity> ORDERS_LEFT =
            TableField.join(CUSTOMERS, "orders", LEFT);
    private static final TableField<CustomerEntity, OrderEntity> ORDERS_INNER =
            TableField.join(CUSTOMERS, "orders", INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS =
            TableField.join(ORDERS_INNER, "items", INNER);

    /** No item has a quantity of 100: the LEFT join finds no row for any order. */
    private static final TableField<OrderEntity, OrderItemEntity> NO_ITEMS =
            TableField.<OrderEntity, OrderItemEntity>join(ORDERS_INNER, "items", LEFT)
                    .as("none").on((i, cb) -> cb.ge(i.<Integer>get("quantity"), 100));

    static final ColumnField<C, CustomerEntity, Long> C_ID =
            ColumnField.of(C.class, CUSTOMERS, "id", Long.class);
    static final ColumnField<C, CustomerEntity, String> C_NAME =
            ColumnField.of(C.class, CUSTOMERS, "name", String.class);
    private static final ColumnField<C, OrderEntity, String> LEFT_STATUS =
            ColumnField.of(C.class, ORDERS_LEFT, "status", String.class);
    private static final ColumnField<C, OrderEntity, String> INNER_STATUS =
            ColumnField.of(C.class, ORDERS_INNER, "status", String.class);
    private static final ColumnField<C, OrderItemEntity, Long> NO_ITEM_ID =
            ColumnField.of(C.class, NO_ITEMS, "id", Long.class);
    private static final ColumnField<C, OrderEntity, BigDecimal> ORDER_TOTAL =
            ColumnField.of(C.class, ORDERS_INNER, "total", BigDecimal.class);
    private static final ColumnField<C, OrderItemEntity, Integer> ORDER_QUANTITY =
            ColumnField.of(C.class, ORDER_ITEMS, "quantity", Integer.class);

    static final ModelQuery.Builder<CustomerEntity, Object, C> CUSTOMER_QUERY =
            ModelQuery.builder(CUSTOMERS, row -> new C(row.get(C_ID))).columns(ColumnSet.of(C_ID))
                    .orderBy(C_ID.asc());

    // ---- nullable columns, for NOT over NULL

    record N(Long id, Integer sortInt) {}

    static final TableField<NullableSortEntity, NullableSortEntity> NULLABLE =
            TableField.root(NullableSortEntity.class);
    static final ColumnField<N, NullableSortEntity, Long> N_ID =
            ColumnField.of(N.class, NULLABLE, "id", Long.class);
    static final ColumnField<N, NullableSortEntity, Integer> N_INT =
            ColumnField.of(N.class, NULLABLE, "sortInt", Integer.class);
    static final ModelQuery.Builder<NullableSortEntity, Object, N> NULLABLE_QUERY =
            ModelQuery.builder(NULLABLE, row -> new N(row.get(N_ID), row.get(N_INT)))
                    .columns(ColumnSet.of(N_ID, N_INT)).orderBy(N_ID.asc());

    private static final Optional<String> NONE = Optional.empty();
    private static final BigDecimal T500 = new BigDecimal("500.00");
    private static final BigDecimal T900 = new BigDecimal("900.00");
    /** Rows inserted inside a rolled-back transaction start here, above every fixture id. */
    static final long EXTRA = 900_001L;

    // ---- the fixtures, which the write/read parity check also runs (api/14 AC-WRT-07)

    private static final UnaryOperator<Filters<O>> PAID = f -> f.eq(STATUS, "PAID");
    private static final UnaryOperator<Filters<O>> PAID_AND_SKIPPED_OR = f -> PAID.apply(f)
            .or(a -> a.eq(STATUS, NONE), b -> b.like(CUSTOMER_NAME, NONE, LikeMode.CONTAINS));
    private static final UnaryOperator<Filters<O>> OR_ONE_BRANCH_SKIPPED =
            f -> f.or(a -> a.eq(STATUS, Optional.of("PAID")), b -> b.eq(CUSTOMER_NAME, NONE));
    private static final UnaryOperator<Filters<O>> PAID_OR_BIG_NEW =
            f -> f.or(a -> a.eq(STATUS, "PAID"), b -> b.eq(STATUS, "NEW").gt(TOTAL, T500));
    private static final UnaryOperator<Filters<O>> NOT_SKIPPED = f -> f.not(g -> g.eq(STATUS, NONE));
    private static final UnaryOperator<Filters<O>> NOT_BIG_PAID = f -> f.not(g -> g.eq(STATUS, "PAID").gt(TOTAL, T500));
    private static final UnaryOperator<Filters<O>> WHEN_FALSE = f -> f.when(false, g -> g.eq(STATUS, "PAID"));
    private static final UnaryOperator<Filters<O>> WHEN_TRUE = f -> f.when(true, g -> g.eq(STATUS, "PAID"));
    private static final UnaryOperator<Filters<O>> APPLY_NOT_CANCELLED = f -> f.apply(NOT_CANCELLED);
    private static final UnaryOperator<Filters<N>> NOT_SEVEN = f -> f.not(g -> g.eq(N_INT, 7));
    private static final UnaryOperator<Filters<O>> EXISTS_SKIPPED = f -> f
            .exists(ITEMS, i -> i.eq(PRODUCT, NONE))
            .notExists(ITEMS, i -> i.eq(PRODUCT, NONE));
    private static final UnaryOperator<Filters<O>> EXISTS_ITEMS = f -> f.exists(ITEMS);
    private static final UnaryOperator<Filters<O>> NO_ITEM = f -> f.notExists(ITEMS, i -> i.gte(QUANTITY, 1));
    private static final UnaryOperator<Filters<C>> PAID_OR_LONELY_LEFT = f -> f.gte(C_ID, EXTRA)
            .or(a -> a.eq(LEFT_STATUS, "PAID"), b -> b.eq(C_NAME, "Lonely"));
    /** Declared INNER, but first needed inside the or: resolved as LEFT (R-FLT-10). */
    private static final UnaryOperator<Filters<C>> PAID_OR_LONELY_INNER = f -> f.gte(C_ID, EXTRA)
            .or(a -> a.eq(INNER_STATUS, "PAID"), b -> b.eq(C_NAME, "Lonely"));
    /** The same path is needed INNER outside the or, even though the or comes first: that join is reused. */
    private static final UnaryOperator<Filters<C>> INNER_ELSEWHERE = f -> f.gte(C_ID, EXTRA)
            .or(a -> a.eq(INNER_STATUS, "PAID"), b -> b.eq(C_NAME, "Lonely"))
            .ne(INNER_STATUS, "CANCELLED");
    /** Plain SQL NOT over a LEFT join: a customer with no order is UNKNOWN under it, so it is excluded. */
    private static final UnaryOperator<Filters<C>> NOT_PAID_LEFT =
            f -> f.gte(C_ID, EXTRA).not(g -> g.eq(LEFT_STATUS, "PAID"));
    private static final UnaryOperator<Filters<O>> EXISTS_P007_BIG =
            f -> f.exists(ITEMS, i -> i.eq(PRODUCT, "P007").gte(QUANTITY, 8));
    private static final UnaryOperator<Filters<O>> EXISTS_ALIASED_P007_BIG =
            f -> f.exists(ITEMS_B, i -> i.eq(PRODUCT_B, "P007").gte(QUANTITY_B, 8));
    private static final UnaryOperator<Filters<O>> EXISTS_ON_P007 =
            f -> f.exists(ITEMS_BIG, i -> i.eq(PRODUCT_BIG, "P007"));
    /** Inside an or, and next to an outer join to the same path: the sub-query keeps its own join. */
    private static final UnaryOperator<Filters<O>> P007_AND_EXISTS_IN_OR = f -> f.eq(PRODUCT, "P007")
            .or(a -> a.exists(ITEMS_B, i -> i.gte(QUANTITY_B, 5)), b -> b.eq(STATUS, "PAID"));
    /** A LEFT join below the path keeps its on(...): it matches no item, so every order has a NULL there. */
    private static final UnaryOperator<Filters<C>> NO_ITEM_BELOW_PATH =
            f -> f.exists(ORDERS_INNER, o -> o.isNull(NO_ITEM_ID));
    private static final UnaryOperator<Filters<C>> AN_ITEM_BELOW_PATH =
            f -> f.exists(ORDERS_INNER, o -> o.isNotNull(NO_ITEM_ID));
    private static final UnaryOperator<Filters<C>> NESTED_EXISTS = f -> f.exists(ORDERS_INNER,
            o -> o.gt(ORDER_TOTAL, T900).exists(ORDER_ITEMS, i -> i.eq(ORDER_QUANTITY, 9)));
    private static final BigDecimal T950 = new BigDecimal("950.00");
    private static final UnaryOperator<Filters<C>> NESTED_EXISTS_SAME_PATH = f -> f.exists(ORDERS_INNER,
            o -> o.gt(ORDER_TOTAL, T900).exists(ORDERS_INNER, same -> same.lt(ORDER_TOTAL, T950)));
    private static final UnaryOperator<Filters<O>> P007_VIA_EXISTS =
            f -> f.exists(ITEMS, i -> i.eq(PRODUCT, "P007").gte(QUANTITY, 3));
    private static final UnaryOperator<Filters<O>> P007_VIA_JOIN = f -> f.eq(PRODUCT, "P007").gte(QUANTITY, 3);
    private static final UnaryOperator<Filters<O>> CUSTOM_PREDICATES = f -> f
            .gt(TOTAL, T500)
            .add((ctx, cb) -> cb.equal(CUSTOMER.resolve(ctx).get("country"), "VN"))
            .add((ctx, cb) -> cb.lessThan(CUSTOMER_NAME.path(ctx), "Customer 0100"));
    private static final UnaryOperator<Filters<C>> CUSTOM_PREDICATE_IN_OR = f -> f.gte(C_ID, EXTRA)
            .or(a -> a.add((ctx, cb) -> cb.equal(INNER_STATUS.path(ctx), "PAID")), b -> b.eq(C_NAME, "Lonely"));

    static List<Fixture<O>> orderFixtures() {
        return List.of(new Fixture<>("paid and a skipped or", PAID_AND_SKIPPED_OR),
                new Fixture<>("or, one branch skipped", OR_ONE_BRANCH_SKIPPED),
                new Fixture<>("or of a value and a group", PAID_OR_BIG_NEW),
                new Fixture<>("not, skipped", NOT_SKIPPED), new Fixture<>("not of a group", NOT_BIG_PAID),
                new Fixture<>("when false", WHEN_FALSE), new Fixture<>("when true", WHEN_TRUE),
                new Fixture<>("apply", APPLY_NOT_CANCELLED), new Fixture<>("exists, skipped", EXISTS_SKIPPED),
                new Fixture<>("exists(path)", EXISTS_ITEMS), new Fixture<>("notExists", NO_ITEM),
                new Fixture<>("exists", EXISTS_P007_BIG),
                new Fixture<>("exists over an alias", EXISTS_ALIASED_P007_BIG),
                new Fixture<>("exists over on(...)", EXISTS_ON_P007),
                new Fixture<>("exists in an or next to a to-many join", P007_AND_EXISTS_IN_OR),
                new Fixture<>("exists for a count", P007_VIA_EXISTS),
                new Fixture<>("a to-many join for a count", P007_VIA_JOIN),
                new Fixture<>("custom predicates", CUSTOM_PREDICATES));
    }

    /** The customer fixtures; the ones from {@link #EXTRA} read the rows of {@link #insertLeftJoinRows}. */
    static List<Fixture<C>> customerFixtures() {
        return List.of(new Fixture<>("or over a LEFT join", PAID_OR_LONELY_LEFT),
                new Fixture<>("or over an INNER join resolved LEFT", PAID_OR_LONELY_INNER),
                new Fixture<>("or over a join needed INNER elsewhere", INNER_ELSEWHERE),
                new Fixture<>("not over a LEFT join", NOT_PAID_LEFT),
                new Fixture<>("a custom predicate in an or", CUSTOM_PREDICATE_IN_OR),
                new Fixture<>("isNull below an exists path", NO_ITEM_BELOW_PATH),
                new Fixture<>("isNotNull below an exists path", AN_ITEM_BELOW_PATH),
                new Fixture<>("nested exists", NESTED_EXISTS),
                new Fixture<>("nested exists on the same path", NESTED_EXISTS_SAME_PATH));
    }

    static List<Fixture<N>> nullableFixtures() {
        return List.of(new Fixture<>("not over NULLs", NOT_SEVEN));
    }

    // ---- AC-FLT-03

    @TckTest
    void ac_flt_03_an_or_whose_every_branch_was_skipped_returns_the_same_rows_as_the_query_without_it(
            TckDatabase db) {
        List<List<Long>> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-03-skipped-or", ds -> inSession(ds, em -> {
            results.add(ids(em, ORDER_QUERY.where(PAID)));
            // Every branch skipped, one of them over a join: the or leaves no trace in the SQL.
            results.add(ids(em, ORDER_QUERY.where(PAID_AND_SKIPPED_OR)));
            // One branch skipped: it is dropped, and the other stands alone.
            results.add(ids(em, ORDER_QUERY.where(OR_ONE_BRANCH_SKIPPED)));
            results.add(ids(em, ORDER_QUERY.where(PAID_OR_BIG_NEW)));
        }));
        List<Long> expectedPaid = jpql(db, "where o.status = 'PAID'");
        assertThat(expectedPaid).isNotEmpty().hasSizeLessThan(TckFixture.ORDERS);
        assertThat(results.get(0)).isEqualTo(expectedPaid);
        assertThat(results.get(1)).isEqualTo(expectedPaid);
        assertThat(results.get(2)).isEqualTo(expectedPaid);
        assertThat(results.get(3))
                .isEqualTo(jpql(db, "where o.status = 'PAID' or (o.status = 'NEW' and o.total > 500)"))
                .hasSizeGreaterThan(expectedPaid.size());
    }

    @TckTest
    void ac_flt_03_not_when_and_apply_follow_the_same_skip_rules(TckDatabase db) {
        List<List<Long>> results = new ArrayList<>();
        List<List<N>> nullable = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-03-not-when-apply", ds -> inSession(ds, em -> {
            results.add(ids(em, ORDER_QUERY.where(NOT_SKIPPED)));
            results.add(ids(em, ORDER_QUERY.where(NOT_BIG_PAID)));
            results.add(ids(em, ORDER_QUERY.where(WHEN_FALSE)));
            results.add(ids(em, ORDER_QUERY.where(WHEN_TRUE)));
            results.add(ids(em, ORDER_QUERY.where(APPLY_NOT_CANCELLED)));
            nullable.add(run(em, NULLABLE_QUERY.where(NOT_SEVEN)));
            nullable.add(run(em, NULLABLE_QUERY.where(f -> f.ne(N_INT, 7))));
        }));
        List<Long> all = jpql(db, "");
        assertThat(results.get(0)).isEqualTo(all);
        assertThat(results.get(1)).isEqualTo(jpql(db, "where not (o.status = 'PAID' and o.total > 500)"))
                .hasSizeLessThan(all.size());
        assertThat(results.get(2)).isEqualTo(all);
        assertThat(results.get(3)).isEqualTo(jpql(db, "where o.status = 'PAID'"));
        assertThat(results.get(4)).isEqualTo(jpql(db, "where o.status <> 'CANCELLED'"));
        // Plain SQL NOT (R-FLT-05): a row whose column is NULL is UNKNOWN under the group and under its negation,
        // so not(eq) drops it while ne keeps it (R-FLT-04).
        assertThat(nullable.get(0)).isNotEmpty().allSatisfy(n -> assertThat(n.sortInt()).isNotNull().isNotEqualTo(7));
        assertThat(nullable.get(1).stream().filter(n -> n.sortInt() == null).count()).isPositive();
        assertThat(nullable.get(1).stream().filter(n -> n.sortInt() != null).toList()).isEqualTo(nullable.get(0));
    }

    // ---- AC-FLT-04

    @TckTest
    void ac_flt_04_exists_with_every_inner_filter_skipped_is_skipped_and_exists_path_still_renders(TckDatabase db) {
        List<List<Long>> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-04-exists-skipped", ds -> inSession(ds, em -> {
            ModelQuery<OrderEntity, Object, O> skipped = ORDER_QUERY.where(EXISTS_SKIPPED).build();
            assertThat(skipped.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable()).query().getRestriction())
                    .isNull();
            results.add(ids(em, skipped));
            results.add(ids(em, ORDER_QUERY.where(EXISTS_ITEMS)));
        }));
        assertThat(results.get(0)).hasSize(TckFixture.ORDERS);
        assertThat(results.get(1)).hasSize(TckFixture.ORDERS);

        // An order without items shows that exists(path) still narrows.
        inRolledBack(db, em -> {
            insertCustomer(em, EXTRA, "Extra");
            insertOrder(em, EXTRA, EXTRA, "NEW");
            assertThat(ids(em, ORDER_QUERY)).contains(EXTRA);
            assertThat(ids(em, ORDER_QUERY.where(EXISTS_ITEMS))).hasSize(TckFixture.ORDERS)
                    .doesNotContain(EXTRA);
            assertThat(ids(em, ORDER_QUERY.where(NO_ITEM))).containsExactly(EXTRA);
        });
    }

    // ---- AC-FLT-09

    @TckTest
    void ac_flt_09_an_or_branch_over_a_left_joined_column_keeps_rows_that_have_no_joined_row(TckDatabase db) {
        var selectingInner = CUSTOMER_QUERY.columns(ColumnSet.of(C_ID, INNER_STATUS));
        List<ModelQuery.Builder<CustomerEntity, Object, C>> queries = List.of(
                CUSTOMER_QUERY.where(PAID_OR_LONELY_LEFT),
                CUSTOMER_QUERY.where(PAID_OR_LONELY_INNER),
                CUSTOMER_QUERY.where(INNER_ELSEWHERE),
                selectingInner.where(PAID_OR_LONELY_INNER));
        SqlSnapshots.assertMatches(db, "flt-09-left-join-in-or", ds -> inSession(ds, em -> queries.forEach(q -> {
            List<C> none = run(em, q);
            assertThat(none).isEmpty();
        })));
        inRolledBack(db, em -> {
            insertLeftJoinRows(em);
            List<List<Long>> ids = queries.stream().map(q -> run(em, q).stream().map(C::id).toList()).toList();
            assertThat(ids.get(0)).containsExactly(EXTRA, EXTRA + 2);
            assertThat(ids.get(1)).containsExactly(EXTRA, EXTRA + 2);
            // Those rows are already required by the INNER join, so no branch can bring them back.
            assertThat(ids.get(2)).containsExactly(EXTRA + 2);
            assertThat(ids.get(3)).containsExactly(EXTRA + 2);
            // Plain SQL NOT: a customer with no order is NULL on the LEFT join, so it is excluded as Buyer is.
            assertThat(run(em, CUSTOMER_QUERY.where(NOT_PAID_LEFT)).stream().map(C::id).toList())
                    .containsExactly(EXTRA + 3);
        });
    }

    // ---- AC-FLT-10

    @TckTest
    void ac_flt_10_an_aliased_path_and_its_on_condition_work_inside_exists(TckDatabase db) {
        List<List<Long>> results = new ArrayList<>();
        List<List<C>> belowPath = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-10-exists-alias", ds -> inSession(ds, em -> {
            results.add(ids(em, ORDER_QUERY.where(EXISTS_P007_BIG)));
            results.add(ids(em, ORDER_QUERY.where(EXISTS_ALIASED_P007_BIG)));
            results.add(ids(em, ORDER_QUERY.where(EXISTS_ON_P007)));
            results.add(ids(em, ORDER_QUERY.columns(ColumnSet.of(ID, PRODUCT)).where(P007_AND_EXISTS_IN_OR)));
            belowPath.add(run(em, CUSTOMER_QUERY.where(NO_ITEM_BELOW_PATH)));
            belowPath.add(run(em, CUSTOMER_QUERY.where(AN_ITEM_BELOW_PATH)));
        }));
        assertThat(belowPath.get(0)).hasSize(TckFixture.CUSTOMERS);
        assertThat(belowPath.get(1)).isEmpty();
        List<Long> expected = jpql(db, "where exists (select 1 from OrderItemEntity i where i.order = o"
                + " and i.productCode = 'P007' and i.quantity >= 8)");
        // Some P007 orders have no item of quantity 8 or more, so a lost on(...) condition would show.
        assertThat(expected).isNotEmpty().hasSizeLessThan(jpql(db, "where exists (select 1 from OrderItemEntity i"
                + " where i.order = o and i.productCode = 'P007')").size());
        assertThat(results.get(0)).isEqualTo(expected);
        assertThat(results.get(1)).isEqualTo(expected);
        assertThat(results.get(2)).isEqualTo(expected);
        assertThat(results.get(3)).isEqualTo(jpql(db, "join o.items p where p.productCode = 'P007' and (o.status ="
                + " 'PAID' or exists (select 1 from OrderItemEntity i where i.order = o and i.quantity >= 5))"));
    }

    @TckTest
    void ac_flt_10_a_nested_exists_correlates_to_the_enclosing_exists_path(TckDatabase db) {
        List<Long> nested = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-10-nested-exists", ds -> inSession(ds, em -> nested.addAll(
                run(em, CUSTOMER_QUERY.where(NESTED_EXISTS)).stream().map(C::id).toList())));
        List<Long> expected = new ArrayList<>();
        List<Long> anyOrder = new ArrayList<>();
        inSession(db, em -> {
            // The item must belong to the order over 900, not to any order of the customer.
            expected.addAll(em.createQuery("select c.id from CustomerEntity c where exists (select 1 from OrderEntity"
                    + " o join o.items i where o.customer = c and o.total > 900 and i.quantity = 9)"
                    + " order by c.id", Long.class).getResultList());
            anyOrder.addAll(em.createQuery("select c.id from CustomerEntity c where exists (select 1 from OrderEntity"
                    + " o where o.customer = c and o.total > 900) and exists (select 1 from OrderEntity o2 join"
                    + " o2.items i where o2.customer = c and i.quantity = 9) order by c.id", Long.class)
                    .getResultList());
        });
        assertThat(expected).isNotEmpty().hasSizeLessThan(anyOrder.size());
        assertThat(nested).isEqualTo(expected);
    }

    @TckTest
    void ac_flt_10_a_nested_exists_on_the_same_path_correlates_to_the_same_child_row(TckDatabase db) {
        List<Long> nested = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-10-nested-exists-same-path", ds -> inSession(ds, em -> nested.addAll(
                run(em, CUSTOMER_QUERY.where(NESTED_EXISTS_SAME_PATH)).stream().map(C::id).toList())));
        List<Long> sameRow = new ArrayList<>();
        List<Long> anyRow = new ArrayList<>();
        inSession(db, em -> {
            sameRow.addAll(em.createQuery("select c.id from CustomerEntity c where exists (select 1 from OrderEntity"
                    + " o where o.customer = c and o.total > 900 and o.total < 950) order by c.id", Long.class)
                    .getResultList());
            anyRow.addAll(em.createQuery("select c.id from CustomerEntity c where exists (select 1 from OrderEntity"
                    + " o where o.customer = c and o.total > 900) and exists (select 1 from OrderEntity o2 where"
                    + " o2.customer = c and o2.total < 950) order by c.id", Long.class).getResultList());
        });
        // The inner exists tests the order the outer one found, not another order of the same customer.
        assertThat(sameRow).isNotEmpty().hasSizeLessThan(anyRow.size());
        assertThat(nested).isEqualTo(sameRow);
    }

    // ---- AC-FLT-11

    @TckTest
    void ac_flt_11_count_over_exists_equals_distinct_count_over_the_equivalent_join(TckDatabase db) {
        // The executor's count is M2.1; the counts are built here the way R-EXE-04 describes, from the built query.
        var viaExists = ORDER_QUERY.where(P007_VIA_EXISTS);
        var viaJoin = ORDER_QUERY.where(P007_VIA_JOIN);
        long[] counts = new long[3];
        SqlSnapshots.assertMatches(db, "flt-11-exists-count", ds -> inSession(ds, em -> {
            counts[0] = count(em, viaExists, false);
            counts[1] = count(em, viaJoin, true);
            counts[2] = count(em, viaJoin, false);
        }));
        List<Long> distinctJoined = new ArrayList<>();
        inSession(db, em -> {
            assertThat(ids(em, viaExists)).isEqualTo(ids(em, viaJoin).stream().distinct().toList());
            distinctJoined.addAll(ids(em, viaJoin).stream().distinct().toList());
        });
        assertThat(counts[0]).isEqualTo(counts[1]).isEqualTo(distinctJoined.size()).isPositive();
        // The join multiplies orders with several matching items; exists never does (R-FLT-12).
        assertThat(counts[2]).isGreaterThan(counts[1]);
    }

    // ---- add(...), the escape hatch (D-24)

    @TckTest
    void d_24_a_custom_predicate_is_anded_with_the_other_filters_and_shares_the_query_joins(TckDatabase db) {
        List<List<Long>> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-add-custom-predicate", ds -> inSession(ds, em -> results.add(ids(em,
                ORDER_QUERY.columns(ColumnSet.of(ID, CUSTOMER_NAME)).where(CUSTOM_PREDICATES)))));
        assertThat(results.get(0)).isNotEmpty().isEqualTo(jpql(db,
                "where o.total > 500 and o.customer.country = 'VN' and o.customer.name < 'Customer 0100'"));
    }

    @TckTest
    void ac_flt_09_a_custom_predicate_inside_an_or_branch_resolves_its_join_as_left(TckDatabase db) {
        var query = CUSTOMER_QUERY.where(CUSTOM_PREDICATE_IN_OR);
        SqlSnapshots.assertMatches(db, "flt-add-custom-in-or", ds -> inSession(ds, em ->
                assertThat(run(em, query)).isEmpty()));
        inRolledBack(db, em -> {
            insertCustomer(em, EXTRA, "Lonely");
            insertCustomer(em, EXTRA + 1, "Buyer");
            insertCustomer(em, EXTRA + 2, "Quiet");
            insertOrder(em, EXTRA, EXTRA + 1, "PAID");
            assertThat(run(em, query).stream().map(C::id).toList()).containsExactly(EXTRA, EXTRA + 1);
        });
    }

    @TckTest
    void ac_flt_02_a_custom_predicate_returning_null_throws_mq1305_when_the_query_is_built(TckDatabase db) {
        var query = ORDER_QUERY.where(f -> f.eq(STATUS, "PAID").add((ctx, cb) -> null)).build();
        inSession(db, em -> assertThatThrownBy(() -> query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable()))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1305))
                .hasMessageContaining("add(...)"));
    }

    // ---- helpers

    /** {@code count(root)}, or {@code count(distinct root)}, over the query's own joins and predicate. */
    private static long count(EntityManager em, ModelQuery.Builder<?, ?, ?> query, boolean distinct) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> criteria = query.build().buildQuery(cb, Phase.MODEL, portable()).query();
        Root<?> root = criteria.getRoots().iterator().next();
        criteria.multiselect(distinct ? cb.countDistinct(root) : cb.count(root)).orderBy(List.of());
        return em.createQuery(criteria).getSingleResult().get(0, Long.class);
    }

    private static List<Long> jpql(TckDatabase db, String where) {
        List<Long> ids = new ArrayList<>();
        inSession(db, em -> ids.addAll(em.createQuery("select o.id from OrderEntity o " + where + " order by o.id",
                Long.class).getResultList()));
        return ids;
    }

    /** Customers from {@link #EXTRA} with no order, a PAID order and a NEW one; neither order has an item. */
    static void insertLeftJoinRows(EntityManager em) {
        insertCustomer(em, EXTRA, "Lonely");         // no order, matches the name branch
        insertCustomer(em, EXTRA + 1, "Quiet");      // no order, matches nothing
        insertCustomer(em, EXTRA + 2, "Buyer");      // a PAID order
        insertCustomer(em, EXTRA + 3, "Browser");    // a NEW order only
        insertOrder(em, EXTRA, EXTRA + 2, "PAID");
        insertOrder(em, EXTRA + 1, EXTRA + 3, "NEW");
    }

    private static void insertCustomer(EntityManager em, long id, String name) {
        em.createNativeQuery(
                        "insert into customers (id, name, email, country, vip, created_at) values (?, ?, ?, ?, ?, ?)")
                .setParameter(1, id)
                .setParameter(2, name)
                .setParameter(3, name + "@example.test")
                .setParameter(4, "US")
                .setParameter(5, false)
                .setParameter(6, LocalDateTime.of(2020, 1, 1, 0, 0))
                .executeUpdate();
    }

    private static void insertOrder(EntityManager em, long id, long customerId, String status) {
        em.createNativeQuery("insert into orders (id, customer_id, status, total, placed_at) values (?, ?, ?, ?, ?)")
                .setParameter(1, id)
                .setParameter(2, customerId)
                .setParameter(3, status)
                .setParameter(4, BigDecimal.ONE)
                .setParameter(5, LocalDateTime.of(2020, 1, 1, 0, 0))
                .executeUpdate();
    }

    private static void inRolledBack(TckDatabase db, Consumer<EntityManager> work) {
        inSession(db, em -> {
            em.getTransaction().begin();
            try {
                work.accept(em);
            } finally {
                em.getTransaction().rollback();
            }
        });
    }

    private static void inSession(TckDatabase db, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(work::accept);
        }
    }

    private static void inSession(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(work::accept);
        }
    }

    private static List<Long> ids(EntityManager em, ModelQuery.Builder<?, ?, O> query) {
        return ids(em, query.build());
    }

    private static List<Long> ids(EntityManager em, ModelQuery<?, ?, O> query) {
        return run(em, query).stream().map(O::id).toList();
    }

    private static <V> List<V> run(EntityManager em, ModelQuery.Builder<?, ?, V> query) {
        return run(em, query.build());
    }

    private static <V> List<V> run(EntityManager em, ModelQuery<?, ?, V> query) {
        BuiltQuery<V> built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable());
        return em.createQuery(built.query()).getResultList().stream().map(built::map).toList();
    }
}
