package com.rey.modelquery.core;

import com.rey.modelquery.annotations.JoinKind;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityJoinTest {

    static final class Day {}
    static final class Sales {}
    static class Profile {}
    static final class Manager extends Profile {}
    static final class View {}

    private static final TableField<Day, Day> DAY = TableField.root(Day.class);
    private static final OrderedColumnField<Sales, Day, Long> S_ID =
            ColumnField.of(Sales.class, DAY, "id", Long.class).named("id");
    private static final OrderedColumnField<Sales, Day, Long> S_MANAGER =
            ColumnField.of(Sales.class, DAY, "managerId", Long.class).named("managerId");
    private static final SourceQuery<Sales> SALES = SourceQuery.builder(Sales.class, DAY,
            SelectSet.of(S_ID, S_MANAGER), PrimaryKey.of(S_ID)).build();
    private static final OrderedColumnField<View, Day, Long> V_ID =
            ColumnField.of(View.class, DAY, "id", Long.class);
    private static final SourceJoin<View, Sales> SOURCE = source("sales");
    private static final OrderedColumnField<View, Sales, Long> MANAGER_ID =
            ColumnField.over(View.class, SOURCE.root(), S_MANAGER);
    private static final EntityJoin<View, Profile> MANAGER = entity("manager", MANAGER_ID);
    private static final OrderedColumnField<View, Profile, String> NAME =
            ColumnField.of(View.class, MANAGER.root(), "name", String.class);

    private static SourceJoin<View, Sales> source(String name) {
        return SourceJoin.of(name, Sales.class, JoinKind.LEFT, List.of(SourceJoin.on(V_ID, S_ID)));
    }

    private static EntityJoin<View, Profile> entity(String name, ColumnField<View, ?, Long> anchor) {
        return EntityJoin.of(name, Profile.class, JoinKind.LEFT, List.of(EntityJoin.on(anchor, "id")));
    }

    private static ModelQuery.Builder<Day, Long, View> base() {
        return ModelQuery.builder(DAY, row -> new View()).primaryKey(PrimaryKey.of(V_ID))
                .select(SelectSet.of(V_ID, NAME)).joinSource(SOURCE, SALES);
    }

    @Test
    void ac_src_56_entity_identity_and_to_one_properties_keep_join_and_model_names() {
        var other = entity("other", MANAGER_ID);
        assertThat(NAME.identityPath()).isEqualTo("entity(manager).name");
        assertThat(ColumnField.of(View.class, other.root(), "name", String.class).identityPath())
                .isEqualTo("entity(other).name");
        var type = TableField.<Profile, Profile>join(MANAGER.root(), "type", JoinType.LEFT);
        var nested = TableField.<Profile, Profile>join(type, "parent", JoinType.LEFT);
        var column = ColumnField.of(View.class, nested, "name", String.class).named("managerType");
        assertThat(column.identityPath()).isEqualTo("entity(manager).type.parent.name");
        assertThat(column.propertyPath()).isEqualTo("managerType");
        assertThat(ColumnField.of(View.class, type.named("kind"), "name", String.class).named("label")
                .propertyPath()).isEqualTo("kind.label");
        var root = TableField.root(Profile.class);
        assertThat(ColumnField.of(View.class, root, "name", String.class).identityPath()).isEqualTo("name");
        assertThat(TableField.<Profile, Profile>join(root, "type", JoinType.LEFT).propertyPath()).isNull();
        assertThat(MANAGER_ID.identityPath()).isEqualTo("source(sales).mq_managerId");
    }

    @Test
    void ac_src_53_entity_definitions_and_query_bindings_are_immutable() {
        var pairs = new ArrayList<>(List.of(EntityJoin.on(MANAGER_ID, "id")));
        var join = EntityJoin.of("manager", Profile.class, JoinKind.INNER, pairs);
        pairs.clear();

        assertThat(join.name()).isEqualTo("manager");
        assertThat(join.entity()).isEqualTo(Profile.class);
        assertThat(join.kind()).isEqualTo(JoinKind.INNER);
        assertThat(join.on()).hasSize(1);
        assertThat(join.on().get(0).anchor()).isSameAs(MANAGER_ID);
        assertThat(join.on().get(0).idAttribute()).isEqualTo("id");
        assertThatThrownBy(() -> join.on().clear()).isInstanceOf(UnsupportedOperationException.class);

        var unbound = base();
        var bound = unbound.joinEntity(MANAGER);
        assertThat(unbound.build().entityJoins()).isEmpty();
        assertThat(bound.build().entityJoins()).containsExactly(MANAGER);
        assertThatThrownBy(() -> bound.build().entityJoins().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(bound.build().entityJoins()).containsExactly(MANAGER);
    }

    @Test
    void ac_src_53_named_entity_tables_are_distinct_from_sources_roots_and_other_names() {
        var again = entity("manager", MANAGER_ID);
        var reviewer = entity("reviewer", MANAGER_ID);
        var root = MANAGER.root();

        assertThat(root).isEqualTo(again.root()).hasSameHashCodeAs(again.root());
        assertThat(root).isNotEqualTo(reviewer.root()).isNotEqualTo(TableField.root(Profile.class))
                .isNotEqualTo(TableField.joinedSource("manager", Profile.class));
        assertThat(root.isJoinedEntity()).isTrue();
        assertThat(root.isJoinedSource()).isFalse();
        assertThat(root.isSource()).isFalse();
        assertThat(root.describe()).isEqualTo("joined entity 'manager' (Profile)");
        assertThat(MANAGER.toString()).contains("manager", "LEFT", "Profile");
        assertThat(TableField.join(root, "department", JoinType.LEFT))
                .isNotEqualTo(TableField.join(reviewer.root(), "department", JoinType.LEFT));
        assertThat(base().joinEntity(MANAGER).joinEntity(reviewer).build().entityJoins())
                .containsExactly(MANAGER, reviewer);
    }

    @Test
    void ac_src_56_duplicate_entity_registration_and_source_names_are_mq1911() {
        mq1911(() -> base().joinEntity(MANAGER).joinEntity(MANAGER).build(), "manager");
        mq1911(() -> base().joinEntity(MANAGER).joinEntity(entity("manager", MANAGER_ID)).build(), "manager");
        mq1911(() -> base().joinEntity(entity("sales", MANAGER_ID)).build(), "sales");
        mq1911(() -> ModelQuery.builder(DAY, row -> new View()).select(SelectSet.of(V_ID))
                .joinEntity(entity("sales", MANAGER_ID)).joinSource(SOURCE, SALES).build(), "sales");
    }

    @Test
    void ac_src_56_unbound_or_non_source_anchors_are_mq1911() {
        mq1911(() -> ModelQuery.builder(DAY, row -> new View()).select(SelectSet.of(V_ID))
                .joinEntity(MANAGER).build(), "not bound");
        mq1911(() -> base().joinEntity(EntityJoin.of("manager", Profile.class, JoinKind.LEFT,
                List.of(EntityJoin.on(MANAGER_ID, "id"), EntityJoin.on(V_ID, "tenant")))).build(), "mix");
        var missing = ColumnField.over(View.class, source("missing").root(), S_MANAGER);
        mq1911(() -> base().joinEntity(entity("manager", missing)).build(), "not bound");
        var otherEntity = ColumnField.of(View.class, MANAGER.root(), "id", Long.class);
        mq1911(() -> base().joinEntity(entity("other", otherEntity)).build(), "reads the entity join");
    }

    @Test
    void ac_src_56_pairs_from_two_bound_sources_are_mq1911() {
        var other = source("other");
        var otherId = ColumnField.over(View.class, other.root(), S_MANAGER);
        var join = EntityJoin.of("manager", Profile.class, JoinKind.LEFT,
                List.of(EntityJoin.on(MANAGER_ID, "tenant"), EntityJoin.on(otherId, "id")));

        mq1911(() -> base().joinSource(other, SALES).joinEntity(join).build(), "two joined sources");
    }

    @Test
    void ac_src_56_a_converted_anchor_is_mq1911_at_build() {
        ColumnConverter<Long, String> converter = new ColumnConverter<>() {
            @Override
            public Long toModel(String attribute) {
                return Long.valueOf(attribute);
            }

            @Override
            public String toAttribute(Long model) {
                return model.toString();
            }
        };
        var field = ColumnField.of(Sales.class, DAY, "managerCode", Long.class, String.class, converter)
                .named("managerCode");
        var converted = ColumnField.over(View.class, SOURCE.root(), field);

        mq1911(() -> base().joinEntity(entity("manager", converted)).build(), "converted");
    }

    @Test
    void ac_src_56_blank_names_and_empty_pairs_are_mq1911() {
        mq1911(() -> entity(" ", MANAGER_ID), "name");
        mq1911(() -> EntityJoin.of("manager", Profile.class, JoinKind.LEFT, List.of()), "pair");
    }

    @Test
    void ac_src_53_every_builder_copy_carries_the_entity_binding() {
        var bound = base().joinEntity(MANAGER);
        List<UnaryOperator<ModelQuery.Builder<Day, Long, View>>> copies = List.of(
                b -> b.select(SelectSet.of(V_ID, NAME)),
                b -> b.fetch(FetchPlan.of(SelectSet.of(V_ID, NAME))),
                b -> b.forChildren(FetchPlan.of(SelectSet.of(V_ID, NAME)), List.of(), List.of()),
                b -> b.primaryKey(PrimaryKey.of(V_ID)),
                b -> b.orderBy(V_ID.asc()),
                b -> b.keyset(),
                b -> b.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(1_000L)),
                b -> b.lock(RowLock.shared()),
                b -> b.where(f -> f.eq(V_ID, 1L)),
                b -> b.afterMap((view, row) -> { }),
                b -> b.finisher(view -> view),
                b -> b.customize((spec, joins, query, cb, phase) -> { }),
                b -> b.groupBy(V_ID),
                b -> b.groupBy(SelectSet.of(V_ID)),
                b -> b.having(h -> h),
                b -> b.joinSource(source("other"), SALES));

        for (var copy : copies) {
            mq1911(() -> copy.apply(bound).joinEntity(MANAGER).build(), "bound twice");
        }
    }

    @Test
    void ac_src_56_a_joined_entity_cannot_be_the_query_root() {
        var id = ColumnField.of(View.class, MANAGER.root(), "id", Long.class);
        assertThatThrownBy(() -> ModelQuery.builder(MANAGER.root(), row -> new View()))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .extracting(e -> ((ModelQueryDefinitionException) e).code()).isEqualTo(MqCode.MQ1203);
        assertThatThrownBy(() -> SourceQuery.builder(View.class, MANAGER.root(), SelectSet.of(id), PrimaryKey.of(id)))
                .isInstanceOf(ModelQueryDefinitionException.class)
                .extracting(e -> ((ModelQueryDefinitionException) e).code()).isEqualTo(MqCode.MQ1203);
    }

    @Test
    void ac_src_58_renderer_options_preserve_and_overlay_the_entity_hook() {
        EntityJoinRenderer renderer = new EntityJoinRenderer() {
            @Override
            public <E> From<?, E> join(From<?, ?> anchor, Class<E> entity, JoinKind kind,
                    Function<From<?, E>, Predicate> on) {
                throw new AssertionError("Definition tests do not build statements");
            }
        };
        var portable = RenderOptions.portable();
        var options = portable.withEntityJoinRenderer(renderer);

        assertThat(portable.entityJoinRenderer()).isEmpty();
        assertThat(options.withInlinesValues(true).withConstantRenderer((entity, attribute, value) -> Optional.empty())
                .entityJoinRenderer()).containsSame(renderer);
        assertThat(options.withSourceRenderer(new SourceRenderer() {}).entityJoinRenderer()).containsSame(renderer);
        assertThat(portable.withRenderersOf(options).entityJoinRenderer()).containsSame(renderer);
        assertThat(options.withRenderersOf(portable).entityJoinRenderer()).containsSame(renderer);
    }

    @Test
    void ac_src_58_missing_entity_metadata_is_mq4014() {
        assertThatThrownBy(() -> MANAGER.checkIds(null))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014));
    }

    @Test
    void ac_pag_33_filter_only_entries_of_an_entity_join_and_a_root_path_of_the_same_name_do_not_clash() {
        var rootName = ColumnField.of(View.class, DAY, "name", String.class);
        TableField<Day, Profile> association = TableField.join(DAY, "manager", JoinType.LEFT);
        var associationName = ColumnField.of(View.class, association, "name", String.class);
        assertThat(rootName.identityPath()).isEqualTo("name");
        assertThat(associationName.identityPath()).isEqualTo("manager.name");
        assertThat(NAME.identityPath()).isEqualTo("entity(manager).name");
        assertThatCode(() -> FieldIndex.builder(View.class).filterOnly(rootName, associationName, NAME).build())
                .doesNotThrowAnyException();
    }

    @Test
    void ac_col_65_a_named_joined_root_starts_its_columns_property_paths() {
        TableField<Profile, Profile> boss = MANAGER.root().named("boss");
        var bossName = ColumnField.of(View.class, boss, "name", String.class).named("name");
        assertThat(boss.propertyPath()).isEqualTo("boss");
        assertThat(bossName.propertyPath()).isEqualTo("boss.name");
        assertThat(bossName.identityPath()).isEqualTo("entity(manager).name");
        assertThat(boss).isEqualTo(MANAGER.root());
        // Unnamed, the joined root keeps an empty path (R-SRC-23).
        assertThat(MANAGER.root().propertyPath()).isEmpty();
        assertThat(ColumnField.of(View.class, MANAGER.root(), "name", String.class).named("name").propertyPath())
                .isEqualTo("name");
        // An unnamed association node below it adds no segment; a named one does.
        var type = TableField.<Profile, Profile>join(boss, "type", JoinType.LEFT);
        assertThat(ColumnField.of(View.class, type, "name", String.class).named("typeName").propertyPath())
                .isEqualTo("boss.typeName");
        var parent = TableField.<Profile, Profile>join(type, "parent", JoinType.LEFT);
        assertThat(ColumnField.of(View.class, parent, "name", String.class).named("parentName").propertyPath())
                .isEqualTo("boss.parentName");
        assertThat(ColumnField.of(View.class, type.named("kind"), "name", String.class).named("label")
                .propertyPath()).isEqualTo("boss.kind.label");
        // A joined source's root too; the query's own root never.
        assertThat(ColumnField.over(View.class, SOURCE.root().named("sales"), S_MANAGER).named("managerId")
                .propertyPath())
                .isEqualTo("sales.managerId");
        assertThat(ColumnField.of(View.class, DAY.named("day"), "id", Long.class).named("id").propertyPath())
                .isEqualTo("id");
        assertThat(ColumnField.of(View.class, boss.treat(Manager.class), "name", String.class).named("name")
                .propertyPath()).isEqualTo("boss.name");
    }

    @Test
    void ac_col_65_a_sort_names_a_named_joined_root_column_by_its_path_and_a_bare_name_the_root_column() {
        var rootName = ColumnField.of(View.class, DAY, "name", String.class).named("name");
        var bossName = ColumnField.of(View.class, MANAGER.root().named("boss"), "name", String.class).named("name");
        var query = ModelQuery.builder(DAY, row -> new View()).primaryKey(PrimaryKey.of(V_ID))
                .select(SelectSet.of(V_ID, rootName, bossName)).joinSource(SOURCE, SALES).joinEntity(MANAGER)
                .build();

        assertThat(query.orderedBy(SortSpec.of(SortSpec.Key.desc("boss.name"))).orderBy())
                .containsExactly(bossName.desc());
        assertThat(query.orderedBy(SortSpec.of(SortSpec.Key.asc("name"))).orderBy())
                .containsExactly(rootName.asc());
        // A column without a property matches by its attribute path after the joined root's property.
        var bossId = ColumnField.of(View.class, MANAGER.root().named("boss"), "id", Long.class);
        var withId = ModelQuery.builder(DAY, row -> new View()).primaryKey(PrimaryKey.of(V_ID))
                .select(SelectSet.of(V_ID, bossId)).joinSource(SOURCE, SALES).joinEntity(MANAGER).build();
        assertThat(withId.orderedBy(SortSpec.of(SortSpec.Key.asc("boss.id"))).orderBy())
                .containsExactly(bossId.asc());
        assertThat(withId.orderedBy(SortSpec.of(SortSpec.Key.asc("id"))).orderBy()).containsExactly(V_ID.asc());
        var index = FieldIndex.builder(View.class).select(rootName, bossName).build();
        assertThat(index.filter("boss.name")).containsSame(bossName);
        assertThat(index.filter("name")).containsSame(rootName);
    }

    @Test
    void ac_col_65_a_named_joined_root_keys_a_join_set_and_an_unnamed_node_below_it_does_not() {
        TableField<Profile, Profile> boss = MANAGER.root().named("boss");
        var set = SelectSet.of(NAME);
        assertThat(FieldIndex.builder(View.class).joinSet(boss, set).build().set("boss")).containsSame(set);

        var type = TableField.<Profile, Profile>join(boss, "type", JoinType.LEFT);
        assertThatThrownBy(() -> FieldIndex.builder(View.class).joinSet(type, set))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a join set needs a join with a property");
        assertThatThrownBy(() -> FieldIndex.builder(View.class).joinSet(MANAGER.root(), set))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FieldIndex.builder(View.class).joinSet(type.named("kind"), set).build().set("boss.kind"))
                .containsSame(set);
    }

    private static EntityJoin<View, Profile> rootJoin(String name, JoinKind kind, ColumnField<View, ?, Long> anchor) {
        return EntityJoin.of(name, Profile.class, kind, List.of(EntityJoin.on(anchor, "id")));
    }

    private static ModelQuery.Builder<Day, Long, View> chain(EntityJoin<View, ?>... joins) {
        var query = ModelQuery.builder(DAY, row -> new View()).primaryKey(PrimaryKey.of(V_ID))
                .select(SelectSet.of(V_ID));
        for (EntityJoin<View, ?> join : joins) {
            query = query.joinEntity(join);
        }
        return query;
    }

    @Test
    void ac_src_63_a_column_anchored_join_reads_an_earlier_one_and_its_to_one_paths() {
        var first = rootJoin("first", JoinKind.LEFT, V_ID);
        var second = rootJoin("second", JoinKind.LEFT,
                ColumnField.of(View.class, first.root(), "managerId", Long.class));
        var below = TableField.<Profile, Profile>join(first.root(), "boss", JoinType.LEFT);
        var third = rootJoin("third", JoinKind.LEFT, ColumnField.of(View.class, second.root(), "managerId", Long.class))
                .when(ColumnField.of(View.class, first.root(), "kind", String.class), "staff");
        var throughPath = rootJoin("throughPath", JoinKind.LEFT,
                ColumnField.of(View.class, below, "managerId", Long.class));

        assertThat(chain(first, second, third, throughPath).build().entityJoins())
                .containsExactly(first, second, third, throughPath);
        var inner = rootJoin("first", JoinKind.INNER, V_ID);
        var innerOnInner = rootJoin("second", JoinKind.INNER,
                ColumnField.of(View.class, inner.root(), "managerId", Long.class));
        var leftOnInner = rootJoin("third", JoinKind.LEFT,
                ColumnField.of(View.class, innerOnInner.root(), "managerId", Long.class));
        assertThatCode(() -> chain(inner, innerOnInner, leftOnInner).build()).doesNotThrowAnyException();
    }

    @Test
    void ac_src_64_a_chain_out_of_creation_order_or_inner_on_left_is_mq1911_at_build() {
        var first = rootJoin("first", JoinKind.LEFT, V_ID);
        var second = rootJoin("second", JoinKind.LEFT,
                ColumnField.of(View.class, first.root(), "managerId", Long.class));
        mq1911(() -> chain(second, first).build(), "anchor managerId: reads the entity join 'first', bound after it; "
                + "bind 'first' first");
        mq1911(() -> chain(second).build(), "reads the entity join 'first', which this query does not bind");
        // An equal-keyed join of the same name: a join cannot be built over its own root otherwise.
        var self = rootJoin("self", JoinKind.LEFT, ColumnField.of(View.class,
                rootJoin("self", JoinKind.LEFT, V_ID).root(), "managerId", Long.class));
        mq1911(() -> chain(self).build(), "reads the entity join 'self', its own root");

        var innerOnLeft = rootJoin("second", JoinKind.INNER,
                ColumnField.of(View.class, first.root(), "managerId", Long.class));
        mq1911(() -> chain(first, innerOnLeft).build(), "which is LEFT: this INNER join would drop every row "
                + "'first' misses; declare 'second' LEFT, or 'first' INNER");
        var below = TableField.<Profile, Profile>join(first.root(), "boss", JoinType.INNER);
        var innerBelowLeft = rootJoin("second", JoinKind.INNER, ColumnField.of(View.class, below, "id", Long.class));
        mq1911(() -> chain(first, innerBelowLeft).build(), "which is LEFT");

        var laterWhen = rootJoin("third", JoinKind.LEFT, V_ID)
                .when(ColumnField.of(View.class, second.root(), "kind", String.class), "staff");
        mq1911(() -> chain(first, laterWhen, second).build(), "when(kind, ...): reads the entity join 'second', "
                + "bound after it");
    }

    @Test
    void ac_src_64_source_anchored_chains_and_treated_anchors_stay_mq1911() {
        var onSourceJoin = rootJoin("onSource", JoinKind.LEFT,
                ColumnField.of(View.class, MANAGER.root(), "managerId", Long.class));
        mq1911(() -> base().joinEntity(MANAGER).joinEntity(onSourceJoin).build(),
                "reads the entity join 'manager', which is anchored on a joined source");
        var first = rootJoin("first", JoinKind.LEFT, V_ID);
        mq1911(() -> MANAGER.when(ColumnField.of(View.class, first.root(), "kind", String.class), "staff"),
                "a source-anchored join's term reads the root or a to-one path from it, not another join");
        var treated = first.root().treat(Manager.class);
        var onTreated = rootJoin("second", JoinKind.LEFT, ColumnField.of(View.class, treated, "managerId", Long.class));
        mq1911(() -> chain(first, onTreated).build(), "reads the treated");
    }

    @Test
    void ac_src_65_a_join_key_describes_the_join_not_the_record() {
        var first = rootJoin("first", JoinKind.LEFT, V_ID);
        var boss = TableField.<Profile, Profile>join(first.root(), "boss", JoinType.LEFT);
        assertThat(first.root().key().describe()).isEqualTo("joined entity 'first'");
        assertThat(SOURCE.root().key().describe()).isEqualTo("joined source 'sales'");
        assertThat(DAY.key().describe()).isEqualTo("root Day");
        assertThat(boss.key().describe()).isEqualTo("join 'boss' (LEFT)");
        assertThat(boss.treat(Manager.class).key().describe()).isEqualTo("join 'boss' (LEFT).treat(Manager)");
    }

    private static void mq1911(Runnable action, String detail) {
        assertThatThrownBy(action::run).isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining(detail)
                .extracting(e -> ((ModelQueryDefinitionException) e).code()).isEqualTo(MqCode.MQ1911);
    }
}
