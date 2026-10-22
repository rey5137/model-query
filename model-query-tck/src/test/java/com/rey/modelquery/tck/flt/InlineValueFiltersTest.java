package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.ConstantRowEntity;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderStatus;
import com.rey.modelquery.tck.col.PlainOrderItemEntity;
import com.rey.modelquery.tck.fch.FetchTestSupport;
import com.rey.modelquery.tck.harness.InlineValueHandling;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import org.hibernate.SessionFactory;

/**
 * Filter values under {@code hibernate.criteria.value_handling_mode=inline}: each filter path of the value-path audit
 * binds its value as a parameter, so no input reaches the SQL text, and returns the rows it returns under the default
 * setting; a test provider with no operand binder binds a {@code String} and refuses an enum with {@code MQ1502}
 * before any statement (spec api/12 R-FLT-08, R-FLT-21, vendor/40 R-VND-28, D-177).
 */
class InlineValueFiltersTest {

    record O(Long id) {}

    record C(Long id) {}

    record I(Long orderId) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER =
            TableField.join(ORDERS, "customer", JoinType.INNER);
    private static final ColumnField<O, OrderEntity, Long> ID = ColumnField.of(O.class, ORDERS, "id", Long.class);
    private static final ColumnField<O, OrderEntity, String> STATUS =
            ColumnField.of(O.class, ORDERS, "status", String.class);
    /** The status through a JPA {@code AttributeConverter}, so its value binds only typed by the mapping. */
    private static final ColumnField<O, OrderEntity, OrderStatus> STATUS_CODE =
            ColumnField.of(O.class, ORDERS, "statusCode", OrderStatus.class);
    private static final ColumnField<O, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(O.class, ORDERS, "total", BigDecimal.class);
    private static final ColumnField<O, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(O.class, CUSTOMER, "name", String.class);
    private static final ModelQuery.Builder<OrderEntity, Object, O> ORDER_QUERY =
            ModelQuery.builder(ORDERS, row -> new O(row.get(ID))).select(SelectSet.of(ID)).orderBy(ID.asc());

    private static final TableField<PlainOrderItemEntity, PlainOrderItemEntity> ITEMS =
            TableField.root(PlainOrderItemEntity.class);
    private static final ColumnField<I, PlainOrderItemEntity, Long> ITEM_ORDER_ID =
            ColumnField.of(I.class, ITEMS, "orderId", Long.class);
    private static final ColumnField<I, PlainOrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(I.class, ITEMS, "productCode", String.class);
    private static final SubSelect<I, Long> P007_ITEMS =
            SubSelect.of(ITEM_ORDER_ID).where(f -> f.eq(ITEM_PRODUCT, "P007"));

    private static final TableField<ConstantRowEntity, ConstantRowEntity> ROWS =
            TableField.root(ConstantRowEntity.class);
    private static final ColumnField<C, ConstantRowEntity, Long> ROW_ID =
            ColumnField.of(C.class, ROWS, "id", Long.class);
    /** Stored as its first letter through an {@code AttributeConverter}. */
    private static final ColumnField<C, ConstantRowEntity, ConstantStatus> STATE =
            ColumnField.of(C.class, ROWS, "state", ConstantStatus.class);
    /** A {@code Boolean} stored as {@code 'Y'}/{@code 'N'} through an {@code AttributeConverter}. */
    private static final ColumnField<C, ConstantRowEntity, Boolean> ACTIVE =
            ColumnField.of(C.class, ROWS, "active", Boolean.class);
    /** An {@code @Enumerated(STRING)} enum. */
    private static final ColumnField<C, ConstantRowEntity, ConstantStatus> ROW_STATUS =
            ColumnField.of(C.class, ROWS, "status", ConstantStatus.class);
    private static final ModelQuery.Builder<ConstantRowEntity, Object, C> ROW_QUERY =
            ModelQuery.builder(ROWS, row -> new C(row.get(ROW_ID))).select(SelectSet.of(ROW_ID))
                    .orderBy(ROW_ID.asc());

