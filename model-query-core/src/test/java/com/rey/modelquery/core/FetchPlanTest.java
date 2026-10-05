package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.SortSpec.Key;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * A fetch plan's selection, the columns it needs and its build-time checks, before any Criteria query exists (spec
 * api/15 R-FCH-01, R-FCH-02, R-FCH-07, R-FCH-10, R-FCH-11, R-FCH-13). The fixture copies the generated
 * {@code QCountryView}, {@code QCustomerView} and {@code QInvoiceView} of the processor's golden files.
 */
class FetchPlanTest {

    static final class CountryEntity {}

    static final class CustomerEntity {}

    static final class InvoiceEntity {}

    static final class LineEntity {}

    static final class CountryView {}

    static final class CustomerView {}

    static final class InvoiceView {}

    static final class LineView {}

    // QCountryView
    private static final TableField<CountryEntity, CountryEntity> COUNTRY_ROOT = TableField.root(CountryEntity.class);
    private static final OrderedColumnField<CountryView, CountryEntity, String> CODE =
            ColumnField.of(CountryView.class, COUNTRY_ROOT, "code", String.class).named("code");
    private static final OrderedColumnField<CountryView, CountryEntity, String> NAME =
            ColumnField.of(CountryView.class, COUNTRY_ROOT, "name", String.class).named("name");
    private static final PrimaryKey<CountryView, String> COUNTRY_KEY = PrimaryKey.of(CODE);

