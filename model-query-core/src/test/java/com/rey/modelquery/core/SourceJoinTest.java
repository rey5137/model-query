package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.rey.modelquery.annotations.JoinKind;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** The {@code joinSource} bindings checked when the query is built: {@code MQ1910}, {@code MQ1905} (R-SRC-21). */
class SourceJoinTest {

    static final class Day {}

    static final class Sales {}

    static final class Node {}

    static final class View {}

    private static final TableField<Day, Day> DAY = TableField.root(Day.class);
    private static final ColumnField<Sales, Day, LocalDate> S_DATE =
            ColumnField.of(Sales.class, DAY, "businessDate", LocalDate.class).named("businessDate");
    private static final ColumnField<Sales, Day, String> S_SHOP =
            ColumnField.of(Sales.class, DAY, "shop", String.class).named("shop");
    private static final ColumnField<Sales, Day, BigDecimal> S_CASH =
            ColumnField.of(Sales.class, DAY, "cash", BigDecimal.class).named("cash");
    private static final SourceQuery<Sales> SALES = SourceQuery.builder(Sales.class, DAY,
            SelectSet.of(S_DATE, S_SHOP, S_CASH), PrimaryKey.composite(S_DATE, S_SHOP)).build();

    private static final ColumnField<View, Day, Long> V_ID = ColumnField.of(View.class, DAY, "id", Long.class);
    private static final ColumnField<View, Day, LocalDate> V_DATE =
            ColumnField.of(View.class, DAY, "businessDate", LocalDate.class);
    private static final ColumnField<View, Day, String> V_SHOP = ColumnField.of(View.class, DAY, "shop", String.class);
    private static final ColumnField<View, Day, BigDecimal> V_COUNTED =
            ColumnField.of(View.class, DAY, "countedCash", BigDecimal.class);
    private static final SourceJoin<View, Sales> JOIN = SourceJoin.of("sales", Sales.class, JoinKind.LEFT,
            List.of(SourceJoin.on(V_DATE, S_DATE), SourceJoin.on(V_SHOP, S_SHOP)));
    private static final ColumnField<View, Sales, BigDecimal> V_CASH =
            ColumnField.over(View.class, JOIN.root(), S_CASH);
    private static final RowMapper<View> MAPPER = row -> new View();

    /** A source over the walk {@link Node}: its rows per parent, grouped, or its ids, ungrouped. */
    static final class Counts {}

    static final class Copy {}

    private static final ColumnField<Node, Day, Long> N_ID = ColumnField.of(Node.class, DAY, "id", Long.class)
            .named("id");
    private static final ColumnField<Node, Day, Long> N_PARENT =
            ColumnField.of(Node.class, DAY, "parentId", Long.class).named("parentId");
    private static final ColumnField<Node, Day, Integer> N_LEVEL =
            ColumnField.of(Node.class, DAY, "level", Integer.class).named("level");
    private static final TableField<Node, Node> NODE_SOURCE = TableField.source(Node.class);
    private static final ColumnField<Counts, Node, Long> C_PARENT =
            ColumnField.over(Counts.class, NODE_SOURCE, N_PARENT).named("parentId");
    private static final SourceJoin<View, Node> NODES = SourceJoin.of("nodes", Node.class, JoinKind.LEFT,
            List.of(SourceJoin.on(V_ID, N_ID)));
    private static final SourceJoin<View, Counts> COUNTS = SourceJoin.of("counts", Counts.class, JoinKind.LEFT,
            List.of(SourceJoin.on(V_ID, C_PARENT)));

    private static ModelQuery.Builder<Day, Object, View> base() {
        return ModelQuery.builder(DAY, MAPPER).select(SelectSet.of(V_ID, V_CASH));
    }

    private static SourceJoin<View, Sales> join(String name, List<SourceJoin.On<View, Sales>> on) {
        return SourceJoin.of(name, Sales.class, JoinKind.INNER, on);
    }

