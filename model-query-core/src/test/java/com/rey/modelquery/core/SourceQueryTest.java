package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CompoundSelection;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void ac_src_26_inner_source_survives_builder_copies_without_mutating_the_base() {
        var inner = source();
        var base = SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, inner);
        var filtered = base.where(f -> f.eq(O_STATUS, "PAID")).build();

        assertThat(base.build().inner()).isSameAs(inner);
        assertThat(base.build().where()).isEmpty();
        assertThat(filtered.inner()).isSameAs(inner);
        assertThat(filtered.where()).hasSize(1);
        assertThat(filtered.unique()).isTrue();
        assertThat(source().inner()).isNull();
    }

    @Test
    void ac_src_31_chained_ctes_are_mq4014_when_the_renderer_does_not_implement_the_chain_spi() {
        var chain = SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, source()).build();
        var depth = ColumnField.of(Source.class, ENTITY_ROOT, "level", Integer.class).named("level");
        var parent = ColumnField.of(Source.class, ENTITY_ROOT, "parentId", Long.class).named("parentId");
        var walk = SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL.with(depth), S_KEY).depth(depth)
                .recursive(step -> step.childKey(parent).parentKey(S_ID), 4).build();
        var walkChain = SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, walk).build();
        var root = TableField.source(Outer.class);
        var id = ColumnField.over(OtherSource.class, root, O_ID).named("id");
        var cb = criteria();
        var options = RenderOptions.portable().withSourceRenderer(new SourceRenderer() {
            @Override
            public Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
                throw new AssertionError("Unexpected single-level cte");
            }

            @Override
            public CriteriaQuery<Tuple> combine(CriteriaBuilder builder, SetOperation op, CriteriaQuery<Tuple> left,
                    CriteriaQuery<Tuple> right) {
                return left;
            }
        });

        var plainId = ColumnField.of(Outer.class, ENTITY_ROOT, "id", Long.class).named("id");
        var plainStatus = ColumnField.of(Outer.class, ENTITY_ROOT, "status", String.class).named("status");
        var plain = SourceQuery.builder(
                Outer.class, ENTITY_ROOT, SelectSet.of(plainId, plainStatus), PrimaryKey.of(plainId)).build();
        for (var definition : List.of(chain, walkChain, chain.union(chain), plain.union(chain), chain.union(plain),
                plain.unionAll(chain), chain.unionAll(plain), chain.intersect(plain), plain.intersect(chain),
                chain.except(plain), plain.except(chain), plain.union(chain).intersect(plain),
                plain.union(plain.union(chain)))) {
            var query = ModelQuery.builder(root, row -> new OtherSource(), definition)
                    .select(SelectSet.of(id)).primaryKey(PrimaryKey.of(id)).build();
            for (Phase phase : Phase.values()) {
                Throwable thrown = catchThrowable(() -> query.buildQuery(cb, phase, options));
                assertThat(thrown).isInstanceOf(ModelQueryConfigurationException.class)
                        .hasMessageContaining("chained CTEs");
                assertThat(((ModelQueryConfigurationException) thrown).code()).isEqualTo(MqCode.MQ4014);
            }
        }
    }

    @Test
    void ac_src_31_and_32_a_chain_registers_every_level_on_top_and_places_walk_guards_in_the_next_body() {
        var outerRoot = TableField.source(Outer.class);
        var otherId = ColumnField.over(OtherSource.class, outerRoot, O_ID).named("id");
        var otherStatus = ColumnField.over(OtherSource.class, outerRoot, O_STATUS).named("status");
        var otherAll = SelectSet.of(otherId, otherStatus);
        var otherKey = PrimaryKey.of(otherId);
        var topRoot = TableField.source(OtherSource.class);
        var topId = ColumnField.over(Entity.class, topRoot, otherId).named("id");

        // 3-level plain chain: Source -> Outer -> OtherSource
        var level1 = source();
        var level2 = SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, level1).build();
        var level3 = SourceQuery.builder(OtherSource.class, outerRoot, otherAll, otherKey, level2).build();
        var threeLevelQuery = ModelQuery.builder(topRoot, row -> new Entity(), level3)
                .select(SelectSet.of(topId)).primaryKey(PrimaryKey.of(topId)).build();
        for (Phase phase : Phase.values()) {
            var recorder = new ChainRecorder(true);
            BuiltQuery<Entity> built = threeLevelQuery.buildQuery(
                    criteria(), phase, RenderOptions.portable().withSourceRenderer(recorder));
            assertThat(recorder.registeredTops).containsOnly(built.query());
            assertThat(recorder.registeredHandles).hasSize(3);
            assertThat(recorder.fromCalls.get(recorder.registeredBodies.get(1)))
                    .isSameAs(recorder.registeredHandles.get(0));
            assertThat(recorder.fromCalls.get(recorder.registeredBodies.get(2)))
                    .isSameAs(recorder.registeredHandles.get(1));
            assertThat(recorder.fromCalls.get(built.query()))
                    .isSameAs(recorder.registeredHandles.get(2));
            assertThat(built.recursionChecks()).isEmpty();
        }

        // Union shape as inner: combined branches register as the inner CTE on top
        var combined = source().unionAll(source());
        var overUnion = SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, combined).build();
        var unionRecorder = new ChainRecorder(true);
        BuiltQuery<OtherSource> builtUnion = ModelQuery.builder(outerRoot, row -> new OtherSource(), overUnion)
                .select(SelectSet.of(otherId)).primaryKey(PrimaryKey.of(otherId)).build()
                .buildQuery(criteria(), Phase.MODEL, RenderOptions.portable().withSourceRenderer(unionRecorder));
        assertThat(unionRecorder.combinedCount).isEqualTo(1);
        assertThat(unionRecorder.registeredHandles).hasSize(2);
        assertThat(unionRecorder.registeredTops).containsOnly(builtUnion.query());

        // 3-level chain over a tree: walk filter goes into level 2's body, checks stay in the top statement
        var depth = ColumnField.of(Source.class, ENTITY_ROOT, "level", Integer.class).named("level");
        var parent = ColumnField.of(Source.class, ENTITY_ROOT, "parentId", Long.class).named("parentId");
        var tree = SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL.with(depth), S_KEY).depth(depth)
                .tree(step -> step.childKey(parent).parentKey(S_ID), 4).build();
        var overTree = SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, tree).build();
        var topOverTree = SourceQuery.builder(OtherSource.class, outerRoot, otherAll, otherKey, overTree).build();
        var treeQuery = ModelQuery.builder(topRoot, row -> new Entity(), topOverTree)
                .select(SelectSet.of(topId)).primaryKey(PrimaryKey.of(topId)).build();
        var treeRecorder = new ChainRecorder(true);
        BuiltQuery<Entity> builtTree = treeQuery.buildQuery(
                criteria(), Phase.MODEL, RenderOptions.portable().withSourceRenderer(treeRecorder));
        assertThat(treeRecorder.registeredTops).containsOnly(builtTree.query());
        assertThat(treeRecorder.registeredHandles).hasSize(3);
        assertThat(wherePredicates.get(treeRecorder.registeredBodies.get(1))).hasSize(2);
        assertThat(wherePredicates.get(treeRecorder.registeredBodies.get(2))).isNull();
        assertThat(wherePredicates.get(builtTree.query())).isNull();
        assertThat(builtTree.recursionChecks()).extracting(Selection::getAlias).containsExactly(
                BuiltQuery.RECURSION_DEPTH, BuiltQuery.RECURSION_CYCLE, BuiltQuery.RECURSION_DUPLICATE_KEY);
        assertThat(builtTree.sourceMaxDepth()).isEqualTo(4);
        assertThat(builtTree.sourceKeyColumns()).containsExactly("id");

        // Missing duplicate check on an inner tree is MQ4014
        Throwable noDup = catchThrowable(() -> treeQuery.buildQuery(
                criteria(), Phase.MODEL, RenderOptions.portable().withSourceRenderer(new ChainRecorder(false))));
        assertThat(noDup).isInstanceOf(ModelQueryConfigurationException.class)
                .hasMessageContaining("duplicate-key check");
        assertThat(((ModelQueryConfigurationException) noDup).code()).isEqualTo(MqCode.MQ4014);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ac_src_26_missing_wrong_and_entity_inner_sources_are_mq1907() {
        assertThat(code(() -> SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY)).code())
                .isEqualTo(MqCode.MQ1907);
        assertThat(code(() -> SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY,
                SelectSet.of(O_ID))).code()).isEqualTo(MqCode.MQ1907);
        assertThat(code(() -> SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY,
                (SourceQuery<Source>) null)).code()).isEqualTo(MqCode.MQ1907);
        assertThat(code(() -> SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY,
                (SourceQuery) entitySource())).code()).isEqualTo(MqCode.MQ1907);
        assertThat(code(() -> SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL, S_KEY, entitySource())).code())
                .isEqualTo(MqCode.MQ1907);
        assertThat(code(() -> SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL, S_KEY,
                S_GROUP_KEYS, entitySource())).code()).isEqualTo(MqCode.MQ1907);
        var depth = ColumnField.of(Source.class, ENTITY_ROOT, "level", Integer.class).named("level");
        var oDepth = ColumnField.over(Outer.class, SOURCE_ROOT, depth).named("level");
        var withDepth = SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL.with(oDepth), O_KEY, source())
                .depth(oDepth);
        assertThat(code(withDepth::build)).hasMessageContaining("Outer: a source over Source cannot be recursive")
                .extracting(ModelQueryDefinitionException::code).isEqualTo(MqCode.MQ1907);
        assertThat(code(() -> withDepth.recursive(step -> step.childKey(O_ID).parentKey(O_ID), 3).build()))
                .hasMessageContaining("Outer: a source over Source cannot be recursive")
                .extracting(ModelQueryDefinitionException::code).isEqualTo(MqCode.MQ1907);
        assertThat(code(() -> withDepth.tree(step -> step.childKey(O_ID).parentKey(O_ID), 3).build()))
                .hasMessageContaining("Outer: a source over Source cannot be recursive")
                .extracting(ModelQueryDefinitionException::code).isEqualTo(MqCode.MQ1907);
        assertThat(code(() -> SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, source())
                .shape(OtherSource.class).build()))
                .hasMessageContaining("Outer: a source over Source cannot be a branch")
                .extracting(ModelQueryDefinitionException::code).isEqualTo(MqCode.MQ1907);
    }

    @Test
    void ac_src_28_a_branch_inner_is_checked_by_its_shape() {
        SourceQuery<Source> branch = SourceQuery.builder(Entity.class, ENTITY_ROOT, SelectSet.of(E_ID),
                PrimaryKey.of(E_ID)).shape(Source.class).build();
        assertThat(SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, branch).build().inner())
                .isSameAs(branch);
        SourceQuery<Source> combined = branch.union(branch);
        assertThat(SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, combined).build().inner())
                .isSameAs(combined);
    }

    @Test
    void ac_src_29_ungrouped_inherits_walk_uniqueness_and_grouped_restores_it() {
        var depth = ColumnField.of(Source.class, ENTITY_ROOT, "level", Integer.class).named("level");
        var parent = ColumnField.of(Source.class, ENTITY_ROOT, "parentId", Long.class).named("parentId");
        var walk = SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL.with(depth), S_KEY).depth(depth)
                .recursive(step -> step.childKey(parent).parentKey(S_ID), 4).build();
        var tree = SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL.with(depth), S_KEY).depth(depth)
                .tree(step -> step.childKey(parent).parentKey(S_ID), 4).build();
        var derived = SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, walk).build();
        assertThat(derived.unique()).isFalse();
        assertThat(SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, tree).build().unique()).isTrue();
        var grouped = SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL, O_KEY, SelectSet.of(O_ID), walk)
                .where(f -> f.eq(O_STATUS, "PAID")).having(h -> h.gt(O_COUNT, 1L)).build();
        assertThat(grouped.inner()).isSameAs(walk);
        assertThat(grouped.unique()).isTrue();
        assertThat(grouped.having()).isNotNull();
        var root = TableField.source(Outer.class);
        var id = ColumnField.over(OtherSource.class, root, O_ID).named("id");
        var query = ModelQuery.builder(root, row -> new OtherSource(), derived).select(SelectSet.of(id))
                .primaryKey(PrimaryKey.of(id));
        assertThat(code(() -> query.keyset().build())).hasMessageContaining("tree(...)").extracting(
                ModelQueryDefinitionException::code).isEqualTo(MqCode.MQ1901);
        assertThat(code(() -> query.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(1)).build()).code())
                .isEqualTo(MqCode.MQ1901);
        assertThat(ModelQuery.builder(root, row -> new OtherSource(), grouped).select(SelectSet.of(id))
                .primaryKey(PrimaryKey.of(id)).keyset().build().isKeyset()).isTrue();
    }

    // AC-SRC-37
    @Test
    void ac_src_37_direct_and_nested_correlated_fields_are_mq1908_at_the_builder() {
        var correlated = Correlated.count(Source.class, SubSelect.of(E_ID),
                (f, outer) -> f.compare(E_ID, Op.EQ, outer.column(S_ID)));
        for (var field : List.of(correlated, Expr.plus(Expr.coalesce(correlated, 0L), 1L))) {
            assertThat(code(() -> SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL.with(field), S_KEY)).code())
                    .isEqualTo(MqCode.MQ1908);
            assertThat(code(() -> SourceQuery.builder(Source.class, ENTITY_ROOT, S_ALL.with(field), S_KEY,
                    S_GROUP_KEYS)).code()).isEqualTo(MqCode.MQ1908);
        }
        var outerCorrelated = Correlated.count(Outer.class, SubSelect.of(E_ID),
                (f, outer) -> f.compare(E_ID, Op.EQ, outer.column(O_ID)));
        assertThat(code(() -> SourceQuery.builder(Outer.class, SOURCE_ROOT, O_ALL.with(outerCorrelated), O_KEY,
                source())).code()).isEqualTo(MqCode.MQ1908);
    }

    private final Map<CriteriaQuery<?>, List<Predicate>> wherePredicates = new IdentityHashMap<>();

    private static final class ChainRecorder implements SourceRenderer {

        private final boolean duplicateCheck;
        private final List<CriteriaQuery<?>> registeredTops = new ArrayList<>();
        private final List<CriteriaQuery<Tuple>> registeredBodies = new ArrayList<>();
        private final List<CteHandle> registeredHandles = new ArrayList<>();
        private final Map<AbstractQuery<?>, CteHandle> fromCalls = new IdentityHashMap<>();
        private int combinedCount;

        private ChainRecorder(boolean duplicateCheck) {
            this.duplicateCheck = duplicateCheck;
        }

        @Override
        public Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
            throw new AssertionError("a chain must register levels via register/from, not single-level cte");
        }

        @Override
        public CteHandle register(CriteriaQuery<?> top, CriteriaQuery<Tuple> body) {
            registeredTops.add(top);
            registeredBodies.add(body);
            CteHandle handle = new CteHandle() {};
            registeredHandles.add(handle);
            return handle;
        }

        @Override
        public RecursiveHandle registerRecursive(CriteriaQuery<?> top, RecursiveSpec spec) {
            registeredTops.add(top);
            registeredBodies.add(spec.seed());
            CteHandle handle = new CteHandle() {};
            registeredHandles.add(handle);
            Expression<?> depth = expression(Expression.class, Long.class);
            Expression<?> cycle = expression(Expression.class, Long.class);
            Expression<?> dup = duplicateCheck ? expression(Expression.class, Long.class) : null;
            return new RecursiveHandle(handle, depth, cycle, dup);
        }

        @Override
        public Root<Tuple> from(AbstractQuery<?> query, CteHandle inner) {
            fromCalls.put(query, inner);
            return expression(Root.class, Tuple.class);
        }

        @Override
        public CriteriaQuery<Tuple> combine(CriteriaBuilder cb, SetOperation operation, CriteriaQuery<Tuple> left,
                CriteriaQuery<Tuple> right) {
            combinedCount++;
            return left;
        }
    }

    private CriteriaBuilder criteria() {
        return (CriteriaBuilder) Proxy.newProxyInstance(CriteriaBuilder.class.getClassLoader(),
                new Class<?>[] {CriteriaBuilder.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createTupleQuery" -> criteriaQuery();
                    case "literal" -> expression(Expression.class, args[0].getClass());
                    case "le", "isFalse" -> expression(Predicate.class, Boolean.class);
                    default -> throw new AssertionError(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private CriteriaQuery<Tuple> criteriaQuery() {
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
                    case "where" -> {
                        wherePredicates.put((CriteriaQuery<?>) proxy, List.of((Predicate[]) args[0]));
                        yield proxy;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "CriteriaQuery";
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
                            args[0].toString().contains("level") ? Integer.class
                                    : args[0].toString().contains("status") ? String.class : Long.class);
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
