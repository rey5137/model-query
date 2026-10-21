package com.rey.modelquery.tck.vnd;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.Having;
import com.rey.modelquery.core.LikeMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.hibernate.HibernateOperators;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.flt.OperatorProviderSupport;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.harness.TckVendor;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Assumptions;

/**
 * What a JPA {@code @Convert} on a {@code String} attribute does to a pattern, on both lines and Tier 1: Hibernate
 * runs the attribute's {@code AttributeConverter} on {@code like}'s and {@code ilike}'s finished pattern, and on an
 * ignore-case filter's value through the binder's parameter under {@code lower(?)}, while a plain column and a run
 * without a binder keep the value lowered in Java (spec api/12 R-FLT-06, R-FLT-07, R-FLT-21, D-171, D-177; TCK
 * AC-FLT-21, AC-FLT-33, AC-FLT-34).
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

    /** Stores {@code AUTO:<code>}, applied to every {@code String} attribute of its factory, no {@code @Convert}. */
    @Converter(autoApply = true)
    static final class AutoPrefix implements AttributeConverter<String, String> {
        @Override
        public String convertToDatabaseColumn(String code) {
            return code == null ? null : "AUTO:" + code;
        }

        @Override
        public String convertToEntityAttribute(String column) {
            return column == null ? null : column.substring(5);
        }
    }

    @Embeddable
    static class Tag {
        @Convert(converter = SkuPrefix.class)
        @Column(name = "tag_code")
        String tagCode;
    }

    @Entity
    @Table(name = "converted_like_rows")
    static class ConvertedLikeRow {
        @Id
        Long id;

        @Convert(converter = SkuPrefix.class)
        @Column(name = "code")
        String code;

        @Column(name = "grp")
        String group;

        @Embedded
        Tag tag;

        @ManyToOne
        ConvertedLikeRow parent;

        ConvertedLikeRow() {}

        ConvertedLikeRow(long id, String code, String group, String tag, ConvertedLikeRow parent) {
            this.id = id;
            this.code = code;
            this.group = group;
            this.tag = new Tag();
            this.tag.tagCode = tag;
            this.parent = parent;
        }
    }

    /** A {@code String} attribute the factory's auto-applied {@link AutoPrefix} converts. */
    @Entity
    @Table(name = "converted_like_auto_rows")
    static class AutoRow {
        @Id
        Long id;

        String code;

        AutoRow() {}

        AutoRow(long id, String code) {
            this.id = id;
            this.code = code;
        }
    }

    record R(Long id) {}

    static final TableField<ConvertedLikeRow, ConvertedLikeRow> ROWS = TableField.root(ConvertedLikeRow.class);
    static final TableField<ConvertedLikeRow, ConvertedLikeRow> PARENT = TableField.join(ROWS, "parent", JoinType.LEFT);
    static final ColumnField<R, ConvertedLikeRow, Long> ID = ColumnField.of(R.class, ROWS, "id", Long.class);
    static final ColumnField<R, ConvertedLikeRow, String> CODE = ColumnField.of(R.class, ROWS, "code", String.class);
    static final ColumnField<R, ConvertedLikeRow, String> GROUP = ColumnField.of(R.class, ROWS, "group", String.class);
    static final ColumnField<R, ConvertedLikeRow, String> TAG =
            ColumnField.of(R.class, ROWS, "tag.tagCode", String.class);
    static final ColumnField<R, ConvertedLikeRow, String> PARENT_CODE =
            ColumnField.of(R.class, PARENT, "code", String.class);
    static final ModelQuery.Builder<ConvertedLikeRow, Object, R> QUERY =
            ModelQuery.builder(ROWS, row -> new R(row.get(ID))).select(SelectSet.of(ID)).orderBy(ID.asc());

    /** A group of rows with the least and the greatest of its converted codes. */
    record G(String group, String low, String high) {}

    static final ColumnField<G, ConvertedLikeRow, String> G_GROUP =
            ColumnField.of(G.class, ROWS, "group", String.class);
    static final AggregateField<G, String> LOW = Agg.min(ColumnField.of(G.class, ROWS, "code", String.class));
    static final AggregateField<G, String> HIGH = Agg.max(ColumnField.of(G.class, ROWS, "code", String.class));

    static final TableField<AutoRow, AutoRow> AUTO_ROWS = TableField.root(AutoRow.class);
    static final ColumnField<R, AutoRow, Long> AUTO_ID = ColumnField.of(R.class, AUTO_ROWS, "id", Long.class);
    static final ColumnField<R, AutoRow, String> AUTO_CODE =
            ColumnField.of(R.class, AUTO_ROWS, "code", String.class);

    /** One statement's rows (ids, or groups), the values it bound and its SQL. */
    record Run(List<?> ids, List<Object> bound, String sql) {

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
    void ac_flt_21_a_convert_attribute_converts_like_ilike_and_like_ignore_case_patterns(TckDatabase db) {
        for (String handling : List.of("bind", "inline")) {
            List<Run> runs = new ArrayList<>();
            run(db, handling, runs, f -> f.like(CODE, "a12", LikeMode.EXACT));
            run(db, handling, runs, f -> f.like(CODE, "12", LikeMode.STARTS_WITH));
            run(db, handling, runs, f -> f.like(CODE, "12", LikeMode.CONTAINS));
            // The finished pattern goes through the converter, so a prefix lands before the wildcards: under either
            // value handling a like's literal, and an ilike's parameter (D-170), is the converted pattern.
            assertThat(runs).as(handling).extracting(Run::ids).containsExactly(List.of(1L), List.of(2L),
                    List.of(1L, 2L));
            assertThat(runs).as(handling).extracting(Run::pattern).containsExactly("SKU:a12", "SKU:12%", "SKU:%12%");
            assertThat(runs).as(handling).allSatisfy(run -> assertThat(run.bound().isEmpty())
                    .isEqualTo(handling.equals("inline")));
            // likeIgnoreCase's pattern goes through the binder's parameter, never inlined, under lower(?) (D-177).
            List<Run> ignoreCase = new ArrayList<>();
            run(db, handling, ignoreCase, f -> f.likeIgnoreCase(CODE, "A12", LikeMode.EXACT));
            run(db, handling, ignoreCase, f -> f.likeIgnoreCase(CODE, "12", LikeMode.STARTS_WITH));
            assertThat(ignoreCase).as(handling).extracting(Run::ids).containsExactly(List.of(1L), List.of(2L));
            assertThat(ignoreCase).as(handling).extracting(Run::bound)
                    .containsExactly(List.of("SKU:A12"), List.of("SKU:12%"));
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
    void ac_flt_33_ignore_case_filters_over_a_convert_attribute_bind_through_the_converter_and_fold_both_sides(
            TckDatabase db) {
        for (String handling : List.of("bind", "inline")) {
            List<Run> runs = new ArrayList<>();
            run(db, handling, runs, f -> f.eqIgnoreCase(CODE, "A12"));
            run(db, handling, runs, f -> f.likeIgnoreCase(CODE, "A12", LikeMode.EXACT));
            run(db, handling, runs, f -> f.likeIgnoreCase(CODE, "12B", LikeMode.STARTS_WITH));
            run(db, handling, runs, f -> f.likeIgnoreCase(CODE, "2B", LikeMode.CONTAINS));
            run(db, handling, runs, f -> f.likeIgnoreCase(CODE, "B7", LikeMode.ENDS_WITH));
            // Escaped before the converter: only Q_1 holds a literal underscore, where a bare _ would match every row.
            run(db, handling, runs, f -> f.likeIgnoreCase(CODE, "_", LikeMode.CONTAINS));
            // Through a join, and through an embeddable.
            run(db, handling, runs, f -> f.eqIgnoreCase(PARENT_CODE, "A12"));
            run(db, handling, runs, f -> f.likeIgnoreCase(PARENT_CODE, "12B", LikeMode.EXACT));
            run(db, handling, runs, f -> f.eqIgnoreCase(TAG, "t1"));
            run(db, handling, runs, f -> f.likeIgnoreCase(TAG, "t3", LikeMode.ENDS_WITH));
            assertThat(runs).as(handling).extracting(Run::ids).containsExactly(List.of(1L), List.of(1L),
                    List.of(2L), List.of(2L), List.of(5L), List.of(4L), List.of(2L, 5L), List.of(3L), List.of(1L),
                    List.of(3L));
            // The value, or its pattern, unlowered and converted: a parameter even under inline handling (D-170).
            assertThat(runs).as(handling).extracting(Run::bound).containsExactly(List.of("SKU:A12"),
                    List.of("SKU:A12"), List.of("SKU:12B%"), List.of("SKU:%2B%"), List.of("SKU:%B7"),
                    List.of("SKU:%\\_%"), List.of("SKU:A12"), List.of("SKU:12B"), List.of("SKU:t1"),
                    List.of("SKU:%t3"));
            assertThat(runs).as(handling).allSatisfy(run -> assertThat(run.sql()).contains("lower(?)"));
            if (handling.equals("bind")) {
                SqlSnapshots.assertMatches(db, "flt-33-converted-ignore-case", runs.stream().map(Run::sql).toList());
            }
        }
    }

    @TckTest
    void ac_flt_33_having_ignore_case_over_min_and_max_of_a_convert_attribute_binds_through_the_converter(
            TckDatabase db) {
        for (String handling : List.of("bind", "inline")) {
            List<Run> runs = new ArrayList<>();
            group(db, handling, runs, h -> h.eqIgnoreCase(HIGH, "B7"));
            group(db, handling, runs, h -> h.likeIgnoreCase(LOW, "12B", LikeMode.EXACT));
            group(db, handling, runs, h -> h.likeIgnoreCase(LOW, "A", LikeMode.STARTS_WITH));
            group(db, handling, runs, h -> h.likeIgnoreCase(HIGH, "X", LikeMode.ENDS_WITH));
            assertThat(runs).as(handling).extracting(Run::ids).containsExactly(List.of("g1"), List.of("g2"),
                    List.of("g1"), List.of("g2"));
            assertThat(runs).as(handling).extracting(Run::bound).containsExactly(List.of("SKU:B7"),
                    List.of("SKU:12B"), List.of("SKU:A%"), List.of("SKU:%X"));
            assertThat(runs).as(handling).allSatisfy(run -> assertThat(run.sql()).contains("lower(?)"));
        }
    }

    @TckTest
    void ac_flt_33_ignore_case_filters_over_an_auto_applied_converter_bind_through_it(TckDatabase db) {
        for (String handling : List.of("bind", "inline")) {
            List<Run> runs = new ArrayList<>();
            auto(db, handling, runs, f -> f.eqIgnoreCase(AUTO_CODE, "A12"));
            auto(db, handling, runs, f -> f.likeIgnoreCase(AUTO_CODE, "12B", LikeMode.STARTS_WITH));
            assertThat(runs).as(handling).extracting(Run::ids).containsExactly(List.of(1L), List.of(2L));
            assertThat(runs).as(handling).extracting(Run::bound)
                    .containsExactly(List.of("AUTO:A12"), List.of("AUTO:12B%"));
        }
    }

    @TckTest
    void ac_flt_34_a_plain_column_and_a_run_without_a_binder_keep_the_value_lowered_in_java(TckDatabase db) {
        for (String handling : List.of("bind", "inline")) {
            List<Run> runs = new ArrayList<>();
            run(db, handling, runs, f -> f.eqIgnoreCase(GROUP, "G1"));
            run(db, handling, runs, f -> f.likeIgnoreCase(GROUP, "G", LikeMode.STARTS_WITH));
            // Without a binder a @Convert attribute keeps R-FLT-07's limitation: the lowered value never meets the
            // stored SKU: prefix, so a12 and 12b are missed without an error.
            OperatorProviderSupport.serving(feature -> false, null, () -> {
                run(db, handling, runs, f -> f.eqIgnoreCase(CODE, "A12"));
                run(db, handling, runs, f -> f.likeIgnoreCase(CODE, "A12", LikeMode.EXACT));
                run(db, handling, runs, f -> f.likeIgnoreCase(CODE, "12B", LikeMode.STARTS_WITH));
            });
            assertThat(runs).as(handling).extracting(Run::ids).containsExactly(List.of(1L, 5L),
                    List.of(1L, 2L, 3L, 4L, 5L), List.of(), List.of(), List.of());
            assertThat(runs).as(handling).allSatisfy(run -> assertThat(run.sql()).doesNotContain("lower(?)"));
            if (handling.equals("bind")) {
                assertThat(runs).extracting(Run::bound).containsExactly(List.of("g1"), List.of("g%"),
                        List.of("a12"), List.of("a12"), List.of("12b%"));
                SqlSnapshots.assertMatches(db, "flt-34-unconverted-ignore-case",
                        runs.stream().map(Run::sql).toList());
            } else {
                assertThat(runs).allSatisfy(run -> assertThat(run.bound()).isEmpty());
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

    /** {@code where}'s run over {@link ConvertedLikeRow}, its ids in order. */
    private static void run(TckDatabase db, String handling, List<Run> runs, UnaryOperator<Filters<R>> where) {
        runs.add(capture(db, handling, ConvertedLikeRow.class, "converted_like_rows", (em, executor) -> executor
                .list(QUERY.where(where).build(), Limit.unlimited()).stream().map(R::id).toList()));
    }

    /** {@code having}'s run over {@link ConvertedLikeRow} grouped by {@code grp}, its groups in order. */
    private static void group(TckDatabase db, String handling, List<Run> runs, UnaryOperator<Having<G>> having) {
        ModelQuery<ConvertedLikeRow, Object, G> query = ModelQuery.builder(ROWS,
                        row -> new G(row.get(G_GROUP), row.get(LOW), row.get(HIGH)))
                .select(SelectSet.of(G_GROUP, LOW, HIGH)).groupBy(G_GROUP).having(having).orderBy(G_GROUP.asc())
                .build();
        runs.add(capture(db, handling, ConvertedLikeRow.class, "converted_like_rows", (em, executor) -> executor
                .list(query, Limit.unlimited()).stream().map(G::group).toList()));
    }

    /** {@code where}'s run over {@link AutoRow}, its ids in order. */
    private static void auto(TckDatabase db, String handling, List<Run> runs, UnaryOperator<Filters<R>> where) {
        ModelQuery<AutoRow, Object, R> query = ModelQuery.builder(AUTO_ROWS, row -> new R(row.get(AUTO_ID)))
                .select(SelectSet.of(AUTO_ID)).orderBy(AUTO_ID.asc()).where(where).build();
        runs.add(capture(db, handling, AutoRow.class, "converted_like_auto_rows", (em, executor) -> executor
                .list(query, Limit.unlimited()).stream().map(R::id).toList()));
    }

    /** Runs {@code work} on a fresh factory of {@code entity}, recording the one select it issues on {@code table}. */
    private static <E> Run capture(TckDatabase db, String handling, Class<E> entity, String table,
            BiFunction<EntityManager, ModelQueryExecutor<E>, List<?>> work) {
        List<Object> bound = new ArrayList<>();
        List<String> sql = new ArrayList<>();
        DataSource recording = ProxyDataSourceBuilder.create(JoinTestSupport.dataSource(db))
                .afterQuery((exec, queries) -> queries.forEach(q -> {
                    if (q.getQuery().startsWith("select") && q.getQuery().contains(table)) {
                        q.getParametersList().forEach(set -> set.forEach(op -> bound.add(op.getArgs()[1])));
                        sql.add(SqlSnapshots.normalize(q.getQuery()));
                    }
                }))
                .build();
        List<Object> ids = new ArrayList<>();
        try (SessionFactory sf = entity == AutoRow.class ? autoFactory(recording, handling)
                : factory(recording, handling)) {
            sf.inSession(em -> ids.addAll(work.apply(em,
                    ModelQueryExecutor.create(em, entity, ModelQueryConfig.defaults()))));
        }
        assertThat(sql).hasSize(1);
        return new Run(ids, bound, sql.get(0));
    }

    /**
     * A factory of {@link ConvertedLikeRow} alone over {@code ds}, its table created, seeded and dropped with it, and
     * {@code hibernate.criteria.value_handling_mode} set to {@code handling}.
     */
    private static SessionFactory factory(DataSource ds, String handling) {
        SessionFactory sf = new Configuration().addAnnotatedClass(ConvertedLikeRow.class)
                .buildSessionFactory(registry(ds, handling));
        // Codes stored as SKU:a12, SKU:12b, SKU:x, SKU:q_1 and SKU:b7; tags as SKU:T1, SKU:t2 and so on.
        sf.inTransaction(session -> {
            ConvertedLikeRow first = new ConvertedLikeRow(1, "a12", "g1", "T1", null);
            ConvertedLikeRow second = new ConvertedLikeRow(2, "12b", "g2", "t2", first);
            session.persist(first);
            session.persist(second);
            session.persist(new ConvertedLikeRow(3, "x", "g2", "T3", second));
            session.persist(new ConvertedLikeRow(4, "q_1", "g2", "t4", null));
            session.persist(new ConvertedLikeRow(5, "b7", "g1", "T5", first));
        });
        return sf;
    }

    /** A factory of {@link AutoRow} with {@link AutoPrefix} auto-applied, as {@link #factory}. */
    private static SessionFactory autoFactory(DataSource ds, String handling) {
        SessionFactory sf = new Configuration().addAnnotatedClass(AutoRow.class).addAnnotatedClass(AutoPrefix.class)
                .buildSessionFactory(registry(ds, handling));
        // Stored as AUTO:a12 and AUTO:12b.
        sf.inTransaction(session -> {
            session.persist(new AutoRow(1, "a12"));
            session.persist(new AutoRow(2, "12b"));
        });
        return sf;
    }

    private static StandardServiceRegistry registry(DataSource ds, String handling) {
        return new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .applySetting(AvailableSettings.CRITERIA_VALUE_HANDLING_MODE, handling)
                .build();
    }
}
