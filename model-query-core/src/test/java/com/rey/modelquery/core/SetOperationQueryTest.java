package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
    private static final SelectSet<Second> SECOND_ALL = SelectSet.of(S2_KIND, S2_ID);
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
        assertThat(code(() -> branch("A").union(branch("A"))).code()).isEqualTo(MqCode.MQ1904);
        assertThat(code(() -> branch("A").unionAll(branch("A"))).code()).isEqualTo(MqCode.MQ1904);
        // The check reads every leaf of the operation tree, not only its two sides.
        assertThat(code(() -> branch("A").union(branch("B")).unionAll(branch("A"))).code())
                .isEqualTo(MqCode.MQ1904);
        assertThat(code(() -> branch("A").union(branch("B")).unionAll(branch("C")))).isNull();
    }

    @Test
    void ac_src_06_intersect_and_except_do_not_check_discriminator_values() {
        assertThat(branch("A").intersect(branch("A")).unique()).isTrue();
        assertThat(branch("A").except(branch("A")).unique()).isTrue();
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

    private static ModelQueryDefinitionException code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        if (thrown == null) {
            return null;
        }
        assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
        return (ModelQueryDefinitionException) thrown;
    }
}
