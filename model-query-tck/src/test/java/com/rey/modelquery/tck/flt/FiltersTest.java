package com.rey.modelquery.tck.flt;

import static com.rey.modelquery.core.RenderOptions.portable;
import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
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
import java.util.Locale;
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

    static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);

    static final ColumnField<O, OrderEntity, Long> ID = ColumnField.of(O.class, ORDERS, "id", Long.class);
    static final ColumnField<O, OrderEntity, String> STATUS =
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
    static final ModelQuery.Builder<OrderEntity, Object, O> ORDER_QUERY = ModelQuery.builder(ORDERS, O_MAPPER)
            .select(SelectSet.of(ID, STATUS, TOTAL, PLACED_AT, CUSTOMER_NAME))
            .orderBy(ID.asc());

    // ---- order items against their order, for column-against-column

    record I(Long id, Long orderId) {}

    static final TableField<OrderItemEntity, OrderItemEntity> ITEMS = TableField.root(OrderItemEntity.class);
    private static final TableField<OrderItemEntity, OrderEntity> ITEM_ORDER = TableField.join(ITEMS, "order", INNER);
    static final ColumnField<I, OrderItemEntity, Long> ITEM_ID =
            ColumnField.of(I.class, ITEMS, "id", Long.class);
    private static final ColumnField<I, OrderEntity, Long> ITEM_ORDER_ID =
            ColumnField.of(I.class, ITEM_ORDER, "id", Long.class);
    static final ModelQuery.Builder<OrderItemEntity, Object, I> ITEM_QUERY =
            ModelQuery.builder(ITEMS, row -> new I(row.get(ITEM_ID), row.get(ITEM_ORDER_ID)))
                    .select(SelectSet.of(ITEM_ID, ITEM_ORDER_ID))
                    .orderBy(ITEM_ID.asc());

    // ---- nullable columns

    record N(Long id, Integer sortInt, String sortText, LocalDateTime sortTs) {}

    static final TableField<NullableSortEntity, NullableSortEntity> NULLABLE =
            TableField.root(NullableSortEntity.class);
    static final ColumnField<N, NullableSortEntity, Long> N_ID =
            ColumnField.of(N.class, NULLABLE, "id", Long.class);
    static final ColumnField<N, NullableSortEntity, Integer> N_INT =
            ColumnField.of(N.class, NULLABLE, "sortInt", Integer.class);
    static final ColumnField<N, NullableSortEntity, String> N_TEXT =
            ColumnField.of(N.class, NULLABLE, "sortText", String.class);
    private static final ColumnField<N, NullableSortEntity, LocalDateTime> N_TS =
            ColumnField.of(N.class, NULLABLE, "sortTs", LocalDateTime.class);
    static final ModelQuery.Builder<NullableSortEntity, Object, N> NULLABLE_QUERY =
            ModelQuery.builder(NULLABLE, row -> new N(row.get(N_ID), row.get(N_INT), row.get(N_TEXT), row.get(N_TS)))
                    .select(SelectSet.of(N_ID, N_INT, N_TEXT, N_TS))
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

    /** A named filter of this group, which the write/read parity check also runs (api/14 AC-WRT-07). */
    record Fixture<V>(String name, UnaryOperator<Filters<V>> where) {}

    static final UnaryOperator<Filters<O>> EMPTY_IN = f -> f.in(STATUS, List.of());
    static final UnaryOperator<Filters<O>> EMPTY_NOT_IN = f -> f.notIn(STATUS, List.of());
    static final UnaryOperator<Filters<N>> NE_SEVEN = f -> f.ne(N_INT, 7);
    static final UnaryOperator<Filters<N>> NOT_IN_T01 = f -> f.notIn(N_TEXT, List.of("t01"));
    /** The strict SQL meaning of {@link #NE_SEVEN}. */
    static final UnaryOperator<Filters<N>> STRICT_NE_SEVEN = f -> f.ne(N_INT, 7).isNotNull(N_INT);
    /** Seven values, one of them no order's id. */
    static final List<Long> SPLIT_IDS = List.of(13L, 2L, 7L, 3L, 999_999L, 11L, 5L);
    static final UnaryOperator<Filters<O>> IN_SPLIT_IDS = f -> f.in(ID, SPLIT_IDS);
    /** Ten of sortInt's 50 values; none is a multiple of 5, where NULLs fall. */
    static final List<Integer> SPLIT_EXCLUDED = List.of(1, 2, 3, 4, 6, 7, 8, 9, 11, 12);
    static final UnaryOperator<Filters<N>> NOT_IN_SPLIT_EXCLUDED = f -> f.notIn(N_INT, SPLIT_EXCLUDED);
    /** The first id of the rows {@link #insertLikeRows} adds. */
    static final long LIKE_FIRST = 900_001L;
    private static final List<String> LIKE_TEXTS = List.of("50%off", "50xoff", "a_b", "axb", "c\\d", "cd", "A_B");

    /** The order filters of the tests after AC-FLT-01. */
    static List<Fixture<O>> orderFixtures() {
        return List.of(new Fixture<>("empty in", EMPTY_IN), new Fixture<>("empty notIn", EMPTY_NOT_IN),
                new Fixture<>("in above the IN-list limit", IN_SPLIT_IDS));
    }

    /** The nullable-row filters of the tests after AC-FLT-01; the LIKE ones need {@link #insertLikeRows}. */
    static List<Fixture<N>> nullableFixtures() {
        var fixtures = new ArrayList<Fixture<N>>(List.of(new Fixture<>("ne keeps NULLs", NE_SEVEN),
                new Fixture<>("notIn keeps NULLs", NOT_IN_T01), new Fixture<>("strict ne", STRICT_NE_SEVEN),
                new Fixture<>("notIn above the IN-list limit", NOT_IN_SPLIT_EXCLUDED)));
        fixtures.addAll(likeFixtures());
        return fixtures;
    }

    /** LIKE over {@code %}, {@code _} and {@code \}, each over the rows of {@link #insertLikeRows} only. */
    static List<Fixture<N>> likeFixtures() {
        return List.<Fixture<N>>of(
                        new Fixture<>("like %", f -> f.like(N_TEXT, "50%", LikeMode.CONTAINS)),
                        new Fixture<>("like _ at the start", f -> f.like(N_TEXT, "a_", LikeMode.STARTS_WITH)),
                        new Fixture<>("like _ at the end", f -> f.like(N_TEXT, "_b", LikeMode.ENDS_WITH)),
                        new Fixture<>("like \\", f -> f.like(N_TEXT, "c\\d", LikeMode.CONTAINS)),
                        new Fixture<>("likeIgnoreCase _", f -> f.likeIgnoreCase(N_TEXT, "a_B", LikeMode.CONTAINS)),
                        new Fixture<>("like EXACT", f -> f.like(N_TEXT, "a_b", LikeMode.EXACT)))
                .stream()
                .map(like -> new Fixture<N>(like.name(), f -> like.where().apply(f.gte(N_ID, LIKE_FIRST))))
                .toList();
    }

    /** Adds rows from {@link #LIKE_FIRST} whose text holds LIKE's wildcards and the escape character. */
    static void insertLikeRows(EntityManager em) {
        for (int i = 0; i < LIKE_TEXTS.size(); i++) {
            em.createNativeQuery("insert into nullable_sort_rows (id, sort_text) values (?, ?)")
                    .setParameter(1, LIKE_FIRST + i)
                    .setParameter(2, LIKE_TEXTS.get(i))
                    .executeUpdate();
        }
    }

    static List<Case<O>> orderCases() {
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

    static List<Case<I>> itemCases() {
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

    static List<Case<N>> nullableCases() {
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
                    results.add(run(em, ORDER_QUERY.where(EMPTY_IN)));
                    results.add(run(em, ORDER_QUERY.where(EMPTY_NOT_IN)));
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

                List<N> ne = run(em, NULLABLE_QUERY.where(NE_SEVEN));
                assertThat(ne.stream().filter(n -> n.sortInt() == null).count()).isEqualTo(nullInts);
                List<N> notIn = run(em, NULLABLE_QUERY.where(NOT_IN_T01));
                assertThat(notIn.stream().filter(n -> n.sortText() == null).count()).isEqualTo(nullTexts);

                // The strict SQL meaning is one isNotNull away.
                List<N> strict = run(em, NULLABLE_QUERY.where(STRICT_NE_SEVEN));
                assertThat(strict).hasSize(ne.size() - (int) nullInts).allSatisfy(n -> assertThat(n.sortInt())
                        .isNotNull().isNotEqualTo(7));
            });
        }
    }

    // ---- AC-FLT-08

    /** An IN-list limit small enough that a handful of values shows the chunks (R-FLT-09). */
    private static final RenderOptions THREE_PER_LIST = RenderOptions.of(3, 100);
    private static final Pattern IN_LIST = Pattern.compile("\\bin \\(");

    @TckTest
    void ac_flt_08_an_in_list_above_the_limit_is_split_and_returns_the_same_rows_as_an_unsplit_one(TckDatabase db) {
        // Seven values: chunks of 3, 3 and 1.
        var q = ORDER_QUERY.where(IN_SPLIT_IDS).build();
        List<List<O>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "flt-08-in-chunks", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    results.add(run(em, q, THREE_PER_LIST));
                    results.add(run(em, q, portable()));
                });
            }
        });
        assertThat(inLists(sql.get(0))).isEqualTo(3);
        assertThat(inLists(sql.get(1))).isEqualTo(1);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(results.get(0)).extracting(O::id).containsExactly(2L, 3L, 5L, 7L, 11L, 13L);
    }

    @TckTest
    void ac_flt_08_not_in_above_the_limit_is_an_and_of_chunks_that_still_keeps_null_rows(TckDatabase db) {
        // Ten values, in chunks of 3, 3, 3 and 1; every fifth row's sortInt is NULL.
        List<Integer> excluded = SPLIT_EXCLUDED;
        var q = NULLABLE_QUERY.where(NOT_IN_SPLIT_EXCLUDED).build();
        List<List<N>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.assertMatches(db, "flt-08-not-in-chunks", ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> {
                    results.add(run(em, q, THREE_PER_LIST));
                    results.add(run(em, q, portable()));
                });
            }
        });
        assertThat(inLists(sql.get(0))).isEqualTo(4);
        assertThat(inLists(sql.get(1))).isEqualTo(1);
        List<N> split = results.get(0);
        assertThat(split).isEqualTo(results.get(1));
        assertThat(split).noneMatch(n -> n.sortInt() != null && excluded.contains(n.sortInt()));
        assertThat(split.stream().filter(n -> n.sortInt() == null).count())
                .isEqualTo(TckFixture.NULLABLE_SORT_ROWS / 5);
        // No excluded value is a multiple of 5, where NULLs fall, so each holds 1 in 50 rows.
        int perValue = TckFixture.NULLABLE_SORT_ROWS / 50;
        assertThat(split).hasSize(TckFixture.NULLABLE_SORT_ROWS - excluded.size() * perValue);
    }

    private static long inLists(String statement) {
        return IN_LIST.matcher(statement.toLowerCase(Locale.ROOT)).results().count();
    }

    // ---- AC-FLT-07

    @TckTest
    void ac_flt_07_like_matches_percent_underscore_and_backslash_literally(TckDatabase db) {
        // In likeFixtures() order; EXACT passes the pattern through, wildcards included.
        List<List<String>> expected = List.of(List.of("50%off"), List.of("a_b"), List.of("a_b"), List.of("c\\d"),
                List.of("a_b", "A_B"), List.of("a_b", "axb"));
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    insertLikeRows(em);
                    List<Fixture<N>> likes = likeFixtures();
                    for (int i = 0; i < likes.size(); i++) {
                        assertThat(run(em, NULLABLE_QUERY.where(likes.get(i).where())).stream().map(N::sortText))
                                .as(likes.get(i).name()).containsExactlyElementsOf(expected.get(i));
                    }
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
        var query = ModelQuery.builder(root, row -> new Id(row.get(id))).select(SelectSet.of(id)).orderBy(id.asc());
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
                    results.add(run(em, query.select(SelectSet.of(id, country))
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
        return run(em, query, portable());
    }

    private static <V> List<V> run(EntityManager em, ModelQuery<?, ?, V> query, RenderOptions options) {
        BuiltQuery<V> built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options);
        return em.createQuery(built.query()).getResultList().stream().map(built::map).toList();
    }
}
