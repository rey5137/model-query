package com.rey.modelquery.core;

import com.rey.modelquery.annotations.JoinKind;
import jakarta.persistence.criteria.JoinType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Definition checks of entity joins anchored on the root and its to-one paths (R-SRC-25, D-197). */
class ColumnEntityJoinTest {

    static class Invoice {}
    static final class CreditNote extends Invoice {}
    static final class Region {}
    static final class Employee {}
    static final class Keys {}
    static final class Row {}

    enum Kind { INVOICE }

    private static final TableField<Invoice, Invoice> INVOICE = TableField.root(Invoice.class);
    private static final TableField<Invoice, Region> REGION = TableField.join(INVOICE, "region", JoinType.LEFT);
    private static final OrderedColumnField<Row, Invoice, Long> ID =
            ColumnField.of(Row.class, INVOICE, "id", Long.class);
    private static final ColumnField<Row, Invoice, String> REP_CODE =
            ColumnField.of(Row.class, INVOICE, "repCode", String.class);
    private static final ColumnField<Row, Invoice, String> OWNER_TYPE =
            ColumnField.of(Row.class, INVOICE, "ownerType", String.class);
    private static final ColumnField<Row, Region, String> REGION_REP =
            ColumnField.of(Row.class, REGION, "repCode", String.class);
    private static final EntityJoin<Row, Employee> REP = rep("rep", REP_CODE);
    private static final ColumnField<Row, Employee, String> REP_NAME =
            ColumnField.of(Row.class, REP.root(), "name", String.class);

    private static final OrderedColumnField<Keys, Invoice, Long> K_ID =
            ColumnField.of(Keys.class, INVOICE, "id", Long.class).named("id");
    private static final SourceQuery<Keys> KEYS = SourceQuery.builder(Keys.class, INVOICE, SelectSet.of(K_ID),
            PrimaryKey.of(K_ID)).build();
    private static final SourceJoin<Row, Keys> SOURCE = SourceJoin.of("keys", Keys.class, JoinKind.LEFT,
            List.of(SourceJoin.on(ID, K_ID)));
    private static final ColumnField<Row, Keys, Long> SOURCE_ID = ColumnField.over(Row.class, SOURCE.root(), K_ID);

    private static EntityJoin<Row, Employee> rep(String name, ColumnField<Row, ?, ?> anchor) {
        return EntityJoin.of(name, Employee.class, JoinKind.LEFT, List.of(EntityJoin.on(anchor, "code")));
    }

    private static ModelQuery.Builder<Invoice, Long, Row> base() {
        return ModelQuery.builder(INVOICE, row -> new Row()).primaryKey(PrimaryKey.of(ID))
                .select(SelectSet.of(ID, REP_NAME));
    }

    @Test
    void ac_src_59_root_and_to_one_path_anchors_build_without_a_joined_source() {
        var regionRep = rep("regionRep", REGION_REP);
        var both = EntityJoin.of("both", Employee.class, JoinKind.INNER,
                List.of(EntityJoin.on(REP_CODE, "code"), EntityJoin.on(REGION_REP, "region")));

        assertThatCode(() -> base().joinEntity(REP).joinEntity(regionRep).joinEntity(both).build())
                .doesNotThrowAnyException();
        assertThat(REP.anchoredOnSource()).isFalse();
        assertThat(rep("keyed", SOURCE_ID).anchoredOnSource()).isTrue();
        assertThat(REP.root()).isNotEqualTo(regionRep.root());
        assertThat(base().joinEntity(REP).joinEntity(regionRep).build().entityJoins())
                .containsExactly(REP, regionRep);
    }

    @Test
    void ac_src_61_mixed_kinds_chains_converted_and_treated_anchors_are_mq1911_at_build() {
        var mixed = EntityJoin.of("mixed", Employee.class, JoinKind.LEFT,
                List.of(EntityJoin.on(REP_CODE, "code"), EntityJoin.on(SOURCE_ID, "id")));
        var mixedSourceFirst = EntityJoin.of("mixed", Employee.class, JoinKind.LEFT,
                List.of(EntityJoin.on(SOURCE_ID, "id"), EntityJoin.on(REP_CODE, "code")));
        var chain = rep("chain", ColumnField.of(Row.class, REP.root(), "managerCode", String.class));
        var below = TableField.join(REP.root(), "team", JoinType.LEFT);
        var belowChain = rep("belowChain", ColumnField.of(Row.class, below, "leadCode", String.class));
        var treated = rep("treated", ColumnField.of(Row.class, INVOICE.treat(CreditNote.class), "repCode",
                String.class));
        var converted = rep("converted", ColumnField.of(Row.class, INVOICE, "repNumber", Long.class, String.class,
                new ColumnConverter<Long, String>() {
                    @Override
                    public Long toModel(String attribute) {
                        return Long.valueOf(attribute);
                    }

                    @Override
                    public String toAttribute(Long model) {
                        return model.toString();
                    }
                }));
        var lifted = rep("lifted", ColumnField.<Row, Invoice, String>lifted(REP_CODE));

        mq1911(() -> base().joinSource(SOURCE, KEYS).joinEntity(mixed).build(), "mix");
        mq1911(() -> base().joinSource(SOURCE, KEYS).joinEntity(mixedSourceFirst).build(), "mix");
        mq1911(() -> base().joinEntity(REP).joinEntity(chain).build(), "entity join");
        mq1911(() -> base().joinEntity(REP).joinEntity(belowChain).build(), "entity join");
        mq1911(() -> base().joinEntity(treated).build(), "treated");
        mq1911(() -> base().joinEntity(converted).build(), "converted");
        mq1911(() -> base().joinEntity(lifted).build(), "Outer lift");
    }

