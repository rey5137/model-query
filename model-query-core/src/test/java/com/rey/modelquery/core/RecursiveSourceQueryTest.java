package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The core shape of a recursive source: {@link SourceQuery.Builder#depth} names the level column, the step and its
 * limit come from {@link SourceQuery.Builder#recursive}, and a recursive source is never unique (spec api/17
 * R-SRC-07, R-SRC-08, R-SRC-09; D-144).
 */
class RecursiveSourceQueryTest {

    static final class Entity {}

    /** The model of a recursive source, which declares its level column as a {@code @Depth} field would. */
    static final class Node {}

    static final class Other {}

    private static final TableField<Entity, Entity> ENTITY_ROOT = TableField.root(Entity.class);
    private static final OrderedColumnField<Node, Entity, Long> N_ID =
            ColumnField.of(Node.class, ENTITY_ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<Node, Entity, Long> N_PARENT_ID =
            ColumnField.of(Node.class, ENTITY_ROOT, "parentId", Long.class).named("parentId");
    private static final OrderedColumnField<Node, Entity, Integer> N_LEVEL =
            ColumnField.of(Node.class, ENTITY_ROOT, "level", Integer.class).named("level");
    private static final SelectSet<Node> N_ALL = SelectSet.of(N_ID, N_PARENT_ID, N_LEVEL);
    private static final PrimaryKey<Node, Long> N_KEY = PrimaryKey.of(N_ID);
    private static final OrderedColumnField<Other, Entity, Long> O_ID =
            ColumnField.of(Other.class, ENTITY_ROOT, "id", Long.class).named("id");

    /** A plain source over {@link Other}, the control for what recursion changes. */
    private static SourceQuery<Other> plain() {
        return SourceQuery.builder(Other.class, ENTITY_ROOT, SelectSet.of(O_ID), PrimaryKey.of(O_ID)).build();
    }

    /** A source over {@link Node} whose level column is named, as a recursive model's generated {@code source()} is. */
    private static SourceQuery.Builder<Entity, Node, Node> builder() {
        return SourceQuery.builder(Node.class, ENTITY_ROOT, N_ALL, N_KEY).depth(N_LEVEL);
    }

    /** {@link #builder()} with its step: the tree of {@code parentId}, levels seeded at 0, at most {@code maxDepth}. */
    private static SourceQuery<Node> tree(int maxDepth) {
        return builder().recursive(step -> step.childKey(N_PARENT_ID).parentKey(N_ID), maxDepth).build();
    }

    @Test
    void r_src_07_the_builder_names_the_step_and_the_level_column() {
        SourceQuery<Node> tree = tree(3);

        assertThat(tree.recursive()).isTrue();
        assertThat(tree.levelAlias()).isEqualTo("mq_level");
        assertThat(tree.maxDepth()).isEqualTo(3);
    }

    @Test
    void r_src_07_a_source_that_took_no_recursion_is_not_recursive() {
        // No .depth(...) and no step: a source like any other, which is what a model without @Depth generates.
        SourceQuery<Node> plainNode = SourceQuery.builder(Node.class, ENTITY_ROOT, N_ALL, N_KEY).build();
        SourceQuery<Other> plain = plain();

        assertThat(plainNode.recursive()).isFalse();
        assertThat(plainNode.levelAlias()).isNull();
        assertThat(plainNode.maxDepth()).isZero();
        assertThat(plain.recursive()).isFalse();
    }

    @Test
    void r_src_07_a_second_step_replaces_the_first_and_keeps_the_level_column() {
        SourceQuery<Node> tree = builder()
                .recursive(step -> step.childKey(N_ID).parentKey(N_PARENT_ID), 5)
                .recursive(step -> step.childKey(N_PARENT_ID).parentKey(N_ID), 2)
                .build();

        assertThat(tree.recursive()).isTrue();
        assertThat(tree.levelAlias()).isEqualTo("mq_level");
        assertThat(tree.maxDepth()).isEqualTo(2);
    }

    @Test
    void r_src_08_a_recursive_source_is_not_unique_by_its_key() {
        assertThat(tree(3).unique()).isFalse();
        assertThat(plain().unique()).isTrue();
    }

    @Test
    void r_src_07_a_max_depth_below_one_is_mq1902() {
        for (int maxDepth : new int[] {0, -1, Integer.MIN_VALUE}) {
            ModelQueryDefinitionException thrown = code(() ->
                    builder().recursive(step -> step.childKey(N_ID).parentKey(N_PARENT_ID), maxDepth));

            assertThat(thrown.code()).isEqualTo(MqCode.MQ1902);
            assertThat(thrown.getMessage()).startsWith("MQ1902: Node: maxDepth " + maxDepth + " is out of range");
        }
    }

    @Test
    void r_src_07_a_max_depth_above_the_ceiling_is_mq1902_and_the_boundaries_are_not() {
        for (int maxDepth : new int[] {Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
            ModelQueryDefinitionException thrown = code(() ->
                    builder().recursive(step -> step.childKey(N_ID).parentKey(N_PARENT_ID), maxDepth));

            assertThat(thrown.code()).isEqualTo(MqCode.MQ1902);
            assertThat(thrown.getMessage()).contains("at most " + (Integer.MAX_VALUE - 2));
        }

        assertThat(tree(1).maxDepth()).isEqualTo(1);
        assertThat(tree(Integer.MAX_VALUE - 2).maxDepth()).isEqualTo(Integer.MAX_VALUE - 2);
    }

    @Test
    void r_src_07_the_step_on_a_model_with_no_depth_field_is_mq1902() {
        ModelQueryDefinitionException thrown = code(() -> SourceQuery
                .builder(Other.class, ENTITY_ROOT, SelectSet.of(O_ID), PrimaryKey.of(O_ID))
                .recursive(step -> step.childKey(O_ID).parentKey(O_ID), 3));

        assertThat(thrown.code()).isEqualTo(MqCode.MQ1902);
        assertThat(thrown.getMessage()).isEqualTo("MQ1902: Other: only a source whose model declares a @Depth field "
                + "recurses, and this one has none");
    }

    @Test
    void r_src_07_a_recursive_model_built_without_its_step_is_mq1902() {
        ModelQueryDefinitionException thrown = code(() -> builder().build());

        assertThat(thrown.code()).isEqualTo(MqCode.MQ1902);
        assertThat(thrown.getMessage()).isEqualTo("MQ1902: Node: a recursive source needs recursive(step -> "
                + "step.childKey(childKey).parentKey(parentKey), maxDepth) before build()");
    }

    @Test
    void r_src_07_a_recursive_source_is_never_a_branch_of_a_set_operation() {
        SourceQuery<Node> recursive = tree(3);
        SourceQuery<Node> flat = SourceQuery.builder(Node.class, ENTITY_ROOT, N_ALL, N_KEY).build();
        List<UnaryOperator<SourceQuery<Node>>> operations = List.of(recursive::union, recursive::unionAll,
                recursive::intersect, recursive::except);
        List<UnaryOperator<SourceQuery<Node>>> reversed = List.of(flat::union, flat::unionAll, flat::intersect,
                flat::except);

        for (UnaryOperator<SourceQuery<Node>> operation : operations) {
            assertRefused(() -> operation.apply(flat));
        }
        for (UnaryOperator<SourceQuery<Node>> operation : reversed) {
            assertRefused(() -> operation.apply(recursive));
        }
        // Both sides recursive is refused too, not read as two seeds.
        assertRefused(() -> recursive.union(tree(2)));
    }

    @Test
    void r_src_07_a_depth_field_outside_the_sources_fields_is_mq1902() {
        OrderedColumnField<Node, Entity, Integer> stray =
                ColumnField.of(Node.class, ENTITY_ROOT, "stray", Integer.class).named("stray");
        var builder = SourceQuery.builder(Node.class, ENTITY_ROOT, N_ALL, N_KEY).depth(stray)
                .recursive(step -> step.childKey(N_PARENT_ID).parentKey(N_ID), 3);

        ModelQueryDefinitionException thrown = code(builder::build);

        assertThat(thrown.code()).isEqualTo(MqCode.MQ1902);
        assertThat(thrown.getMessage()).contains("stray");
    }

    private static void assertRefused(Runnable call) {
        ModelQueryDefinitionException thrown = code(call);
        assertThat(thrown.code()).isEqualTo(MqCode.MQ1904);
        assertThat(thrown.getMessage()).startsWith("MQ1904: a recursive source can't be a branch of ")
                .contains("Node recurses");
    }

    private static ModelQueryDefinitionException code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
        return (ModelQueryDefinitionException) thrown;
    }
}