    /** A walk over {@link Node} from parent to child, checked as a tree or a plain {@code recursive(...)}. */
    private static SourceQuery<Node> walk(boolean tree) {
        var builder = SourceQuery.builder(Node.class, DAY, SelectSet.of(N_ID, N_PARENT, N_LEVEL), PrimaryKey.of(N_ID))
                .depth(N_LEVEL);
        return (tree ? builder.tree(step -> step.childKey(N_PARENT).parentKey(N_ID), 3)
                : builder.recursive(step -> step.childKey(N_PARENT).parentKey(N_ID), 3)).build();
    }

    /** The rows of {@code walk} per parent: unique by its group key, whichever walk it reads (R-SRC-09). */
    private static SourceQuery<Counts> counts(SourceQuery<Node> walk) {
        AggregateField<Counts, Long> children = Agg.<Counts>count(NODE_SOURCE).named("children");
        return SourceQuery.builder(Counts.class, NODE_SOURCE, SelectSet.of(C_PARENT, children),
                PrimaryKey.of(C_PARENT), SelectSet.of(C_PARENT), walk).build();
    }

    private static ModelQueryDefinitionException mq1910(Runnable build) {
        Throwable thrown = catchThrowable(build::run);
        assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
        var definition = (ModelQueryDefinitionException) thrown;
        assertThat(definition.code()).isEqualTo(MqCode.MQ1910);
        return definition;
    }

    static final class Keyed {}

    /** Stores a number as its text: any converted key or column, whose attribute is a {@code String}. */
    private static final ColumnConverter<Long, String> AS_TEXT = new ColumnConverter<>() {
        @Override
        public Long toModel(String attribute) {
            return Long.valueOf(attribute);
        }

        @Override
        public String toAttribute(Long model) {
            return String.valueOf(model);
        }
    };

    private static <C> SourceQuery<Keyed> keyed(ColumnField<Keyed, Day, C> key) {
        return SourceQuery.builder(Keyed.class, DAY, SelectSet.of(key), PrimaryKey.of(key)).build();
    }

    private static <C> SourceJoin<View, Keyed> keyedJoin(ColumnField<View, Day, C> column,
            ColumnField<Keyed, Day, C> key) {
        return SourceJoin.of("keyed", Keyed.class, JoinKind.LEFT, List.of(SourceJoin.on(column, key)));
    }

    @Test
    void ac_src_51_a_converted_source_key_is_mq1910() {
        ColumnField<Keyed, Day, Long> key =
                ColumnField.of(Keyed.class, DAY, "code", Long.class, String.class, AS_TEXT).named("code");

        assertThat(mq1910(() -> base().joinSource(keyedJoin(V_ID, key), keyed(key)).build()))
                .hasMessageContaining("on(id, code) joins a converted or differently typed key")
                .hasMessageContaining("can't be joined");
    }

