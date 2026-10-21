package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.SourceRenderer;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;

/**
 * {@code INLINE_COUNT} without a provider window: every page runs {@code COUNT}'s statements and returns the same
 * {@code Slice}, and the fallback's reason logs at {@code DEBUG} once per executor and query (R-EXE-15, R-VND-27).
 */
class InlineCountTest {

    record Row(Long id, Integer c) {}

    record Group(Integer c, Long n) {}

    /** Serves only the factories built here with this property: inlineCounts true, but no sources(). */
    private static final String WITHOUT_SOURCES = "modelquery.test.inline-counts-without-sources";
    /** Serves only the factories built here with this property: inlineCounts true and a sources() renderer. */
    private static final String WINDOWED = "modelquery.test.inline-counts-windowed";

    /** How often {@link WithoutSources} was asked {@code inlineCounts}. */
    private static final AtomicInteger WITHOUT_SOURCES_ASKED = new AtomicInteger();

    /** A {@link ProviderSupport} serving the factories with {@code marker}, which streams portably. */
    public abstract static class Stub implements ProviderSupport {

        private final String marker;

        Stub(String marker) {
            this.marker = marker;
        }

        @Override
        public boolean supports(EntityManagerFactory emf) {
            return marker != null && emf.getProperties().containsKey(marker);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }
    }

    /** Answers {@code inlineCounts} true with no {@code sources()}, which the executor must not trust. */
    public static final class WithoutSources extends Stub {

        public WithoutSources() {
            super(WITHOUT_SOURCES);
        }

        @Override
        public boolean inlineCounts(EntityManagerFactory emf) {
            WITHOUT_SOURCES_ASKED.incrementAndGet();
            return true;
        }
    }

    /** Answers {@code inlineCounts} true and serves a source renderer that refuses everything. */
    public static final class Windowed extends Stub {

        public Windowed() {
            super(WINDOWED);
        }

        @Override
        public Optional<SourceRenderer> sources() {
            return Optional.of(new SourceRenderer() {});
        }

        @Override
        public boolean inlineCounts(EntityManagerFactory emf) {
            return true;
        }
    }

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row, KeysetRowEntity, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> C =
            ColumnField.of(Row.class, ROOT, "c", Integer.class);
    private static final ColumnField<Group, KeysetRowEntity, Integer> GROUP_C =
            ColumnField.of(Group.class, ROOT, "c", Integer.class);
    private static final AggregateField<Group, Long> GROUP_N = Agg.count(ROOT);

