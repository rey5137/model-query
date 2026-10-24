package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** {@code QueryParam}, {@code Expr.param} and {@code bind}: the build checks and the binding (R-COL-40, D-198). */
class QueryParamTest {

    static final class Order {}

    static final class Line {}

    static final class View {}

    static final class LineView {}

    static final class Band {}

    record NewOrder(Long id, String status) {}

    private enum Status { OPEN }

    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final TableField<Line, Line> LINES = TableField.root(Line.class);
    private static final OrderedColumnField<View, Order, Long> ID = ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final OrderedColumnField<View, Order, String> STATUS =
            ColumnField.of(View.class, ROOT, "status", String.class);
    private static final ColumnField<LineView, Line, Long> LINE_ID =
            ColumnField.of(LineView.class, LINES, "id", Long.class);
    private static final ColumnField<LineView, Line, Long> LINE_ORDER =
            ColumnField.of(LineView.class, LINES, "orderId", Long.class);
    private static final ColumnField<LineView, Line, String> LINE_STATUS =
            ColumnField.of(LineView.class, LINES, "status", String.class);

    private static final ColumnField<Band, Order, Long> BAND_ID = ColumnField.of(Band.class, ROOT, "id", Long.class);
    private static final ColumnField<Band, Order, String> BAND_STATUS =
            ColumnField.of(Band.class, ROOT, "status", String.class);

    private static final ColumnField<NewOrder, Order, Long> NEW_ID =
            ColumnField.of(NewOrder.class, ROOT, "id", Long.class);
    private static final ColumnField<NewOrder, Order, String> NEW_STATUS =
            ColumnField.of(NewOrder.class, ROOT, "status", String.class);
    private static final InsertColumns<NewOrder, Order> INSERT = InsertColumns.<NewOrder, Order>of(ROOT)
            .addKey(NEW_ID, NewOrder::id).add(NEW_STATUS, NewOrder::status);

    private static final QueryParam<String> CODE = QueryParam.of("code", String.class);
    private static final QueryParam<String> OTHER_CODE = QueryParam.of("code", String.class);
    private static final QueryParam<Long> MIN = QueryParam.of("min", long.class);

    private static ModelQuery.Builder<Order, Long, View> base() {
        return ModelQuery.builder(ROOT, row -> new View()).primaryKey(PrimaryKey.of(ID))
                .select(SelectSet.of(ID, STATUS));
    }

    private static ModelQuery.Builder<Order, Long, View> base(SelectField<View, ?> extra) {
        return ModelQuery.builder(ROOT, row -> new View()).primaryKey(PrimaryKey.of(ID))
                .select(SelectSet.of(ID, STATUS, extra));
    }

    // ---- AC-COL-61

    @Test
    void ac_col_61_a_param_read_and_not_bound_is_mq1512_at_build() {
        var labelled = Expr.<View>concat(STATUS, Expr.param(CODE)).named("labelled");
        assertCode(() -> base(labelled).build(), MqCode.MQ1512)
                .hasMessage("MQ1512: View: labelled reads the param code: String, which the query does not bind; "
                        + "bind it with bind(param, value)");
        assertThatCode(() -> base(labelled).bind(CODE, "x").build()).doesNotThrowAnyException();
    }

    @Test
    void ac_col_61_every_read_site_is_checked_filters_order_group_and_a_correlated_filter() {
        assertCode(() -> base().where(f -> f.compare(STATUS, Op.EQ, Expr.param(CODE))).build(), MqCode.MQ1512)
                .hasMessageContaining("param code");
        assertCode(() -> base().orderBy(Expr.plus(ID, Expr.param(MIN)).named("shifted").asc()).build(),
                MqCode.MQ1512).hasMessageContaining("param min");
        ExpressionField<View, Long> open = Correlated.count(View.class, SubSelect.of(LINE_ID),
                (f, outer) -> f.compare(LINE_ORDER, Op.EQ, outer.column(ID))
                        .compare(LINE_STATUS, Op.EQ, Expr.param(CODE))).named("openLines");
        assertCode(() -> base(open).build(), MqCode.MQ1512)
                .hasMessageContaining("openLines reads the param code");
        assertThatCode(() -> base(open).bind(CODE, "OPEN").build()).doesNotThrowAnyException();
    }

    @Test
    void ac_col_61_two_params_with_one_name_are_mq1512_even_when_both_are_bound() {
        var both = Expr.<View>concat(Expr.param(CODE), Expr.param(OTHER_CODE)).named("both");
        assertCode(() -> base(both).bind(CODE, "a").bind(OTHER_CODE, "b").build(), MqCode.MQ1512)
                .hasMessageContaining("reads two params named 'code'");
    }

