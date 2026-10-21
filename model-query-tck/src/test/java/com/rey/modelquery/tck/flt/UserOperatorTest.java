package com.rey.modelquery.tck.flt;

import static jakarta.persistence.criteria.JoinType.INNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OperatorFeature;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.ConstantRowEntity;
import com.rey.modelquery.tck.col.ConstantStatus;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * User-defined {@code FilterOperator}s through the executor, with Hibernate's operand binder and, where a test says so,
 * with none: what binds and what is refused, the renderer's failures, NULL rows and {@code Having.op} (spec api/12
 * R-FLT-20..22; TCK AC-FLT-22, AC-FLT-23, AC-FLT-24's no-gate case, AC-FLT-26).
 */
class UserOperatorTest {

    record R(Long id, String label) {}

    static final TableField<ConstantRowEntity, ConstantRowEntity> ROWS = TableField.root(ConstantRowEntity.class);
    static final ColumnField<R, ConstantRowEntity, Long> ID = ColumnField.of(R.class, ROWS, "id", Long.class);
    static final ColumnField<R, ConstantRowEntity, String> LABEL =
            ColumnField.of(R.class, ROWS, "label", String.class);
    static final ColumnField<R, ConstantRowEntity, ConstantStatus> STATUS =
            ColumnField.of(R.class, ROWS, "status", ConstantStatus.class);
    /** The label column through a {@link ColumnConverter} that trims, so a padded model value is stored trimmed. */
    static final ColumnField<R, ConstantRowEntity, String> TRIMMED_LABEL =
            ColumnField.of(R.class, ROWS, "label", String.class, String.class, new Trimmed());
    static final ModelQuery.Builder<ConstantRowEntity, Object, R> ROWS_QUERY =
            ModelQuery.builder(ROWS, row -> new R(row.get(ID), row.get(LABEL)))
                    .select(SelectSet.of(ID, LABEL)).orderBy(ID.asc());

    record O(Long id) {}

    static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    static final ColumnField<O, OrderEntity, Long> ORDER_ID = ColumnField.of(O.class, ORDERS, "id", Long.class);
    static final ColumnField<O, OrderEntity, LocalDateTime> PLACED_AT =
            ColumnField.of(O.class, ORDERS, "placedAt", LocalDateTime.class);
    static final ModelQuery.Builder<OrderEntity, Object, O> ORDERS_QUERY =
            ModelQuery.builder(ORDERS, row -> new O(row.get(ORDER_ID))).select(SelectSet.of(ORDER_ID))
                    .orderBy(ORDER_ID.asc());
    static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS = TableField.join(ORDERS, "items", INNER);
    static final ColumnField<O, OrderItemEntity, String> ITEM_PRODUCT =
            ColumnField.of(O.class, ORDER_ITEMS, "productCode", String.class);

    record G(ConstantStatus status, Long rows) {}

    static final ColumnField<G, ConstantRowEntity, ConstantStatus> G_STATUS =
            ColumnField.of(G.class, ROWS, "status", ConstantStatus.class);
    static final AggregateField<G, Long> G_ROWS = Agg.count(ROWS);

    // ---- the operators, kept in constants as R-FLT-20 asks

