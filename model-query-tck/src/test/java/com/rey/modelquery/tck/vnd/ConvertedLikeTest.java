package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.hibernate.HibernateOperators;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Assumptions;

/**
 * What a JPA {@code @Convert} on a {@code String} attribute does to a pattern, on both lines and Tier 1: Hibernate
 * runs the attribute's {@code AttributeConverter} on {@code like}'s and {@code ilike}'s finished pattern, and not on
 * {@code likeIgnoreCase}'s, whose left side is {@code lower(col)} (spec api/12 R-FLT-06, R-FLT-21, D-171; TCK
 * AC-FLT-21).
 */
class ConvertedLikeTest {

    /** Stores {@code SKU:<code>}, as a prefixing converter of the attribute's own type. */
    static final class SkuPrefix implements AttributeConverter<String, String> {
        @Override
        public String convertToDatabaseColumn(String code) {
            return code == null ? null : "SKU:" + code;
        }

        @Override
        public String convertToEntityAttribute(String column) {
            return column == null ? null : column.substring(4);
        }
    }

    @Entity
    @Table(name = "converted_like_rows")
    static class ConvertedLikeRow {
        @Id
        Long id;

        @Convert(converter = SkuPrefix.class)
        @Column(name = "code")
        String code;

        ConvertedLikeRow() {}

        ConvertedLikeRow(long id, String code) {
            this.id = id;
            this.code = code;
        }
    }

    record R(Long id) {}

    static final TableField<ConvertedLikeRow, ConvertedLikeRow> ROWS = TableField.root(ConvertedLikeRow.class);
    static final ColumnField<R, ConvertedLikeRow, Long> ID = ColumnField.of(R.class, ROWS, "id", Long.class);
    static final ColumnField<R, ConvertedLikeRow, String> CODE = ColumnField.of(R.class, ROWS, "code", String.class);
    static final ModelQuery.Builder<ConvertedLikeRow, Object, R> QUERY =
            ModelQuery.builder(ROWS, row -> new R(row.get(ID))).select(SelectSet.of(ID)).orderBy(ID.asc());

    /** One filter's rows, the values its statement bound and its SQL. */
    record Run(List<Long> ids, List<Object> bound, String sql) {

        /** The pattern the statement compared with: its one bound value, or its inlined literal. */
        String pattern() {
            if (!bound.isEmpty()) {
                assertThat(bound).hasSize(1);
                return (String) bound.get(0);
            }
            int start = sql.indexOf("like '") + "like '".length();
            return sql.substring(start, sql.indexOf('\'', start));
        }
    }

    @TckTest
    void ac_flt_21_a_convert_attribute_converts_like_and_ilike_patterns_and_like_ignore_case_misses_rows_q30(
            TckDatabase db) {
        for (String handling : List.of("bind", "inline")) {
            List<Run> runs = new ArrayList<>();
            run(db, handling, runs, f -> f.like(CODE, "a12", LikeMode.EXACT));
            run(db, handling, runs, f -> f.like(CODE, "12", LikeMode.STARTS_WITH));
            run(db, handling, runs, f -> f.like(CODE, "12", LikeMode.CONTAINS));
            // Pins Q-30's known-wrong rows; change when Q-30 is resolved.
            // lower(code) like ?: Hibernate binds the lower-cased pattern as given, without the converter, so it
            // never meets the stored SKU: prefix and these match nothing, where a12 and 12b are the model's rows.
            run(db, handling, runs, f -> f.likeIgnoreCase(CODE, "A12", LikeMode.EXACT));
            run(db, handling, runs, f -> f.likeIgnoreCase(CODE, "12", LikeMode.STARTS_WITH));
            // The finished pattern goes through the converter, so a prefix lands before the wildcards: under either
            // value handling a like's literal, and an ilike's parameter (D-170), is the converted pattern.
            assertThat(runs).as(handling).extracting(Run::ids).containsExactly(List.of(1L), List.of(2L),
                    List.of(1L, 2L), List.of(), List.of());
            assertThat(runs).as(handling).extracting(Run::pattern).containsExactly("SKU:a12", "SKU:12%", "SKU:%12%",
                    "a12", "12%");
            assertThat(runs).as(handling).allSatisfy(run -> assertThat(run.bound().isEmpty())
                    .isEqualTo(handling.equals("inline")));
            if (db.vendor() != TckVendor.MYSQL) {
                List<Run> ilike = new ArrayList<>();
                run(db, handling, ilike, f -> f.op(HibernateOperators.ilike(LikeMode.EXACT), CODE, "A12"));
                run(db, handling, ilike, f -> f.op(HibernateOperators.ilike(LikeMode.STARTS_WITH), CODE, "12"));
                assertThat(ilike).as(handling).extracting(Run::ids).containsExactly(List.of(1L), List.of(2L));
                assertThat(ilike).as(handling).extracting(Run::bound)
                        .containsExactly(List.of("SKU:A12"), List.of("SKU:12%"));
            }
        }
    }