    // QCustomerView
    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMER_ROOT =
            TableField.root(CustomerEntity.class);
    private static final TableField<CustomerEntity, CountryEntity> COUNTRY_TABLE = TableField
            .<CustomerEntity, CountryEntity>join(CUSTOMER_ROOT, "country", LEFT).presentBy(COUNTRY_KEY)
            .named("country");
    private static final OrderedColumnField<CustomerView, CustomerEntity, Long> CUSTOMER_ID =
            ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<CustomerView, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "name", String.class).named("name");
    private static final OrderedColumnField<CustomerView, CustomerEntity, String> CUSTOMER_EMAIL =
            ColumnField.of(CustomerView.class, CUSTOMER_ROOT, "email", String.class);
    private static final OrderedColumnField<CustomerView, CountryEntity, String> COUNTRY_CODE =
            CODE.withTable(CustomerView.class, COUNTRY_TABLE);
    private static final OrderedColumnField<CustomerView, CountryEntity, String> COUNTRY_NAME =
            NAME.withTable(CustomerView.class, COUNTRY_TABLE);
    private static final PrimaryKey<CustomerView, Long> CUSTOMER_KEY = PrimaryKey.of(CUSTOMER_ID);

    // QInvoiceView
    private static final TableField<InvoiceEntity, InvoiceEntity> ROOT = TableField.root(InvoiceEntity.class);
    private static final TableField<InvoiceEntity, CustomerEntity> CUSTOMER_TABLE = TableField
            .<InvoiceEntity, CustomerEntity>join(ROOT, "customer", LEFT).as("customer").presentBy(CUSTOMER_KEY)
            .named("customer");
    private static final TableField<CustomerEntity, CountryEntity> CUSTOMER_COUNTRY_TABLE =
            COUNTRY_TABLE.withParent(CUSTOMER_TABLE);
    private static final TableField<InvoiceEntity, CustomerEntity> BUYER_TABLE = TableField
            .<InvoiceEntity, CustomerEntity>join(ROOT, "customer", INNER).as("payer").presentBy(CUSTOMER_KEY)
            .named("payer");
    private static final TableField<CustomerEntity, CountryEntity> BUYER_COUNTRY_TABLE =
            COUNTRY_TABLE.withParent(BUYER_TABLE);
    private static final OrderedColumnField<InvoiceView, InvoiceEntity, Long> ID =
            ColumnField.of(InvoiceView.class, ROOT, "id", Long.class).named("id");
    private static final OrderedColumnField<InvoiceView, InvoiceEntity, String> STATUS =
            ColumnField.of(InvoiceView.class, ROOT, "status", String.class).named("status");
    private static final OrderedColumnField<InvoiceView, InvoiceEntity, String> NUMBER =
            ColumnField.of(InvoiceView.class, ROOT, "number", String.class).named("number");
    private static final OrderedColumnField<InvoiceView, CustomerEntity, Long> INVOICE_CUSTOMER_ID =
            CUSTOMER_ID.withTable(InvoiceView.class, CUSTOMER_TABLE);
    private static final OrderedColumnField<InvoiceView, CustomerEntity, String> INVOICE_CUSTOMER_NAME =
            CUSTOMER_NAME.withTable(InvoiceView.class, CUSTOMER_TABLE);
    private static final OrderedColumnField<InvoiceView, CountryEntity, String> CUSTOMER_COUNTRY_CODE =
            COUNTRY_CODE.withTable(InvoiceView.class, CUSTOMER_COUNTRY_TABLE);
    private static final OrderedColumnField<InvoiceView, CountryEntity, String> CUSTOMER_COUNTRY_NAME =
            COUNTRY_NAME.withTable(InvoiceView.class, CUSTOMER_COUNTRY_TABLE);
    private static final OrderedColumnField<InvoiceView, CustomerEntity, Long> BUYER_ID =
            CUSTOMER_ID.withTable(InvoiceView.class, BUYER_TABLE);
    private static final OrderedColumnField<InvoiceView, CustomerEntity, String> BUYER_NAME =
            CUSTOMER_NAME.withTable(InvoiceView.class, BUYER_TABLE);
    private static final OrderedColumnField<InvoiceView, CountryEntity, String> BUYER_COUNTRY_CODE =
            COUNTRY_CODE.withTable(InvoiceView.class, BUYER_COUNTRY_TABLE);
    private static final OrderedColumnField<InvoiceView, CountryEntity, String> BUYER_COUNTRY_NAME =
            COUNTRY_NAME.withTable(InvoiceView.class, BUYER_COUNTRY_TABLE);
    private static final PrimaryKey<InvoiceView, Long> KEY = PrimaryKey.of(ID);

    // Hand-written in place of the generated ones (M8.13).
    private static final JoinField<CustomerView, CountryView> COUNTRY_JOIN =
            joinField("country", CustomerView.class, COUNTRY_TABLE);
    private static final JoinField<InvoiceView, CustomerView> CUSTOMER_JOIN =
            joinField("customer", InvoiceView.class, CUSTOMER_TABLE);
    private static final JoinField<InvoiceView, CustomerView> BUYER_JOIN =
            joinField("payer", InvoiceView.class, BUYER_TABLE);

    private static final TableField<LineEntity, LineEntity> LINE_ROOT = TableField.root(LineEntity.class);
    private static final OrderedColumnField<LineView, LineEntity, Long> LINE_ID =
            ColumnField.of(LineView.class, LINE_ROOT, "id", Long.class);
    private static final OrderedColumnField<LineView, LineEntity, Long> LINE_INVOICE_ID =
            ColumnField.of(LineView.class, LINE_ROOT, "invoiceId", Long.class);
    private static final ChildField<InvoiceView, LineView> LINES = childField("lines", ID, LINE_INVOICE_ID,
            () -> ModelQuery.builder(LINE_ROOT, row -> new LineView()).primaryKey(PrimaryKey.of(LINE_ID)));
    private static final ChildField<CustomerView, InvoiceView> INVOICES = childField("invoices", CUSTOMER_ID,
            CUSTOMER_ID.withTable(InvoiceView.class, CUSTOMER_TABLE), FetchPlanTest::invoices);

    private static final FetchPlan<CountryView> COUNTRY = FetchPlan.of(SelectSet.of(CODE, NAME));
    private static final FetchPlan<CustomerView> CUSTOMER =
            FetchPlan.of(SelectSet.of(CUSTOMER_ID, CUSTOMER_NAME)).join(COUNTRY_JOIN, COUNTRY);
    private static final FetchPlan<LineView> LINE = FetchPlan.of(SelectSet.of(LINE_ID));

    private static ModelQuery.Builder<InvoiceEntity, Long, InvoiceView> invoices() {
        return ModelQuery.builder(ROOT, row -> new InvoiceView()).primaryKey(KEY);
    }

    // ---- AC-FCH-05: re-rooting

    @Test
    void ac_fch_05_a_join_plan_re_roots_equal_to_the_generated_join_constants() {
        var plan = FetchPlan.of(SelectSet.of(ID, STATUS)).join(CUSTOMER_JOIN, CUSTOMER).join(BUYER_JOIN, CUSTOMER);

        // One plan serves on its own and nested: the customer plan's own country join, then both its uses.
        assertThat(CUSTOMER.selection().fields()).containsExactly(CUSTOMER_ID, CUSTOMER_NAME, COUNTRY_CODE,
                COUNTRY_NAME);
        assertThat(invoices().fetch(plan).build().select().fields()).containsExactly(ID, STATUS,
                INVOICE_CUSTOMER_ID, INVOICE_CUSTOMER_NAME, CUSTOMER_COUNTRY_CODE, CUSTOMER_COUNTRY_NAME,
                BUYER_ID, BUYER_NAME, BUYER_COUNTRY_CODE, BUYER_COUNTRY_NAME);
        // Equal as columns, and on the same join path: the two aliased joins to one entity stay apart.
        var rerooted = invoices().fetch(plan).build().select().fields();
        assertThat(((ColumnField<?, ?, ?>) rerooted.get(4)).table().key()).isEqualTo(CUSTOMER_COUNTRY_TABLE.key());
        assertThat(((ColumnField<?, ?, ?>) rerooted.get(8)).table().key()).isEqualTo(BUYER_COUNTRY_TABLE.key());
        var joins = new LinkedHashMap<String, JoinKey>();
        plan.joinTables().forEach((path, table) -> joins.put(path, table.key()));
        assertThat(joins).containsExactly(Map.entry("customer", CUSTOMER_TABLE.key()),
                Map.entry("customer.country", CUSTOMER_COUNTRY_TABLE.key()), Map.entry("payer", BUYER_TABLE.key()),
                Map.entry("payer.country", BUYER_COUNTRY_TABLE.key()));
    }

    @Test
    void ac_fch_05_a_re_rooted_join_column_is_selected_and_sortable() {
        var query = invoices().fetch(FetchPlan.of(SelectSet.of(ID)).join(CUSTOMER_JOIN, CUSTOMER)).build();

        assertThat(query.orderedBy(SortSpec.of(Key.asc("customer.country.name"))).orderBy())
                .containsExactly(CUSTOMER_COUNTRY_NAME.asc());
    }

    // ---- AC-FCH-06, AC-FCH-07: the columns a plan needs (R-FCH-02)

    @Test
    void ac_fch_06_child_keys_and_enricher_columns_are_selected_but_left_out_of_select() {
        Enricher<CustomerView> email = Enricher.of(UnaryOperator.identity(), CUSTOMER_EMAIL);
        Enricher<InvoiceView> number = Enricher.of(UnaryOperator.identity(), NUMBER);
        var plan = FetchPlan.of(SelectSet.of(STATUS))
                .child(LINES, LINE)
                .join(CUSTOMER_JOIN, FetchPlan.of(SelectSet.of(CUSTOMER_NAME)).enrich(email))
                .enrich(number);
        var query = invoices().fetch(plan).build();
        var invoiceEmail = CUSTOMER_EMAIL.withTable(InvoiceView.class, CUSTOMER_TABLE);

        assertThat(plan.needed()).containsExactly(ID, NUMBER, invoiceEmail);
        assertThat(query.select().fields()).containsExactly(STATUS, INVOICE_CUSTOMER_NAME);
        assertThat(query.fetch()).containsSame(plan);
        // After the key, the order and group keys; the presence key of the customer join last, as for a selected one.
        assertThat(query.modelColumns()).containsExactly(STATUS, INVOICE_CUSTOMER_NAME, ID, ID, NUMBER, invoiceEmail,
                INVOICE_CUSTOMER_ID, INVOICE_CUSTOMER_ID);
        // select() leaves them out, so a sort cannot name them.
        assertThatThrownBy(() -> query.orderedBy(SortSpec.of(Key.asc("number"))))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2301));
    }

    @Test
    void ac_fch_06_a_needed_column_under_a_present_by_join_adds_its_presence_key() {
        var plan = FetchPlan.of(SelectSet.of(STATUS))
                .enrich(Enricher.of(UnaryOperator.identity(), INVOICE_CUSTOMER_NAME));

        assertThat(invoices().fetch(plan).build().modelColumns()).containsExactly(STATUS, ID,
                INVOICE_CUSTOMER_NAME, INVOICE_CUSTOMER_ID);
    }

    @Test
    void ac_fch_06_an_enricher_column_on_a_grouped_query_must_be_a_group_key() {
        var plan = FetchPlan.of(SelectSet.of(STATUS, Agg.count(ROOT)))
                .enrich(Enricher.of(UnaryOperator.identity(), NUMBER));

        assertCode(() -> invoices().fetch(plan).groupBy(STATUS).build(), MqCode.MQ1401,
                "MQ1401: InvoiceView.number: read by an enricher of the fetch plan but not in groupBy; an enricher on "
                        + "a grouped query reads group keys");
        assertThatCode(() -> invoices().fetch(plan).groupBy(STATUS, NUMBER).build()).doesNotThrowAnyException();
    }

    @Test
    void ac_fch_06_an_enricher_column_under_a_present_by_join_on_a_grouped_query_needs_its_key_grouped() {
        var plan = FetchPlan.of(SelectSet.of(STATUS, Agg.count(ROOT)))
                .enrich(Enricher.of(UnaryOperator.identity(), INVOICE_CUSTOMER_NAME));

        assertCode(() -> invoices().fetch(plan).groupBy(STATUS, INVOICE_CUSTOMER_NAME).build(), MqCode.MQ1409,
                "MQ1409: InvoiceView.name: selected on a grouped query under the presentBy join 'customer' (LEFT, "
                        + "alias 'customer'), whose key column id is not in groupBy; a grouped query adds no presence "
                        + "key, so group by the key or select the column through a join without presentBy");
    }

    @Test
    void ac_fch_06_an_enricher_declares_a_copy_of_its_columns() {
        var columns = new ColumnField<?, ?, ?>[] {NUMBER};
        @SuppressWarnings("unchecked")
        var enricher = Enricher.byKey(InvoiceView::hashCode, keys -> Map.<Integer, String>of(), (m, v) -> m,
                (ColumnField<InvoiceView, ?, ?>[]) columns);
        columns[0] = STATUS;

        assertThat(enricher.columns()).containsExactly(NUMBER);
        assertThatThrownBy(() -> enricher.columns().add(STATUS)).isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- AC-FCH-07, AC-FCH-05: the MQ17xx checks

    @Test
    void ac_fch_05_a_join_plan_whose_join_has_no_selected_column_throws_mq1701() {
        var empty = FetchPlan.of(SelectSet.<CustomerView>of());

        assertCode(() -> invoices().fetch(FetchPlan.of(SelectSet.of(ID)).join(CUSTOMER_JOIN, empty)).build(),
                MqCode.MQ1701, "MQ1701: InvoiceView.customer: the join plan selects no column under its join, which "
                        + "then reads no nested model; select a column of the nested model in its plan, or drop the "
                        + "join(...)");
        // At any depth: the country join below the customer join.
        var noCountry = FetchPlan.of(SelectSet.of(CUSTOMER_NAME))
                .join(COUNTRY_JOIN, FetchPlan.of(SelectSet.<CountryView>of()));
        assertCode(() -> invoices().fetch(FetchPlan.of(SelectSet.of(ID)).join(CUSTOMER_JOIN, noCountry)).build(),
                MqCode.MQ1701, "MQ1701: InvoiceView.customer.country: the join plan selects no column under its "
                        + "join, which then reads no nested model; select a column of the nested model in its plan, "
                        + "or drop the join(...)");
        // A column selected under the join by the outer plan, or needed by the nested one, is enough.
        assertThatCode(() -> invoices().fetch(FetchPlan.of(SelectSet.of(ID, INVOICE_CUSTOMER_NAME))
                .join(CUSTOMER_JOIN, empty)).build()).doesNotThrowAnyException();
        assertThatCode(() -> invoices().fetch(FetchPlan.of(SelectSet.of(ID))
                .join(CUSTOMER_JOIN, empty.child(INVOICES, FetchPlan.of(SelectSet.of(ID))))).build())
                .doesNotThrowAnyException();
    }

    @Test
    void ac_fch_07_a_duplicate_child_or_join_throws_mq1703() {
        var withLines = FetchPlan.of(SelectSet.of(ID)).child(LINES, LINE);
        var withCustomer = FetchPlan.of(SelectSet.of(ID)).join(CUSTOMER_JOIN, CUSTOMER);

        assertCode(() -> withLines.child(LINES, LINE, c -> c.maxPerParent(5)), MqCode.MQ1703,
                "MQ1703: InvoiceView.lines: the fetch plan names this child twice; pass one plan for it, with "
                        + "everything it loads");
        assertCode(() -> withCustomer.join(CUSTOMER_JOIN, FetchPlan.of(SelectSet.of(CUSTOMER_ID))), MqCode.MQ1703,
                "MQ1703: InvoiceView.customer: the fetch plan names this join twice; pass one plan for it, with "
                        + "everything it loads");
        // Two aliased joins to one entity are two joins.
        assertThatCode(() -> withCustomer.join(BUYER_JOIN, CUSTOMER)).doesNotThrowAnyException();
    }

    @Test
    void ac_fch_07_a_child_on_a_grouped_query_throws_mq1704_at_any_join_depth() {
        var top = FetchPlan.of(SelectSet.of(STATUS, Agg.count(ROOT))).child(LINES, LINE);
        var nested = FetchPlan.of(SelectSet.of(STATUS, Agg.count(ROOT))).join(CUSTOMER_JOIN,
                FetchPlan.of(SelectSet.of(CUSTOMER_NAME)).child(INVOICES, FetchPlan.of(SelectSet.of(ID))));

        // Before MQ1401, which the child's key, not a group key, would raise.
        assertCode(() -> invoices().fetch(top).groupBy(STATUS).build(), MqCode.MQ1704,
                "MQ1704: InvoiceView.lines: a fetch plan loads this child on a grouped query, whose rows have no "
                        + "single key to match children on; load it from an ungrouped query");
        assertCode(() -> invoices().fetch(nested).groupBy(STATUS, INVOICE_CUSTOMER_NAME).build(), MqCode.MQ1704,
                "MQ1704: InvoiceView.customer.invoices: a fetch plan loads this child on a grouped query, whose rows "
                        + "have no single key to match children on; load it from an ungrouped query");
    }

    @Test
    void ac_fch_05_a_join_plan_selecting_an_aggregate_throws_mq1705() {
        var counted = FetchPlan.of(SelectSet.<CustomerView>of(CUSTOMER_NAME, Agg.count(CUSTOMER_ROOT)));
        var plan = FetchPlan.of(SelectSet.of(ID)).join(CUSTOMER_JOIN, counted);
        String message = "MQ1705: InvoiceView.customer: the join plan selects the aggregate count(CustomerEntity), "
                + "which cannot be re-rooted under the join; select the aggregate in the outer plan";

        assertCode(() -> invoices().fetch(plan).build(), MqCode.MQ1705, message);
        var query = invoices().select(SelectSet.of(ID)).build();
        assertCode(() -> query.withFetch(plan), MqCode.MQ1705, message);
    }

    @Test
    void ac_fch_18_a_join_plan_selecting_an_expression_throws_mq1705() {
        ExpressionField<CustomerView, Long> plusOne = Expr.plus(CUSTOMER_ID, 1L);
        var plan = FetchPlan.of(SelectSet.of(ID)).join(CUSTOMER_JOIN, FetchPlan.of(SelectSet.of(plusOne)));
        String message = "MQ1705: InvoiceView.customer: the join plan selects the expression " + plusOne.name()
                + ", which cannot be re-rooted under the join; select the expression in the outer plan";

        assertCode(() -> invoices().fetch(plan).build(), MqCode.MQ1705, message);
        var query = invoices().select(SelectSet.of(ID)).build();
        assertCode(() -> query.withFetch(plan), MqCode.MQ1705, message);
    }

    @Test
    void ac_fch_08_max_per_parent_below_one_throws_mq2001() {
        ChildQuery<LineView> query = ChildQuery.empty();

        for (int n : new int[] {0, -1}) {
            assertThatThrownBy(() -> query.maxPerParent(n))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ2001))
                    .hasMessage("MQ2001: maxPerParent(" + n + ") must be positive");
        }
        assertThat(query.maxPerParent(1).bound()).isEqualTo(1);
        assertThat(query.bound()).isZero();
    }

    @Test
    void ac_fch_04_a_child_query_is_a_copy_per_call() {
        ChildQuery<LineView> query = ChildQuery.empty();
        var ordered = query.orderBy(LINE_INVOICE_ID.desc());
        var filtered = ordered.where(f -> f.eq(LINE_INVOICE_ID, Optional.of(1L)));

        assertThat(query.order()).isEmpty();
        assertThat(ordered.order()).containsExactly(LINE_INVOICE_ID.desc());
        assertThat(ordered.filters()).isEmpty();
        assertThat(filtered.filters()).hasSize(1);
        assertThat(filtered.order()).isEqualTo(ordered.order());
    }

    @Test
    void ac_fch_04_a_child_load_selects_the_foreign_key_with_the_child_filters_and_order() {
        var plan = FetchPlan.of(SelectSet.of(ID)).child(LINES, LINE, c -> c
                .where(f -> f.eq(LINE_INVOICE_ID, Optional.of(1L))).orderBy(LINE_INVOICE_ID.desc()).maxPerParent(3));

        @SuppressWarnings("unchecked") // the plan's only child is LINES
        var load = (ChildLoad<InvoiceView, LineView>) plan.childLoads().get(0);
        assertThat(load.field()).isSameAs(LINES);
        assertThat(load.maxPerParent()).isEqualTo(3);
        assertThat(load.toString()).isEqualTo("InvoiceView.lines");
        // The child plan's selection, then the foreign key a load reads from each child row (R-FCH-05).
        assertThat(load.query().select().fields()).containsExactly(LINE_ID, LINE_INVOICE_ID);
        assertThat(load.query().orderBy()).containsExactly(LINE_INVOICE_ID.desc());
        assertThat(load.query().fetch()).hasValueSatisfying(child -> assertThat(child.isSelectionOnly()).isTrue());
        assertThat(plan.isSelectionOnly()).isFalse();
        assertThat(FetchPlan.of(SelectSet.of(ID)).isSelectionOnly()).isTrue();
    }

    @Test
    void ac_fch_11_a_child_field_needs_exactly_one_of_foreign_key_and_through() {
        var through = TableField.<InvoiceEntity, LineEntity>join(ROOT, "lines", INNER);
        Supplier<ModelQuery.Builder<?, ?, LineView>> lines =
                () -> ModelQuery.builder(LINE_ROOT, row -> new LineView()).primaryKey(PrimaryKey.of(LINE_ID));
        var both = childField("lines", ID, LINE_INVOICE_ID, through, lines);
        var neither = childField("lines", ID, null, null, lines);

        assertThatThrownBy(() -> FetchPlan.of(SelectSet.of(ID)).child(both, LINE))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("InvoiceView.lines: a child field needs exactly one of foreignKey() and through(), found "
                        + "both");
        assertThatThrownBy(() -> FetchPlan.of(SelectSet.of(ID)).child(neither, LINE))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("InvoiceView.lines: a child field needs exactly one of foreignKey() and through(), found "
                        + "neither");
    }

    @Test
    void ac_fch_11_a_through_child_selects_no_foreign_key_and_refuses_a_grouped_model_with_mq1704() {
        var through = TableField.<InvoiceEntity, LineEntity>join(ROOT, "lines", INNER);
        var lines = childField("lines", ID, null, through,
                () -> ModelQuery.builder(LINE_ROOT, row -> new LineView()).primaryKey(PrimaryKey.of(LINE_ID)));
        var plan = FetchPlan.of(SelectSet.of(ID)).child(lines, LINE);

        @SuppressWarnings("unchecked") // the plan's only child is lines
        var load = (ChildLoad<InvoiceView, LineView>) plan.childLoads().get(0);
        assertThat(load.query().select().fields()).containsExactly(LINE_ID);
        var grouped = childField("lines", ID, null, through,
                () -> ModelQuery.builder(LINE_ROOT, row -> new LineView()).groupBy(LINE_INVOICE_ID));
        assertCode(() -> FetchPlan.of(SelectSet.of(ID)).child(grouped,
                        FetchPlan.of(SelectSet.<LineView>of(LINE_INVOICE_ID, Agg.count(LINE_ROOT)))), MqCode.MQ1704,
                "MQ1704: InvoiceView.lines: a child loaded through an association path can't be grouped, since its "
                        + "rows would have to be grouped by the parent's key too");
    }

    // ---- R-FCH-02: select and fetch

    @Test
    void ac_fch_07_build_without_select_or_fetch_throws_mq1202() {
        assertCode(() -> invoices().build(), MqCode.MQ1202,
                "MQ1202: select(...) or fetch(...) is required for a query on InvoiceEntity");
    }

    @Test
    void ac_fch_09_select_after_fetch_drops_the_plan_with_a_warning() {
        var plan = FetchPlan.of(SelectSet.of(ID, STATUS)).child(LINES, LINE);
        List<String> warnings = new ArrayList<>();

        var selected = log(Level.WARNING, warnings, () -> invoices().fetch(plan).select(SelectSet.of(NUMBER)).build());

        assertThat(selected.fetch()).isEmpty();
        assertThat(selected.select().fields()).containsExactly(NUMBER);
        assertThat(warnings).containsExactly("InvoiceView: select(...) after fetch(...) discards the fetch plan, so "
                + "no child, join plan or enricher of it runs; call fetch(...) last, with the selection in its "
                + "FetchPlan.of(...)");
        // The other way round, the plan replaces the selection and nothing is dropped.
        warnings.clear();
        var fetched = log(Level.WARNING, warnings, () -> invoices().select(SelectSet.of(NUMBER)).fetch(plan).build());
        assertThat(fetched.fetch()).containsSame(plan);
        assertThat(fetched.select().fields()).containsExactly(ID, STATUS);
        assertThat(warnings).isEmpty();
    }

    // ---- AC-FCH-09: withFetch (R-FCH-13)

    @Test
    void ac_fch_09_with_fetch_returns_a_new_definition_and_leaves_the_original_unchanged() {
        var query = invoices().select(SelectSet.of(ID, STATUS)).orderBy(STATUS.asc()).build();
        var plan = FetchPlan.of(SelectSet.of(NUMBER)).child(LINES, LINE);

        var fetching = query.withFetch(plan);

        assertThat(fetching).isNotSameAs(query);
        assertThat(fetching.definition()).isSameAs(fetching);
        assertThat(fetching.select().fields()).containsExactly(NUMBER);
        assertThat(fetching.fetch()).containsSame(plan);
        assertThat(fetching.orderBy()).containsExactly(STATUS.asc());
        assertThat(fetching.modelColumns()).contains(ID);
        assertThat(query.fetch()).isEmpty();
        assertThat(query.select().fields()).containsExactly(ID, STATUS);
    }

    @Test
    void ac_fch_09_an_ordered_by_copy_keeps_the_plan() {
        var plan = FetchPlan.of(SelectSet.of(ID, STATUS)).join(CUSTOMER_JOIN, CUSTOMER);
        var fetching = invoices().select(SelectSet.of(ID)).build().withFetch(plan);

        var sorted = fetching.orderedBy(SortSpec.of(Key.desc("customer.name")));

        assertThat(sorted.fetch()).containsSame(plan);
        assertThat(sorted.select().fields()).isEqualTo(fetching.select().fields());
        assertThat(sorted.orderBy()).containsExactly(INVOICE_CUSTOMER_NAME.desc());
        assertThat(sorted.definition()).isSameAs(fetching);
        // The other way round, too.
        var defined = invoices().fetch(plan).build();
        assertThat(defined.orderedBy(SortSpec.of(Key.asc("status"))).fetch()).containsSame(plan);
    }

    @Test
    void ac_fch_09_with_fetch_checks_and_logs_once_per_query_and_plan() {
        var query = invoices().select(SelectSet.of(ID)).build();
        var plan = FetchPlan.of(SelectSet.of(ID, STATUS));
        List<String> built = new ArrayList<>();

        var first = log(Level.FINE, built, () -> query.withFetch(plan));
        var second = log(Level.FINE, built, () -> query.withFetch(plan));

        assertThat(built).containsExactly("built InvoiceView over InvoiceEntity: select [id, status], primaryKey "
                + "[id], paging offset");
        // The same copy, so the executor's first-run checks, keyed on the definition, do not run again.
        assertThat(second).isSameAs(first);
        assertThat(second.definition()).isSameAs(second);
        assertThat(second.select().fields()).containsExactly(ID, STATUS);
        assertThat(second.fetch()).containsSame(plan);
        // Another plan, or the same plan on another query, is checked anew; a failed check is not remembered.
        log(Level.FINE, built, () -> query.withFetch(FetchPlan.of(SelectSet.of(ID, STATUS))));
        log(Level.FINE, built, () -> invoices().select(SelectSet.of(ID)).build().withFetch(plan));
        assertThat(built).hasSize(4);
        var failing = FetchPlan.of(SelectSet.of(ID)).join(CUSTOMER_JOIN, FetchPlan.of(SelectSet.<CustomerView>of()));
        assertCode(() -> query.withFetch(failing), MqCode.MQ1701, null);
        assertCode(() -> query.withFetch(failing), MqCode.MQ1701, null);
    }

    // ---- support

    private static <M, N> JoinField<M, N> joinField(String name, Class<M> model, TableField<?, ?> table) {
        return new JoinField<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<M> model() {
                return model;
            }

            @Override
            public TableField<?, ?> table() {
                return table;
            }

            @Override
            public Optional<N> get(M parent) {
                return Optional.empty();
            }

            @Override
            public M with(M parent, N nested) {
                return parent;
            }
        };
    }

    private static <M, C> ChildField<M, C> childField(String name, ColumnField<M, ?, ?> key,
            ColumnField<C, ?, ?> foreignKey, Supplier<ModelQuery.Builder<?, ?, C>> query) {
        return childField(name, key, foreignKey, null, query);
    }

    private static <M, C> ChildField<M, C> childField(String name, ColumnField<M, ?, ?> key,
            ColumnField<C, ?, ?> foreignKey, TableField<?, ?> through, Supplier<ModelQuery.Builder<?, ?, C>> query) {
        return new ChildField<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ColumnField<M, ?, ?> key() {
                return key;
            }

            @Override
            public Optional<ColumnField<C, ?, ?>> foreignKey() {
                return Optional.ofNullable(foreignKey);
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return Optional.ofNullable(through);
            }

            @Override
            public boolean isToMany() {
                return true;
            }

            @Override
            public ModelQuery.Builder<?, ?, C> query() {
                return query.get();
            }

            @Override
            public M with(M parent, List<C> children) {
                return parent;
            }
        };
    }

    private static void assertCode(ThrowingCallable call, MqCode code, String message) {
        var thrown = assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(code));
        if (message != null) {
            thrown.hasMessage(message);
        }
    }

    /** Runs {@code work}, adding to {@code into} the formatted messages {@code ModelQuery} logs at {@code level}. */
    private static <T> T log(Level level, List<String> into, Supplier<T> work) {
        Logger logger = Logger.getLogger(ModelQuery.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == level) {
                    into.add(MessageFormat.format(r.getMessage(),
                            r.getParameters() == null ? new Object[0] : r.getParameters()));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Level before = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            return work.get();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(before);
        }
    }
}
