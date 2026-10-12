package com.rey.modelquery.tck.col;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * {@code Expr.value} renders a statement parameter, not definition text, on every statement path the executor
 * renders: list, count, offset page, primary-key-first (both steps), keyset, grouped and offset export,
 * {@code in}/{@code exists} sub-select bodies, a CASE condition, a bulk update and a bulk delete (spec api/10
 * R-COL-26; TCK AC-COL-31). Every snapshot below shows {@code ?} where a constant of the same value would be text.
 */
class BoundArgumentTest {

    /** A customer row as the read models below return it. */
    record C(Long id, String name, String country) {}

    /** An order row, for the sub-select over {@code orders} whose body carries the bound value. */
    record O(Long id, String status, Long referrerId) {}

    /** A customer with the CASE's mark: its name when it is in the US, otherwise the CASE's ELSE. */
    record Flagged(Long id, String mark) {}

    /** A country and how many customers it holds. */
    record CountryTotal(String country, Long count) {}

    private static final TableField<CustomerEntity, CustomerEntity> CUSTOMERS = TableField.root(CustomerEntity.class);
    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);

    private static final ColumnField<C, CustomerEntity, Long> ID = ColumnField.of(C.class, CUSTOMERS, "id", Long.class);
    private static final ColumnField<C, CustomerEntity, String> NAME =
            ColumnField.of(C.class, CUSTOMERS, "name", String.class);
    private static final ColumnField<C, CustomerEntity, String> COUNTRY =
            ColumnField.of(C.class, CUSTOMERS, "country", String.class);

    private static final ColumnField<O, OrderEntity, String> O_STATUS =
            ColumnField.of(O.class, ORDERS, "status", String.class);
    private static final ColumnField<O, OrderEntity, Long> O_REFERRER_ID =
            ColumnField.of(O.class, ORDERS, "referrerId", Long.class);

    private static final ColumnField<Flagged, CustomerEntity, Long> FLAG_ID =
            ColumnField.of(Flagged.class, CUSTOMERS, "id", Long.class);
    /** The CASE's condition and result over the {@link Flagged} model, as the CASE below reads them. */
    private static final ColumnField<Flagged, CustomerEntity, String> FLAG_COUNTRY =
            ColumnField.of(Flagged.class, CUSTOMERS, "country", String.class);
    private static final ColumnField<Flagged, CustomerEntity, String> FLAG_NAME =
            ColumnField.of(Flagged.class, CUSTOMERS, "name", String.class);
    private static final ColumnField<CountryTotal, CustomerEntity, String> GROUP_COUNTRY =
            ColumnField.of(CountryTotal.class, CUSTOMERS, "country", String.class);
    private static final AggregateField<CountryTotal, Long> GROUP_COUNT = Agg.count(CUSTOMERS);

    private static final TableField<ConstantRowEntity, ConstantRowEntity> CONSTANT_ROWS =
            TableField.root(ConstantRowEntity.class);
    private static final ColumnField<ConstantRowEntity, ConstantRowEntity, Long> CONSTANT_ID =
            ColumnField.of(ConstantRowEntity.class, CONSTANT_ROWS, "id", Long.class);

    private static final ModelQuery.Builder<CustomerEntity, Long, C> CUSTOMER_QUERY =
            ModelQuery.builder(CUSTOMERS, row -> new C(row.get(ID), row.get(NAME), row.get(COUNTRY)))
                    .select(SelectSet.of(ID, NAME, COUNTRY))
                    .primaryKey(PrimaryKey.of(ID))
                    .orderBy(ID.asc());

    /** The customers of the US: the value is a bound argument on every statement that renders this filter. */
    private static final String US_IDS = "select c.id from CustomerEntity c where c.country = 'US' order by c.id";

    /**
     * The referrers of the PAID orders. Its own WHERE carries an {@code Expr.value}, so the parameter is on the
     * sub-select's statement, not the outer query's (R-COL-26).
     */
    private static final SubSelect<O, Long> REFERRERS_OF_PAID =
            SubSelect.of(O_REFERRER_ID).where(f -> f.compare(O_STATUS, Op.EQ, Expr.value("PAID")));

    /** The customers of the US, with the CASE that marks them, for the CASE-condition path. */
    private static final ExpressionField<Flagged, String> US_MARK = Expr.cases(Flagged.class, String.class)
            .when(f -> f.compare(FLAG_COUNTRY, Op.EQ, Expr.value("US")), FLAG_NAME)
            .otherwise("other");

    // ---- the read paths

    @TckTest
    void ac_col_31_a_value_argument_binds_on_the_list_statement(TckDatabase db) {
        List<Long> expected = longIds(db, US_IDS);
        var listed = new ArrayList<Long>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-list", ds -> withExecutor(ds, CustomerEntity.class,
                executor -> listed.addAll(ids(executor, usFiltered().build()))));
        assertThat(listed).isEqualTo(expected).hasSize(125);
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("country=?").doesNotContain("'US'");
    }

    @TckTest
    void ac_col_31_a_value_argument_binds_on_the_count_statement(TckDatabase db) {
        long[] counted = new long[1];
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-count", ds -> withExecutor(ds, CustomerEntity.class,
                executor -> counted[0] = executor.count(usFiltered().build())));
        assertThat(counted[0]).isEqualTo(longIds(db, US_IDS).size());
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("count(").contains("country=?").doesNotContain("'US'");
    }

    @TckTest
    void ac_col_31_a_value_argument_binds_on_an_offset_page_statement(TckDatabase db) {
        List<Long> expected = longIds(db, US_IDS);
        var content = new ArrayList<Long>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-offset-page", ds -> withExecutor(ds,
                CustomerEntity.class, executor -> {
                    Slice<C> page = executor.page(usFiltered().build(), PageSpec.of(1, 5), CountMode.NO_COUNT);
                    page.content().forEach(row -> content.add(row.id()));
                }));
        assertThat(content).isEqualTo(expected.subList(5, 10));
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("country=?").doesNotContain("'US'");
    }

    @TckTest
    void ac_col_31_a_value_argument_binds_on_both_primary_key_first_steps(TckDatabase db) {
        List<Long> expected = longIds(db, US_IDS);
        var content = new ArrayList<Long>();
        // Offset 5 is above the threshold 0, so the page runs the key select and then the row fetch (R-PAG-07).
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-primary-key-first", ds -> withExecutor(ds,
                CustomerEntity.class, executor -> {
                    Slice<C> page = executor.page(usFiltered().primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0))
                            .build(), PageSpec.of(1, 5), CountMode.NO_COUNT);
                    page.content().forEach(row -> content.add(row.id()));
                }));
        assertThat(content).isEqualTo(expected.subList(5, 10));
        // The key select renders the filter and its bind; the row fetch reads the keys of that step.
        assertThat(sql).hasSize(2);
        assertThat(sql.get(0)).contains("country=?").doesNotContain("'US'");
        assertThat(sql.get(1)).contains("id in (");
    }

    @TckTest
    void ac_col_31_a_value_argument_binds_on_the_keyset_page_statement(TckDatabase db) {
        List<Long> expected = longIds(db, US_IDS);
        int size = TckFixture.CUSTOMERS / 8;
        var content = new ArrayList<Long>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-keyset", ds -> withExecutor(ds,
                CustomerEntity.class, executor -> executor.page(usFiltered().keyset().build(), KeysetSpec.first(size))
                        .content().forEach(row -> content.add(row.id()))));
        assertThat(content).isEqualTo(expected.subList(0, size));
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("country=?").doesNotContain("'US'");
    }

    // ---- the export paths

    @TckTest
    void ac_col_31_a_value_argument_binds_on_the_offset_export_statements(TckDatabase db) {
        List<Long> expected = longIds(db, US_IDS);
        var exported = new ArrayList<Long>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-offset-export", ds -> withExecutor(ds,
                CustomerEntity.class, executor -> assertThat(executor.export(usFiltered().build(),
                        ExportOptions.of(500), page -> page, row -> exported.add(row.id()))).isEqualTo(125)));
        assertThat(exported).isEqualTo(expected);
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("country=?").doesNotContain("'US'");
    }

    @TckTest
    void ac_col_31_a_value_argument_binds_on_the_grouped_export_statements(TckDatabase db) {
        var groups = new ArrayList<CountryTotal>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-grouped-export", ds -> withExecutor(ds,
                CustomerEntity.class, executor -> assertThat(executor.export(groupedByCountry().build(),
                        ExportOptions.of(5), page -> page, groups::add)).isEqualTo(1)));
        assertThat(groups).containsExactly(new CountryTotal("US", 125L));
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("group by").contains("country=?").doesNotContain("'US'");
    }

    // ---- the sub-select paths

    @TckTest
    void ac_col_31_a_value_argument_binds_in_a_sub_select_body(TckDatabase db) {
        List<Long> expected = longIds(db, "select c.id from CustomerEntity c where c.id in "
                + "(select o.referrerId from OrderEntity o where o.status = 'PAID') order by c.id");
        var listed = new ArrayList<Long>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-sub-select", ds -> withExecutor(ds,
                CustomerEntity.class, executor -> listed.addAll(ids(executor, CUSTOMER_QUERY
                        .where(f -> f.in(ID, REFERRERS_OF_PAID)).build()))));
        assertThat(listed).isEqualTo(expected).isNotEmpty();
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("in ((select oe1_0.referrer_id from orders").contains("status=?")
                .doesNotContain("'PAID'");
    }

    @TckTest
    void ac_col_31_a_value_argument_binds_in_an_exists_body(TckDatabase db) {
        List<Long> expected = longIds(db, "select c.id from CustomerEntity c where exists "
                + "(select 1 from OrderEntity o where o.status = 'PAID' and o.referrerId = c.id) order by c.id");
        var listed = new ArrayList<Long>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-exists", ds -> withExecutor(ds,
                CustomerEntity.class, executor -> listed.addAll(ids(executor, CUSTOMER_QUERY.where(f -> f.exists(
                        REFERRERS_OF_PAID, (s, outer) -> s.compare(O_REFERRER_ID, Op.EQ, outer.column(ID))))
                        .build()))));
        assertThat(listed).isEqualTo(expected).isNotEmpty();
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("exists(select 1 from orders").contains("status=?")
                .doesNotContain("'PAID'");
    }

    // ---- the CASE condition

    @TckTest
    void ac_col_31_a_value_argument_binds_in_a_case_condition(TckDatabase db) {
        var query = ModelQuery.builder(CUSTOMERS, row -> new Flagged(row.get(FLAG_ID), row.get(US_MARK)))
                .select(SelectSet.of(FLAG_ID, US_MARK))
                .primaryKey(PrimaryKey.of(FLAG_ID))
                .orderBy(FLAG_ID.asc())
                .build();
        List<Long> expected = longIds(db, US_IDS);
        var rows = new ArrayList<Flagged>();
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-case-condition", ds -> withExecutor(ds,
                CustomerEntity.class, executor -> rows.addAll(executor.list(query, Limit.unlimited()))));
        assertThat(rows).hasSize(TckFixture.CUSTOMERS);
        assertThat(rows.stream().filter(row -> !row.mark().equals("other")).map(Flagged::id).toList())
                .isEqualTo(expected);
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("case when").contains("country=?").doesNotContain("'US'");
    }

    // ---- the write paths

    @TckTest
    void ac_col_31_a_value_argument_binds_on_a_bulk_update_statement(TckDatabase db) {
        var update = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(ID)).set(NAME, "VIP")
                .where(f -> f.compare(COUNTRY, Op.EQ, Expr.value("US")))
                .build();
        long[] written = new long[1];
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-bulk-update", ds -> inRolledBackTransaction(ds,
                em -> written[0] = ModelQueryExecutor.create(em, CustomerEntity.class, ModelQueryConfig.defaults())
                        .update(update)));
        assertThat(written[0]).isEqualTo(125);
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).startsWith("update").contains("country=?").doesNotContain("'US'");
    }

    @TckTest
    void ac_col_31_a_value_argument_binds_on_a_bulk_delete_statement(TckDatabase db) {
        // constant_rows has no dependant table, so the delete cannot trip a foreign key inside the transaction.
        var delete = ModelDelete.builder(CONSTANT_ROWS).primaryKey(PrimaryKey.of(CONSTANT_ID))
                .where(f -> f.compare(CONSTANT_ID, Op.LTE, Expr.value(2L)))
                .build();
        long[] written = new long[1];
        List<String> sql = SqlSnapshots.assertMatches(db, "col-31-bulk-delete", ds -> inRolledBackTransaction(ds,
                em -> written[0] = ModelQueryExecutor.create(em, ConstantRowEntity.class, ModelQueryConfig.defaults())
                        .delete(delete)));
        assertThat(written[0]).isEqualTo(2);
        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).startsWith("delete").contains("id<=").contains("?");
    }

    @TckTest
    void ac_col_31_a_value_argument_binds_on_each_chunk_of_a_chunked_update_and_delete(TckDatabase db) {
        var update = ModelUpdate.builder(CUSTOMERS).primaryKey(PrimaryKey.of(ID)).set(NAME, "VIP")
                .where(f -> f.compare(COUNTRY, Op.EQ, Expr.value("US")))
                .chunked(ChunkOptions.size(50))
                .build();
        var delete = ModelDelete.builder(CONSTANT_ROWS).primaryKey(PrimaryKey.of(CONSTANT_ID))
                .where(f -> f.compare(CONSTANT_ID, Op.LTE, Expr.value(2L)))
                .chunked(ChunkOptions.size(1))
                .build();
        long[] written = new long[2];
        List<String> sql = SqlSnapshots.capture(db, ds -> inRolledBackTransaction(ds, em -> {
            written[0] = ModelQueryExecutor.create(em, CustomerEntity.class, ModelQueryConfig.defaults())
                    .update(update);
            written[1] = ModelQueryExecutor.create(em, ConstantRowEntity.class, ModelQueryConfig.defaults())
                    .delete(delete);
        }));
        assertThat(written).containsExactly(125, 2);
        assertThat(sql).anyMatch(s -> s.startsWith("update") && s.contains("country=?"));
        assertThat(sql).noneMatch(s -> s.contains("'US'"));
    }

    // ---- support

    /** The customers of the US. Each statement that renders the filter binds the value, not a {@code 'US'} literal. */
    private static ModelQuery.Builder<CustomerEntity, Long, C> usFiltered() {
        return CUSTOMER_QUERY.where(f -> f.compare(COUNTRY, Op.EQ, Expr.value("US")));
    }

    /** The customers of the US grouped by country, so the one group and its count come from a bound filter. */
    private static ModelQuery.Builder<CustomerEntity, Object, CountryTotal> groupedByCountry() {
        return ModelQuery.builder(CUSTOMERS, row -> new CountryTotal(row.get(GROUP_COUNTRY), row.get(GROUP_COUNT)))
                .select(SelectSet.of(GROUP_COUNTRY, GROUP_COUNT))
                .where(f -> f.compare(GROUP_COUNTRY, Op.EQ, Expr.value("US")))
                .groupBy(GROUP_COUNTRY)
                .orderBy(GROUP_COUNTRY.asc());
    }

    private static List<Long> ids(ModelQueryExecutor<CustomerEntity> executor, ModelQuery<CustomerEntity, ?, C> q) {
        return executor.list(q, Limit.unlimited()).stream().map(C::id).toList();
    }

    /** The ids {@code jpql} returns, read through Hibernate with literal text, to check a bound run's rows. */
    private static List<Long> longIds(TckDatabase db, String jpql) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            var ids = new ArrayList<Long>();
            sf.inSession(em -> ids.addAll(em.createQuery(jpql, Long.class).getResultList()));
            return ids;
        }
    }

    private static <E> void withExecutor(DataSource ds, Class<E> root, Consumer<ModelQueryExecutor<E>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, root, ModelQueryConfig.defaults())));
        }
    }

    /** Runs {@code work} in a transaction over {@code ds} and rolls it back, so the fixture stays as seeded. */
    private static void inRolledBackTransaction(DataSource ds, Consumer<EntityManager> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> {
                em.getTransaction().begin();
                try {
                    work.accept(em);
                } finally {
                    em.getTransaction().rollback();
                }
            });
        }
    }
}
