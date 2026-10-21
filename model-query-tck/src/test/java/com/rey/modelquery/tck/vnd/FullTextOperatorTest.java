package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.FilterOperator;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
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
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Assumptions;

/**
 * {@link HibernateOperators#fullTextMatch} per Tier-1 database: the rows request text returns on PostgreSQL, the
 * configuration a literal and the text a parameter, and the operator refused before any statement elsewhere, the
 * vendor's check first (spec vendor/40 R-VND-23, D-173; TCK AC-FLT-32).
 */
class FullTextOperatorTest {

    @Entity
    @Table(name = "full_text_rows")
    static class FullTextRow {
        @Id
        Long id;

        @Column(name = "name")
        String name;

        FullTextRow() {}

        FullTextRow(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    record R(Long id) {}

    /** A schema-qualified copy of {@code english}, created and dropped by the PostgreSQL case. */
    private static final String CUSTOM_CONFIGURATION = "public.mq_full_text_config";

    static final TableField<FullTextRow, FullTextRow> ROWS = TableField.root(FullTextRow.class);
    static final ColumnField<R, FullTextRow, Long> ID = ColumnField.of(R.class, ROWS, "id", Long.class);
    static final ColumnField<R, FullTextRow, String> NAME = ColumnField.of(R.class, ROWS, "name", String.class);
    static final ModelQuery.Builder<FullTextRow, Object, R> QUERY =
            ModelQuery.builder(ROWS, row -> new R(row.get(ID))).select(SelectSet.of(ID)).orderBy(ID.asc());

    static final FilterOperator<String, String> ENGLISH = HibernateOperators.fullTextMatch("english");

    @TckTest
    void ac_flt_32_request_text_returns_the_matching_rows_on_postgresql_with_no_database_error(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL, "only PostgreSQL has FULL_TEXT");
        FilterOperator<String, String> custom = HibernateOperators.fullTextMatch(CUSTOM_CONFIGURATION);
        List<List<Long>> results = new ArrayList<>();
        List<String> captured = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = factory(ds, true)) {
                // Dropped first too, in case an earlier run against a reused database stopped before its finally.
                configuration(sf, "drop text search configuration if exists ");
                configuration(sf, "create text search configuration ", " (copy = english)");
                try {
                    sf.inSession(em -> {
                        var executor = ModelQueryExecutor.create(em, FullTextRow.class, ModelQueryConfig.defaults());
                        // Words, all of which must match; punctuation and quotes are never tsquery syntax (D-173).
                        results.add(ids(executor, f -> f.op(ENGLISH, NAME, "acme corp")));
                        results.add(ids(executor, f -> f.op(ENGLISH, NAME, "acme, corp.")));
                        results.add(ids(executor, f -> f.op(ENGLISH, NAME, "o'brien")));
                        results.add(ids(executor, f -> f.op(ENGLISH, NAME, "\"best\" widgets")));
                        results.add(ids(executor, f -> f.op(ENGLISH, NAME, "corp")));
                        // Neither an OR nor a negation: websearch_to_tsquery would widen both.
                        results.add(ids(executor, f -> f.op(ENGLISH, NAME, "acme or corp")));
                        results.add(ids(executor, f -> f.op(ENGLISH, NAME, "-acme")));
                        // No lexemes matches no row, with a notice and no error.
                        results.add(ids(executor, f -> f.op(ENGLISH, NAME, "the")));
                        results.add(ids(executor, f -> f.op(ENGLISH, NAME, "&|!():*")));
                        // A null name matches neither the filter nor its not(...).
                        results.add(ids(executor, f -> f.not(g -> g.op(ENGLISH, NAME, "acme"))));
                        results.add(ids(executor, f -> f.op(custom, NAME, "industries")));
                    });
                } finally {
                    configuration(sf, "drop text search configuration if exists ");
                }
            }
        });
        // The queries alone, not the fixture's DDL and inserts.
        List<String> sql = SqlSnapshots.assertMatches(db, "flt-32-full-text",
                captured.stream().filter(s -> s.contains("from full_text_rows")).toList());
        assertThat(results).containsExactly(List.of(1L), List.of(1L), List.of(4L), List.of(5L), List.of(1L, 3L),
                List.of(1L), List.of(1L, 2L), List.of(), List.of(), List.of(3L, 4L, 5L), List.of(2L));
        // The configuration a literal in both calls, so gin (to_tsvector('english', name)) matches; the text bound.
        assertThat(sql.subList(0, sql.size() - 1)).allSatisfy(s -> assertThat(s)
                .contains("to_tsvector('english', ftr1_0.name)").contains("plainto_tsquery('english', ?)"));
        assertThat(sql.get(sql.size() - 1)).contains("to_tsvector('" + CUSTOM_CONFIGURATION + "', ftr1_0.name)")
                .contains("plainto_tsquery('" + CUSTOM_CONFIGURATION + "', ?)");
        assertThat(sql).allSatisfy(s -> assertThat(s.chars().filter(c -> c == '?').count()).isEqualTo(1));
    }

    @TckTest
    void ac_flt_32_the_vendor_check_refuses_full_text_with_mq4015_before_any_statement(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.POSTGRESQL, "PostgreSQL serves FULL_TEXT");
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = factory(ds, false)) {
                sf.inSession(em -> refused(
                        ModelQueryExecutor.create(em, FullTextRow.class, ModelQueryConfig.defaults()),
                        MqCode.MQ4015, "FULL_TEXT"));
            }
        });
        assertThat(sql).noneMatch(s -> s.contains("full_text_rows"));
    }

    @TckTest
    void ac_flt_32_a_profile_answering_true_meets_hibernates_check_mq4014_never_a_database_error(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() != TckVendor.POSTGRESQL, "PostgreSQL serves FULL_TEXT");
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = factory(ds, false)) {
                ModelQueryConfig config = ModelQueryConfig.defaults()
                        .vendorProfiles(List.of(OperatorProviderSupport.answering(sf, feature -> true)));
                // No mq_ts_match is registered off PostgreSQL's dialect, so Hibernate answers false.
                sf.inSession(em -> refused(ModelQueryExecutor.create(em, FullTextRow.class, config), MqCode.MQ4014,
                        "ProviderSupport#operators"));
            }
        });
        assertThat(sql).noneMatch(s -> s.contains("full_text_rows"));
    }

    // ---- helpers

    private static void refused(ModelQueryExecutor<FullTextRow> executor, MqCode code, String named) {
        assertThatThrownBy(() -> ids(executor, f -> f.op(ENGLISH, NAME, "acme corp")))
                .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                        e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining("op(fullTextMatch(english))").hasMessageContaining(named);
    }

    private static List<Long> ids(ModelQueryExecutor<FullTextRow> executor, UnaryOperator<Filters<R>> where) {
        return executor.list(QUERY.where(where).build(), Limit.unlimited()).stream().map(R::id).toList();
    }

    /**
     * A factory of {@link FullTextRow} alone over {@code ds}; when {@code seeded} (PostgreSQL only) its table is
     * created, seeded and dropped with it, and otherwise never created, so a statement reaching it would fail.
     */
    private static SessionFactory factory(DataSource ds, boolean seeded) {
        SessionFactory sf = new Configuration().addAnnotatedClass(FullTextRow.class).buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds)
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, seeded ? "create-drop" : "none")
                        .build());
        if (seeded) {
            sf.inTransaction(session -> {
                session.persist(new FullTextRow(1, "Acme Corp"));
                session.persist(new FullTextRow(2, "Acme Industries"));
                session.persist(new FullTextRow(3, "Global Corp"));
                session.persist(new FullTextRow(4, "O'Brien and Sons"));
                session.persist(new FullTextRow(5, "The \"Best\" Widgets"));
                session.persist(new FullTextRow(6, null));
            });
        }
        return sf;
    }

    /** Runs {@code head}, {@link #CUSTOM_CONFIGURATION} and {@code tail} as one statement in its own transaction. */
    private static void configuration(SessionFactory sf, String head, String... tail) {
        sf.inTransaction(session -> session.createNativeMutationQuery(head + CUSTOM_CONFIGURATION
                + String.join("", tail)).executeUpdate());
    }
}
