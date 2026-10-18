package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceReadTest {

    static final class Entity {}

    static final class Source {}

    static final class Chained {}

    static final class Outer {}

    private static final TableField<Entity, Entity> ENTITY_ROOT = TableField.root(Entity.class);
    private static final TableField<Source, Source> SOURCE_ROOT = TableField.source(Source.class);
    private static final OrderedColumnField<Source, Entity, Long> ID =
            ColumnField.of(Source.class, ENTITY_ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<Source, Entity, Long> CUSTOMER_ID =
            ColumnField.of(Source.class, ENTITY_ROOT, "customer", Long.class).named("customerId");
    private static final WindowField<Source, Long> RANK = WindowField.of(Source.class, WindowFunction.ROW_NUMBER,
            Long.class, null, List.of(CUSTOMER_ID), List.of(ID.asc()), WindowFrame.NONE).named("rank");
    private static final AggregateField<Source, Long> COUNT = Agg.<Source>count(ENTITY_ROOT).named("count");
    private static final SelectSet<Source> ALL = SelectSet.of(ID, CUSTOMER_ID, RANK);
    private static final PrimaryKey<Source, Long> KEY = PrimaryKey.of(ID);
    private static final OrderedColumnField<Source, Source, Long> ROW_ID =
            ColumnField.over(Source.class, SOURCE_ROOT, ID).named("id");
    private static final OrderedColumnField<Source, Source, Long> ROW_CUSTOMER_ID =
            ColumnField.over(Source.class, SOURCE_ROOT, CUSTOMER_ID).named("customerId");
    private static final OrderedColumnField<Source, Source, Long> ROW_RANK =
            ColumnField.over(Source.class, SOURCE_ROOT, RANK).named("rank");
    private static final SelectSet<Source> ROWS = SelectSet.of(ROW_ID, ROW_CUSTOMER_ID, ROW_RANK);
    private static final PrimaryKey<Source, Long> ROW_KEY = PrimaryKey.of(ROW_ID);
    private static final RowMapper<Source> MAPPER = row -> new Source();

    private static SourceQuery.Builder<Entity, Source, Source> sourceBuilder() {
        return SourceQuery.builder(Source.class, ENTITY_ROOT, ALL, KEY);
    }

    private static ModelQuery.Builder<Source, Long, Source> readBuilder() {
        return ModelQuery.builder(SOURCE_ROOT, MAPPER, sourceBuilder().build()).primaryKey(ROW_KEY);
    }

    @Test
    void ac_src_35_selecting_definition_columns_is_mq1905_at_build() {
        var builder = readBuilder().select(SelectSet.of(ID));

        rejects(builder::build, "QSource.Rows.ID");
    }

    @Test
    void ac_src_35_filtering_definition_columns_is_mq1905_at_build() {
        var builder = readBuilder().select(ROWS).where(f -> f.eq(CUSTOMER_ID, Optional.of(3L)));

        rejects(builder::build, "QSource.Rows.CUSTOMER_ID");
    }

    @Test
    void ac_src_35_ordering_definition_columns_is_mq1905_at_build() {
        var builder = readBuilder().select(ROWS).orderBy(CUSTOMER_ID.asc());

        rejects(builder::build, "QSource.Rows.CUSTOMER_ID");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ac_src_35_an_outer_read_names_its_own_constant_in_select_where_and_order_errors() {
        var id = ColumnField.over(Outer.class, SOURCE_ROOT, ID).named("id");
        var builder = ModelQuery.builder(SOURCE_ROOT, row -> new Outer(), sourceBuilder().build())
                .primaryKey(PrimaryKey.of(id));

        rejects(builder.select((SelectSet) SelectSet.of(ID))::build, "QOuter.ID");
        rejects(builder.select(SelectSet.of(id)).where(f -> f.eq((ScalarField) ID, 1L))::build, "QOuter.ID");
        rejects(builder.select(SelectSet.of(id)).orderBy((OrderField) ID.asc())::build, "QOuter.ID");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ac_src_35_an_erased_read_model_names_both_replacement_vocabularies() {
        var builder = ModelQuery.builder(SOURCE_ROOT, row -> new Outer(), sourceBuilder().build())
                .select((SelectSet) SelectSet.of(ID));

        assertThatThrownBy(builder::build).isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("use QSource.Rows.ID for a direct read")
                .hasMessageContaining("the outer query model's ID constant")
                .extracting(error -> ((ModelQueryDefinitionException) error).code()).isEqualTo(MqCode.MQ1905);
    }

    @Test
    void ac_src_35_combined_sources_reject_definition_aggregate_operands_at_build() {
        var first = sourceBuilder().where(f -> f.eq(ID, 1L)).build();
        var second = sourceBuilder().where(f -> f.eq(ID, 2L)).build();
        for (var source : List.of(first.union(second), first.unionAll(second), first.intersect(second),
                first.except(second), first.union(second).except(first))) {
            for (var aggregate : List.of(Agg.sum(ID), Agg.count(ID), Agg.countDistinct(ID), Agg.min(ID),
                    Agg.max(ID), Agg.avg(ID))) {
                var builder = ModelQuery.builder(SOURCE_ROOT, MAPPER, source).select(SelectSet.of(aggregate));
                rejects(builder::build, "QSource.Rows.ID");
            }
            var legal = ModelQuery.builder(SOURCE_ROOT, MAPPER, source).select(SelectSet.of(Agg.sum(ROW_ID))).build();
            assertThat(legal.source()).containsSame(source);
        }
    }

    @Test
    void ac_src_35_definition_windows_and_aggregates_name_the_rows_constant() {
        rejects(readBuilder().select(SelectSet.of(RANK))::build, "QSource.Rows.RANK");
        var grouped = SourceQuery.builder(Source.class, ENTITY_ROOT, SelectSet.of(CUSTOMER_ID, COUNT),
                PrimaryKey.of(CUSTOMER_ID), SelectSet.of(CUSTOMER_ID)).build();
        var query = ModelQuery.builder(SOURCE_ROOT, MAPPER, grouped).select(SelectSet.of(COUNT));

        rejects(query::build, "QSource.Rows.COUNT");
    }

    @Test
    void ac_src_35_nested_expressions_and_filters_cannot_hide_definition_columns() {
        var selected = Expr.plus(ID, 1L);
        rejects(readBuilder().select(SelectSet.of(selected))::build, "QSource.Rows.ID");
        var filtered = readBuilder().select(ROWS)
                .where(f -> f.not(n -> n.eq(Expr.plus(CUSTOMER_ID, 1L), 3L)));

        rejects(filtered::build, "QSource.Rows.CUSTOMER_ID");
    }

    @Test
    void ac_src_36_filtering_rows_columns_in_a_source_is_mq1905_at_build() {
        var builder = sourceBuilder().where(f -> f.eq(ROW_CUSTOMER_ID, Optional.of(3L)));

        rejects(builder::build, "QSource.CUSTOMER_ID");
    }

    @Test
    void ac_src_36_selecting_rows_columns_in_a_source_is_mq1905_at_build() {
        var builder = SourceQuery.builder(Source.class, ENTITY_ROOT, ALL.with(ROW_CUSTOMER_ID), KEY);

        rejects(builder::build, "QSource.CUSTOMER_ID");
    }

    @Test
    void ac_src_36_window_orders_arguments_and_partitions_reject_rows_columns() {
        var ordered = WindowField.of(Source.class, WindowFunction.ROW_NUMBER, Long.class, null, List.of(),
                List.of(ROW_CUSTOMER_ID.asc()), WindowFrame.NONE).named("ordered");
        var partitioned = WindowField.of(Source.class, WindowFunction.ROW_NUMBER, Long.class, null,
                List.of(ROW_CUSTOMER_ID), List.of(ID.asc()), WindowFrame.NONE).named("partitioned");
        var summed = WindowField.of(Source.class, WindowFunction.SUM, Long.class, ROW_CUSTOMER_ID, List.of(),
                List.of(ID.asc()), WindowFrame.NONE).named("summed");

        for (var window : List.of(ordered, partitioned, summed)) {
            var builder = SourceQuery.builder(Source.class, ENTITY_ROOT, ALL.with(window), KEY);
            rejects(builder::build, "QSource.CUSTOMER_ID");
        }
    }

    @Test
    void ac_src_36_group_keys_and_having_reject_rows_columns() {
        var grouped = SourceQuery.builder(Source.class, ENTITY_ROOT, ALL, KEY, SelectSet.of(ROW_CUSTOMER_ID));
        rejects(grouped::build, "QSource.CUSTOMER_ID");
        var having = SourceQuery.builder(Source.class, ENTITY_ROOT, SelectSet.of(CUSTOMER_ID, COUNT),
                PrimaryKey.of(CUSTOMER_ID), SelectSet.of(CUSTOMER_ID))
                .having(h -> h.gt(Agg.sum(ROW_CUSTOMER_ID), 1L));

        rejects(having::build, "QSource.CUSTOMER_ID");
    }

    @Test
    void ac_src_36_nested_expressions_and_filters_cannot_hide_rows_columns() {
        var selected = SourceQuery.builder(Source.class, ENTITY_ROOT,
                ALL.with(Expr.plus(ROW_CUSTOMER_ID, 1L).named("computed")), KEY);
        rejects(selected::build, "QSource.CUSTOMER_ID");
        var filtered = sourceBuilder().where(f -> f.not(n -> n.eq(Expr.plus(ROW_CUSTOMER_ID, 1L), 3L)));

        rejects(filtered::build, "QSource.CUSTOMER_ID");
    }

    @Test
    void ac_src_34_rows_select_filter_order_and_keyset_build_without_mutating_definitions() {
        var sourceBuilder = sourceBuilder();
        var source = sourceBuilder.where(f -> f.eq(CUSTOMER_ID, 3L)).build();
        var base = ModelQuery.builder(SOURCE_ROOT, MAPPER, source).select(ROWS).primaryKey(ROW_KEY);
        var filtered = base.where(f -> f.lte(ROW_RANK, 3L)).orderBy(ROW_ID.asc()).keyset();

        assertThat(filtered.build().source()).containsSame(source);
        assertThat(filtered.build().select().fields()).containsExactlyElementsOf(ROWS.fields());
        assertThat(base.build().conditions().where()).isEmpty();
        assertThat(sourceBuilder.build().where()).isEmpty();
    }

    @Test
    void ac_src_35_skipped_filters_do_not_mix_vocabularies() {
        var query = readBuilder().select(ROWS).where(f -> f.eq(CUSTOMER_ID, Optional.empty())).build();
        var source = sourceBuilder().where(f -> f.eq(ROW_CUSTOMER_ID, Optional.empty())).build();

        assertThat(query.conditions().where()).isEmpty();
        assertThat(source.where()).isEmpty();
    }

    @Test
    void ac_src_36_a_source_over_a_source_keeps_its_inner_vocabulary() {
        var id = ColumnField.over(Chained.class, SOURCE_ROOT, ID).named("id");
        var fields = SelectSet.of(id);
        var source = SourceQuery.builder(Chained.class, SOURCE_ROOT, fields, PrimaryKey.of(id),
                sourceBuilder().build()).where(f -> f.eq(id, 1L)).build();
        var root = TableField.source(Chained.class);
        var rowId = ColumnField.over(Chained.class, root, id).named("id");
        var query = ModelQuery.builder(root, row -> new Chained(), source).select(SelectSet.of(rowId))
                .primaryKey(PrimaryKey.of(rowId)).orderBy(rowId.asc()).keyset().build();

        assertThat(query.source()).containsSame(source);
        var wrongRead = ModelQuery.builder(root, row -> new Chained(), source).select(fields);
        rejects(wrongRead::build, "QChained.Rows.ID");
        var wrongSource = SourceQuery.builder(Chained.class, SOURCE_ROOT, fields, PrimaryKey.of(id),
                sourceBuilder().build()).where(f -> f.eq(rowId, 1L));
        rejects(wrongSource::build, "QChained.ID");
    }

    private static void rejects(Runnable build, String replacement) {
        assertThatThrownBy(build::run).isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining("use " + replacement)
                .extracting(error -> ((ModelQueryDefinitionException) error).code()).isEqualTo(MqCode.MQ1905);
    }
}
