package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

/**
 * The profile decides the streaming fetch size and the factory's provider support sets it; with none, {@code stream}
 * sets nothing and warns once per factory that the driver may buffer (R-VND-12, D-108).
 */
class StreamFetchSizeTest {

    record Row(Long id) {}

    /** Serves only the factories built here with this property, so no other test resolves through it. */
    private static final String MARKER = "modelquery.test.recording-fetch-size";

    /** The fetch sizes {@link RecordingSupport} was asked to set, in order. */
    private static final List<Integer> APPLIED = new CopyOnWriteArrayList<>();

    /** Records the fetch size it is asked to stream with, and streams portably. */
    public static final class RecordingSupport implements ProviderSupport {

        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(MARKER);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            APPLIED.add(fetchSize);
            return query.getResultStream();
        }
    }

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row, KeysetRowEntity, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ModelQuery<KeysetRowEntity, Long, Row> ROWS = ModelQuery
            .builder(ROOT, row -> new Row(row.get(ID)))
            .select(SelectSet.of(ID))
            .primaryKey(PrimaryKey.of(ID))
            .orderBy(ID.asc())
            .build();
    private static final ModelQueryConfig H2 = ModelQueryConfig.defaults().vendor(DatabaseVendor.H2);

    @Test
    void ac_vnd_09_without_provider_support_stream_warns_once_per_factory_however_many_streams_run() {
        List<String> warnings = new ArrayList<>();
        try (SessionFactory first = factory("unserved-1", false);
                SessionFactory second = factory("unserved-2", false)) {
            capturingExecutorWarnings(warnings, () -> {
                stream(first, H2);
                stream(first, H2);
                stream(second, H2);
            });
        }
        assertThat(warnings).hasSize(2).allSatisfy(w -> assertThat(w).contains("no fetch size",
                "add a ProviderSupport for its provider (model-query-hibernate for Hibernate)"));
    }

    @Test
    void ac_vnd_09_with_provider_support_the_profiles_fetch_size_reaches_it_and_nothing_is_warned() {
        List<String> warnings = new ArrayList<>();
        APPLIED.clear();
        try (SessionFactory served = factory("served", true)) {
            capturingExecutorWarnings(warnings, () -> {
                stream(served, H2);
                stream(served, H2.streamFetchSize(250));
                // MySQL streams row by row at Integer.MIN_VALUE whatever is configured, unless in cursor-fetch mode.
                stream(served, ModelQueryConfig.defaults().vendor(DatabaseVendor.MYSQL).streamFetchSize(250));
                stream(served, ModelQueryConfig.defaults().vendor(DatabaseVendor.MYSQL).streamFetchSize(250)
                        .mysqlStreamingMode(MysqlStreamingMode.CURSOR_FETCH));
            });
        }
        assertThat(APPLIED).containsExactly(500, 250, Integer.MIN_VALUE, 250);
        assertThat(warnings).isEmpty();
    }

    private static void stream(SessionFactory sessions, ModelQueryConfig config) {
        try (EntityManager em = sessions.createEntityManager()) {
            long rows = ModelQueryExecutor.create(em, KeysetRowEntity.class, config)
                    .stream(ROWS, Limit.unlimited(), Stream::count);
            assertThat(rows).isZero();
        }
    }

    private static SessionFactory factory(String name, boolean served) {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:fetch-size-" + name)
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        if (served) {
            registry.applySetting(MARKER, "true");
        }
        return new Configuration().addAnnotatedClass(KeysetRowEntity.class).buildSessionFactory(registry.build());
    }

    private static void capturingExecutorWarnings(List<String> warnings, Runnable work) {
        Logger logger = Logger.getLogger(DefaultModelQueryExecutor.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING) {
                    warnings.add(new SimpleFormatter().formatMessage(r));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            work.run();
        } finally {
            logger.removeHandler(handler);
        }
    }
}
