package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.hibernate.HibernateOperators;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.flt.OperatorProviderSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@link HibernateOperators}' array and {@code jsonb} containment per Tier-1 database: the rows each returns on
 * PostgreSQL, the value bound typed by the column, and each refused before any statement elsewhere, the vendor's
 * check first (spec api/12 R-FLT-20, vendor/40 R-VND-23, R-VND-24, D-170, D-171; TCK AC-FLT-24's array and
 * {@code jsonb} cases).
 */
class ContainmentOperatorsTest {

    /** PostgreSQL's {@code varchar[]}, {@code integer[]} and {@code jsonb} columns; only created there. */
    @Entity
    @Table(name = "containment_rows")
    static class ContainmentRow {
        @Id
        Long id;

        @Column(name = "tags")
        String[] tags;

        @Column(name = "nums")
        Integer[] nums;

        /** Mapped as text, so no JSON library is needed on either line; PostgreSQL casts it on write. */
        @Column(name = "doc", columnDefinition = "jsonb")
        @ColumnTransformer(write = "cast(? as jsonb)")
        String doc;

        ContainmentRow() {}

        ContainmentRow(long id, String[] tags, Integer[] nums, String doc) {
            this.id = id;
            this.tags = tags;
            this.nums = nums;
            this.doc = doc;
        }
    }

    record R(Long id) {}

    static final TableField<ContainmentRow, ContainmentRow> ROWS = TableField.root(ContainmentRow.class);
    static final ColumnField<R, ContainmentRow, Long> ID = ColumnField.of(R.class, ROWS, "id", Long.class);
    static final ColumnField<R, ContainmentRow, String[]> TAGS =
            ColumnField.of(R.class, ROWS, "tags", String[].class);
    static final ColumnField<R, ContainmentRow, Integer[]> NUMS =
            ColumnField.of(R.class, ROWS, "nums", Integer[].class);
    static final ColumnField<R, ContainmentRow, String> DOC = ColumnField.of(R.class, ROWS, "doc", String.class);
    static final ModelQuery.Builder<ContainmentRow, Object, R> QUERY =
            ModelQuery.builder(ROWS, row -> new R(row.get(ID))).select(SelectSet.of(ID)).orderBy(ID.asc());

    static final FilterOperator<String[], String[]> TAGS_CONTAIN = HibernateOperators.arrayContains();
    static final FilterOperator<String[], String[]> TAGS_OVERLAP = HibernateOperators.arrayOverlaps();
    static final FilterOperator<Integer[], Integer[]> NUMS_OVERLAP = HibernateOperators.arrayOverlaps();
    static final FilterOperator<String, String> DOC_CONTAINS = HibernateOperators.jsonbContains();

    @Test
    void ac_flt_24_the_array_and_jsonb_factories_are_cached() {
        assertThat(HibernateOperators.<String>arrayContains()).isSameAs(TAGS_CONTAIN).isNotSameAs(TAGS_OVERLAP);
        assertThat((Object) NUMS_OVERLAP).isSameAs(TAGS_OVERLAP);
        assertThat(HibernateOperators.<Integer>jsonbContains()).isSameAs(DOC_CONTAINS);
        assertThat(List.of(TAGS_CONTAIN.name(), TAGS_OVERLAP.name(), DOC_CONTAINS.name()))
                .containsExactly("arrayContains", "arrayOverlaps", "jsonbContains");
    }