    static final FilterOperator<String, String> EQUALS =
            FilterOperator.of("equals", (ctx, left, right) -> ctx.cb().equal(left, right));
    static final FilterOperator<String, String> DIFFERS =
            FilterOperator.of("differs", (ctx, left, right) -> ctx.cb().notEqual(left, right));
    /** A value-typed twin of {@link #EQUALS}: its value binds as given, never through a converter (R-FLT-21). */
    static final FilterOperator<String, String> EQUALS_RAW =
            FilterOperator.of("equalsRaw", String.class, (ctx, left, right) -> ctx.cb().equal(left, right));
    static final FilterOperator<String, String> STARTS =
            FilterOperator.<String>of("starts", (ctx, left, right) -> ctx.cb().like(left, right))
                    .preparing(value -> value + "%");
    static final FilterOperator<Long, Long> AT_MOST =
            FilterOperator.of("atMost", (ctx, left, right) -> ctx.cb().le(left, right));
    static final FilterOperator<LocalDateTime, LocalDateTime> BEFORE =
            FilterOperator.of("before", (ctx, left, right) -> ctx.cb().lessThan(left, right));
    static final FilterOperator<ConstantStatus, ConstantStatus> STATUS_IS =
            FilterOperator.of("statusIs", (ctx, left, right) -> ctx.cb().equal(left, right));
    static final FilterOperator<String, String[]> ANY_OF =
            FilterOperator.of("anyOf", String[].class, (ctx, left, right) -> ctx.cb().conjunction());
    static final FilterOperator<Long, Long> AT_LEAST =
            FilterOperator.of("atLeast", (ctx, left, right) -> ctx.cb().ge(left, right));
    static final FilterOperator<String, Boolean> FLAGGED =
            FilterOperator.of("flagged", Boolean.class, (ctx, left, right) -> ctx.cb().conjunction());
    static final FilterOperator<String, UUID> TAGGED =
            FilterOperator.of("tagged", UUID.class, (ctx, left, right) -> ctx.cb().conjunction());
    static final FilterOperator<String, LocalDate[]> ON_ANY =
            FilterOperator.of("onAny", LocalDate[].class, (ctx, left, right) -> ctx.cb().conjunction());

    static final class Trimmed implements ColumnConverter<String, String> {
        @Override
        public String toModel(String attribute) {
            return attribute;
        }

        @Override
        public String toAttribute(String model) {
            return model.trim();
        }
    }

    // ---- AC-FLT-22: what binds with no operand binder

