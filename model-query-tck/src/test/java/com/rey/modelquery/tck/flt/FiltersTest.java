package com.rey.modelquery.tck.flt;

import static com.rey.modelquery.core.RenderOptions.portable;
import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.NullableSortEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.OrderStatus;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.hibernate.SessionFactory;

/** The {@code Filters} operators, their {@code Optional} forms and skip rules (spec api/12, api/10 R-COL-07). */
class FiltersTest {

    // ---- orders, with the customer joined

    record O(Long id, String status, BigDecimal total, LocalDateTime placedAt, String customerName) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);

    private static final ColumnField<O, OrderEntity, Long> ID = ColumnField.of(O.class, ORDERS, "id", Long.class);
    private static final ColumnField<O, OrderEntity, String> STATUS =
            ColumnField.of(O.class, ORDERS, "status", String.class);
    private static final ColumnField<O, OrderEntity, OrderStatus> STATUS_CODE =
            ColumnField.of(O.class, ORDERS, "statusCode", OrderStatus.class);
    private static final ColumnField<O, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(O.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<O, OrderEntity, LocalDateTime> PLACED_AT =
            ColumnField.of(O.class, ORDERS, "placedAt", LocalDateTime.class);
    private static final ColumnField<O, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(O.class, CUSTOMER, "name", String.class);
    /** Filter-only: no field of {@code O} reads it (R-COL-07). */
    private static final ColumnField<O, CustomerEntity, String> CUSTOMER_COUNTRY =
            ColumnField.of(O.class, CUSTOMER, "country", String.class);

    private static final RowMapper<O> O_MAPPER = row -> new O(row.get(ID), row.get(STATUS), row.get(TOTAL),
            row.get(PLACED_AT), row.get(CUSTOMER_NAME));
    private static final ModelQuery.Builder<OrderEntity, Object, O> ORDER_QUERY = ModelQuery.builder(ORDERS, O_MAPPER)
            .columns(ColumnSet.of(ID, STATUS, TOTAL, PLACED_AT, CUSTOMER_NAME))
            .orderBy(ID.asc());

    // ---- order items against their order, for column-against-column

    record I(Long id, Long orderId) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final TableField<OrderItemEntity, OrderEntity> ITEM_ORDER = TableField.join(ITEMS, "order", INNER);
    private static final ColumnField<I, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(I.class, ITEMS, "id", Long.class);
    private static final ColumnField<I, OrderEntity, Long> ITEM_ORDER_ID =
            ColumnField.of(I.class, ITEM_ORDER, "id", Long.class);
    private static final ModelQuery.Builder<OrderItemEntity, Object, I> ITEM_QUERY =
            ModelQuery.builder(ITEMS, row -> new I(row.get(ITEM_ID), row.get(ITEM_ORDER_ID)))
                    .columns(ColumnSet.of(ITEM_ID, ITEM_ORDER_ID))
                    .orderBy(ITEM_ID.asc());

    // ---- nullable columns

    record N(Long id, Integer sortInt, String sortText, LocalDateTime sortTs) {}

    private static final TableField<NullableSortEntity, NullableSortEntity> NULLABLE =
            TableField.root(NullableSortEntity.class);
    private static final ColumnField<N, NullableSortEntity, Long> N_ID =
            ColumnField.of(N.class, NULLABLE, "id", Long.class);
    private static final ColumnField<N, NullableSortEntity, Integer> N_INT =
            ColumnField.of(N.class, NULLABLE, "sortInt", Integer.class);
    private static final ColumnField<N, NullableSortEntity, String> N_TEXT =
            ColumnField.of(N.class, NULLABLE, "sortText", String.class);
    private static final ColumnField<N, NullableSortEntity, LocalDateTime> N_TS =
            ColumnField.of(N.class, NULLABLE, "sortTs", LocalDateTime.class);
    private static final ModelQuery.Builder<NullableSortEntity, Object, N> NULLABLE_QUERY =
            ModelQuery.builder(NULLABLE, row -> new N(row.get(N_ID), row.get(N_INT), row.get(N_TEXT), row.get(N_TS)))
                    .columns(ColumnSet.of(N_ID, N_INT, N_TEXT, N_TS))
                    .orderBy(N_ID.asc());

    private static final LocalDateTime BASE = LocalDateTime.of(2020, 1, 1, 0, 0);

    /**
     * One operator: its value form, its {@code Optional} form with a present value, its {@code Optional} form with an
     * empty value, and the rows it should keep. The two {@code Optional} forms are {@code null} for an operator that
     * takes no value.
     */
    record Case<V>(String name, UnaryOperator<Filters<V>> value, UnaryOperator<Filters<V>> present,
            UnaryOperator<Filters<V>> empty, Predicate<V> expected) {

        static <V> Case<V> of(String name, UnaryOperator<Filters<V>> value, UnaryOperator<Filters<V>> present,
                UnaryOperator<Filters<V>> empty, Predicate<V> expected) {
            return new Case<>(name, value, present, empty, expected);
        }
    }

    private static List<Case<O>> orderCases() {
        BigDecimal t500 = new BigDecimal("500.00");
        BigDecimal t100 = new BigDecimal("100.00");
        BigDecimal t200 = new BigDecimal("200.00");
        LocalDateTime from = BASE.plusDays(10);
        LocalDateTime to = BASE.plusDays(30);
        Pattern exact = Pattern.compile("Customer 0.1.*");
        return List.of(
                Case.<O>of("eq", f -> f.eq(STATUS, "PAID"), f -> f.eq(STATUS, Optional.of("PAID")),
                        f -> f.eq(STATUS, Optional.empty()), o -> o.status().equals("PAID")),
                Case.<O>of("eq through a converter", f -> f.eq(STATUS_CODE, OrderStatus.SHIPPED),
                        f -> f.eq(STATUS_CODE, Optional.of(OrderStatus.SHIPPED)),
                        f -> f.eq(STATUS_CODE, Optional.empty()), o -> o.status().equals("SHIPPED")),
                Case.<O>of("ne", f -> f.ne(STATUS, "PAID"), f -> f.ne(STATUS, Optional.of("PAID")),
                        f -> f.ne(STATUS, Optional.empty()), o -> !o.status().equals("PAID")),
                Case.<O>of("gt", f -> f.gt(TOTAL, t500), f -> f.gt(TOTAL, Optional.of(t500)),
                        f -> f.gt(TOTAL, Optional.empty()), o -> o.total().compareTo(t500) > 0),
                Case.<O>of("gte", f -> f.gte(TOTAL, t500), f -> f.gte(TOTAL, Optional.of(t500)),
                        f -> f.gte(TOTAL, Optional.empty()), o -> o.total().compareTo(t500) >= 0),
                Case.<O>of("lt", f -> f.lt(TOTAL, t100), f -> f.lt(TOTAL, Optional.of(t100)),
                        f -> f.lt(TOTAL, Optional.empty()), o -> o.total().compareTo(t100) < 0),
                Case.<O>of("lte", f -> f.lte(TOTAL, t100), f -> f.lte(TOTAL, Optional.of(t100)),
                        f -> f.lte(TOTAL, Optional.empty()), o -> o.total().compareTo(t100) <= 0),
                Case.<O>of("range", f -> f.gte(PLACED_AT, from).lt(PLACED_AT, to),
                        f -> f.range(PLACED_AT, Optional.of(from), Optional.of(to)),
                        f -> f.range(PLACED_AT, Optional.empty(), Optional.empty()),
                        o -> !o.placedAt().isBefore(from) && o.placedAt().isBefore(to)),
                Case.<O>of("range, lower bound only", f -> f.gte(PLACED_AT, from),
                        f -> f.range(PLACED_AT, Optional.of(from), Optional.empty()),
                        f -> f.range(PLACED_AT, Optional.empty(), Optional.empty()),
                        o -> !o.placedAt().isBefore(from)),
                Case.<O>of("range, upper bound only", f -> f.lt(PLACED_AT, to),
                        f -> f.range(PLACED_AT, Optional.empty(), Optional.of(to)),
                        f -> f.range(PLACED_AT, Optional.empty(), Optional.empty()),
                        o -> o.placedAt().isBefore(to)),
                Case.<O>of("between", f -> f.between(TOTAL, t100, t200),
                        f -> f.between(TOTAL, Optional.of(t100), Optional.of(t200)),
                        f -> f.between(TOTAL, Optional.empty(), Optional.empty()),
                        o -> o.total().compareTo(t100) >= 0 && o.total().compareTo(t200) <= 0),
                Case.<O>of("between, lower bound only", f -> f.gte(TOTAL, t100),
                        f -> f.between(TOTAL, Optional.of(t100), Optional.empty()),
                        f -> f.between(TOTAL, Optional.empty(), Optional.empty()),
                        o -> o.total().compareTo(t100) >= 0),
                Case.<O>of("between, upper bound only", f -> f.lte(TOTAL, t200),
                        f -> f.between(TOTAL, Optional.empty(), Optional.of(t200)),
                        f -> f.between(TOTAL, Optional.empty(), Optional.empty()),
                        o -> o.total().compareTo(t200) <= 0),
                Case.<O>of("in", f -> f.in(STATUS, List.of("NEW", "SHIPPED")),
                        f -> f.in(STATUS, Optional.of(List.of("NEW", "SHIPPED"))),
                        f -> f.in(STATUS, Optional.<List<String>>empty()),
                        o -> o.status().equals("NEW") || o.status().equals("SHIPPED")),
                Case.<O>of("notIn", f -> f.notIn(STATUS, List.of("NEW", "SHIPPED")),
                        f -> f.notIn(STATUS, Optional.of(List.of("NEW", "SHIPPED"))),
                        f -> f.notIn(STATUS, Optional.<List<String>>empty()),
                        o -> !o.status().equals("NEW") && !o.status().equals("SHIPPED")),
                Case.<O>of("like STARTS_WITH", f -> f.like(CUSTOMER_NAME, "Customer 00", LikeMode.STARTS_WITH),
                        f -> f.like(CUSTOMER_NAME, Optional.of("Customer 00"), LikeMode.STARTS_WITH),
                        f -> f.like(CUSTOMER_NAME, Optional.empty(), LikeMode.STARTS_WITH),
                        o -> o.customerName().startsWith("Customer 00")),
                Case.<O>of("like CONTAINS", f -> f.like(CUSTOMER_NAME, "r 012", LikeMode.CONTAINS),
                        f -> f.like(CUSTOMER_NAME, Optional.of("r 012"), LikeMode.CONTAINS),
                        f -> f.like(CUSTOMER_NAME, Optional.empty(), LikeMode.CONTAINS),
                        o -> o.customerName().contains("r 012")),
                Case.<O>of("like ENDS_WITH", f -> f.like(CUSTOMER_NAME, "77", LikeMode.ENDS_WITH),
                        f -> f.like(CUSTOMER_NAME, Optional.of("77"), LikeMode.ENDS_WITH),
                        f -> f.like(CUSTOMER_NAME, Optional.empty(), LikeMode.ENDS_WITH),
                        o -> o.customerName().endsWith("77")),
                Case.<O>of("like EXACT", f -> f.like(CUSTOMER_NAME, "Customer 0_1%", LikeMode.EXACT),
                        f -> f.like(CUSTOMER_NAME, Optional.of("Customer 0_1%"), LikeMode.EXACT),
                        f -> f.like(CUSTOMER_NAME, Optional.empty(), LikeMode.EXACT),
                        o -> exact.matcher(o.customerName()).matches()),
                Case.<O>of("likeIgnoreCase",
                        f -> f.likeIgnoreCase(CUSTOMER_NAME, "cUSTOMER 012", LikeMode.STARTS_WITH),
                        f -> f.likeIgnoreCase(CUSTOMER_NAME, Optional.of("cUSTOMER 012"), LikeMode.STARTS_WITH),
                        f -> f.likeIgnoreCase(CUSTOMER_NAME, Optional.empty(), LikeMode.STARTS_WITH),
                        o -> o.customerName().startsWith("Customer 012")),
                Case.<O>of("eqIgnoreCase", f -> f.eqIgnoreCase(STATUS, "pAiD"),
                        f -> f.eqIgnoreCase(STATUS, Optional.of("pAiD")),
                        f -> f.eqIgnoreCase(STATUS, Optional.empty()), o -> o.status().equals("PAID")));
    }

    private static List<Case<I>> itemCases() {
        var cases = new ArrayList<Case<I>>();
        for (Op op : Op.values()) {
            Predicate<I> expected = switch (op) {
                case EQ -> i -> i.id().equals(i.orderId());
                case NE -> i -> !i.id().equals(i.orderId());
                case LT -> i -> i.id() < i.orderId();
                case LTE -> i -> i.id() <= i.orderId();
                case GT -> i -> i.id() > i.orderId();
                case GTE -> i -> i.id() >= i.orderId();
            };
            cases.add(Case.of("compare " + op, f -> f.compare(ITEM_ID, op, ITEM_ORDER_ID), null, null, expected));
        }
        return cases;
    }

    private static List<Case<N>> nullableCases() {
        LocalDateTime ts = BASE.plusDays(7);
        return List.of(
                Case.<N>of("isNull", f -> f.isNull(N_INT), f -> f.isNull(N_INT, Optional.of(true)),
                        f -> f.isNull(N_INT, Optional.empty()), n -> n.sortInt() == null),
                Case.<N>of("isNotNull", f -> f.isNotNull(N_INT), f -> f.isNull(N_INT, Optional.of(false)),
                        f -> f.isNull(N_INT, Optional.empty()), n -> n.sortInt() != null),
                Case.<N>of("ne over NULLs", f -> f.ne(N_INT, 7), f -> f.ne(N_INT, Optional.of(7)),
                        f -> f.ne(N_INT, Optional.empty()), n -> !Objects.equals(n.sortInt(), 7)),
                Case.<N>of("notIn over NULLs", f -> f.notIn(N_TEXT, List.of("t01", "t02")),
                        f -> f.notIn(N_TEXT, Optional.of(List.of("t01", "t02"))),
                        f -> f.notIn(N_TEXT, Optional.<List<String>>empty()),
                        n -> n.sortText() == null || !List.of("t01", "t02").contains(n.sortText())),
                Case.<N>of("eq on a timestamp", f -> f.eq(N_TS, ts), f -> f.eq(N_TS, Optional.of(ts)),
                        f -> f.eq(N_TS, Optional.empty()), n -> ts.equals(n.sortTs())));
    }

    // ---- AC-FLT-01

    @TckTest
    void ac_flt_01_every_operator_keeps_the_same_rows_in_value_and_optional_form(TckDatabase db) {
        // The snapshot holds the value form of every operator: each value is a "?" (R-FLT-08).
        SqlSnapshots.assertMatches(db, "flt-01-operators", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    orderCases().forEach(c -> run(em, ORDER_QUERY.where(c.value())));
                    itemCases().forEach(c -> run(em, ITEM_QUERY.where(c.value())));
                    nullableCases().forEach(c -> run(em, NULLABLE_QUERY.where(c.value())));
                });
            }
        });
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                check(em, ORDER_QUERY, O::id, orderCases());
                check(em, ITEM_QUERY, I::id, itemCases());
                check(em, NULLABLE_QUERY, N::id, nullableCases());
            });
        }
    }

    private static <E, V> void check(EntityManager em, ModelQuery.Builder<E, Object, V> base, Function<V, Long> id,
            List<Case<V>> cases) {
        List<V> all = run(em, base);
        List<Long> allIds = all.stream().map(id).toList();
        for (Case<V> c : cases) {
            List<Long> expected = all.stream().filter(c.expected()).map(id).toList();
            List<Long> value = run(em, base.where(c.value())).stream().map(id).toList();
            assertThat(value).as(c.name() + ": value form").isEqualTo(expected);
            // A case that keeps no row or every row would pass against a broken operator.
            assertThat(expected).as(c.name() + ": narrows the fixture").isNotEmpty().hasSizeLessThan(all.size());
            if (c.present() != null) {
                List<Long> present = run(em, base.where(c.present())).stream().map(id).toList();
                assertThat(present).as(c.name() + ": Optional form").isEqualTo(value);
                ModelQuery<E, Object, V> skipped = base.where(c.empty()).build();
                assertThat(skipped.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable()).query()
                                .getRestriction())
                        .as(c.name() + ": empty Optional renders no predicate").isNull();
                assertThat(run(em, skipped).stream().map(id).toList()).as(c.name() + ": empty Optional")
                        .isEqualTo(allIds);
            }
        }
    }

    // ---- AC-FLT-05

    @TckTest
    void ac_flt_05_empty_in_returns_no_rows_and_empty_not_in_returns_every_row(TckDatabase db) {
        List<List<O>> results = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "flt-05-empty-sets", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    results.add(run(em, ORDER_QUERY.where(f -> f.in(STATUS, List.of()))));
                    results.add(run(em, ORDER_QUERY.where(f -> f.notIn(STATUS, List.of()))));
                });
            }
        });
        assertThat(results.get(0)).isEmpty();
        assertThat(results.get(1)).hasSize(TckFixture.ORDERS);
    }

    // ---- AC-FLT-06

    @TckTest
    void ac_flt_06_ne_and_not_in_include_rows_where_the_column_is_null(TckDatabase db) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                List<N> all = run(em, NULLABLE_QUERY);
                long nullInts = all.stream().filter(n -> n.sortInt() == null).count();
                long nullTexts = all.stream().filter(n -> n.sortText() == null).count();
                assertThat(nullInts).isPositive();
                assertThat(nullTexts).isPositive();

                List<N> ne = run(em, NULLABLE_QUERY.where(f -> f.ne(N_INT, 7)));
                assertThat(ne.stream().filter(n -> n.sortInt() == null).count()).isEqualTo(nullInts);
                List<N> notIn = run(em, NULLABLE_QUERY.where(f -> f.notIn(N_TEXT, List.of("t01"))));
                assertThat(notIn.stream().filter(n -> n.sortText() == null).count()).isEqualTo(nullTexts);

                // The strict SQL meaning is one isNotNull away.
                List<N> strict = run(em, NULLABLE_QUERY.where(f -> f.ne(N_INT, 7).isNotNull(N_INT)));
                assertThat(strict).hasSize(ne.size() - (int) nullInts).allSatisfy(n -> assertThat(n.sortInt())
                        .isNotNull().isNotEqualTo(7));
            });
        }
    }

    // ---- AC-FLT-07

    @TckTest
    void ac_flt_07_like_matches_percent_underscore_and_backslash_literally(TckDatabase db) {
        long first = 900_001L;
        List<String> texts = List.of("50%off", "50xoff", "a_b", "axb", "c\\d", "cd", "A_B");
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    for (int i = 0; i < texts.size(); i++) {
                        em.createNativeQuery("insert into nullable_sort_rows (id, sort_text) values (?, ?)")
                                .setParameter(1, first + i)
                                .setParameter(2, texts.get(i))
                                .executeUpdate();
                    }
                    Function<UnaryOperator<Filters<N>>, List<String>> matching = filter -> run(em,
                            NULLABLE_QUERY.where(f -> filter.apply(f.gte(N_ID, first))))
                            .stream().map(N::sortText).toList();

                    assertThat(matching.apply(f -> f.like(N_TEXT, "50%", LikeMode.CONTAINS))).containsExactly("50%off");
                    assertThat(matching.apply(f -> f.like(N_TEXT, "a_", LikeMode.STARTS_WITH)))
                            .containsExactly("a_b");
                    assertThat(matching.apply(f -> f.like(N_TEXT, "_b", LikeMode.ENDS_WITH))).containsExactly("a_b");
                    assertThat(matching.apply(f -> f.like(N_TEXT, "c\\d", LikeMode.CONTAINS)))
                            .containsExactly("c\\d");
                    assertThat(matching.apply(f -> f.likeIgnoreCase(N_TEXT, "a_B", LikeMode.CONTAINS)))
                            .containsExactly("a_b", "A_B");
                    // EXACT passes the pattern through, wildcards included.
                    assertThat(matching.apply(f -> f.like(N_TEXT, "a_b", LikeMode.EXACT)))
                            .containsExactly("a_b", "axb");
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }

    // ---- AC-COL-07

    @TckTest
    void ac_col_07_a_filter_only_column_filters_adds_no_join_when_skipped_and_never_reaches_the_model(
            TckDatabase db) {
        record Id(Long id) {}
        var root = TableField.root(OrderEntity.class);
        var customer = TableField.join(root, "customer", INNER);
        var id = ColumnField.of(Id.class, root, "id", Long.class);
        var country = ColumnField.of(Id.class, customer, "country", String.class);
        var query = ModelQuery.builder(root, row -> new Id(row.get(id))).columns(ColumnSet.of(id)).orderBy(id.asc());
        List<List<Id>> results = new ArrayList<>();
        List<Long> expected = new ArrayList<>();
        SqlSnapshots.assertMatches(db, "col-07-filter-only-column", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    results.add(run(em, query.where(f -> f.eq(country, Optional.of("VN")))));
                    // Skipped: the customer join is never resolved, so the SQL reads orders alone.
                    ModelQuery<OrderEntity, Object, Id> skipped =
                            query.where(f -> f.eq(country, Optional.empty())).build();
                    assertThat(skipped.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable()).query().getRoots())
                            .allSatisfy(r -> assertThat(r.getJoins()).isEmpty());
                    results.add(run(em, skipped));
                    // Even when selected, the model has no field for it and is unchanged.
                    results.add(run(em, query.columns(ColumnSet.of(id, country))
                            .where(f -> f.eq(country, Optional.of("VN")))));
                });
            }
        });
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> expected.addAll(em.createQuery(
                    "select o.id from OrderEntity o where o.customer.country = 'VN' order by o.id", Long.class)
                    .getResultList()));
        }
        assertThat(expected).isNotEmpty().hasSizeLessThan(TckFixture.ORDERS);
        assertThat(results.get(0).stream().map(Id::id).toList()).isEqualTo(expected);
        assertThat(results.get(1)).hasSize(TckFixture.ORDERS);
        assertThat(results.get(2)).isEqualTo(results.get(0));
    }

    private static <V> List<V> run(EntityManager em, ModelQuery.Builder<?, ?, V> query) {
        return run(em, query.build());
    }

    private static <V> List<V> run(EntityManager em, ModelQuery<?, ?, V> query) {
        BuiltQuery<V> built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, portable());
        return em.createQuery(built.query()).getResultList().stream().map(built::map).toList();
    }
}
