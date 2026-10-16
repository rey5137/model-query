package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CompoundSelection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** The tree declaration and the provider's mandatory duplicate check (R-SRC-13, R-SRC-14, AC-SRC-24). */
class TreeSourceQueryTest {

    static final class Entity {}
    static final class Node {}
    static final class View {}

    private static final TableField<Entity, Entity> ENTITY_ROOT = TableField.root(Entity.class);
    private static final OrderedColumnField<Node, Entity, Long> ID =
            ColumnField.of(Node.class, ENTITY_ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<Node, Entity, Long> PARENT_ID =
            ColumnField.of(Node.class, ENTITY_ROOT, "parentId", Long.class).named("parentId");
    private static final OrderedColumnField<Node, Entity, Integer> LEVEL =
            ColumnField.of(Node.class, ENTITY_ROOT, "level", Integer.class).named("level");
    private static final SelectSet<Node> ALL = SelectSet.of(ID, PARENT_ID, LEVEL);
    private static final PrimaryKey<Node, Long> KEY = PrimaryKey.of(ID);
    private static final TableField<Node, Node> SOURCE_ROOT = TableField.source(Node.class);
    private static final OrderedColumnField<View, Node, Long> VIEW_ID =
            ColumnField.over(View.class, SOURCE_ROOT, ID).named("id");

    private static SourceQuery.Builder<Entity, Node, Node> builder() {
        return SourceQuery.builder(Node.class, ENTITY_ROOT, ALL, KEY).depth(LEVEL);
    }

    private static SourceQuery<Node> tree() {
        return builder().tree(step -> step.childKey(PARENT_ID).parentKey(ID), 3).build();
    }

    private static SourceQuery<Node> recursive() {
        return builder().recursive(step -> step.childKey(PARENT_ID).parentKey(ID), 3).build();
    }

    @Test
    void r_src_13_tree_is_carried_to_the_renderer_without_changing_the_base_builder() {
        var base = builder().recursive(step -> step.childKey(PARENT_ID).parentKey(ID), 2);
        var tree = base.tree(step -> step.childKey(ID).parentKey(PARENT_ID), 5).build();

        assertThat(spec(base.build()).tree()).isFalse();
        assertThat(spec(tree).tree()).isTrue();
        assertThat(tree.recursive()).isTrue();
        assertThat(tree.levelAlias()).isEqualTo("mq_level");
        assertThat(tree.maxDepth()).isEqualTo(5);
        assertThat(base.build().maxDepth()).isEqualTo(2);
        assertThat(tree.unique()).isTrue();
    }

    @Test
    void r_src_13_the_last_tree_or_recursive_call_replaces_the_previous_one() {
        var treeBuilder = builder().tree(step -> step.childKey(ID).parentKey(PARENT_ID), 5);
        SourceQuery<Node> recursive = treeBuilder
                .recursive(step -> step.childKey(PARENT_ID).parentKey(ID), 2).build();
        SourceQuery<Node> tree = treeBuilder
                .tree(step -> step.childKey(PARENT_ID).parentKey(ID), 4).build();

        assertThat(spec(recursive).tree()).isFalse();
        assertThat(recursive.maxDepth()).isEqualTo(2);
        assertThat(spec(tree).tree()).isTrue();
        assertThat(tree.maxDepth()).isEqualTo(4);
        assertThat(treeBuilder.build().maxDepth()).isEqualTo(5);
    }

    @Test
    void r_src_13_tree_uses_the_recursive_depth_limits() {
        for (int depth : new int[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
            Throwable thrown = catchThrowable(() -> builder()
                    .tree(step -> step.childKey(PARENT_ID).parentKey(ID), depth));

            assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
            assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1902);
            assertThat(thrown.getMessage()).contains("Node: maxDepth " + depth + " is out of range");
        }
        for (int depth : new int[] {1, Integer.MAX_VALUE - 2}) {
            assertThat(builder().tree(step -> step.childKey(PARENT_ID).parentKey(ID), depth).build().maxDepth())
                    .isEqualTo(depth);
        }
    }

    @Test
    void r_src_13_tree_requires_a_depth_column() {
        Throwable thrown = catchThrowable(() -> SourceQuery.builder(Node.class, ENTITY_ROOT, ALL, KEY)
                .tree(step -> step.childKey(PARENT_ID).parentKey(ID), 3));

        assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
        assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1902);
        assertThat(thrown.getMessage()).contains("Node: only a source whose model declares a @Depth field");
    }

