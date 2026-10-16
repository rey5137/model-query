package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Set operations over sources and the key uniqueness they give a result: {@link SourceQuery}'s {@code union},
 * {@code unionAll}, {@code intersect} and {@code except} (spec api/17 R-SRC-05, R-SRC-06, R-SRC-09; RFC 0013 §4, §6).
 */
class SetOperationQueryTest {

    static final class Entity {}

    static final class Source {}

    /** A second branch model of the same shape: its rows are another entity's. */
    static final class Second {}

    static final class Outer {}

    private static final TableField<Entity, Entity> ENTITY_ROOT = TableField.root(Entity.class);
    private static final TableField<Source, Source> SOURCE_ROOT = TableField.source(Source.class);
    private static final OrderedColumnField<Source, Entity, String> KIND =
            ColumnField.of(Source.class, ENTITY_ROOT, "kind", String.class).named("kind");
    private static final OrderedColumnField<Source, Entity, Long> ID =
            ColumnField.of(Source.class, ENTITY_ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<Source, Entity, String> TITLE =
            ColumnField.of(Source.class, ENTITY_ROOT, "title", String.class).named("title");
    private static final SelectSet<Source> ALL = SelectSet.of(KIND, ID, TITLE);
    private static final PrimaryKey<Source, ?> KEY = PrimaryKey.composite(KIND, ID);
    private static final OrderedColumnField<Second, Entity, String> S2_KIND =
            ColumnField.of(Second.class, ENTITY_ROOT, "kind", String.class).named("kind");
    private static final OrderedColumnField<Second, Entity, Long> S2_ID =
            ColumnField.of(Second.class, ENTITY_ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<Second, Entity, String> S2_TITLE =
            ColumnField.of(Second.class, ENTITY_ROOT, "title", String.class).named("title");
    private static final SelectSet<Second> SECOND_ALL = SelectSet.of(S2_KIND, S2_ID, S2_TITLE);
    private static final PrimaryKey<Second, ?> SECOND_KEY = PrimaryKey.composite(S2_KIND, S2_ID);

    private static final OrderedColumnField<Outer, Source, String> O_KIND =
            ColumnField.over(Outer.class, SOURCE_ROOT, KIND).named("kind");
    private static final OrderedColumnField<Outer, Source, Long> O_ID =
            ColumnField.over(Outer.class, SOURCE_ROOT, ID).named("id");
    private static final OrderedColumnField<Outer, Source, String> O_TITLE =
            ColumnField.over(Outer.class, SOURCE_ROOT, TITLE).named("title");
    private static final SelectSet<Outer> O_ALL = SelectSet.of(O_KIND, O_ID, O_TITLE);
    private static final PrimaryKey<Outer, ?> O_KEY = PrimaryKey.composite(O_KIND, O_ID);
    private static final RowMapper<Outer> MAPPER = row -> new Outer();

    /** A source of this model, read as its own shape, with no predicate. */
    private static SourceQuery<Source> source() {
        return SourceQuery.builder(Source.class, ENTITY_ROOT, ALL, KEY).build();
    }

    /** A branch of the feed shape, its key column fixed to {@code value}. */
    private static SourceQuery<Source> branch(String value) {
        return SourceQuery.builder(Source.class, ENTITY_ROOT, ALL, KEY).discriminator(KIND, value).build();
    }

    /** The shape itself, with {@link #second()} as its only branch, so its rows are another entity's. */
    private static SourceQuery<Second> shapedSelf() {
        return SourceQuery.builder(Source.class, ENTITY_ROOT, ALL, KEY).shape(Second.class).build();
    }

    /** The other branch model of the shape. */
    private static SourceQuery<Second> second() {
        return SourceQuery.builder(Second.class, ENTITY_ROOT, SECOND_ALL, SECOND_KEY).build();
    }

    /** A unionAll of two reads of one model: each row appears twice, so the key is not unique (R-SRC-05). */
    private static SourceQuery<Source> nonUnique() {
        return source().unionAll(source());
    }

    private static ModelQuery.Builder<Source, ?, Outer> query(SourceQuery<Source> source) {
        return ModelQuery.builder(SOURCE_ROOT, MAPPER, source).select(O_ALL).primaryKey(O_KEY);
    }

    @Test
    void ac_src_07_the_operations_nest_in_the_order_they_are_called() {
        // The left side of an except must be unique, of an intersect either side must be: (n except b) intersect c
        // is unique through the right branch of the intersect, n except (b intersect c) is not (R-SRC-05).
        SourceQuery<Source> chained = branch("A").except(branch("B")).intersect(branch("C"));
        SourceQuery<Source> nested = branch("A").except(branch("B").intersect(branch("C")));

        assertThat(chained.unique()).isTrue();
        assertThat(nested.unique()).isTrue();
        assertThat(nonUnique().except(branch("B")).intersect(branch("C")).unique()).isTrue();
        assertThat(nonUnique().except(branch("B").intersect(branch("C"))).unique()).isFalse();
    }

    @Test
    void ac_src_07_a_combined_source_carries_the_shape_structure_and_no_predicates_of_its_own() {
        SourceQuery<Source> both = branch("A").union(branch("B").intersect(branch("C")));

        assertThat(both.model()).isEqualTo(Source.class);
        assertThat(both.key()).isSameAs(KEY);
        assertThat(both.fields()).isSameAs(ALL);
        assertThat(both.where()).isEmpty();
        assertThat(both.having()).isNull();
    }

    @Test
    void ac_src_05_a_union_of_branches_with_different_discriminators_is_unique() {
        assertThat(branch("A").union(branch("B")).unique()).isTrue();
        assertThat(branch("A").unionAll(branch("B")).unique()).isTrue();
    }

    @Test
    void ac_src_05_a_distinct_union_of_one_branch_model_is_unique() {
        SourceQuery<Source> owned = SourceQuery.builder(Source.class, ENTITY_ROOT, ALL, KEY)
                .where(f -> f.eq(TITLE, "owned")).build();

        // Each root row yields the same columns in both branches, so the distinct union collapses them (R-SRC-05).
        assertThat(owned.union(source()).unique()).isTrue();
        // A unionAll keeps both copies of every shared row, so the key is no longer unique.
        assertThat(owned.unionAll(source()).unique()).isFalse();
    }

    @Test
    void ac_src_05_a_distinct_union_of_one_grouped_model_is_not_unique() {
        // An aggregate is computed over the branch's own filtered rows, so one key may carry two different rows.
        SourceQuery<Source> grouped = SourceQuery.builder(Source.class, ENTITY_ROOT, ALL, KEY, SelectSet.of(KIND, ID))
                .where(f -> f.eq(TITLE, "owned")).build();
        SourceQuery<Source> all = SourceQuery.builder(Source.class, ENTITY_ROOT, ALL, KEY, SelectSet.of(KIND, ID))
                .build();

        assertThat(grouped.union(all).unique()).isFalse();
    }

    @Test
    void ac_src_05_a_union_of_two_branch_models_is_not_unique() {
        SourceQuery<Second> shape = shapedSelf();
        SourceQuery<Second> other = second();

        // Two models of one shape read different entities, so one key may carry two different rows.
        assertThat(shape.union(other).unique()).isFalse();
        assertThat(shape.unionAll(other).unique()).isFalse();
        // Either unique branch makes an intersect unique, a unique left branch makes an except unique.
        assertThat(shape.intersect(other).unique()).isTrue();
        assertThat(shape.except(other).unique()).isTrue();
    }

    @Test
    void ac_src_05_a_union_all_of_one_model_is_not_unique() {
        assertThat(nonUnique().unique()).isFalse();
        assertThat(nonUnique().intersect(source()).unique()).isTrue();
        assertThat(source().intersect(nonUnique()).unique()).isTrue();
        assertThat(nonUnique().except(source()).unique()).isFalse();
        assertThat(source().except(nonUnique()).unique()).isTrue();
    }

    @Test
    void ac_src_06_one_discriminator_value_in_one_union_is_mq1904() {
        assertThat(code(() -> branch("A").unionAll(branch("A"))).code()).isEqualTo(MqCode.MQ1904);
        // A distinct union of one model removes the duplicates itself, so it doesn't clash.
        assertThat(code(() -> branch("A").union(branch("A")))).isNull();
        // The check reads the values every side can carry, not only its two sides.
        assertThat(code(() -> branch("A").union(branch("B")).unionAll(branch("A"))).code())
                .isEqualTo(MqCode.MQ1904);
        // An intersect carries only the values both sides share, so it doesn't clash with a different one.
        assertThat(code(() -> branch("A").intersect(branch("A")).unionAll(branch("B")))).isNull();
        assertThat(code(() -> branch("A").union(branch("B")).unionAll(branch("C")))).isNull();
    }

    @Test
    void ac_src_06_intersect_and_except_do_not_check_discriminator_values() {
        assertThat(branch("A").intersect(branch("A")).unique()).isTrue();
        assertThat(branch("A").except(branch("A")).unique()).isTrue();
    }

    @Test
    void ac_src_06_an_intersect_inside_a_union_all_carries_only_the_values_both_sides_share() {
        SourceQuery<Source> feed = branch("X").intersect(branch("X")).unionAll(branch("Y"));

        assertThat(feed.unique()).isTrue();
        assertThat(code(() -> query(feed).keyset().build())).isNull();
        // An intersect of different values carries none, so it is disjoint from everything.
        assertThat(branch("X").intersect(branch("Y")).unionAll(branch("X")).unique()).isTrue();
    }

    @Test
    void ac_src_06_an_except_carries_the_values_of_its_left_side_only() {
        // (a[X] except b[Y]) carries {X}, so a union with c[Y] is disjoint and unique, and with c[X] it clashes.
        assertThat(branch("X").except(branch("Y")).union(branch("Y")).unique()).isTrue();
        assertThat(branch("X").except(branch("Y")).unionAll(branch("Y")).unique()).isTrue();
        assertThat(code(() -> branch("X").except(branch("Y")).unionAll(branch("X"))).code())
                .isEqualTo(MqCode.MQ1904);
    }

    @Test
    void ac_src_06_a_branch_without_a_discriminator_value_keeps_a_union_of_two_models_from_being_unique() {
        // A branch that fixes no value can carry any value, so no disjointness is known: never unique, never MQ1904.
        assertThat(source().unionAll(branch("A")).unique()).isFalse();
        assertThat(branch("A").unionAll(branch("B").unionAll(source())).unique()).isFalse();
    }

    @Test
    void ac_src_06_a_distinct_union_of_one_model_with_one_discriminator_value_is_unique() {
        assertThat(branch("A").union(branch("A")).unique()).isTrue();
        // Two values of one model are disjoint, so the union is unique without the same-model rule.
        assertThat(branch("A").union(branch("B")).union(branch("C")).unique()).isTrue();
        // A third branch repeating a value of the first two clashes, since the three do not share one value.
        assertThat(code(() -> branch("A").union(branch("B")).union(branch("A"))).code()).isEqualTo(MqCode.MQ1904);
        // A branch with no discriminator says nothing about the key, so the union is not unique by value.
        assertThat(code(() -> branch("A").unionAll(source()))).isNull();
        assertThat(branch("A").unionAll(source()).unique()).isFalse();
    }

    @Test
    void ac_src_06_a_discriminator_outside_the_key_is_mq1904_at_build() {
        var builder = SourceQuery.builder(Source.class, ENTITY_ROOT, ALL, KEY).discriminator(TITLE, "A");

        ModelQueryDefinitionException thrown = code(builder::build);

        assertThat(thrown.code()).isEqualTo(MqCode.MQ1904);
        assertThat(thrown.getMessage()).contains("title").contains("key");
    }

    @Test
    void ac_src_06_branches_whose_fields_differ_do_not_combine() {
        OrderedColumnField<Source, Entity, Long> titleAsLong =
                ColumnField.of(Source.class, ENTITY_ROOT, "title", Long.class).named("title");
        OrderedColumnField<Source, Entity, String> other =
                ColumnField.of(Source.class, ENTITY_ROOT, "other", String.class).named("other");
        SourceQuery<Source> reordered = SourceQuery.builder(Source.class, ENTITY_ROOT,
                SelectSet.of(KIND, TITLE, ID), KEY).build();
        SourceQuery<Source> retyped = SourceQuery.builder(Source.class, ENTITY_ROOT,
                SelectSet.of(KIND, ID, titleAsLong), KEY).build();
        SourceQuery<Source> renamed = SourceQuery.builder(Source.class, ENTITY_ROOT,
                SelectSet.of(KIND, ID, other), KEY).build();
        SourceQuery<Source> shorter = SourceQuery.builder(Source.class, ENTITY_ROOT, SelectSet.of(KIND, ID), KEY)
                .build();

        for (SourceQuery<Source> differing : List.of(reordered, retyped, renamed, shorter)) {
            assertThat(code(() -> source().union(differing)).code()).isEqualTo(MqCode.MQ1904);
            assertThat(code(() -> source().except(differing)).code()).isEqualTo(MqCode.MQ1904);
        }
        assertThat(code(() -> source().union(source()))).isNull();
    }

    @Test
    void ac_src_09_keyset_over_a_non_unique_source_is_mq1901() {
        assertThat(code(() -> query(nonUnique()).keyset().build()).code()).isEqualTo(MqCode.MQ1901);
        assertThat(code(() -> query(nonUnique()).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(1_000L)).build())
                .code()).isEqualTo(MqCode.MQ1901);
        // A unique source builds the same read, and a non-key read of a non-unique one builds too (R-SRC-09).
        assertThat(code(() -> query(source().union(source())).keyset().build())).isNull();
        assertThat(code(() -> query(nonUnique()).build())).isNull();
    }

    @Test
    void ac_src_20_overlapping_union_all_keyset_is_mq1901_without_a_tree_hint() {
        var failure = code(() -> query(nonUnique()).keyset().build());
        assertThat(failure.code()).isEqualTo(MqCode.MQ1901);
        assertThat(failure).hasMessageNotContaining("tree(...)");
    }

    private static ModelQueryDefinitionException code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        if (thrown == null) {
            return null;
        }
        assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
        return (ModelQueryDefinitionException) thrown;
    }
}
