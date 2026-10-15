package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.criteria.JoinType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The core shape of a source: {@link TableField#source}, {@link SourceQuery}'s builder, the outer model's columns and a
 * {@link ModelQuery} that carries the source (spec api/17 R-SRC-01, R-SRC-02, R-SRC-03, R-SRC-10).
 */
class SourceQueryTest {

    static final class Entity {}

    static final class Source {}

    static final class OtherSource {}

    static final class Outer {}

    enum Status {
        NEW
    }

    /** A converter that orders, so the column it converts stays an {@link OrderedColumnField}. */
    static final class StatusConverter implements OrderedColumnConverter<Status, String> {

        @Override
        public Status toModel(String attribute) {
            return Status.valueOf(attribute);
        }

        @Override
        public String toAttribute(Status model) {
            return model.name();
        }
    }

    /** A converter that does not order, so the column it converts is a plain {@link ColumnField}. */
    static final class PlainConverter implements ColumnConverter<Status, String> {

        @Override
        public Status toModel(String attribute) {
            return Status.valueOf(attribute);
        }

        @Override
        public String toAttribute(Status model) {
            return model.name();
        }
    }

    private static final TableField<Entity, Entity> ENTITY_ROOT = TableField.root(Entity.class);
    private static final TableField<Source, Source> SOURCE_ROOT = TableField.source(Source.class);
    private static final OrderedColumnField<Entity, Entity, Long> E_ID =
            ColumnField.of(Entity.class, ENTITY_ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<Source, Entity, Long> S_ID =
            ColumnField.of(Source.class, ENTITY_ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<Source, Entity, String> S_STATUS =
            ColumnField.of(Source.class, ENTITY_ROOT, "status", String.class).named("status");
    private static final ColumnField<Source, Entity, Status> S_ORDERED_CONVERTED =
            ColumnField.of(Source.class, ENTITY_ROOT, "state", Status.class, String.class, new StatusConverter())
                    .named("state");
    private static final ColumnField<Source, Entity, Status> S_PLAIN_CONVERTED =
            ColumnField.of(Source.class, ENTITY_ROOT, "state", Status.class, String.class, new PlainConverter())
                    .named("state");
    private static final AggregateField<Source, Long> S_COUNT = Agg.count(ENTITY_ROOT);
    private static final SelectSet<Source> S_ALL = SelectSet.of(S_ID, S_STATUS);
    private static final SelectSet<Source> S_GROUPED = SelectSet.of(S_STATUS, S_COUNT);
    private static final SelectSet<Source> S_GROUP_KEYS = SelectSet.of(S_STATUS);
    private static final PrimaryKey<Source, Long> S_KEY = PrimaryKey.of(S_ID);
    private static final PrimaryKey<Source, String> S_GROUP_KEY = PrimaryKey.of(S_STATUS);

    private static final OrderedColumnField<Outer, Source, Long> O_ID =
            ColumnField.over(Outer.class, SOURCE_ROOT, S_ID).named("id");
    private static final OrderedColumnField<Outer, Source, String> O_STATUS =
            ColumnField.over(Outer.class, SOURCE_ROOT, S_STATUS).named("status");
    private static final OrderedColumnField<Outer, Source, Status> O_CONVERTED =
            ColumnField.over(Outer.class, SOURCE_ROOT, (SelectField<Source, Status>) S_ORDERED_CONVERTED)
                    .named("state");
    private static final AggregateField<Outer, Long> O_COUNT = Agg.count(SOURCE_ROOT);
    private static final SelectSet<Outer> O_ALL = SelectSet.of(O_ID, O_STATUS);
    private static final PrimaryKey<Outer, Long> O_KEY = PrimaryKey.of(O_ID);
    private static final RowMapper<Outer> MAPPER = row -> new Outer();

    private static SourceQuery<Source> source() {
        return SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL, S_KEY).build();
    }

    /** A source whose shape is the entity itself, the only kind an entity root could carry. */
    private static SourceQuery<Entity> entitySource() {
        return SourceQuery.builder(Entity.class, ENTITY_ROOT, SelectSet.of(E_ID), PrimaryKey.of(E_ID)).build();
    }

    @Test
    void r_src_01_the_builder_records_the_fields_the_key_and_the_predicates() {
        SourceQuery<Source> query = SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL, S_KEY)
                .where(f -> f.eq(S_STATUS, "PAID"))
                .build();

        assertThat(query.model()).isEqualTo(Source.class);
        assertThat(query.root()).isSameAs(ENTITY_ROOT);
        assertThat(query.fields()).isSameAs(S_ALL);
        assertThat(query.key()).isSameAs(S_KEY);
        assertThat(query.groupKeys()).isNull();
        assertThat(query.where()).hasSize(1);
        assertThat(query.having()).isNull();
    }

    @Test
    void r_src_02_a_grouped_source_carries_its_group_keys_and_its_having() {
        SourceQuery<Source> query = SourceQuery.builder(Source.class, ENTITY_ROOT, S_GROUPED, S_GROUP_KEY, S_GROUP_KEYS)
                .where(f -> f.eq(S_STATUS, "PAID"))
                .having(h -> h.gt(S_COUNT, 1L))
                .build();

        assertThat(query.groupKeys()).isSameAs(S_GROUP_KEYS);
        assertThat(query.where()).hasSize(1);
        assertThat(query.having()).isNotNull();
    }

    @Test
    void r_src_03_having_on_an_ungrouped_source_is_mq1407() {
        ModelQueryDefinitionException thrown = code(() -> SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL, S_KEY)
                .having(h -> h.gt(S_COUNT, 1L))
                .build());

        assertThat(thrown.code()).isEqualTo(MqCode.MQ1407);
    }

    @Test
    void r_src_10_the_source_builder_rejects_a_join_root_with_mq1203() {
        TableField<Entity, Entity> join = TableField.join(ENTITY_ROOT, "parent", JoinType.LEFT);

        assertThat(code(() -> SourceQuery.builder(Source.class, join, S_ALL, S_KEY)).code())
                .isEqualTo(MqCode.MQ1203);
    }

    @Test
    void r_src_10_the_model_query_builder_takes_a_source_root_and_carries_the_source() {
        SourceQuery<Source> source = source();
        ModelQuery<Source, Long, Outer> query = ModelQuery.builder(SOURCE_ROOT, MAPPER, source).select(O_ALL)
                .primaryKey(O_KEY).build();

        assertThat(query.rootEntity()).isEqualTo(Source.class);
        assertThat(query.source()).containsSame(source);
        assertThat(query.select().fields()).containsExactly(O_ID, O_STATUS);
    }

    @Test
    void r_src_10_every_builder_copy_carries_the_source() {
        SourceQuery<Source> source = source();
        ModelQuery.Builder<Source, Long, Outer> base =
                ModelQuery.builder(SOURCE_ROOT, MAPPER, source).select(O_ALL).primaryKey(O_KEY);
        var grouped = ModelQuery.builder(SOURCE_ROOT, MAPPER, source).select(SelectSet.of(O_STATUS, O_COUNT));

        List<ModelQuery<Source, ?, Outer>> copies = List.of(
                base.build(),
                base.build().withLock(RowLock.exclusive()),
                base.build().withFetch(FetchPlan.of(O_ALL)),
                base.build().orderedBy(SortSpec.of(SortSpec.Key.asc("status"))),
                base.select(O_ALL).build(),
                base.fetch(FetchPlan.of(O_ALL)).build(),
                base.primaryKey(O_KEY).build(),
                base.orderBy(O_ID.asc()).build(),
                base.keyset().build(),
                base.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(1_000L)).build(),
                base.lock(RowLock.shared()).build(),
                base.where(f -> f.eq(O_STATUS, "PAID")).build(),
                base.afterMap((outer, row) -> { }).build(),
                base.finisher(outer -> outer).build(),
                base.customize((spec, joins, query, cb, phase) -> { }).build(),
                grouped.groupBy(O_STATUS).build(),
                grouped.groupBy(O_STATUS).having(h -> h.gt(O_COUNT, 1L)).build());

        assertThat(copies).allSatisfy(copy -> assertThat(copy.source()).containsSame(source));
    }

    @Test
    void r_src_10_the_model_query_builder_rejects_a_mismatched_root_with_mq1203() {
        assertThat(code(() -> ModelQuery.builder(SOURCE_ROOT, MAPPER)).code()).isEqualTo(MqCode.MQ1203);
        assertThat(code(() -> ModelQuery.builder(ENTITY_ROOT, MAPPER, entitySource())).code())
                .isEqualTo(MqCode.MQ1203);
    }

    @Test
    void r_src_10_a_write_or_insert_builder_rejects_a_source_root_with_mq1203() {
        assertThat(code(() -> ModelUpdate.builder(SOURCE_ROOT)).code()).isEqualTo(MqCode.MQ1203);
        assertThat(code(() -> ModelDelete.builder(SOURCE_ROOT)).code()).isEqualTo(MqCode.MQ1203);
        assertThat(code(() -> InsertColumns.of(SOURCE_ROOT)).code()).isEqualTo(MqCode.MQ1203);
    }

    @Test
    void r_src_10_column_field_of_over_a_source_root_is_mq1905() {
        assertThat(code(() -> ColumnField.of(Outer.class, SOURCE_ROOT, "id", Long.class)).code())
                .isEqualTo(MqCode.MQ1905);
        assertThat(code(() -> ColumnField.of(Outer.class, SOURCE_ROOT, "state", Status.class, String.class,
                new PlainConverter())).code()).isEqualTo(MqCode.MQ1905);
    }

    @Test
    void r_src_10_table_field_join_from_a_source_root_is_mq1905() {
        assertThat(code(() -> TableField.join(SOURCE_ROOT, "id", JoinType.LEFT)).code()).isEqualTo(MqCode.MQ1905);
    }

    @Test
    void r_src_10_over_reads_the_source_fields_alias_and_keeps_its_converter() {
        assertThat(O_ID.name()).isEqualTo("mq_id");
        assertThat(O_ID.property()).contains("id");
        assertThat(O_ID.type()).isEqualTo(Long.class);
        assertThat(O_ID.table()).isSameAs(SOURCE_ROOT);
        assertThat(O_ID.converterClass()).isNull();

        assertThat(O_CONVERTED.name()).isEqualTo("mq_state");
        assertThat(O_CONVERTED.type()).isEqualTo(Status.class);
        assertThat(O_CONVERTED.converterClass()).isEqualTo(StatusConverter.class);
        assertThat(SOURCE_ROOT.isSource()).isTrue();
        assertThat(ENTITY_ROOT.isSource()).isFalse();
    }

    @Test
    void r_src_10_over_on_an_entity_root_or_a_field_with_no_property_is_mq1905() {
        OrderedColumnField<Source, Entity, String> unnamed = ColumnField.of(Source.class, ENTITY_ROOT, "status",
                String.class);

        assertThat(code(() -> ColumnField.over(Outer.class, ENTITY_ROOT, E_ID)).code()).isEqualTo(MqCode.MQ1905);
        assertThat(code(() -> ColumnField.over(Outer.class, SOURCE_ROOT, unnamed)).code()).isEqualTo(MqCode.MQ1905);
    }

    @Test
    void r_src_10_over_of_a_select_field_refuses_a_converter_that_does_not_order() {
        SelectField<Source, Status> plain = S_PLAIN_CONVERTED;
        SelectField<Source, Status> ordered = S_ORDERED_CONVERTED;

        assertThat(code(() -> ColumnField.over(Outer.class, SOURCE_ROOT, plain)).code()).isEqualTo(MqCode.MQ1905);
        assertThat(ColumnField.over(Outer.class, SOURCE_ROOT, ordered).converterClass())
                .isEqualTo(StatusConverter.class);
    }

    @Test
    void r_src_10_a_source_root_shares_its_models_key_so_mq1003_still_catches_it() {
        TableField<Source, Source> sourceRoot = TableField.source(Source.class);

        assertThat(sourceRoot.key()).isEqualTo(TableField.root(Source.class).key());
        assertThat(sourceRoot.key()).isNotEqualTo(TableField.source(OtherSource.class).key());
    }

    private static ModelQueryDefinitionException code(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(ModelQueryDefinitionException.class);
        return (ModelQueryDefinitionException) thrown;
    }
}