    @TckTest
    void ac_flt_24_array_and_jsonb_containment_return_the_matching_rows_on_postgresql(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL, "only PostgreSQL has the containment features");
        List<List<Long>> results = new ArrayList<>();
        List<String> captured = SqlSnapshots.capture(db, ds -> withExecutor(ds, true, executor -> {
            results.add(ids(executor, f -> f.op(TAGS_CONTAIN, TAGS, new String[] {"red"})));
            results.add(ids(executor, f -> f.op(TAGS_CONTAIN, TAGS, new String[] {"red", "blue"})));
            // Every array contains the empty one; a null column contains nothing.
            results.add(ids(executor, f -> f.op(TAGS_CONTAIN, TAGS, new String[0])));
            results.add(ids(executor, f -> f.op(TAGS_OVERLAP, TAGS, new String[] {"green", "yellow"})));
            results.add(ids(executor, f -> f.op(NUMS_OVERLAP, NUMS, new Integer[] {2, 7})));
            results.add(ids(executor, f -> f.not(g -> g.op(TAGS_OVERLAP, TAGS, new String[] {"red"}))));
            results.add(ids(executor, f -> f.op(DOC_CONTAINS, DOC, "{\"kind\": \"a\"}")));
            results.add(ids(executor, f -> f.op(DOC_CONTAINS, DOC, "{\"tags\": [\"y\"]}")));
        }));
        // The queries alone, not the fixture's DDL and inserts.
        List<String> sql = SqlSnapshots.assertMatches(db, "flt-24-containment",
                captured.stream().filter(s -> s.contains("from containment_rows")).toList());
        assertThat(results).containsExactly(List.of(1L, 2L, 5L), List.of(2L, 5L), List.of(1L, 2L, 3L, 4L, 5L),
                List.of(3L, 5L), List.of(1L, 3L, 5L), List.of(3L, 4L), List.of(1L, 3L), List.of(2L, 3L));
        // The bare pattern, never compared to true, and the value a parameter.
        assertThat(sql).allSatisfy(s -> assertThat(s).containsAnyOf(" @> ?", " && ?", " @> cast(? as jsonb)")
                .doesNotContain("true").doesNotContain("'"));
    }

    @TckTest
    void ac_flt_24_an_array_value_with_no_binder_is_mq1502_before_any_statement(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL, "only PostgreSQL serves ARRAY_CONTAINMENT");
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = factory(ds, true)) {
                OperatorProviderSupport.serving(feature -> true, null, () -> sf.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, ContainmentRow.class, ModelQueryConfig.defaults());
                    assertThatThrownBy(() -> ids(executor, f -> f.op(TAGS_CONTAIN, TAGS, new String[] {"red"})))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                            .hasMessageContaining("op(arrayContains)");
                }));
            }
        });
        assertThat(sql).noneMatch(s -> s.contains("from containment_rows"));
    }

    @TckTest
    void ac_flt_24_malformed_json_text_is_the_providers_error_on_postgresql(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL, "only PostgreSQL has the containment features");
        // D-172 (4): the library doesn't parse the text; PostgreSQL's error passes through.
        SqlSnapshots.capture(db, ds -> withExecutor(ds, true, executor ->
                assertThatThrownBy(() -> ids(executor, f -> f.op(DOC_CONTAINS, DOC, "{kind:")))
                        .isInstanceOf(PersistenceException.class)));
    }

    @TckTest
    void ac_flt_24_the_vendor_check_refuses_the_containment_operators_with_mq4015_before_any_statement(
            TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.POSTGRESQL, "PostgreSQL serves both features");
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, false, executor -> {
            refused(executor, f -> f.op(TAGS_CONTAIN, TAGS, new String[] {"red"}), MqCode.MQ4015,
                    "op(arrayContains)", "ARRAY_CONTAINMENT");
            refused(executor, f -> f.op(NUMS_OVERLAP, NUMS, new Integer[] {2}), MqCode.MQ4015,
                    "op(arrayOverlaps)", "ARRAY_CONTAINMENT");
            refused(executor, f -> f.op(DOC_CONTAINS, DOC, "{}"), MqCode.MQ4015, "op(jsonbContains)",
                    "JSONB_CONTAINMENT");
        }));
        assertThat(sql).noneMatch(s -> s.contains("containment_rows"));
    }

    @TckTest
    void ac_flt_24_a_profile_answering_true_meets_hibernates_check_on_containment_mq4014_never_a_database_error(
            TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.POSTGRESQL, "PostgreSQL serves both features");
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = factory(ds, false)) {
                ModelQueryConfig config = ModelQueryConfig.defaults()
                        .vendorProfiles(List.of(OperatorProviderSupport.answering(sf, feature -> true)));
                sf.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, ContainmentRow.class, config);
                    // H2 has Hibernate's array_overlaps, which fails to prepare there; no mq_* function is registered.
                    refused(executor, f -> f.op(NUMS_OVERLAP, NUMS, new Integer[] {2}), MqCode.MQ4014,
                            "op(arrayOverlaps)", "ProviderSupport#operators");
                    refused(executor, f -> f.op(TAGS_CONTAIN, TAGS, new String[] {"red"}), MqCode.MQ4014,
                            "op(arrayContains)", "ProviderSupport#operators");
                    refused(executor, f -> f.op(DOC_CONTAINS, DOC, "{}"), MqCode.MQ4014, "op(jsonbContains)",
                            "ProviderSupport#operators");
                });
            }
        });
        assertThat(sql).noneMatch(s -> s.contains("containment_rows"));
    }

    // ---- helpers

    private static void refused(ModelQueryExecutor<ContainmentRow> executor, UnaryOperator<Filters<R>> where,
            MqCode code, String operator, String named) {
        assertThatThrownBy(() -> ids(executor, where))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(operator).hasMessageContaining(named);
    }

    private static List<Long> ids(ModelQueryExecutor<ContainmentRow> executor, UnaryOperator<Filters<R>> where) {
        return executor.list(QUERY.where(where).build(), Limit.unlimited()).stream().map(R::id).toList();
    }

    private static void withExecutor(DataSource ds, boolean seeded, Consumer<ModelQueryExecutor<ContainmentRow>> work) {
        try (SessionFactory sf = factory(ds, seeded)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, ContainmentRow.class,
                    ModelQueryConfig.defaults())));
        }
    }

    /**
     * A factory of {@link ContainmentRow} alone over {@code ds}; when {@code seeded} (PostgreSQL only) its table is
     * created, seeded and dropped with it, and otherwise never created, so a statement reaching it would fail.
     */
    private static SessionFactory factory(DataSource ds, boolean seeded) {
        SessionFactory sf = new Configuration().addAnnotatedClass(ContainmentRow.class).buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds)
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, seeded ? "create-drop" : "none")
                        .build());
        if (seeded) {
            sf.inTransaction(session -> {
                session.persist(new ContainmentRow(1, new String[] {"red"}, new Integer[] {1, 2},
                        "{\"kind\": \"a\", \"n\": 1}"));
                session.persist(new ContainmentRow(2, new String[] {"red", "blue"}, new Integer[] {3},
                        "{\"kind\": \"b\", \"tags\": [\"x\", \"y\"]}"));
                session.persist(new ContainmentRow(3, new String[] {"green"}, new Integer[] {2, 5},
                        "{\"kind\": \"a\", \"tags\": [\"y\"]}"));
                session.persist(new ContainmentRow(4, new String[0], new Integer[0], "{}"));
                session.persist(new ContainmentRow(5, new String[] {"blue", "green", "red"}, new Integer[] {7},
                        "{\"kind\": \"c\"}"));
                session.persist(new ContainmentRow(6, null, null, null));
            });
        }
        return sf;
    }
}
