package com.rey.modelquery.core;

import static com.rey.modelquery.annotations.WindowFrame.NONE;
import static com.rey.modelquery.annotations.WindowFunction.FIRST_VALUE;
import static com.rey.modelquery.annotations.WindowFunction.ROW_NUMBER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The temporary definition scan refuses treated nodes before an executor can issue any statement. */
class TreatChecksTest {

    static class Payment {}
    static class Card extends Payment {}
    static class View {}
    static class Source {}

    private static final TableField<Payment, Payment> ROOT = TableField.root(Payment.class);
    private static final TableField<Payment, Card> CARD = ROOT.treat(Card.class);
    private static final ColumnField<View, Payment, Long> ID = ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final ColumnField<View, Payment, String> STATUS =
            ColumnField.of(View.class, ROOT, "status", String.class);
    private static final ColumnField<View, Card, String> NUMBER =
            ColumnField.of(View.class, CARD, "number", String.class);

    @Test
    void ac_vnd_16_select_order_and_nested_expressions_are_scanned() {
        missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(NUMBER)).build());
        missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID)).orderBy(NUMBER.asc()).build());
        missing(ModelQuery.builder(ROOT, row -> new View())
                .select(SelectSet.of(Expr.coalesce(Expr.coalesce(NUMBER, "x"), "y"))).build());
        missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(Expr.cases(View.class, String.class)
                .when(f -> f.eq(NUMBER, "x"), "yes").otherwise("no"))).build());
        missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(Agg.<View>count(CARD))).build());
        missing(ModelQuery.builder(ROOT, row -> new View())
                .select(SelectSet.of(Agg.count(Expr.coalesce(NUMBER, "x")))).build());
    }

    @Test
    void ac_vnd_16_filters_scan_both_operands_nested_conditions_and_subqueries() {
        missing(ModelQuery.builder(ROOT, row -> new View())
                .select(SelectSet.of(ID))
                .where(f -> f.eq(NUMBER, "x")).build());
        missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.compare(STATUS, Op.EQ, NUMBER)).build());
        missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.in(STATUS, NUMBER, STATUS)).build());
        missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.or(n -> n.eq(NUMBER, "x"), n -> n.eq(STATUS, "y"))).build());
        missing(ModelQuery.builder(ROOT, row -> new View())
                .select(SelectSet.of(ID))
                .where(f -> f.exists(CARD)).build());
        missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.in(STATUS, SubSelect.of(NUMBER))).build());
        missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.in(STATUS, SubSelect.of(STATUS).where(s -> s.eq(NUMBER, "x")))).build());
    }

    @Test
    void ac_vnd_16_correlated_nodes_scan_their_selection_predicates_and_order() {
        var selected = Correlated.first(View.class, SubSelect.of(NUMBER),
                (f, outer) -> f.compare(ID, Op.EQ, outer.column(ID)), ID.asc());
        var filtered = Correlated.count(View.class, SubSelect.of(ID).where(f -> f.eq(NUMBER, "x")),
                (f, outer) -> f.compare(ID, Op.EQ, outer.column(ID)));
        var correlated = Correlated.count(View.class, SubSelect.of(ID),
                (f, outer) -> f.compare(STATUS, Op.EQ, outer.column(NUMBER)));
        var ordered = Correlated.first(View.class, SubSelect.of(STATUS),
                (f, outer) -> f.compare(ID, Op.EQ, outer.column(ID)), NUMBER.asc(), ID.asc());
        for (var field : List.of(selected, filtered, correlated, ordered)) {
            missing(ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(field)).build());
        }
    }

    @Test
    void ac_vnd_16_source_bodies_scan_selection_filters_windows_and_combined_branches() {
        ColumnField<Source, Payment, Long> id = ColumnField.of(Source.class, ROOT, "id", Long.class).named("id");
        ColumnField<Source, Card, String> number =
                ColumnField.of(Source.class, CARD, "number", String.class).named("number");
        var plain = SourceQuery.builder(Source.class, ROOT, SelectSet.of(id), PrimaryKey.of(id)).build();
        var selected = SourceQuery.builder(Source.class, ROOT, SelectSet.of(id, number), PrimaryKey.of(id)).build();
        var filtered = SourceQuery.builder(Source.class, ROOT, SelectSet.of(id), PrimaryKey.of(id))
                .where(f -> f.eq(number, "x")).build();
        var window = WindowField.of(Source.class, ROW_NUMBER, Long.class, null, List.of(number),
                List.of(id.asc()), NONE).named("rank");
        var windowed = SourceQuery.builder(Source.class, ROOT, SelectSet.of(id, window), PrimaryKey.of(id)).build();
        var ordered = WindowField.of(Source.class, ROW_NUMBER, Long.class, null, List.of(id),
                List.of(number.asc(), id.asc()), NONE).named("rank");
        var orderedSource = SourceQuery.builder(Source.class, ROOT, SelectSet.of(id, ordered), PrimaryKey.of(id))
                .build();
        var argument = WindowField.of(Source.class, FIRST_VALUE, String.class, number, List.of(id),
                List.of(id.asc()), NONE).named("first");
        var argumentSource = SourceQuery.builder(Source.class, ROOT, SelectSet.of(id, argument), PrimaryKey.of(id))
                .build();
        TableField<Source, Source> sourceRoot = TableField.source(Source.class);
        ColumnField<View, Source, Long> outerId = ColumnField.over(View.class, sourceRoot, id);
        for (var source : List.of(selected, filtered, windowed, orderedSource, argumentSource, plain.union(filtered))) {
            missing(ModelQuery.builder(sourceRoot, row -> new View(), source).select(SelectSet.of(outerId)).build());
        }
        assertThatCode(() -> TreatChecks.check(ModelQuery.builder(sourceRoot, row -> new View(), plain)
                .select(SelectSet.of(outerId)).build())).doesNotThrowAnyException();
    }

    @Test
    void ac_vnd_16_write_definitions_scan_rows_assignments_insert_mappings_and_persist_columns() {
        missing(ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "paid")
                .where(f -> f.eq(NUMBER, "x")).build());
        missing(ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.eq(NUMBER, "x")).build());
        var columns = InsertColumns.<View, Payment>of(ROOT).add(STATUS, row -> "paid");
        missing(ModelInsert.select(columns, ROOT).map(STATUS, NUMBER).all().build());
        assertThatCode(() -> TreatChecks.check(ModelPersist.of(columns, Long.class, new View())))
                .doesNotThrowAnyException();
        assertThatCode(() -> TreatChecks.check(ValuesInsert.builder(columns, Long.class, List.of(new View())).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void ac_vnd_16_a_fetch_childs_through_association_is_scanned() {
        var through = TableField.join(ROOT, "children", LEFT).treat(Card.class);
        ChildField<View, View> child = new ChildField<>() {
            @Override public String name() { return "children"; }
            @Override public ColumnField<View, ?, ?> key() { return ID; }
            @Override public Optional<ColumnField<View, ?, ?>> foreignKey() { return Optional.empty(); }
            @Override public Optional<TableField<?, ?>> through() { return Optional.of(through); }
            @Override public boolean isToMany() { return true; }
            @Override public ModelQuery.Builder<?, ?, View> query() {
                return ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                        .primaryKey(PrimaryKey.of(ID));
            }
            @Override public View with(View parent, List<View> children) { return parent; }
        };
        var plan = FetchPlan.of(SelectSet.of(ID));
        missing(ModelQuery.builder(ROOT, row -> new View()).primaryKey(PrimaryKey.of(ID))
                .fetch(plan.child(child, plan)).build());
    }

    @Test
    void ac_vnd_16_successful_scan_reports_nested_definitions_for_caching() {
        ChildField<View, View> child = new ChildField<>() {
            @Override public String name() { return "children"; }
            @Override public ColumnField<View, ?, ?> key() { return ID; }
            @Override public Optional<ColumnField<View, ?, ?>> foreignKey() { return Optional.of(ID); }
            @Override public Optional<TableField<?, ?>> through() { return Optional.empty(); }
            @Override public boolean isToMany() { return true; }
            @Override public ModelQuery.Builder<?, ?, View> query() {
                return ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                        .primaryKey(PrimaryKey.of(ID));
            }
            @Override public View with(View parent, List<View> children) { return parent; }
        };
        var leaf = FetchPlan.of(SelectSet.of(ID));
        var query = ModelQuery.builder(ROOT, row -> new View()).primaryKey(PrimaryKey.of(ID))
                .fetch(leaf.child(child, leaf.child(child, leaf))).build();
        var nested = query.fetch().orElseThrow().childLoads().get(0).query();
        var grandchild = nested.fetch().orElseThrow().childLoads().get(0).query();
        var checked = TreatChecks.check(query);
        assertThat(checked).containsExactlyInAnyOrder(query, nested, grandchild);
        assertThatThrownBy(checked::clear).isExactlyInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ac_vnd_16_plain_definitions_and_ordinary_joins_are_accepted_on_repeated_checks() {
        var joined = ColumnField.of(View.class, TableField.join(ROOT, "other", LEFT), "status", String.class);
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, joined)).build();
        assertThatCode(() -> {
            TreatChecks.check(query);
            TreatChecks.check(query);
        }).doesNotThrowAnyException();
        var treated = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(NUMBER)).build();
        missing(treated);
        missing(treated);
    }

    private static void missing(Object definition) {
        assertThatThrownBy(() -> TreatChecks.check(definition))
                .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .satisfies(error -> assertThat(((ModelQueryConfigurationException) error).code())
                        .isEqualTo(MqCode.MQ4014));
    }
}