    @Test
    void ac_col_61_a_bound_param_nothing_reads_is_allowed() {
        assertThatCode(() -> base().bind(CODE, "unused").build()).doesNotThrowAnyException();
    }

    @Test
    void ac_col_61_a_null_a_non_instance_and_an_unbindable_value_are_mq1502_at_bind() {
        assertCode(() -> base().bind(CODE, null), MqCode.MQ1502).hasMessageContaining("bind(code: String, null)");
        @SuppressWarnings({"unchecked", "rawtypes"})
        QueryParam<Object> raw = (QueryParam) MIN;
        assertCode(() -> base().bind(raw, "seven"), MqCode.MQ1502).hasMessageContaining("is not a Long");
        QueryParam<Object> any = QueryParam.of("any", Object.class);
        assertCode(() -> base().bind(any, List.of(1)), MqCode.MQ1502)
                .hasMessageContaining("an expression cannot bind");
        assertCode(() -> SourceQuery.builder(View.class, ROOT, SelectSet.of(ID), PrimaryKey.of(ID)).bind(CODE, null),
                MqCode.MQ1502);
    }

    @Test
    void ac_col_61_an_unbindable_declared_type_is_mq1502_when_read() {
        for (Class<?> type : List.of(List.class, Map.class, Optional.class, Date.class, Status.class, int[].class)) {
            QueryParam<?> param = QueryParam.of("p", type);
            assertCode(() -> Expr.param(param), MqCode.MQ1502).hasMessageContaining("cannot bind a value of type");
        }
        assertThatCode(() -> Expr.param(QueryParam.of("asOf", LocalDate.class))).doesNotThrowAnyException();
    }

    @Test
    void ac_col_61_a_second_bind_replaces_the_first() {
        Map<QueryParam<?>, Object> first = ParamReads.bind(Map.of(), CODE, "a");
        Map<QueryParam<?>, Object> second = ParamReads.bind(first, CODE, "b");
        assertThat(second).containsExactly(Map.entry(CODE, "b"));
        assertThat(first).containsExactly(Map.entry(CODE, "a")); // the earlier builder is unchanged
    }