    private static final ModelQuery.Builder<KeysetRowEntity, Long, Row> ROWS = ModelQuery
            .builder(ROOT, row -> new Row(row.get(ID), row.get(C)))
            .select(SelectSet.of(ID, C))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc());
    private static final ModelQuery<KeysetRowEntity, Long, Row> FLAT = ROWS.build();
    private static final ModelQuery<KeysetRowEntity, Long, Row> EMPTY = ROWS.where(f -> f.eq(ID, 99L)).build();
    private static final ModelQuery<KeysetRowEntity, Long, Row> KEYS_FIRST =
            ROWS.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0)).build();
    private static final ModelQuery<KeysetRowEntity, Object, Group> GROUPED = ModelQuery
            .builder(ROOT, row -> new Group(row.get(GROUP_C), row.get(GROUP_N)))
            .select(SelectSet.of(GROUP_C, GROUP_N))
            .groupBy(GROUP_C)
            .orderBy(GROUP_C.asc())
            .build();

    /** Every statement the factories built here prepared, in order. */
    private static final List<String> SQL = new CopyOnWriteArrayList<>();

    /** Held, so the level the logging tests set is not lost with a collected logger. */
    private static final Logger EXECUTOR_LOG = Logger.getLogger(DefaultModelQueryExecutor.class.getName());

    // ---- AC-EXE-21

    @Test
    void ac_exe_21_without_a_provider_window_inline_count_runs_counts_statements_and_returns_the_same_slice() {
        for (String marker : new String[] {null, WITHOUT_SOURCES, WINDOWED}) {
            try (SessionFactory sessions = factory("same-" + marker, marker)) {
                // Five rows; c is id % 2, so two groups.
                assertSameAsCount(sessions, FLAT, PageSpec.of(0, 2), 5, List.of(new Row(1L, 1), new Row(2L, 0)));
                assertSameAsCount(sessions, FLAT, PageSpec.of(1, 2), 5, List.of(new Row(3L, 1), new Row(4L, 0)));
                assertSameAsCount(sessions, FLAT, PageSpec.of(2, 2), 5, List.of(new Row(5L, 1)));
                assertSameAsCount(sessions, FLAT, PageSpec.of(4, 2), 5, List.of());
                assertSameAsCount(sessions, EMPTY, PageSpec.of(0, 2), 0, List.of());
                assertSameAsCount(sessions, GROUPED, PageSpec.of(0, 1), 2, List.of(new Group(0, 2L)));
                assertSameAsCount(sessions, GROUPED, PageSpec.of(1, 1), 2, List.of(new Group(1, 3L)));
                assertSameAsCount(sessions, GROUPED, PageSpec.of(3, 1), 2, List.of());
                assertSameAsCount(sessions, KEYS_FIRST, PageSpec.of(1, 2), 5,
                        List.of(new Row(3L, 1), new Row(4L, 0)));
            }
        }
    }

    @Test
    void ac_exe_21_inline_counts_is_false_by_default_and_unasked_without_sources() {
        WITHOUT_SOURCES_ASKED.set(0);
        try (SessionFactory sessions = factory("default", WITHOUT_SOURCES)) {
            ProviderSupport plain = new Stub(null) {};
            assertThat(plain.inlineCounts(sessions)).isFalse();
            withExecutor(sessions, executor -> executor.page(FLAT, PageSpec.of(0, 2), CountMode.INLINE_COUNT));
        }
        // Without sources() the window can't render, so the executor doesn't ask (R-EXE-15 fallback 1).
        assertThat(WITHOUT_SOURCES_ASKED).hasValue(0);
    }

    // ---- AC-EXE-20 (fallback 1 only; M30.2 adds 2–4)

    @Test
    void ac_exe_20_the_fallback_reason_logs_once_per_executor_and_query() {
        List<String> lines = new ArrayList<>();
        try (SessionFactory sessions = factory("logged", WITHOUT_SOURCES)) {
            capturingFallbackLines(lines, () -> {
                withExecutor(sessions, executor -> {
                    executor.page(FLAT, PageSpec.of(0, 2), CountMode.INLINE_COUNT);
                    executor.page(FLAT, PageSpec.of(1, 2), CountMode.INLINE_COUNT);
                    executor.page(FLAT, PageSpec.of(0, 2), CountMode.COUNT);
                    executor.page(GROUPED, PageSpec.of(0, 1), CountMode.INLINE_COUNT);
                });
                // The same static final query on a second executor logs again: the reason depends on the factory.
                withExecutor(sessions, executor -> executor.page(FLAT, PageSpec.of(0, 2), CountMode.INLINE_COUNT));
            });
        }
        assertThat(lines).hasSize(3).allSatisfy(line -> assertThat(line).contains(
                "INLINE_COUNT runs COUNT's statements for the total, because the provider renders no window over the "
                        + "page statement", "(R-EXE-15)"));
        assertThat(lines.get(0)).contains(FLAT.toString());
        assertThat(lines.get(1)).contains(GROUPED.toString());
        assertThat(lines.get(2)).contains(FLAT.toString());
    }

    @Test
    void ac_exe_20_with_a_provider_window_the_page_still_falls_back_and_says_why() {
        List<String> lines = new ArrayList<>();
        try (SessionFactory sessions = factory("windowed-logged", WINDOWED)) {
            capturingFallbackLines(lines, () -> withExecutor(sessions,
                    executor -> executor.page(FLAT, PageSpec.of(0, 2), CountMode.INLINE_COUNT)));
        }
        assertThat(lines).singleElement().asString()
                .contains("because no page statement carries the inline count's window yet");
    }

    /**
     * Runs {@code page} of {@code q} under {@code COUNT} and then {@code INLINE_COUNT}, each on a fresh executor, and
     * checks both return {@code content} and {@code total} through the same statements.
     */
    private static <M> void assertSameAsCount(SessionFactory sessions, ModelQuery<KeysetRowEntity, ?, M> q,
            PageSpec page, long total, List<M> content) {
        List<Slice<M>> slices = new ArrayList<>();
        List<List<String>> statements = new ArrayList<>();
        for (CountMode mode : new CountMode[] {CountMode.COUNT, CountMode.INLINE_COUNT}) {
            SQL.clear();
            withExecutor(sessions, executor -> slices.add(executor.page(q, page, mode)));
            statements.add(List.copyOf(SQL));
        }
        assertThat(slices.get(0)).isEqualTo(new Slice<>(content, page.pageNumber(), page.pageSize(),
                page.offset() + (long) content.size() < total, OptionalLong.of(total)));
        assertThat(slices.get(1)).isEqualTo(slices.get(0));
        assertThat(statements.get(0)).isNotEmpty();
        assertThat(statements.get(1)).isEqualTo(statements.get(0));
    }

    private static void withExecutor(SessionFactory sessions, Consumer<ModelQueryExecutor<KeysetRowEntity>> work) {
        try (EntityManager em = sessions.createEntityManager()) {
            work.accept(ModelQueryExecutor.create(em, KeysetRowEntity.class, ModelQueryConfig.defaults()));
        }
    }

    private static SessionFactory factory(String name, String marker) {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:inline-count-" + name)
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                .applySetting(AvailableSettings.STATEMENT_INSPECTOR, (StatementInspector) sql -> {
                    SQL.add(sql);
                    return sql;
                });
        if (marker != null) {
            registry.applySetting(marker, "true");
        }
        SessionFactory sessions =
                new Configuration().addAnnotatedClass(KeysetRowEntity.class).buildSessionFactory(registry.build());
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            for (long id = 1; id <= 5; id++) {
                em.persist(new KeysetRowEntity(id, (int) id, (int) id, (int) (id % 2)));
            }
            em.getTransaction().commit();
        }
        return sessions;
    }

    /** Collects the executor's {@code DEBUG} lines that report an {@code INLINE_COUNT} fallback. */
    private static void capturingFallbackLines(List<String> lines, Runnable work) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.FINE && r.getMessage().contains("INLINE_COUNT runs COUNT's statements")) {
                    lines.add(r.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Level level = EXECUTOR_LOG.getLevel();
        EXECUTOR_LOG.setLevel(Level.FINE);
        EXECUTOR_LOG.addHandler(handler);
        try {
            work.run();
        } finally {
            EXECUTOR_LOG.removeHandler(handler);
            EXECUTOR_LOG.setLevel(level);
        }
    }
}