    /** One audited path: its filter and the input values that must not reach the SQL text. */
    record Case<M>(String path, UnaryOperator<Filters<M>> where, List<Object> inputs) {}

    static final List<Case<O>> ORDER_CASES = List.of(
            new Case<O>("comparison-eq", f -> f.eq(STATUS, "PAID"), List.of("PAID")),
            new Case<O>("comparison-ne", f -> f.ne(STATUS, "PAID"), List.of("PAID")),
            new Case<O>("comparison-gt", f -> f.gt(TOTAL, new BigDecimal("500.00")), List.of(500)),
            new Case<O>("comparison-ge", f -> f.gte(TOTAL, new BigDecimal("500.00")), List.of(500)),
            new Case<O>("comparison-lt", f -> f.lt(TOTAL, new BigDecimal("100.00")), List.of(100)),
            new Case<O>("comparison-le", f -> f.lte(TOTAL, new BigDecimal("100.00")), List.of(100)),
            new Case<O>("comparison-range between",
                    f -> f.between(TOTAL, new BigDecimal("100.00"), new BigDecimal("200.00")), List.of(100, 200)),
            new Case<O>("comparison-range both bounds",
                    f -> f.range(TOTAL, Optional.of(new BigDecimal("100.00")), Optional.of(new BigDecimal("200.00"))),
                    List.of(100, 200)),
            new Case<O>("comparison-range one-sided lower",
                    f -> f.range(TOTAL, Optional.of(new BigDecimal("500.00")), Optional.empty()), List.of(500)),
            new Case<O>("comparison-range one-sided upper",
                    f -> f.range(TOTAL, Optional.empty(), Optional.of(new BigDecimal("100.00"))), List.of(100)),
            new Case<O>("filter-membership", f -> f.in(STATUS, List.of("NEW", "SHIPPED")),
                    List.of("NEW", "SHIPPED")),
            new Case<O>("filter-membership notIn", f -> f.notIn(STATUS, List.of("NEW", "SHIPPED")),
                    List.of("NEW", "SHIPPED")),
            new Case<O>("eq-ignore-case", f -> f.eqIgnoreCase(STATUS, "Paid"), List.of("paid")),
            new Case<O>("like", f -> f.like(CUSTOMER_NAME, "Customer 01", LikeMode.STARTS_WITH),
                    List.of("Customer 01")),
            new Case<O>("ignore-case", f -> f.likeIgnoreCase(CUSTOMER_NAME, "CUSTOMER 012", LikeMode.STARTS_WITH),
                    List.of("customer 012")),
            new Case<O>("subquery-filter in", f -> f.in(ID, P007_ITEMS), List.of("P007")),
            new Case<O>("subquery-filter exists", f -> f.exists(P007_ITEMS,
                    (s, outer) -> s.compare(ITEM_ORDER_ID, Op.EQ, outer.column(ID))), List.of("P007")),
            new Case<O>("converted-enum STATUS_CODE", f -> f.eq(STATUS_CODE, OrderStatus.PAID), List.of("PAID")));

    static final List<Case<C>> ROW_CASES = List.of(
            new Case<C>("converted-enum", f -> f.eq(STATE, ConstantStatus.CLOSED), List.of("C")),
            new Case<C>("converted-enum in", f -> f.in(STATE, List.of(ConstantStatus.PENDING, ConstantStatus.FAILED)),
                    List.of("P", "F")),
            new Case<C>("converted-Boolean", f -> f.eq(ACTIVE, true), List.of("Y")),
            new Case<C>("enumerated STRING", f -> f.eq(ROW_STATUS, ConstantStatus.PENDING), List.of("PENDING")));

    // ---- AC-FLT-35