    @Test
    void ac_src_61_a_query_rooted_at_a_source_joins_no_entity() {
        TableField<Keys, Keys> sourceRoot = TableField.source(Keys.class);
        var key = ColumnField.over(Row.class, sourceRoot, K_ID);
        var join = rep("rep", key);

        mq1911(() -> ModelQuery.builder(sourceRoot, row -> new Row(), KEYS).select(SelectSet.of(key))
                .joinEntity(join).build(), "source root");
    }

    @Test
    void ac_src_61_columns_over_a_column_anchored_join_are_read_only_in_writes() {
        var copy = EntityJoin.of("copy", Invoice.class, JoinKind.LEFT, List.of(EntityJoin.on(REP_CODE, "code")));
        ColumnField<Row, Invoice, String> copied = ColumnField.of(Row.class, copy.root(), "repCode", String.class);

        assertThatThrownBy(() -> ModelUpdate.builder(INVOICE).primaryKey(PrimaryKey.of(ID)).set(copied, "x")
                .whereKey(1L).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1604));
        assertThatThrownBy(() -> InsertColumns.<Row, Invoice>of(INVOICE).add(copied, row -> "x"))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1801));
    }

    @Test
    void ac_src_62_when_is_a_definition_constant_on_a_new_join() {
        var typed = REP.when(OWNER_TYPE, "INVOICE");
        var onPath = REP.when(REGION_REP, "NORTH");

        assertThat(REP.when()).isEmpty();
        assertThat(typed).isNotSameAs(REP);
        assertThat(typed.when()).hasValueSatisfying(when -> {
            assertThat(when.column()).isEqualTo(OWNER_TYPE);
            assertThat(when.value()).isEqualTo("INVOICE");
        });
        assertThat(typed.on()).isEqualTo(REP.on());
        assertThat(typed.root()).isEqualTo(REP.root());
        assertThat(onPath.when()).isPresent();
        assertThatCode(() -> base().joinEntity(typed).build()).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_src_62_when_type_and_anchor_rules_are_mq1911() {
        var typed = REP.when(OWNER_TYPE, "INVOICE");
        ColumnField raw = OWNER_TYPE;
        var kind = ColumnField.of(Row.class, INVOICE, "kind", Kind.class);
        var day = ColumnField.of(Row.class, INVOICE, "day", Optional.class);
        var converted = ColumnField.of(Row.class, INVOICE, "issued", LocalDate.class, String.class,
                new ColumnConverter<LocalDate, String>() {
                    @Override
                    public LocalDate toModel(String attribute) {
                        return LocalDate.parse(attribute);
                    }

                    @Override
                    public String toAttribute(LocalDate model) {
                        return model.toString();
                    }
                });

        mq1911(() -> typed.when(OWNER_TYPE, "INVOICE"), "one when");
        mq1911(() -> REP.when(OWNER_TYPE, null), "non-null String");
        mq1911(() -> REP.when(raw, 5), "not a Integer");
        mq1911(() -> REP.when(kind, Kind.INVOICE),
                "a Kind value does not bind as one statement parameter (R-COL-18)");
        mq1911(() -> REP.when(day, Optional.of("x")), "R-COL-18");
        mq1911(() -> REP.when(converted, LocalDate.EPOCH), "converted");
        mq1911(() -> REP.when(REP_NAME, "x"), "entity join");
        mq1911(() -> REP.when(ColumnField.of(Row.class, INVOICE.treat(CreditNote.class), "ownerType",
                String.class), "x"), "treated");
        mq1911(() -> REP.when(ColumnField.<Row, Keys, Long>over(Row.class, SOURCE.root(), K_ID), 1L), "source");
    }

    private static void mq1911(Runnable action, String detail) {
        assertThatThrownBy(action::run).isInstanceOf(ModelQueryDefinitionException.class)
                .hasMessageContaining(detail)
                .extracting(e -> ((ModelQueryDefinitionException) e).code()).isEqualTo(MqCode.MQ1911);
    }
}
