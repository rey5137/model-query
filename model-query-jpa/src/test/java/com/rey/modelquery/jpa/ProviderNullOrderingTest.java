package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A provider that reports a configured default null ordering but renders no null precedence itself: the executor
 * must not leave an explicit precedence bare where the profile's default matches, since the provider re-sorts a bare
 * order, and keyset paging follows the provider's default for a {@code DEFAULT} column (R-COL-12, R-PAG-05, D-36).
 */
class ProviderNullOrderingTest {

    record Row(Long id, Integer a) {}

    /** Serves only the factory built here, marked with this property, so no other test resolves through it. */
    private static final String MARKER = "modelquery.test.provider-null-ordering";

    /** Hibernate's default_null_ordering=last, reported as a provider without a native renderer would. */
    public static final class ConfiguredNullsSupport implements ProviderSupport {

        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(MARKER);
        }

        @Override
        public Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
            return Optional.of(DatabaseVendor.H2);
        }

        @Override
        public Optional<NullPrecedence> defaultNullPrecedence(EntityManagerFactory emf) {
            return Optional.of(NullPrecedence.LAST);
        }
    }

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row, KeysetRowEntity, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> A =
            ColumnField.of(Row.class, ROOT, "a", Integer.class);

    private static SessionFactory sessions;
    private static List<Row> rows;

    @BeforeAll
    static void seed() {
        sessions = new Configuration()
                .addAnnotatedClass(KeysetRowEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL,
                                "jdbc:h2:mem:provider-nulls;DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .applySetting(AvailableSettings.DEFAULT_NULL_ORDERING, "last")
                        .applySetting(MARKER, "true")
                        .build());
        // 12 rows, a NULL on every third: pages of 3 put cursors on NULL and non-NULL values.
        rows = LongStream.rangeClosed(1, 12).mapToObj(id -> new Row(id, id % 3 == 0 ? null : (int) (id % 4)))
                .toList();
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            rows.forEach(r -> em.persist(new KeysetRowEntity(r.id(), r.a(), null, 0)));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    @Test
    void ac_prf_07_an_explicit_precedence_the_profile_matches_is_not_left_bare_under_a_provider_default() {
        // H2 sorts NULLs first ascending, but the provider sorts a bare order's NULLs last, so nullsFirst() must be
        // rendered, not left bare as the profile's matching default would allow.
        for (boolean ascending : List.of(true, false)) {
            for (NullPrecedence nulls : List.of(NullPrecedence.FIRST, NullPrecedence.LAST)) {
                OrderField<Row, Integer> order = (ascending ? A.asc() : A.desc()).nulls(nulls);
                assertThat(export(ModelQueryConfig.defaults(), order)).as("%s", order)
                        .containsExactlyElementsOf(expected(ascending, nulls));
            }
        }
    }

    @Test
    void ac_prf_07_honour_null_precedence_pages_a_default_column_by_the_providers_default_in_both_directions() {
        ModelQueryConfig honour = ModelQueryConfig.defaults().keysetNullKeys(KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        for (OrderField<Row, Integer> order : List.of(A.asc(), A.desc())) {
            assertThat(export(honour, order)).as("%s", order)
                    .containsExactlyElementsOf(expected(order.ascending(), NullPrecedence.LAST));
        }
    }

    private static List<Long> export(ModelQueryConfig config, OrderField<Row, Integer> order) {
        ModelQuery<KeysetRowEntity, Long, Row> q = ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(A)))
                .select(SelectSet.of(ID, A))
                .primaryKey(PrimaryKey.of(ID))
                .orderBy(order)
                .keyset()
                .build();
        List<Long> ids = new ArrayList<>();
        try (EntityManager em = sessions.createEntityManager()) {
            ModelQueryExecutor.create(em, KeysetRowEntity.class, config)
                    .export(q, ExportOptions.of(3), page -> page, row -> ids.add(row.id()));
        }
        return ids;
    }

    private static List<Long> expected(boolean ascending, NullPrecedence nulls) {
        Comparator<Integer> values = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
        Comparator<Row> byId = Comparator.comparing(Row::id);
        return rows.stream()
                .sorted(Comparator.comparing(Row::a, nulls == NullPrecedence.FIRST
                                ? Comparator.nullsFirst(values) : Comparator.nullsLast(values))
                        .thenComparing(ascending ? byId : byId.reversed()))
                .map(Row::id)
                .toList();
    }
}