    @Test
    void ac_src_51_a_converted_outer_column_over_a_key_of_another_attribute_type_is_mq1910() {
        ColumnField<View, Day, Long> column = ColumnField.of(View.class, DAY, "code", Long.class, String.class, AS_TEXT);
        ColumnField<Keyed, Day, Long> key = ColumnField.of(Keyed.class, DAY, "code", Long.class).named("code");

        assertThat(mq1910(() -> base().joinSource(keyedJoin(column, key), keyed(key)).build()))
                .hasMessageContaining("on(code, code) joins a converted or differently typed key");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_src_51_a_pair_of_different_attribute_types_is_mq1910() {
        ColumnField<Keyed, Day, Integer> key = ColumnField.of(Keyed.class, DAY, "code", Integer.class).named("code");
        SourceJoin join = SourceJoin.of("keyed", Keyed.class, JoinKind.LEFT,
                List.of(SourceJoin.on((ColumnField) V_ID, (ColumnField) key)));

        assertThat(mq1910(() -> base().joinSource(join, keyed(key)).build()))
                .hasMessageContaining("on(id, code) joins a converted or differently typed key");
    }

    @Test
    void ac_src_51_a_primitive_and_its_wrapper_are_the_same_attribute_type() {
        ColumnField<View, Day, Integer> column = ColumnField.of(View.class, DAY, "code", int.class);
        ColumnField<Keyed, Day, Integer> key = ColumnField.of(Keyed.class, DAY, "code", Integer.class).named("code");

        assertThat(base().joinSource(keyedJoin(column, key), keyed(key)).build()).isNotNull();
    }

    @Test
    void r_src_07_a_source_that_recurses_under_a_wrapper_cannot_be_a_branch_mq1904() {
        SourceQuery<Counts> wrapped = counts(walk(false));

        for (Runnable combine : List.<Runnable>of(() -> wrapped.union(wrapped), () -> wrapped.intersect(wrapped),
                () -> counts(walk(false)).unionAll(wrapped))) {
            Throwable thrown = catchThrowable(combine::run);
            assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class)
                    .hasMessageContaining("a recursive source can't be a branch of");
            assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1904);
        }
    }

    @Test
    void ac_src_45_a_binding_on_the_full_key_in_any_order_builds() {
        var reversed = join("sales", List.of(SourceJoin.on(V_SHOP, S_SHOP), SourceJoin.on(V_DATE, S_DATE)));

        assertThat(base().joinSource(JOIN, SALES).build()).isNotNull();
        assertThat(base().joinSource(reversed, SALES).build()).isNotNull();
        assertThat(JOIN.name()).isEqualTo("sales");
        assertThat(JOIN.source()).isEqualTo(Sales.class);
        assertThat(JOIN.kind()).isEqualTo(JoinKind.LEFT);
        assertThat(JOIN.on()).extracting(SourceJoin.On::key).containsExactly(S_DATE, S_SHOP);
    }

    @Test
    void ac_src_45_a_partial_key_is_mq1910() {
        var partial = join("sales", List.of(SourceJoin.on(V_DATE, S_DATE)));

        assertThat(mq1910(() -> base().joinSource(partial, SALES).build()))
                .hasMessageContaining("misses the key field(s) [shop]");
    }

    @Test
    void ac_src_45_an_extra_pair_on_a_field_that_is_no_key_field_is_mq1910() {
        var extra = join("sales", List.of(SourceJoin.on(V_DATE, S_DATE), SourceJoin.on(V_SHOP, S_SHOP),
                SourceJoin.on(V_COUNTED, S_CASH)));

        assertThat(mq1910(() -> base().joinSource(extra, SALES).build()))
                .hasMessageContaining("names cash, which is not a key field");
    }

    @Test
    void ac_src_45_a_key_field_paired_twice_is_mq1910() {
        var twice = join("sales", List.of(SourceJoin.on(V_DATE, S_DATE), SourceJoin.on(V_SHOP, S_SHOP),
                SourceJoin.on(V_DATE, S_DATE)));

        assertThat(mq1910(() -> base().joinSource(twice, SALES).build()))
                .hasMessageContaining("pairs the key field businessDate twice");
    }

    @Test
    void ac_src_45_a_join_bound_twice_is_mq1910_by_its_name() {
        var sameName = join("sales", JOIN.on());

        assertThat(mq1910(() -> base().joinSource(JOIN, SALES).joinSource(JOIN, SALES).build()))
                .hasMessageContaining("'sales' is bound twice");
        mq1910(() -> base().joinSource(JOIN, SALES).joinSource(sameName, SALES).build());
        assertThat(base().joinSource(JOIN, SALES).joinSource(join("other", JOIN.on()), SALES).build()).isNotNull();
    }

    @Test
    void ac_src_45_a_non_unique_source_is_mq1910() {
        SourceQuery<Sales> repeated = SALES.unionAll(SALES);
        assertThat(repeated.unique()).isFalse();

        assertThat(mq1910(() -> base().joinSource(JOIN, repeated).build())).hasMessageContaining("no unique key");
    }

    @Test
    void ac_src_45_a_joined_tree_and_a_grouped_source_over_a_plain_walk_build() {
        SourceQuery<Node> tree = walk(true);
        SourceQuery<Counts> counts = counts(walk(false));
        assertThat(tree.unique()).isTrue();
        assertThat(counts.unique()).isTrue();
        assertThat(counts.recursive()).isFalse();

        assertThat(base().joinSource(NODES, tree).build().joinsSource()).isTrue();
        assertThat(base().joinSource(COUNTS, counts).build().joinsSource()).isTrue();
    }

    @Test
    void ac_src_45_a_plain_recursive_source_and_an_ungrouped_source_over_one_are_mq1910_naming_tree() {
        SourceQuery<Node> walk = walk(false);
        var copyId = ColumnField.over(Copy.class, NODE_SOURCE, N_ID).named("id");
        SourceQuery<Copy> over = SourceQuery.builder(Copy.class, NODE_SOURCE, SelectSet.of(copyId),
                PrimaryKey.of(copyId), walk).build();
        var copies = SourceJoin.of("copies", Copy.class, JoinKind.LEFT, List.of(SourceJoin.on(V_ID, copyId)));

        assertThat(mq1910(() -> base().joinSource(NODES, walk).build()))
                .hasMessageContaining("no unique key").hasMessageContaining("use tree(...)");
        assertThat(mq1910(() -> base().joinSource(copies, over).build()))
                .hasMessageContaining("no unique key").hasMessageContaining("use tree(...)");
        // A source that does not recurse keeps the hint of its own.
        assertThat(mq1910(() -> base().joinSource(JOIN, SALES.unionAll(SALES)).build()))
                .hasMessageNotContaining("tree(...)");
    }

    @Test
    void ac_src_50_a_second_walk_across_the_joined_chains_is_mq1910() {
        assertThat(mq1910(() -> base().joinSource(NODES, walk(true)).joinSource(COUNTS, counts(walk(false)))
                .build())).hasMessageContaining("walk 2 recursive sources");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_src_45_a_source_of_another_model_bound_through_a_raw_type_is_mq1910() {
        var id = ColumnField.of(Node.class, DAY, "id", Long.class).named("id");
        SourceQuery nodes = SourceQuery.builder(Node.class, DAY, SelectSet.of(id), PrimaryKey.of(id)).build();

        assertThat(mq1910(() -> base().joinSource((SourceJoin) JOIN, nodes).build()))
                .hasMessageContaining("bound to a SourceQuery of Node");
    }

    @Test
    void ac_src_45_join_source_on_a_query_rooted_at_a_source_is_mq1905() {
        var root = TableField.source(Sales.class);
        var date = ColumnField.over(View.class, root, S_DATE);
        var builder = ModelQuery.builder(root, MAPPER, SALES).select(SelectSet.of(date)).joinSource(JOIN, SALES);

        Throwable thrown = catchThrowable(builder::build);

        assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class).hasMessageContaining("joinSource(...)");
        assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1905);
    }

    @Test
    void ac_src_45_every_builder_copy_carries_the_binding() {
        var bound = base().primaryKey(PrimaryKey.of(V_ID)).joinSource(JOIN, SALES);
        List<UnaryOperator<ModelQuery.Builder<Day, Long, View>>> copies = List.of(
                b -> b.select(SelectSet.of(V_ID, V_CASH)),
                b -> b.fetch(FetchPlan.of(SelectSet.of(V_ID, V_CASH))),
                b -> b.primaryKey(PrimaryKey.of(V_ID)),
                b -> b.orderBy(V_ID.asc()),
                b -> b.keyset(),
                b -> b.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(1_000L)),
                b -> b.lock(RowLock.shared()),
                b -> b.where(f -> f.eq(V_SHOP, "north")),
                b -> b.afterMap((view, row) -> { }),
                b -> b.finisher(view -> view),
                b -> b.customize((spec, joins, query, cb, phase) -> { }));

        // The second binding of the same join fails the build only if the copy kept the first.
        for (var copy : copies) {
            mq1910(() -> copy.apply(bound).joinSource(JOIN, SALES).build());
        }
    }

    @Test
    void ac_src_42_a_joined_source_table_is_keyed_by_its_join_name() {
        var again = join("sales", JOIN.on());
        var other = join("other", JOIN.on());

        assertThat(again.root()).isEqualTo(JOIN.root()).hasSameHashCodeAs(JOIN.root());
        assertThat(other.root()).isNotEqualTo(JOIN.root());
        assertThat(JOIN.root()).isNotEqualTo(TableField.source(Sales.class));
        assertThat(JOIN.root().isJoinedSource()).isTrue();
        assertThat(JOIN.root().isSource()).isTrue();
        assertThat(JOIN.root()).hasToString("Sales (joined as 'sales')");
    }

    @Test
    void ac_src_45_of_refuses_a_blank_name_and_no_pairs() {
        assertThatIllegalArgumentException().isThrownBy(() -> join(" ", JOIN.on()));
        assertThatIllegalArgumentException().isThrownBy(() -> join("sales", List.of()));
    }

    @Test
    void ac_src_43_a_statement_with_no_source_renderer_is_mq4014_before_anything_is_joined() {
        var query = base().joinSource(JOIN, SALES).build();

        Throwable thrown = catchThrowable(() -> query.buildQuery(criteria(), Phase.MODEL, RenderOptions.portable()));

        assertThat(thrown).isInstanceOf(ModelQueryConfigurationException.class)
                .hasMessageContaining("joins a source");
        assertThat(((ModelQueryConfigurationException) thrown).code()).isEqualTo(MqCode.MQ4014);
    }

    @Test
    void ac_src_43_a_column_of_an_unbound_join_is_mq1003() {
        var query = ModelQuery.builder(DAY, MAPPER).select(SelectSet.of(V_CASH)).build();

        Throwable thrown = catchThrowable(() -> query.buildQuery(criteria(), Phase.MODEL, RenderOptions.portable()));

        assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("binds no source joined as 'sales'");
        assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1003);
    }

    @Test
    void ac_src_45_a_joined_source_s_root_as_a_query_or_source_root_is_mq1203() {
        var copyCash = ColumnField.over(Copy.class, JOIN.root(), S_CASH).named("cash");

        var rooted = catchThrowable(() -> ModelQuery.builder(JOIN.root(), MAPPER, SALES));
        var source = catchThrowable(() -> SourceQuery.builder(Copy.class, JOIN.root(), SelectSet.of(copyCash),
                PrimaryKey.of(copyCash), SALES));

        for (Throwable thrown : List.of(rooted, source)) {
            assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class)
                    .hasMessageContaining("joined source 'sales'");
            assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1203);
        }
    }

    @Test
    void ac_src_45_a_returning_query_that_joins_a_source_inner_or_left_is_mq1809_before_any_statement() {
        for (var bound : List.of(JOIN, join("sales", JOIN.on()))) {
            var query = ModelQuery.builder(DAY, MAPPER).select(SelectSet.of(V_ID)).joinSource(bound, SALES).build();

            // The proxy fails any statement work past createTupleQuery, so the refusal comes before it.
            Throwable thrown = catchThrowable(() -> query.checkReturning(criteria()));

            assertThat(thrown).as(bound.kind().name()).isInstanceOf(ModelQueryDefinitionException.class)
                    .hasMessageContaining("joinSource(...)");
            assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1809);
        }
    }

    @Test
    void ac_src_43_the_fetch_check_passes_an_enricher_reading_a_joined_column() {
        var plan = FetchPlan.of(SelectSet.of(V_ID)).enrich(Enricher.of(UnaryOperator.identity(), V_CASH));
        var query = ModelQuery.builder(DAY, MAPPER).primaryKey(PrimaryKey.of(V_ID)).joinSource(JOIN, SALES)
                .fetch(plan).build();

        // The column is the CTE's element, read through no join, so the check resolves nothing for it.
        assertThatCode(() -> query.checkFetch(criteria())).doesNotThrowAnyException();
    }

    /** A builder whose statements only root themselves: the checks above fail before anything else is rendered. */
    @SuppressWarnings("unchecked")
    private static CriteriaBuilder criteria() {
        Root<Day> root = (Root<Day>) Proxy.newProxyInstance(Root.class.getClassLoader(), new Class<?>[] {Root.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJavaType" -> Day.class;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                });
        CriteriaQuery<Tuple> query = (CriteriaQuery<Tuple>) Proxy.newProxyInstance(
                CriteriaQuery.class.getClassLoader(), new Class<?>[] {CriteriaQuery.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "from" -> root;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                });
        return (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createTupleQuery" -> query;
                    default -> throw new AssertionError(method.getName());
                });
    }
}