    @Test
    void ac_col_61_a_write_that_reads_a_param_is_mq1512_at_its_build() {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "x")
                .where(f -> f.compare(STATUS, Op.EQ, Expr.param(CODE)));
        assertCode(update::build, MqCode.MQ1512).hasMessageContaining("a write binds no param");
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.compare(STATUS, Op.EQ, Expr.param(CODE)));
        assertCode(delete::build, MqCode.MQ1512).hasMessageContaining("reads the param code");
        // A setExpression reading a param binds a parameter, which is refused at the call (R-WRT-53).
        assertCode(() -> ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID))
                .setExpression(STATUS, Expr.<View, String>param(CODE)), MqCode.MQ1613);
    }

    @Test
    void ac_col_61_an_insert_reading_a_param_in_its_where_or_conflict_update_where_is_mq1512_at_its_build() {
        var select = ModelInsert.select(INSERT, LINES).map(NEW_ID, LINE_ID).map(NEW_STATUS, LINE_STATUS)
                .where(f -> f.compare(LINE_STATUS, Op.EQ, Expr.param(CODE)));
        assertCode(select::build, MqCode.MQ1512).hasMessageContaining("Order insert")
                .hasMessageContaining("reads the param code");
        var upsert = ValuesInsert.builder(INSERT, Long.class, List.of(new NewOrder(1L, "a"))).onConflict(NEW_ID)
                .doUpdate(u -> u.setFromRow(NEW_STATUS).where(f -> f.compare(NEW_STATUS, Op.EQ, Expr.param(CODE))));
        assertCode(upsert::build, MqCode.MQ1512).hasMessageContaining("reads the param code");
        // The same inserts with a plain value build: the refusal is the param's, not the filter's.
        assertThatCode(() -> ValuesInsert.builder(INSERT, Long.class, List.of(new NewOrder(1L, "a")))
                .onConflict(NEW_ID).doUpdate(u -> u.setFromRow(NEW_STATUS).where(f -> f.eq(NEW_STATUS, "x")))
                .build()).doesNotThrowAnyException();
    }

    @Test
    void ac_col_61_a_conflict_arbiter_reading_a_param_is_refused_at_build_as_any_expression_value_there() {
        var arbiter = ValuesInsert.builder(INSERT, Long.class, List.of(new NewOrder(1L, "a"))).onConflict(NEW_ID)
                .where(f -> f.compare(NEW_STATUS, Op.EQ, Expr.param(CODE))).doNothing();
        assertCode(arbiter::build, MqCode.MQ1804).hasMessageContaining("plain value");
    }

    @Test
    void ac_col_61_a_source_body_param_is_checked_on_its_own_source() {
        var label = Expr.<View>concat(STATUS, Expr.param(CODE)).named("label");
        var builder = SourceQuery.builder(View.class, ROOT, SelectSet.of(ID, label), PrimaryKey.of(ID));
        assertCode(builder::build, MqCode.MQ1512).hasMessageContaining("label reads the param code");
        assertThatCode(() -> builder.bind(CODE, "x").build()).doesNotThrowAnyException();
    }

    @Test
    void ac_col_61_a_param_is_equal_by_identity_and_named_in_text_never_by_value() {
        assertThat(Expr.<View, String>param(CODE)).isEqualTo(Expr.<View, String>param(CODE));
        assertThat(Expr.<View, String>param(CODE)).isNotEqualTo(Expr.<View, String>param(OTHER_CODE));
        assertThat(Expr.<View, String>param(CODE).name()).isEqualTo("param(code)");
        assertThat(Expr.<View, String>param(CODE).columns()).isEmpty();
        assertThat(MIN.type()).isEqualTo(Long.class);
        assertThat(CODE).hasToString("code: String");
        var query = base().where(f -> f.compare(STATUS, Op.EQ, Expr.param(CODE))).bind(CODE, "secret-42").build();
        assertThat(query.conditions().toString()).contains("param(code)").doesNotContain("secret-42");
        assertThat(query.toString()).doesNotContain("secret-42");
        assertThatThrownBy(() -> QueryParam.of(" ", String.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_col_61_a_param_renders_its_bound_value_and_refuses_a_statement_with_no_binding() {
        JoinContext bound = context(RenderOptions.portable().withBindings(Map.of(CODE, "x")));
        Expr.<View, String>param(CODE).expression(bound);
        assertThat(bound.statementParameters()).containsOnly(
                Map.entry(bound.statementParameters().keySet().iterator().next(), "x"));
        assertCode(() -> Expr.<View, String>param(CODE).expression(context(RenderOptions.portable())), MqCode.MQ1512)
                .hasMessageContaining("carries no binding");
    }

    // ---- AC-COL-60

    @Test
    void ac_col_60_a_bound_build_leaves_the_shared_definition_and_its_constants_unbound() {
        var label = Expr.<View>concat(STATUS, Expr.param(CODE)).named("label");
        ModelQuery.Builder<Order, Long, View> shared = base(label);
        ModelQuery<Order, Long, View> first = shared.bind(CODE, "a").build();
        ModelQuery<Order, Long, View> second = shared.bind(CODE, "b").build();
        assertThat(first).isNotSameAs(second);
        // The values live on each built query, never on the builder, the param or the expression (INV-9).
        assertCode(shared::build, MqCode.MQ1512).hasMessageContaining("label reads the param code");
        assertThat(CODE).hasToString("code: String");
        assertThat(label.name()).isEqualTo("label");
        assertThat(label).isEqualTo(Expr.<View>concat(STATUS, Expr.param(CODE)).named("label"));
    }

    // ---- AC-COL-62

    @Test
    void ac_col_62_lock_fetch_and_sort_copies_carry_the_callers_bindings() {
        var label = Expr.<View>concat(STATUS, Expr.param(CODE)).named("label");
        ModelQuery<Order, Long, View> bound = base(label).bind(CODE, "a").build();
        // Each copy is checked as build() checks a query, so a copy that lost the binding would be MQ1512.
        var bindings = new ArrayList<String>();
        log(bindings, () -> {
            assertThat(bound.withLock(RowLock.exclusive())).isNotSameAs(bound);
            assertThat(bound.withFetch(FetchPlan.of(SelectSet.of(ID, label)))).isNotSameAs(bound);
            assertThat(bound.orderedBy(SortSpec.of(SortSpec.Key.desc("id")))).isNotSameAs(bound);
        });
        // The lock and fetch copies are new definitions, which the build log lists; a sort copy is not.
        assertThat(bindings).hasSize(2).allSatisfy(line -> assertThat(line).endsWith("binds [code: String]"));
    }

    @Test
    void ac_col_62_a_child_load_reading_a_param_is_mq1512_at_the_plan_build() {
        var lineLabel = Expr.<LineView>concat(LINE_STATUS, Expr.param(CODE)).named("lineLabel");
        ChildField<View, LineView> unbound = lines(() -> lineQuery(lineLabel));
        // A child statement is built from the child's definition, which no caller's binding reaches.
        assertCode(() -> FetchPlan.of(SelectSet.of(ID)).child(unbound, FetchPlan.of(SelectSet.of(LINE_ID, lineLabel))),
                MqCode.MQ1512).hasMessageContaining("lineLabel reads the param code");
        ChildField<View, LineView> bound = lines(() -> lineQuery(lineLabel).bind(CODE, "x"));
        assertThatCode(() -> FetchPlan.of(SelectSet.of(ID)).child(bound,
                FetchPlan.of(SelectSet.of(LINE_ID, lineLabel)))).doesNotThrowAnyException();
    }

    @Test
    void ac_col_62_a_source_body_and_its_outer_query_never_bind_each_others_params() {
        var label = Expr.<Band>concat(BAND_STATUS, Expr.param(CODE)).named("label");
        // The outer query's binding does not reach the body: the body's build refuses it on its own.
        assertCode(() -> SourceQuery.builder(Band.class, ROOT, SelectSet.of(BAND_ID, label), PrimaryKey.of(BAND_ID))
                .build(), MqCode.MQ1512);
        SourceQuery<Band> body = SourceQuery.builder(Band.class, ROOT, SelectSet.of(BAND_ID, label),
                PrimaryKey.of(BAND_ID)).bind(CODE, "body").build();
        var join = SourceJoin.of("labelled", Band.class, JoinKind.LEFT, List.of(SourceJoin.on(ID, BAND_ID)));
        // Nor the body's the outer query: the outer filter reading the param is refused until the outer binds it.
        var outer = base().joinSource(join, body).where(f -> f.compare(STATUS, Op.EQ, Expr.param(CODE)));
        assertCode(outer::build, MqCode.MQ1512).hasMessageContaining("which the query does not bind");
        assertThatCode(() -> outer.bind(CODE, "outer").build()).doesNotThrowAnyException();
    }

    @Test
    void ac_col_62_inspection_names_each_bound_param_and_never_its_value() {
        var label = Expr.<View>concat(STATUS, Expr.param(CODE)).named("label");
        ModelQuery<Order, Long, View> query = base(label).where(f -> f.compare(ID, Op.GTE, Expr.param(MIN)))
                .bind(CODE, "secret-42").bind(MIN, 4242L).build();
        var built = new ArrayList<String>();
        log(built, () -> base(label).where(f -> f.compare(ID, Op.GTE, Expr.param(MIN))).bind(CODE, "secret-42")
                .bind(MIN, 4242L).build());
        assertThat(built).singleElement().satisfies(line -> assertThat(line)
                .contains("where [COMPARE(View.id, GTE, param(min))]").endsWith("binds [code: String, min: Long]")
                .doesNotContain("secret-42").doesNotContain("4242"));
        assertThat(query.toString()).doesNotContain("secret-42").doesNotContain("4242");
        assertThat(query.conditions().toString()).contains("param(min)").doesNotContain("4242");
    }

    /** Runs {@code work}, adding to {@code into} each build log line of {@code ModelQuery} (R-INS-05). */
    // ---- AC-COL-63

    @Test
    void ac_col_63_the_queries_one_builder_builds_through_bind_alone_share_one_definition_key() {
        var label = Expr.<View>concat(STATUS, Expr.param(CODE)).named("label");
        ModelQuery.Builder<Order, Long, View> shared = base(label);
        ModelQuery<Order, Long, View> a = shared.bind(CODE, "a").build();
        ModelQuery<Order, Long, View> b = shared.bind(CODE, "b").build();
        assertThat(a.definitionKey()).isSameAs(b.definitionKey());
        assertThat(a.definitionKey().toString()).startsWith("definition@"); // value-free (INV-9)
        // A bind after another change shares the key of that change's builder, and no other.
        var narrowed = shared.where(f -> f.eq(STATUS, "NEW"));
        assertThat(narrowed.bind(CODE, "a").build().definitionKey())
                .isSameAs(narrowed.bind(CODE, "b").build().definitionKey()).isNotSameAs(a.definitionKey());
        // Two builds of one builder are one definition, bound or not.
        var plain = base();
        assertThat(plain.build().definitionKey()).isSameAs(plain.build().definitionKey());
        // Equal queries built apart, and a builder change after bind, each make a new key.
        assertThat(base(label).bind(CODE, "a").build().definitionKey()).isNotSameAs(a.definitionKey());
        assertThat(shared.bind(CODE, "a").orderBy(ID.desc()).build().definitionKey())
                .isNotSameAs(a.definitionKey());
        // A sort copy has its own key, as an orderBy change does; its phase check keys on its definition's (D-21).
        var sorted = a.orderedBy(SortSpec.of(SortSpec.Key.desc("id")));
        assertThat(sorted.definitionKey()).isNotSameAs(a.definitionKey())
                .isNotSameAs(b.orderedBy(SortSpec.of(SortSpec.Key.desc("id"))).definitionKey());
        assertThat(sorted.definition().definitionKey()).isSameAs(a.definitionKey());
        // A lock or fetch copy has its own key, shared by the copies of every binding, one per lock or plan.
        var plan = FetchPlan.of(SelectSet.of(ID, label));
        assertThat(a.withLock(RowLock.exclusive()).definitionKey())
                .isSameAs(b.withLock(RowLock.exclusive()).definitionKey())
                .isNotSameAs(sorted.withLock(RowLock.exclusive()).definitionKey())
                .isNotSameAs(a.definitionKey())
                .isNotSameAs(a.withLock(RowLock.shared()).definitionKey())
                .isNotSameAs(a.withFetch(plan).definitionKey());
        assertThat(a.withFetch(plan).definitionKey()).isSameAs(b.withFetch(plan).definitionKey())
                .isNotSameAs(a.withFetch(FetchPlan.of(SelectSet.of(ID, label))).definitionKey());
    }

    @Test
    void ac_col_63_a_lock_or_fetch_copy_of_another_binding_skips_the_build_checks_its_first_passed() {
        var label = Expr.<View>concat(STATUS, Expr.param(CODE)).named("label");
        ModelQuery.Builder<Order, Long, View> shared = base(label);
        ModelQuery<Order, Long, View> a = shared.bind(CODE, "a").build();
        ModelQuery<Order, Long, View> b = shared.bind(CODE, "b").build();
        var plan = FetchPlan.of(SelectSet.of(ID, STATUS, label));
        var built = new ArrayList<String>();
        log(built, () -> {
            a.withLock(RowLock.exclusive());
            a.withFetch(plan);
        });
        assertThat(built).hasSize(2);
        built.clear();
        var copies = new ArrayList<ModelQuery<Order, Long, View>>();
        log(built, () -> {
            copies.add(b.withLock(RowLock.exclusive()));
            copies.add(b.withFetch(plan));
        });
        // Checked once per definition: the second binding's copies are built without the checks, but as their own.
        assertThat(built).isEmpty();
        assertThat(copies.get(0)).isNotSameAs(a.withLock(RowLock.exclusive()));
        assertThat(copies.get(0).lock()).contains(RowLock.exclusive());
        assertThat(copies.get(1)).isNotSameAs(a.withFetch(plan));
        assertThat(copies.get(1).fetch()).containsSame(plan);
        // A sort copy is a definition of its own, so its fetch copy is checked on its own.
        log(built, () -> a.orderedBy(SortSpec.of(SortSpec.Key.desc("status"))).withFetch(plan));
        assertThat(built).hasSize(1);
    }

    private static void log(List<String> into, Runnable work) {
        Logger logger = Logger.getLogger(ModelQuery.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.FINE && r.getMessage().startsWith("built ")) {
                    into.add(r.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Level before = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            work.run();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(before);
        }
    }

    private static ModelQuery.Builder<Line, Long, LineView> lineQuery(SelectField<LineView, ?> extra) {
        return ModelQuery.builder(LINES, row -> new LineView()).primaryKey(PrimaryKey.of(LINE_ID))
                .select(SelectSet.of(LINE_ID, LINE_ORDER, extra));
    }

    private static ChildField<View, LineView> lines(Supplier<ModelQuery.Builder<?, ?, LineView>> query) {
        return new ChildField<>() {
            @Override
            public String name() {
                return "lines";
            }

            @Override
            public ColumnField<View, ?, ?> key() {
                return ID;
            }

            @Override
            public Optional<ColumnField<LineView, ?, ?>> foreignKey() {
                return Optional.of(LINE_ORDER);
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
            public ModelQuery.Builder<?, ?, LineView> query() {
                return query.get();
            }

            @Override
            public View with(View parent, List<LineView> children) {
                return parent;
            }
        };
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertCode(ThrowingCallable call, MqCode code) {
        return assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    /** A context whose criteria builder makes a parameter of the type it is asked for. */
    private static JoinContext context(RenderOptions options) {
        CriteriaBuilder cb = (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "parameter" -> fake(ParameterExpression.class, (Class<?>) args[0]);
                    default -> null;
                });
        return JoinContext.of(fake(Root.class, Order.class), cb, null, options);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Class<?> javaType) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJavaType" -> javaType;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