    @TckTest
    void ac_flt_22_with_no_binder_a_string_a_number_and_a_java_time_value_bind_as_parameters(TckDatabase db) {
        // Order i is placed 17 minutes after the previous one from 2020-01-01T00:00, so 9 come before 02:50.
        LocalDateTime cutoff = LocalDateTime.of(2020, 1, 1, 2, 50);
        List<List<? extends Record>> results = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withoutBinder(() -> {
            results.add(rows(ds, f -> f.op(EQUALS, LABEL, "plain")));
            withExecutor(ds, OrderEntity.class, executor -> {
                results.add(executor.list(ORDERS_QUERY.where(f -> f.op(AT_MOST, ORDER_ID, 7L)).build(),
                        Limit.unlimited()));
                results.add(executor.list(ORDERS_QUERY.where(f -> f.lte(ORDER_ID, 7L)).build(), Limit.unlimited()));
                results.add(executor.list(ORDERS_QUERY.where(f -> f.op(BEFORE, PLACED_AT, cutoff)).build(),
                        Limit.unlimited()));
                results.add(executor.list(ORDERS_QUERY.where(f -> f.lt(PLACED_AT, cutoff)).build(),
                        Limit.unlimited()));
            });
        }));
        assertThat(results.get(0)).isEqualTo(List.of(new R(1L, "plain")));
        assertThat(results.get(1)).hasSize(7).isEqualTo(results.get(2));
        assertThat(results.get(3)).hasSize(9).isEqualTo(results.get(4));
        // The value is a statement parameter, never a literal (R-FLT-08, R-FLT-21).
        assertThat(sql).filteredOn(s -> s.contains("from constant_rows")).singleElement().asString()
                .contains("label=?").doesNotContain("'plain'");
        assertThat(sql).filteredOn(s -> s.contains("from orders")).allSatisfy(s -> assertThat(s).contains("?"));
    }

    @TckTest
    void ac_flt_22_with_no_binder_an_enum_an_array_a_boolean_or_a_uuid_value_is_mq1502_before_any_statement(
            TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> withoutBinder(() -> withExecutor(ds, ConstantRowEntity.class,
                executor -> {
            assertThatThrownBy(() -> executor.list(ROWS_QUERY.where(f -> f.op(STATUS_IS, STATUS,
                    ConstantStatus.PENDING)).build(), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("op(statusIs)").hasMessageContaining("R.status");
            assertThatThrownBy(() -> executor.list(ROWS_QUERY.where(f -> f.op(ANY_OF, LABEL,
                    new String[] {"plain"})).build(), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("op(anyOf)");
            // A Boolean mapped to 'Y'/'N' or a UUID stored as char(36) would match nothing bound by class (D-168).
            assertThatThrownBy(() -> executor.list(ROWS_QUERY.where(f -> f.op(FLAGGED, LABEL, Boolean.TRUE)).build(),
                    Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("op(flagged)").hasMessageContaining("R.label");
            assertThatThrownBy(() -> executor.list(ROWS_QUERY.where(f -> f.op(TAGGED, LABEL, UUID.randomUUID()))
                    .build(), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("op(tagged)");
            // An array of java.time values is an array, not a java.time value.
            assertThatThrownBy(() -> executor.list(ROWS_QUERY.where(f -> f.op(ON_ANY, LABEL,
                    new LocalDate[] {LocalDate.of(2020, 1, 1)})).build(), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                    .hasMessageContaining("op(onAny)");
        })));
        assertThat(sql).noneMatch(s -> s.contains("constant_rows"));
    }

    @TckTest
    void ac_flt_22_a_column_typed_value_is_converted_and_a_value_typed_one_is_not(TckDatabase db) {
        List<List<R>> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            // " padded " goes through the trimming converter to the stored "padded" (row 5) for EQUALS only.
            results.add(rows(ds, f -> f.op(EQUALS, TRIMMED_LABEL, " padded ")));
            results.add(rows(ds, f -> f.op(EQUALS_RAW, TRIMMED_LABEL, " padded ")));
            results.add(rows(ds, f -> f.op(EQUALS_RAW, TRIMMED_LABEL, "padded")));
        });
        assertThat(results.get(0)).extracting(R::id).containsExactly(5L);
        assertThat(results.get(1)).isEmpty();
        assertThat(results.get(2)).extracting(R::id).containsExactly(5L);
    }

    @TckTest
    void ac_flt_22_the_value_binds_on_every_statement_that_renders_it(TckDatabase db) {
        List<Long> counts = new ArrayList<>();
        List<List<R>> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, ConstantRowEntity.class, executor -> {
            var query = ROWS_QUERY.where(f -> f.op(STARTS, LABEL, "p")).build();
            results.add(executor.list(query, Limit.unlimited()));
            counts.add(executor.count(query));
            results.add(executor.list(query, Limit.unlimited()));
        }));
        // "plain" and "padded" start with p; the prepared "p%" binds on the list, the count and the list again.
        assertThat(results.get(0)).extracting(R::id).containsExactly(1L, 5L);
        assertThat(results.get(1)).isEqualTo(results.get(0));
        assertThat(counts).containsExactly(2L);
    }

    @TckTest
    void ac_flt_22_the_value_binds_on_every_keyset_and_export_page_and_inside_exists_and_a_sub_select(
            TckDatabase db) {
        var query = ORDERS_QUERY.primaryKey(PrimaryKey.of(ORDER_ID)).where(f -> f.op(AT_MOST, ORDER_ID, 7L));
        List<Long> expected = longs(db, "select o.id from OrderEntity o where o.id <= 7 order by o.id");
        List<Long> paged = new ArrayList<>();
        List<Long> exported = new ArrayList<>();
        List<String> pagingSql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor -> {
            var keyset = query.keyset().build();
            KeysetSlice<O> slice = executor.page(keyset, KeysetSpec.first(2));
            slice.content().forEach(row -> paged.add(row.id()));
            while (slice.hasNext()) {
                slice = executor.page(keyset, KeysetSpec.after(slice.nextCursor().orElseThrow(), 2));
                slice.content().forEach(row -> paged.add(row.id()));
            }
            executor.export(query.build(), ExportOptions.of(2), page -> page, row -> exported.add(row.id()));
        }));
        // Pages of 2 over 7 rows: every page after the first binds the value again beside its cursor.
        assertThat(expected).hasSize(7);
        assertThat(paged).isEqualTo(expected);
        assertThat(exported).isEqualTo(expected);
        assertThat(pagingSql).filteredOn(s -> s.contains("from orders")).hasSizeGreaterThanOrEqualTo(8)
                .allSatisfy(s -> assertThat(s).contains("<=?").doesNotContain("<=7"));

        List<List<Long>> found = new ArrayList<>();
        List<String> nestedSql = SqlSnapshots.capture(db, ds -> withExecutor(ds, OrderEntity.class, executor -> {
            found.add(ids(executor, ORDERS_QUERY.where(f -> f.exists(ORDER_ITEMS,
                    g -> g.op(EQUALS, ITEM_PRODUCT, "P007"))).build()));
            found.add(ids(executor, ORDERS_QUERY.where(f -> f.in(ORDER_ID,
                    SubSelect.of(ORDER_ID).where(g -> g.op(AT_MOST, ORDER_ID, 7L)))).build()));
        }));
        assertThat(found.get(0)).isNotEmpty().isEqualTo(longs(db, "select distinct o.id from OrderEntity o "
                + "join o.items i where i.productCode = 'P007' order by o.id"));
        assertThat(found.get(1)).isEqualTo(expected);
        List<String> outer = nestedSql.stream().filter(s -> s.contains("from orders")).toList();
        assertThat(outer).hasSize(2);
        assertThat(outer.get(0)).contains("product_code=?").doesNotContain("'P007'");
        assertThat(outer.get(1)).contains("<=?").doesNotContain("<=7");
    }

    // ---- AC-FLT-23: the renderer's failures

    @TckTest
    void ac_flt_23_a_renderer_returning_null_or_throwing_is_mq1313_before_any_statement(TckDatabase db) {
        FilterOperator<String, String> nothing = FilterOperator.of("nothing", (ctx, left, right) -> null);
        FilterOperator<String, String> failing = FilterOperator.of("failing", (ctx, left, right) -> {
            throw new IllegalArgumentException("no such pattern");
        });
        FilterOperator<String, String> engine = FilterOperator.of("engine", (ctx, left, right) -> {
            throw new ModelQueryDefinitionException(MqCode.MQ1308, "an engine error");
        });
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, ConstantRowEntity.class, executor -> {
            assertThatThrownBy(() -> executor.list(ROWS_QUERY.where(f -> f.op(nothing, LABEL, "a")).build(),
                    Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1313))
                    .hasMessageContaining("op(nothing)").hasMessageContaining("R.label");
            assertThatThrownBy(() -> executor.list(ROWS_QUERY.where(f -> f.op(failing, LABEL, "a")).build(),
                    Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1313))
                    .hasMessageContaining("op(failing)").hasMessageContaining("R.label")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> executor.list(ROWS_QUERY.where(f -> f.op(engine, LABEL, LABEL)).build(),
                    Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1308));
        }));
        assertThat(sql).noneMatch(s -> s.contains("constant_rows"));
    }

    // ---- AC-FLT-24: a required feature with no operator gate

    @TckTest
    void ac_flt_24_an_operator_that_requires_a_feature_is_mq4014_with_no_gate(TckDatabase db) {
        FilterOperator<String, String> gated = EQUALS.requires(OperatorFeature.ILIKE);
        ModelQuery<ConstantRowEntity, Object, R> query = ROWS_QUERY.where(f -> f.op(gated, LABEL, "plain")).build();
        // RenderOptions.portable() carries no operator gate, unlike every executor's options (R-VND-23).
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> assertThatThrownBy(() -> query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL,
                        RenderOptions.portable()))
                        .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014))
                        .hasMessageContaining("op(equals)").hasMessageContaining("ILIKE")
                        .hasMessageContaining("no operator gate"));
            }
        });
        assertThat(sql).noneMatch(s -> s.contains("constant_rows"));
    }

    // ---- AC-FLT-26: NULL rows, composition, having and a CASE condition

    @TckTest
    void ac_flt_26_an_op_in_a_case_condition_is_mq1505_before_any_statement(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, ConstantRowEntity.class, executor ->
                assertThatThrownBy(() -> executor.list(ROWS_QUERY.select(SelectSet.of(ID, LABEL,
                        Expr.cases(R.class, Long.class).when(f -> f.op(EQUALS, LABEL, "plain"), 1L).orNull()))
                        .build(), Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1505))
                        .hasMessageContaining("op(equals)")));
        assertThat(sql).noneMatch(s -> s.contains("constant_rows"));
    }

    @TckTest
    void ac_flt_26_op_and_not_op_both_drop_rows_whose_column_is_null(TckDatabase db) {
        List<List<R>> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            results.add(rows(ds, f -> f.op(DIFFERS, LABEL, "plain")));
            results.add(rows(ds, f -> f.not(g -> g.op(EQUALS, LABEL, "plain"))));
            results.add(rows(ds, f -> f.not(g -> g.op(STARTS, LABEL, "p"))));
            results.add(rows(ds, f -> f.ne(LABEL, "plain")));
        });
        // Row 4's label is NULL: no operator adds OR label IS NULL (R-FLT-22), as ne does (R-FLT-04).
        assertThat(results.get(0)).extracting(R::id).containsExactly(2L, 3L, 5L, 6L);
        assertThat(results.get(1)).extracting(R::id).containsExactly(2L, 3L, 5L, 6L);
        assertThat(results.get(2)).extracting(R::id).containsExactly(2L, 3L, 6L);
        assertThat(results.get(3)).extracting(R::id).containsExactly(2L, 3L, 4L, 5L, 6L);
    }

    @TckTest
    void ac_flt_26_op_composes_in_or_and_in_optional_and_field_form(TckDatabase db) {
        List<List<R>> results = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> {
            results.add(rows(ds, f -> f.or(a -> a.op(EQUALS, LABEL, "plain"), b -> b.op(STARTS, LABEL, "O'"))));
            results.add(rows(ds, f -> f.op(EQUALS, LABEL, Optional.empty())));
            results.add(rows(ds, f -> f.op(EQUALS, LABEL, TRIMMED_LABEL)));
        });
        assertThat(results.get(0)).extracting(R::id).containsExactly(1L, 2L);
        assertThat(results.get(1)).extracting(R::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(results.get(2)).extracting(R::id).containsExactly(1L, 2L, 3L, 5L, 6L);
    }

    @TckTest
    void ac_flt_26_having_op_filters_groups(TckDatabase db) {
        List<G> groups = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, ConstantRowEntity.class, executor -> groups.addAll(
                executor.list(ModelQuery.builder(ROWS, row -> new G(row.get(G_STATUS), row.get(G_ROWS)))
                        .select(SelectSet.of(G_STATUS, G_ROWS))
                        .groupBy(G_STATUS)
                        .having(h -> h.op(AT_LEAST, G_ROWS, 2L))
                        .orderBy(G_STATUS.asc())
                        .build(), Limit.unlimited()))));
        // PENDING (rows 1, 5) and FAILED (rows 2, 6) have two rows each; CLOSED and the NULL status one.
        assertThat(groups).containsExactlyInAnyOrder(new G(ConstantStatus.PENDING, 2L),
                new G(ConstantStatus.FAILED, 2L));
    }

    // ---- helpers

    private static List<Long> ids(ModelQueryExecutor<OrderEntity> executor, ModelQuery<OrderEntity, ?, O> q) {
        return executor.list(q, Limit.unlimited()).stream().map(O::id).toList();
    }

    private static List<Long> longs(TckDatabase db, String jpql) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            var ids = new ArrayList<Long>();
            sf.inSession(em -> ids.addAll(em.createQuery(jpql, Long.class).getResultList()));
            return ids;
        }
    }

    /** Runs {@code work} where the provider supplies no operand binder, Hibernate's answers otherwise. */
    private static void withoutBinder(Runnable work) {
        OperatorProviderSupport.serving(feature -> false, null, work);
    }

    private static List<R> rows(DataSource ds, UnaryOperator<Filters<R>> where) {
        List<R> results = new ArrayList<>();
        withExecutor(ds, ConstantRowEntity.class,
                executor -> results.addAll(executor.list(ROWS_QUERY.where(where).build(), Limit.unlimited())));
        return results;
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }
}