    @Test
    void r_src_13_tree_is_never_a_set_operation_branch() {
        SourceQuery<Node> tree = tree();
        SourceQuery<Node> plain = SourceQuery.builder(Node.class, ENTITY_ROOT, ALL, KEY).build();
        List<UnaryOperator<SourceQuery<Node>>> operations = List.of(tree::union, tree::unionAll,
                tree::intersect, tree::except, plain::union, plain::unionAll, plain::intersect, plain::except);

        for (UnaryOperator<SourceQuery<Node>> operation : operations) {
            Throwable thrown = catchThrowable(() -> operation.apply(tree));
            assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
            assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1904);
        }
    }

    @Test
    void ac_src_17_a_tree_has_a_unique_key_for_keyset_and_primary_key_first() {
        SourceQuery<Node> tree = tree();

        assertThat(tree.unique()).isTrue();
        assertThat(recursive().unique()).isFalse();
        assertThat(queryBuilder(tree).keyset().build().isKeyset()).isTrue();
        assertThat(queryBuilder(tree).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(1)).build().primaryKeyFirst())
                .isPresent();
    }

    @Test
    void ac_src_20_recursive_key_paging_refusals_name_tree() {
        List<Runnable> reads = List.of(() -> queryBuilder(recursive()).keyset().build(),
                () -> queryBuilder(recursive()).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(1)).build());
        for (Runnable read : reads) {
            Throwable thrown = catchThrowable(read::run);
            assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
            assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1901);
            assertThat(thrown.getMessage()).contains("tree(...)");
        }
    }

    @Test
    void ac_src_20_a_depth_model_without_a_step_names_both_recursive_and_tree() {
        Throwable thrown = catchThrowable(() -> builder().build());

        assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
        assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1902);
        assertThat(thrown.getMessage()).contains("recursive(step ->", "tree(step ->");
    }

    @Test
    void ac_src_24_the_recursive_spec_copies_the_key_aliases() {
        RecursiveSpec original = spec(tree());
        var aliases = new ArrayList<>(original.keyAliases());
        var copy = new RecursiveSpec(original.cb(), original.seed(), original.term(), original.keyType(),
                aliases, original.levelAlias(), original.markAlias(), original.maxDepth(), false);
        aliases.clear();

        assertThat(copy.tree()).isFalse();
        assertThat(copy.cb()).isSameAs(original.cb());
        assertThat(copy.seed()).isSameAs(original.seed());
        assertThat(copy.term()).isSameAs(original.term());
        assertThat(copy.keyType()).isEqualTo(original.keyType());
        assertThat(copy.keyAliases()).containsExactlyElementsOf(original.keyAliases());
        assertThat(copy.levelAlias()).isEqualTo(original.levelAlias());
        assertThat(copy.markAlias()).isEqualTo(original.markAlias());
        assertThat(copy.maxDepth()).isEqualTo(original.maxDepth());
    }


    @Test
    void ac_src_24_a_tree_renderer_without_a_duplicate_check_is_mq4014_at_statement_build() {
        ModelQuery<Node, Long, View> query = query(tree());

        for (Phase phase : Phase.values()) {
            Throwable thrown = catchThrowable(() -> query.buildQuery(criteria(), phase,
                    RenderOptions.portable().withSourceRenderer(renderer(false))));

            assertThat(thrown).isInstanceOf(ModelQueryConfigurationException.class);
            assertThat(((ModelQueryConfigurationException) thrown).code()).isEqualTo(MqCode.MQ4014);
            assertThat(thrown.getMessage()).contains("View", "Node", "SourceRenderer returned no duplicate-key check",
                    "tree(...) source");
        }
    }

    @Test
    void ac_src_24_source_key_columns_are_immutable_ordered_and_empty_without_a_source() {
        var columns = new ArrayList<>(List.of("tenantId", "id"));
        var built = new BuiltQuery<>(null, null, null, row -> null, null, List.of(), 0, columns);
        columns.clear();

        assertThat(built.sourceKeyColumns()).containsExactly("tenantId", "id");
        assertThat(catchThrowable(() -> built.sourceKeyColumns().clear()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new BuiltQuery<>(null, null, null, row -> null).sourceKeyColumns()).isEmpty();
    }

    @Test
    void ac_src_24_each_tree_statement_selects_the_duplicate_check_after_depth_and_cycle() {
        ModelQuery<Node, Long, View> query = query(tree());

        for (Phase phase : Phase.values()) {
            BuiltQuery<View> built = query.buildQuery(criteria(), phase,
                    RenderOptions.portable().withSourceRenderer(renderer(true)));

            assertThat(built.recursionChecks()).extracting(Selection::getAlias).containsExactly(
                    BuiltQuery.RECURSION_DEPTH, BuiltQuery.RECURSION_CYCLE, BuiltQuery.RECURSION_DUPLICATE_KEY);
            assertThat(built.sourceMaxDepth()).isEqualTo(3);
            assertThat(built.sourceKeyColumns()).containsExactly("id");
        }
    }

    @Test
    void ac_src_24_recursive_keeps_two_checks_whether_the_renderer_supplies_a_duplicate_check_or_not() {
        for (boolean duplicateCheck : new boolean[] {false, true}) {
            BuiltQuery<View> built = query(recursive()).buildQuery(criteria(), Phase.MODEL,
                    RenderOptions.portable().withSourceRenderer(renderer(duplicateCheck)));

            assertThat(built.recursionChecks()).extracting(Selection::getAlias)
                    .containsExactly(BuiltQuery.RECURSION_DEPTH, BuiltQuery.RECURSION_CYCLE);
        }
    }

    private static RecursiveSpec spec(SourceQuery<Node> source) {
        return source.recursiveSpec(criteria(), RenderOptions.portable(), renderer(false));
    }

    @Test
    void ac_qry_10_customizer_cannot_drop_any_source_check_in_any_phase() {
        for (var source : List.of(recursive(), tree())) {
            List<String> checks = source.unique()
                    ? List.of(BuiltQuery.RECURSION_DEPTH, BuiltQuery.RECURSION_CYCLE,
                            BuiltQuery.RECURSION_DUPLICATE_KEY)
                    : List.of(BuiltQuery.RECURSION_DEPTH, BuiltQuery.RECURSION_CYCLE);
            for (String missing : checks) {
                var query = queryBuilder(source).customize((spec, joins, criteria, cb, phase) ->
                        criteria.multiselect(criteria.getSelection().getCompoundSelectionItems().stream()
                                .filter(item -> !missing.equals(item.getAlias())).toList())).build();
                for (Phase phase : Phase.values()) {
                    Throwable thrown = catchThrowable(() -> query.buildQuery(criteria(), phase,
                            RenderOptions.portable().withSourceRenderer(renderer(true))));
                    assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
                    assertThat(((ModelQueryDefinitionException) thrown).code()).isEqualTo(MqCode.MQ1205);
                    assertThat(thrown.getMessage()).contains("View: the QueryCustomizer replaced the selection",
                            "check columns [" + missing + "] of phase " + phase + ";",
                            "add to the selection instead of replacing it");
                }
            }
        }
    }

    @Test
    void ac_qry_10_customizer_can_add_selections_while_retaining_source_checks() {
        for (var source : List.of(recursive(), tree())) {
            var query = queryBuilder(source).customize((spec, joins, criteria, cb, phase) -> {
                var selected = new ArrayList<Selection<?>>(criteria.getSelection().getCompoundSelectionItems());
                selected.add(cb.literal(1).alias("extra"));
                criteria.multiselect(selected);
            }).build();
            for (Phase phase : Phase.values()) {
                BuiltQuery<View> built = query.buildQuery(criteria(), phase,
                        RenderOptions.portable().withSourceRenderer(renderer(true)));
                assertThat(built.query().getSelection().getCompoundSelectionItems())
                        .extracting(Selection::getAlias).contains("extra", BuiltQuery.RECURSION_DEPTH,
                                BuiltQuery.RECURSION_CYCLE);
            }
        }
    }

    @Test
    void ac_qry_10_customizer_retains_checks_by_alias_rather_than_selection_identity() {
        var query = queryBuilder(tree()).customize((spec, joins, criteria, cb, phase) -> {
            var selected = new ArrayList<Selection<?>>();
            for (Selection<?> item : criteria.getSelection().getCompoundSelectionItems()) {
                selected.add(cb.literal(1).alias(item.getAlias()));
            }
            criteria.multiselect(selected);
        }).build();

        for (Phase phase : Phase.values()) {
            BuiltQuery<View> built = query.buildQuery(criteria(), phase,
                    RenderOptions.portable().withSourceRenderer(renderer(true)));
            assertThat(built.query().getSelection().getCompoundSelectionItems())
                    .extracting(Selection::getAlias).contains(BuiltQuery.RECURSION_DEPTH,
                            BuiltQuery.RECURSION_CYCLE, BuiltQuery.RECURSION_DUPLICATE_KEY);
        }
    }

    private static ModelQuery<Node, Long, View> query(SourceQuery<Node> source) {
        return queryBuilder(source).build();
    }

    private static ModelQuery.Builder<Node, Long, View> queryBuilder(SourceQuery<Node> source) {
        return ModelQuery.builder(SOURCE_ROOT, row -> new View(), source).select(SelectSet.of(VIEW_ID))
                .primaryKey(PrimaryKey.of(VIEW_ID));
    }

    private static SourceRenderer renderer(boolean duplicateCheck) {
        return new SourceRenderer() {
            @Override
            public Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
                throw new AssertionError("a recursive source must use recursive");
            }

            @Override
            public RecursiveCte recursive(CriteriaQuery<?> outer, RecursiveSpec spec) {
                Root<Tuple> root = expression(Root.class, Tuple.class);
                Expression<?> depth = expression(Expression.class, Long.class);
                Expression<?> cycle = expression(Expression.class, Long.class);
                return duplicateCheck
                        ? new RecursiveCte(root, depth, cycle, expression(Expression.class, Long.class))
                        : new RecursiveCte(root, depth, cycle, null);
            }
        };
    }

    private static CriteriaBuilder criteria() {
        return (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createTupleQuery" -> criteriaQuery();
                    case "literal" -> expression(Expression.class, args[0].getClass());
                    case "le", "isFalse" -> expression(Predicate.class, Boolean.class);
                    default -> throw new AssertionError(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static CriteriaQuery<Tuple> criteriaQuery() {
        var selected = new ArrayList<Selection<?>>();
        CompoundSelection<Tuple> selection = (CompoundSelection<Tuple>) Proxy.newProxyInstance(
                CompoundSelection.class.getClassLoader(), new Class<?>[] {CompoundSelection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isCompoundSelection" -> true;
                    case "getCompoundSelectionItems" -> List.copyOf(selected);
                    default -> throw new AssertionError(method.getName());
                });
        return (CriteriaQuery<Tuple>) Proxy.newProxyInstance(CriteriaQuery.class.getClassLoader(),
                new Class<?>[] {CriteriaQuery.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "from" -> expression(Root.class, Entity.class);
                    case "multiselect" -> {
                        selected.clear();
                        selected.addAll(args[0] instanceof List<?> list
                                ? (List<Selection<?>>) list : List.of((Selection<?>[]) args[0]));
                        yield proxy;
                    }
                    case "getSelection" -> selection;
                    case "getOrderList", "getGroupList" -> List.of();
                    case "isDistinct" -> false;
                    case "where" -> proxy;
                    default -> throw new AssertionError(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T expression(Class<T> type, Class<?> javaType) {
        String[] alias = {null};
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getJavaType" -> javaType;
                    case "getModel" -> null;
                    case "get" -> expression(Path.class,
                            args[0].toString().contains("level") ? Integer.class : Long.class);
                    case "alias" -> {
                        alias[0] = (String) args[0];
                        yield proxy;
                    }
                    case "getAlias" -> alias[0];
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName();
                    default -> throw new AssertionError(method.getName());
                });
    }
}
