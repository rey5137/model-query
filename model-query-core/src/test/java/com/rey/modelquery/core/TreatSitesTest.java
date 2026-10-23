package com.rey.modelquery.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Statement-specific sites are checked without building or running a Criteria statement (R-VND-26). */
class TreatSitesTest {

    static class Payment {}
    static class Card extends Payment {}
    static class Bank extends Payment {}
    static class View {}
    static class Source {}

    private static final TableField<Payment, Payment> ROOT = TableField.root(Payment.class);
    private static final ColumnField<View, Payment, Long> ID = ColumnField.of(View.class, ROOT, "id", Long.class);
    private static final ColumnField<View, Payment, String> STATUS =
            ColumnField.of(View.class, ROOT, "status", String.class);
    private static final ColumnField<View, Card, String> NUMBER =
            ColumnField.of(View.class, ROOT.treat(Card.class), "number", String.class);
    private static final ColumnField<View, Card, String> NUMBER2 =
            ColumnField.of(View.class, ROOT.treat(Card.class), "number", String.class);

    @Test
    void ac_vnd_17_each_read_keeps_its_statement_and_enclosing_uses() {
        var query = ModelQuery.builder(ROOT, row -> new View())
                .select(SelectSet.of(ID, Expr.coalesce(NUMBER, "unknown")))
                .orderBy(NUMBER.asc())
                .where(f -> f.or(a -> a.not(n -> n.isNull(NUMBER)), a -> a.eq(ID, 1L))).build();
        var sites = sites(query, StatementShape.CONTENT, false);
        // An order key is selected too (R-QRY-04), so it reads in the select list as well.
        assertThat(sites).containsExactly(
                site(StatementShape.CONTENT, true, TreatUse.SELECT, TreatUse.EXPRESSION),
                site(StatementShape.CONTENT, true, TreatUse.SELECT),
                site(StatementShape.CONTENT, true, TreatUse.ORDER),
                site(StatementShape.CONTENT, true, TreatUse.NULL_TEST, TreatUse.NEGATED, TreatUse.DISJUNCTION));
        assertThatThrownBy(sites::clear).isExactlyInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ac_col_52_count_sites_drop_selection_and_order_but_keep_predicates() {
        var selected = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, NUMBER))
                .orderBy(NUMBER.asc()).build();
        assertThat(sites(selected, StatementShape.COUNT, false)).isEmpty();
        var filtered = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, NUMBER))
                .orderBy(NUMBER.asc()).where(f -> f.not(n -> n.isNotNull(NUMBER))).build();
        assertThat(sites(filtered, StatementShape.COUNT, false)).containsExactly(
                site(StatementShape.COUNT, true, TreatUse.NULL_TEST, TreatUse.NEGATED));
    }

    @Test
    void ac_vnd_17_a_custom_predicate_reports_no_site_so_its_treated_reads_are_refused_at_render() {
        // add(...) records no column, so it can't report even the null-accepting set: JoinContext refuses (D-156).
        var custom = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.add((ctx, cb) -> cb.conjunction()).add("card", (ctx, cb) -> cb.conjunction()))
                .build();
        for (var shape : List.of(StatementShape.CONTENT, StatementShape.COUNT)) {
            assertThat(sites(custom, shape, false)).isEmpty();
        }
        var beside = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.or(a -> a.add((ctx, cb) -> cb.conjunction()), b -> b.eq(NUMBER, "1"))).build();
        assertThat(sites(beside, StatementShape.COUNT, false)).containsExactly(
                site(StatementShape.COUNT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION));
    }

    @Test
    void ac_vnd_17_ne_and_not_in_carry_their_is_null_branch_as_null_test_and_disjunction() {
        for (var shape : List.of(StatementShape.CONTENT, StatementShape.COUNT)) {
            var ne = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.ne(NUMBER, "1")).build();
            assertThat(sites(ne, shape, false)).containsExactly(
                    site(shape, true, TreatUse.PREDICATE, TreatUse.NULL_TEST, TreatUse.DISJUNCTION));
            var notIn = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.not(n -> n.notIn(NUMBER, List.of("1", "2")))).build();
            assertThat(sites(notIn, shape, false)).containsExactly(site(shape, true, TreatUse.PREDICATE,
                    TreatUse.NULL_TEST, TreatUse.DISJUNCTION, TreatUse.NEGATED));
            var eq = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.not(n -> n.eq(NUMBER, "1"))).build();
            assertThat(sites(eq, shape, false)).containsExactly(
                    site(shape, true, TreatUse.PREDICATE, TreatUse.NEGATED));
        }
    }

    @Test
    void ac_vnd_17_null_safe_equality_sites_follow_the_sql_each_form_renders() {
        for (var shape : List.of(StatementShape.CONTENT, StatementShape.COUNT)) {
            var distinct = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.distinctFrom(NUMBER, "1")).build();
            assertThat(sites(distinct, shape, false)).containsExactly(
                    site(shape, true, TreatUse.PREDICATE, TreatUse.NULL_TEST, TreatUse.DISJUNCTION));
            var negatedDistinct = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.not(n -> n.distinctFrom(NUMBER, "1"))).build();
            var negatedNe = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.not(n -> n.ne(NUMBER, "1"))).build();
            assertThat(sites(negatedDistinct, shape, false)).isEqualTo(sites(negatedNe, shape, false))
                    .containsExactly(site(shape, true, TreatUse.PREDICATE, TreatUse.NULL_TEST,
                            TreatUse.DISJUNCTION, TreatUse.NEGATED));
            var same = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.notDistinctFrom(NUMBER, "1")).build();
            assertThat(sites(same, shape, false)).containsExactly(
                    site(shape, true, TreatUse.PREDICATE, TreatUse.NULL_TEST));
            var sameNull = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.notDistinctFrom(NUMBER, (String) null)).build();
            var isNull = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.isNull(NUMBER)).build();
            assertThat(sites(sameNull, shape, false)).isEqualTo(sites(isNull, shape, false))
                    .containsExactly(site(shape, true, TreatUse.NULL_TEST));
            var distinctNull = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.distinctFrom(NUMBER, (String) null)).build();
            assertThat(sites(distinctNull, shape, false)).containsExactly(site(shape, true, TreatUse.NULL_TEST));
            var column = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                    .where(f -> f.notDistinctFromColumn(NUMBER, NUMBER2).distinctFromColumn(NUMBER, NUMBER2)).build();
            assertThat(sites(column, shape, false)).containsExactly(site(shape, true, TreatUse.PREDICATE,
                    TreatUse.NULL_TEST, TreatUse.DISJUNCTION));
        }
    }

    @Test
    void ac_vnd_17_later_keyset_pages_are_checked_before_the_first_page() {
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, NUMBER))
                .primaryKey(PrimaryKey.of(ID)).orderBy(NUMBER.asc().nullsLast()).keyset().build();
        var sites = sites(query, StatementShape.CONTENT, true);
        assertThat(sites).contains(
                site(StatementShape.CONTENT, true, TreatUse.NULL_TEST),
                site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION),
                site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION, TreatUse.NULL_TEST));
        assertThat(sites(query, StatementShape.CONTENT, false)).doesNotContain(
                site(StatementShape.CONTENT, true, TreatUse.NULL_TEST),
                site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION));
        // Without an explicit precedence a NULL key is MQ2202, so no page resumes after one.
        var defaultNulls = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, NUMBER))
                .primaryKey(PrimaryKey.of(ID)).orderBy(NUMBER.asc()).keyset().build();
        assertThat(sites(defaultNulls, StatementShape.CONTENT, true))
                .contains(site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION))
                .doesNotContain(site(StatementShape.CONTENT, true, TreatUse.NULL_TEST),
                        site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION,
                                TreatUse.NULL_TEST));
    }

    @Test
    void ac_vnd_17_a_primary_key_tie_breaker_is_an_order_key_and_a_later_page_key() {
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(STATUS))
                .primaryKey(PrimaryKey.of(NUMBER)).orderBy(STATUS.asc()).keyset().build();
        assertThat(sites(query, StatementShape.CONTENT, true)).containsExactly(
                site(StatementShape.CONTENT, true, TreatUse.SELECT),
                site(StatementShape.CONTENT, true, TreatUse.ORDER),
                site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION));
        assertThat(sites(query, StatementShape.CONTENT, false)).containsExactly(
                site(StatementShape.CONTENT, true, TreatUse.SELECT),
                site(StatementShape.CONTENT, true, TreatUse.ORDER));
        // A key the order already holds is not appended again.
        var ordered = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(STATUS))
                .primaryKey(PrimaryKey.of(NUMBER)).orderBy(NUMBER.desc()).keyset().build();
        assertThat(sites(ordered, StatementShape.CONTENT, true)).containsExactly(
                site(StatementShape.CONTENT, true, TreatUse.SELECT),
                site(StatementShape.CONTENT, true, TreatUse.ORDER),
                site(StatementShape.CONTENT, true, TreatUse.PREDICATE, TreatUse.DISJUNCTION));
    }

    @Test
    void ac_vnd_17_a_correlated_value_reads_its_own_root_unanchored() {
        var innerId = ColumnField.of(Source.class, ROOT, "id", Long.class);
        var innerStatus = ColumnField.of(Source.class, ROOT, "status", String.class);
        var innerNumber = ColumnField.of(Source.class, ROOT.treat(Card.class), "number", String.class);
        var rows = SubSelect.of(innerId).where(f -> f.isNull(innerNumber));
        var count = Correlated.count(View.class, rows,
                (s, outer) -> s.compare(innerStatus, Op.EQ, outer.column(STATUS)));
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, count)).build();
        assertThat(sites(query, StatementShape.CONTENT, false)).containsExactly(
                site(StatementShape.CONTENT, false, TreatUse.SELECT, TreatUse.NULL_TEST, TreatUse.SUBQUERY));
        var max = Correlated.max(View.class, SubSelect.of(innerStatus).where(f -> f.isNull(innerNumber)),
                (s, outer) -> s.compare(innerStatus, Op.EQ, outer.column(STATUS)));
        var maxQuery = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, max)).build();
        assertThat(sites(maxQuery, StatementShape.CONTENT, false)).containsExactly(
                site(StatementShape.CONTENT, false, TreatUse.SELECT, TreatUse.NULL_TEST, TreatUse.SUBQUERY));
    }

    @Test
    void ac_vnd_17_a_correlated_count_over_its_own_treated_root_is_anchored() {
        var treatedId = ColumnField.of(Source.class, ROOT.treat(Card.class), "id", Long.class);
        var rows = SubSelect.of(treatedId);
        var count = Correlated.count(View.class, rows,
                (s, outer) -> s.compare(treatedId, Op.EQ, outer.column(ID)));
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, count)).build();
        // The counted column reads the sub-select's own treated root, so its count site is anchored and not refused
        // as an aggregate (R-COL-31); the correlation's inner treated column stays inside the unanchored sub-select.
        assertThat(sites(query, StatementShape.CONTENT, false)).containsExactly(
                site("id", StatementShape.CONTENT, true, TreatUse.SELECT, TreatUse.AGGREGATE, TreatUse.SUBQUERY),
                site("id", StatementShape.CONTENT, false, TreatUse.SELECT, TreatUse.PREDICATE, TreatUse.SUBQUERY));
    }

    @Test
    void ac_col_46_group_keys_and_aggregate_arguments_are_refused_even_by_an_accepting_renderer() {
        var grouped = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(NUMBER, Agg.count(ID)))
                .groupBy(NUMBER).build();
        var aggregated = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(STATUS, Agg.count(NUMBER)))
                .groupBy(STATUS).build();
        for (var query : List.of(grouped, aggregated)) {
            assertRefused(() -> sites(query, StatementShape.CONTENT, false), "group key or aggregate");
            assertRefused(() -> sites(query, StatementShape.COUNT, false), "group key or aggregate");
        }
        // A treated predicate on a grouped query is a GROUPED site, unanchored, for the renderer to decide.
        var filtered = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(STATUS, Agg.count(ID)))
                .groupBy(STATUS).where(f -> f.eq(NUMBER, "1")).build();
        assertThat(sites(filtered, StatementShape.CONTENT, false)).containsExactly(
                site(StatementShape.GROUPED, false, TreatUse.PREDICATE));
    }

    @Test
    void ac_col_52_a_treated_node_is_found_in_the_statement_but_not_in_its_source_or_children() {
        var plain = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, STATUS)).build();
        var selected = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, NUMBER)).build();
        var filtered = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.isNull(NUMBER)).build();
        var ordered = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .orderBy(NUMBER.asc()).build();
        assertThat(TreatChecks.treated(plain)).isFalse();
        assertThat(TreatChecks.treated(selected)).isTrue();
        assertThat(TreatChecks.treated(filtered)).isTrue();
        assertThat(TreatChecks.treated(ordered)).isTrue();
        assertThat(TreatChecks.selectsRootId(List.of(ID, NUMBER), "id")).isTrue();
        assertThat(TreatChecks.selectsRootId(List.of(STATUS, NUMBER), "id")).isFalse();
        var treatedId = ColumnField.of(View.class, ROOT.treat(Card.class), "id", Long.class);
        assertThat(TreatChecks.selectsRootId(List.of(treatedId), "id")).isFalse();
    }

    @Test
    void ac_vnd_17_source_statements_keep_their_own_shape() {
        var sourceId = ColumnField.of(Source.class, ROOT, "id", Long.class).named("id");
        var number = ColumnField.of(Source.class, ROOT.treat(Card.class), "number", String.class).named("number");
        var source = SourceQuery.builder(Source.class, ROOT, SelectSet.of(sourceId, number), PrimaryKey.of(sourceId))
                .build();
        var sourceRoot = TableField.source(Source.class);
        var outer = ColumnField.over(View.class, sourceRoot, sourceId);
        var query = ModelQuery.builder(sourceRoot, row -> new View(), source).select(SelectSet.of(outer)).build();
        // An ungrouped body over an entity root selects its root id, so its sites are anchored (R-SRC-20).
        assertThat(sites(query, StatementShape.CONTENT, false)).containsExactly(
                site(StatementShape.SOURCE_BODY, true, TreatUse.SELECT));
        assertThat(source.treated()).isTrue();
        assertThat(TreatChecks.treated(query)).isFalse();
    }

    @Test
    void ac_col_46_a_treated_group_key_or_window_argument_in_a_source_body_is_refused() {
        var sourceId = ColumnField.of(Source.class, ROOT, "id", Long.class).named("id");
        var number = ColumnField.of(Source.class, ROOT.treat(Card.class), "number", String.class).named("number");
        var sourceRoot = TableField.source(Source.class);
        var outer = ColumnField.over(View.class, sourceRoot, sourceId);
        var grouped = SourceQuery.builder(Source.class, ROOT, SelectSet.of(number), PrimaryKey.of(number),
                SelectSet.of(number)).build();
        var groupedQuery = ModelQuery.builder(sourceRoot, row -> new View(), grouped)
                .select(SelectSet.of(ColumnField.over(View.class, sourceRoot, number))).build();
        assertRefused(() -> sites(groupedQuery, StatementShape.CONTENT, false), "group key or aggregate argument");
        var lagged = WindowField.of(Source.class, WindowFunction.LAG, String.class, number, List.of(),
                List.of(sourceId.asc()), WindowFrame.NONE).named("lagged");
        var windowed = SourceQuery.builder(Source.class, ROOT, SelectSet.of(sourceId, lagged), PrimaryKey.of(sourceId))
                .build();
        var windowedQuery = ModelQuery.builder(sourceRoot, row -> new View(), windowed).select(SelectSet.of(outer))
                .build();
        assertRefused(() -> sites(windowedQuery, StatementShape.CONTENT, false), "group key or aggregate argument");
    }

    @Test
    void ac_col_46_a_treated_window_argument_in_a_query_is_refused() {
        // No cell measured a window over a treated argument in any statement shape (D-162).
        var lagged = WindowField.of(View.class, WindowFunction.LAG, String.class, NUMBER, List.of(),
                List.of(ID.asc()), WindowFrame.NONE).named("lagged");
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID, lagged)).build();
        assertRefused(() -> sites(query, StatementShape.CONTENT, false), "group key or aggregate argument");
    }

    @Test
    void ac_col_52_a_distinct_set_operation_over_a_treated_branch_without_the_root_id_is_refused() {
        var sourceId = ColumnField.of(Source.class, ROOT, "id", Long.class).named("id");
        var number = ColumnField.of(Source.class, ROOT.treat(Card.class), "number", String.class).named("number");
        var status = ColumnField.of(Source.class, ROOT, "status", String.class).named("number");
        var treated = SourceQuery.builder(Source.class, ROOT, SelectSet.of(sourceId, number), PrimaryKey.of(sourceId))
                .build();
        var plain = SourceQuery.builder(Source.class, ROOT, SelectSet.of(sourceId, status), PrimaryKey.of(sourceId))
                .build();
        var sourceRoot = TableField.source(Source.class);
        var outer = ColumnField.over(View.class, sourceRoot, sourceId);
        for (var combined : List.of(treated.union(plain), plain.intersect(treated), treated.except(plain))) {
            var query = ModelQuery.builder(sourceRoot, row -> new View(), combined).select(SelectSet.of(outer))
                    .build();
            // Without a metamodel the root id is unknown, so the branch is refused, failing closed.
            assertRefused(() -> sites(query, StatementShape.CONTENT, false), "unionAll");
        }
        var all = ModelQuery.builder(sourceRoot, row -> new View(), treated.unionAll(plain))
                .select(SelectSet.of(outer)).build();
        assertThat(sites(all, StatementShape.CONTENT, false)).isNotEmpty();
    }

    private static void assertRefused(Runnable check, String reason) {
        assertThatThrownBy(check::run).isExactlyInstanceOf(ModelQueryConfigurationException.class)
                .hasMessageContaining(reason)
                .extracting(failure -> ((ModelQueryConfigurationException) failure).code())
                .isEqualTo(MqCode.MQ4016);
    }

    @Test
    void ac_vnd_17_renderer_refusals_are_rechecked_for_each_call_and_name_the_site() {
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(NUMBER)).build();
        var renderer = new Renderer() {
            @Override public Optional<String> refusal(TreatSite site) { return Optional.of("unmeasured cell"); }
        };
        for (int call = 0; call < 2; call++) {
            assertThatThrownBy(() -> TreatChecks.check(query, RenderOptions.portable().withTreatRenderer(renderer),
                    null, StatementShape.CONTENT, false, true))
                    .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                    .hasMessageContaining("number").hasMessageContaining("CONTENT")
                    .hasMessageContaining("SELECT").hasMessageContaining("unmeasured cell")
                    .extracting(failure -> ((ModelQueryConfigurationException) failure).code())
                    .isEqualTo(MqCode.MQ4016);
        }
    }

    @Test
    void ac_vnd_20_treated_write_predicates_are_refused_even_by_an_accepting_renderer() {
        var update = ModelUpdate.builder(ROOT).primaryKey(PrimaryKey.of(ID)).set(STATUS, "paid")
                .where(f -> f.eq(NUMBER, "x")).build();
        var delete = ModelDelete.builder(ROOT).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.eq(NUMBER, "x")).build();
        for (Object definition : List.of(update, delete)) {
            assertThatThrownBy(() -> TreatChecks.check(definition,
                    RenderOptions.portable().withTreatRenderer(new Renderer()), null,
                    StatementShape.WRITE, false, false))
                    .isExactlyInstanceOf(ModelQueryConfigurationException.class)
                    .extracting(failure -> ((ModelQueryConfigurationException) failure).code())
                    .isEqualTo(MqCode.MQ4016);
        }
    }

    @Test
    void ac_col_48_sibling_attribute_indexes_keep_the_treat_chain() {
        var bank = ColumnField.of(View.class, ROOT.treat(Bank.class), "number", String.class);
        var selected = FieldIndex.builder(View.class).select(NUMBER, bank).build();
        var filtered = FieldIndex.builder(View.class).filterOnly(NUMBER, bank).build();
        String cardPath = "treat(" + Card.class.getName() + ").number";
        String bankPath = "treat(" + Bank.class.getName() + ").number";
        assertThat(selected.filter(cardPath)).contains(NUMBER);
        assertThat(selected.filter(bankPath)).contains(bank);
        assertThat(filtered.filter(cardPath)).contains(NUMBER);
        assertThat(filtered.filter(bankPath)).contains(bank);
        assertThat(NUMBER.path()).isEqualTo("number");
    }

    @Test
    void ac_vnd_17_an_exists_nested_under_a_treated_exists_path_is_a_correlation_site_but_a_sibling_is_not() {
        TableField<Payment, Card> treated = ROOT.treat(Card.class);
        TableField<Card, View> nested = TableField.join(treated, "views", JoinType.INNER);
        TableField<Card, View> sibling = TableField.join(treated, "views", JoinType.LEFT);
        var nesting = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.exists(treated, e -> e.exists(nested))).build();
        var siblings = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.exists(treated).exists(sibling)).build();
        assertThat(sites(nesting, StatementShape.CONTENT, false))
                .anySatisfy(site -> assertThat(site.uses()).contains(TreatUse.CORRELATION, TreatUse.SUBQUERY));
        assertThat(sites(siblings, StatementShape.CONTENT, false))
                .isNotEmpty().allSatisfy(site -> assertThat(site.uses()).doesNotContain(TreatUse.CORRELATION));
    }

    @Test
    void ac_vnd_17_an_exists_in_a_sub_select_under_a_treated_exists_correlates_on_the_sub_selects_root() {
        TableField<Payment, Card> treated = ROOT.treat(Card.class);
        var cardId = ColumnField.of(View.class, treated, "id", Long.class);
        var innerId = ColumnField.of(Source.class, ROOT, "id", Long.class);
        TableField<Card, View> innerViews = TableField.join(ROOT.treat(Card.class), "views", JoinType.INNER);
        var rows = SubSelect.of(innerId).where(s -> s.exists(innerViews));
        var query = ModelQuery.builder(ROOT, row -> new View()).select(SelectSet.of(ID))
                .where(f -> f.exists(treated, e -> e.in(cardId, rows))).build();
        // The sub-select's exists correlates on the sub-select's own root, not on the enclosing treated path.
        assertThat(sites(query, StatementShape.CONTENT, false))
                .isNotEmpty().allSatisfy(site -> assertThat(site.uses()).doesNotContain(TreatUse.CORRELATION));
    }

    @Test
    void ac_vnd_21_a_treated_node_in_a_source_body_branch_or_inner_source_is_reached_not_anchored() {
        var sourceId = ColumnField.of(Source.class, ROOT, "id", Long.class).named("id");
        var number = ColumnField.of(Source.class, ROOT.treat(Card.class), "number", String.class).named("number");
        var treated = SourceQuery.builder(Source.class, ROOT, SelectSet.of(sourceId, number), PrimaryKey.of(sourceId))
                .build();
        var untreatedNumber = ColumnField.of(Source.class, ROOT, "status", String.class).named("number");
        var plain = SourceQuery.builder(Source.class, ROOT, SelectSet.of(sourceId, untreatedNumber),
                PrimaryKey.of(sourceId)).build();
        var sourceRoot = TableField.source(Source.class);
        var outerId = ColumnField.over(View.class, sourceRoot, sourceId);
        assertThat(reaches(treated, sourceRoot, outerId)).isTrue();
        assertThat(reaches(plain, sourceRoot, outerId)).isFalse();
        // A set-operation branch, on either side, and a source over a treated inner source, at depth.
        assertThat(reaches(plain.unionAll(treated), sourceRoot, outerId)).isTrue();
        assertThat(reaches(treated.unionAll(plain), sourceRoot, outerId)).isTrue();
        assertThat(reaches(plain.unionAll(plain), sourceRoot, outerId)).isFalse();
        var middleId = ColumnField.over(Source.class, sourceRoot, sourceId).named("id");
        var over = SourceQuery.builder(Source.class, sourceRoot, SelectSet.of(middleId), PrimaryKey.of(middleId),
                treated).build();
        assertThat(reaches(over, sourceRoot, outerId)).isTrue();
        // The outer query has no treated node of its own, so the anchor decision is unchanged.
        var query = ModelQuery.builder(sourceRoot, row -> new View(), treated).select(SelectSet.of(outerId)).build();
        assertThat(TreatChecks.treated(query)).isFalse();
        assertThat(TreatChecks.reachesTreat(query)).isTrue();
        // An own treated node is reached too.
        assertThat(TreatChecks.reachesTreat(ModelQuery.builder(ROOT, row -> new View())
                .select(SelectSet.of(ID, NUMBER)).build())).isTrue();
    }

    private static boolean reaches(SourceQuery<Source> source, TableField<Source, Source> root,
            ColumnField<View, Source, Long> outerId) {
        return TreatChecks.reachesTreat(ModelQuery.builder(root, row -> new View(), source)
                .select(SelectSet.of(outerId)).build());
    }

    private static List<TreatSite> sites(ModelQuery<?, ?, ?> query, StatementShape shape, boolean laterPages) {
        var renderer = new Renderer();
        var sites = TreatChecks.check(query, RenderOptions.portable().withTreatRenderer(renderer), null,
                shape, laterPages, true);
        // The site check builds no statement, so it preflights none: the executor preflights the ones it builds.
        assertThat(renderer.preflighted).isEmpty();
        return sites;
    }

    private static TreatSite site(StatementShape shape, boolean anchored, TreatUse... uses) {
        return site("number", shape, anchored, uses);
    }

    private static TreatSite site(String attribute, StatementShape shape, boolean anchored, TreatUse... uses) {
        return new TreatSite(Payment.class, Card.class, Optional.of(attribute), shape, anchored, Set.of(uses));
    }

    private static class Renderer implements TreatRenderer {
        final List<StatementShape> preflighted = new ArrayList<>();

        @Override
        public <T, S extends T> From<?, S> treat(CriteriaBuilder cb, From<?, T> from, Class<S> subtype) {
            throw new AssertionError("sites require no Criteria rendering");
        }

        @Override public Optional<String> refusal(TreatSite site) { return Optional.empty(); }

        @Override
        public Optional<String> preflight(EntityManager em, CommonAbstractCriteria statement, StatementShape shape,
                List<ExpectedNarrowing> expected) {
            preflighted.add(shape);
            return Optional.empty();
        }
    }
}