    @TckTest
    void ac_flt_24_regex_match_pattern_is_never_converted_by_a_convert_attribute_d174(TckDatabase db) {
        Assumptions.assumeTrue(db.vendor() == TckVendor.POSTGRESQL, "only PostgreSQL has REGEX_MATCH");
        for (String handling : List.of("bind", "inline")) {
            List<Run> runs = new ArrayList<>();
            // SkuPrefix would turn the pattern into "SKU:^SKU:a", which matches nothing; the raw one meets the
            // stored SKU:a12 and SKU:12b.
            run(db, handling, runs, f -> f.op(HibernateOperators.regexMatch(), CODE, "^SKU:(a12|12b)$"));
            run(db, handling, runs, f -> f.op(HibernateOperators.regexMatch(), CODE, "^SKU:a"));
            run(db, handling, runs, f -> f.op(HibernateOperators.regexMatch(), CODE, "^a"));
            assertThat(runs).as(handling).extracting(Run::ids)
                    .containsExactly(List.of(1L, 2L), List.of(1L), List.of());
        }
    }

    private static void run(TckDatabase db, String handling, List<Run> runs, UnaryOperator<Filters<R>> where) {
        List<Object> bound = new ArrayList<>();
        List<String> sql = new ArrayList<>();
        DataSource recording = ProxyDataSourceBuilder.create(JoinTestSupport.dataSource(db))
                .afterQuery((exec, queries) -> queries.forEach(q -> {
                    if (q.getQuery().startsWith("select") && q.getQuery().contains("converted_like_rows")) {
                        q.getParametersList().forEach(set -> set.forEach(op -> bound.add(op.getArgs()[1])));
                        sql.add(q.getQuery());
                    }
                }))
                .build();
        List<Long> ids = new ArrayList<>();
        try (SessionFactory sf = factory(recording, handling)) {
            sf.inSession(em -> ModelQueryExecutor.create(em, ConvertedLikeRow.class, ModelQueryConfig.defaults())
                    .list(QUERY.where(where).build(), Limit.unlimited()).forEach(r -> ids.add(r.id())));
        }
        assertThat(sql).hasSize(1);
        runs.add(new Run(ids, bound, sql.get(0)));
    }

    /** A factory of {@link ConvertedLikeRow} alone over {@code ds}, its table created, seeded and dropped with it. */
    private static SessionFactory factory(DataSource ds, String handling) {
        SessionFactory sf = new Configuration().addAnnotatedClass(ConvertedLikeRow.class).buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds)
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .applySetting(AvailableSettings.CRITERIA_VALUE_HANDLING_MODE, handling)
                        .build());
        // Stored as SKU:a12, SKU:12b and SKU:x.
        sf.inTransaction(session -> {
            session.persist(new ConvertedLikeRow(1, "a12"));
            session.persist(new ConvertedLikeRow(2, "12b"));
            session.persist(new ConvertedLikeRow(3, "x"));
        });
        return sf;
    }
}