    @TckTest
    void ac_flt_35_each_filter_path_binds_its_value_and_returns_the_default_settings_rows(TckDatabase db) {
        List<List<Long>> defaults = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            ORDER_CASES.forEach(c -> defaults.add(orderIds(sf, ModelQueryConfig.defaults(), c.where())));
            ROW_CASES.forEach(c -> defaults.add(rowIds(sf, c.where())));
        }
        // Each default run returns rows, so matching them is no accident of an empty result.
        assertThat(defaults).allSatisfy(ids -> assertThat(ids).isNotEmpty());
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            int i = 0;
            for (Case<O> c : ORDER_CASES) {
                inline.watch(c.inputs(), List.of());
                assertThat(orderIds(inline.factory(), ModelQueryConfig.defaults(), c.where())).as(c.path())
                        .isEqualTo(defaults.get(i++));
                assertBound(inline, c.path());
            }
            for (Case<C> c : ROW_CASES) {
                inline.watch(c.inputs(), List.of());
                assertThat(rowIds(inline.factory(), c.where())).as(c.path()).isEqualTo(defaults.get(i++));
                assertBound(inline, c.path());
            }
        }
    }

    @TckTest
    void ac_flt_35_later_and_chunked_in_elements_bind(TckDatabase db) {
        // Three values per IN list, so seven ids render three chunks; 999999 matches no row (R-FLT-09).
        ModelQueryConfig chunked = ModelQueryConfig.defaults().vendorProfiles(List.of(FetchTestSupport.limited(db, 3)));
        UnaryOperator<Filters<O>> where = f -> f.in(ID, List.of(13L, 2L, 7L, 3L, 999_999L, 11L, 5L));
        List<Long> expected;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            expected = orderIds(sf, chunked, where);
        }
        assertThat(expected).containsExactly(2L, 3L, 5L, 7L, 11L, 13L);
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            inline.watch(13L, 11L, 999_999L);
            assertThat(orderIds(inline.factory(), chunked, where)).isEqualTo(expected);
            assertThat(inline.statements()).singleElement()
                    .satisfies(sql -> assertThat(sql.split(" in \\(", -1)).hasSize(4))
                    .satisfies(sql -> assertThat(sql.chars().filter(ch -> ch == '?').count()).isEqualTo(7));
        }
    }

    @TckTest
    void ac_flt_35_structural_literals_are_classified_not_failed(TckDatabase db) {
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            // like's escape character is definition text, so is a user's typed constant; the value is input.
            inline.watch(List.of("Customer 01"), List.of("'cancelled'"));
            List<Long> ids = orderIds(inline.factory(), ModelQueryConfig.defaults(), f -> f
                    .like(CUSTOMER_NAME, "Customer 01", LikeMode.STARTS_WITH)
                    .compare(STATUS, Op.NE, Expr.constant(STATUS, "CANCELLED")));
            assertThat(ids).isNotEmpty();
            assertThat(inline.structural()).anySatisfy(literal -> assertThat(literal).startsWith("escape '"))
                    .contains("'cancelled'");
            // A watched value inlined fails the statement before it runs.
            inline.watch(List.of("CANCELLED"), List.of());
            assertThatThrownBy(() -> orderIds(inline.factory(), ModelQueryConfig.defaults(),
                    f -> f.compare(STATUS, Op.NE, Expr.constant(STATUS, "CANCELLED"))))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("'cancelled'");
        }
    }

    /**
     * The inspector reads every literal of a statement, so a value the audit's marker regex missed, a {@code between}
     * upper bound, a function's argument and a later {@code in} element, fails it when inlined: a criteria value given
     * without a parameter reaches the SQL text as a literal under the setting.
     */
    @TckTest
    void ac_flt_35_the_inspector_catches_a_between_bound_a_function_argument_and_a_later_in_element(TckDatabase db) {
        record Probe(String form, Object watched, BiFunction<CriteriaBuilder, Root<OrderEntity>, Predicate> where) {}
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            for (Probe probe : List.of(
                    new Probe("between", 417, (cb, root) -> cb.between(root.get("total"), new BigDecimal("0.01"),
                            new BigDecimal("417.00"))),
                    new Probe("function argument", "zq-none", (cb, root) -> cb.equal(
                            cb.coalesce(root.<String>get("status"), "ZQ-NONE"), "PAID")),
                    new Probe("later in element", 417L, (cb, root) -> root.get("id").in(1L, 2L, 417L)))) {
                inline.watch(probe.watched());
                assertThatThrownBy(() -> inline.factory().inSession(em -> {
                    var cb = em.getCriteriaBuilder();
                    CriteriaQuery<OrderEntity> query = cb.createQuery(OrderEntity.class);
                    Root<OrderEntity> root = query.from(OrderEntity.class);
                    em.createQuery(query.where(probe.where().apply(cb, root))).getResultList();
                })).as(probe.form()).hasStackTraceContaining("reached the SQL text as literals");
            }
        }
    }

    // ---- AC-FLT-36

    @TckTest
    void ac_flt_36_without_a_binder_a_string_binds_and_an_enum_is_mq1502_before_any_statement(TckDatabase db) {
        UnaryOperator<Filters<O>> byText = f -> f.eq(STATUS, "PAID");
        UnaryOperator<Filters<O>> byEnum = f -> f.eq(STATUS_CODE, OrderStatus.PAID);
        List<List<Long>> defaults = new ArrayList<>();
        // Under the default setting both run as today, with or without a binder: Hibernate's provider has one.
        OperatorProviderSupport.serving(feature -> false, null, () -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
                defaults.add(orderIds(sf, ModelQueryConfig.defaults(), byText));
                defaults.add(orderIds(sf, ModelQueryConfig.defaults(), byEnum));
            }
        });
        assertThat(defaults.get(0)).isNotEmpty().isEqualTo(defaults.get(1));
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            assertThat(orderIds(sf, ModelQueryConfig.defaults(), byText)).isEqualTo(defaults.get(0));
            assertThat(orderIds(sf, ModelQueryConfig.defaults(), byEnum)).isEqualTo(defaults.get(1));
        }
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            OperatorProviderSupport.serving(feature -> false, null, () -> {
                inline.watch("PAID");
                assertThat(orderIds(inline.factory(), ModelQueryConfig.defaults(), byText))
                        .isEqualTo(defaults.get(0));
                assertBound(inline, "String without a binder");
                inline.watch("PAID");
                assertThatThrownBy(() -> orderIds(inline.factory(), ModelQueryConfig.defaults(), byEnum))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                        .hasMessageContaining("statusCode").hasMessageContaining("eq(...) under inline value handling")
                        .hasMessageContaining("OrderStatus");
                assertThat(inline.statements()).isEmpty();
            });
        }
    }

    // ---- helpers

    /** Each statement since the last {@code watch} bound a value: no input reached it (the inspector checked). */
    private static void assertBound(InlineValueHandling inline, String path) {
        assertThat(inline.statements()).as(path).isNotEmpty()
                .allSatisfy(sql -> assertThat(sql).as(path).contains("?"));
    }

    private static List<Long> orderIds(SessionFactory sf, ModelQueryConfig config, UnaryOperator<Filters<O>> where) {
        List<Long> ids = new ArrayList<>();
        sf.inSession(em -> ModelQueryExecutor.create(em, OrderEntity.class, config)
                .list(ORDER_QUERY.where(where).build(), Limit.unlimited()).forEach(o -> ids.add(o.id())));
        return ids;
    }

    private static List<Long> rowIds(SessionFactory sf, UnaryOperator<Filters<C>> where) {
        List<Long> ids = new ArrayList<>();
        sf.inSession(em -> ModelQueryExecutor.create(em, ConstantRowEntity.class, ModelQueryConfig.defaults())
                .list(ROW_QUERY.where(where).build(), Limit.unlimited()).forEach(c -> ids.add(c.id())));
        return ids;
    }
}
