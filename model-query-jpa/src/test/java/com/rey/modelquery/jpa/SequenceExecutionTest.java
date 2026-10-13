package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SequenceRenderer;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The executor offers a provider's {@link SequenceRenderer} only where the vendor profile says the database has
 * sequences, so {@code Expr.nextval} is {@code MQ1509} otherwise (R-VND-18, R-COL-28).
 */
class SequenceExecutionTest {

    @Entity
    @Table(name = "sequence_rows")
    public static class SequenceRow {
        @Id
        Long id;

        protected SequenceRow() {}

        SequenceRow(long id) {
            this.id = id;
        }
    }

    record Row(Long id, Long next) {}

    /** Serves the factories marked with {@link #H2} or {@link #MYSQL}: a renderer that writes a constant 7. */
    private static final String H2 = "modelquery.test.sequence-h2";
    private static final String MYSQL = "modelquery.test.sequence-mysql";

    public static final class Sequences implements ProviderSupport {
        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(H2) || emf.getProperties().containsKey(MYSQL);
        }

        @Override
        public Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
            return Optional.of(emf.getProperties().containsKey(MYSQL) ? DatabaseVendor.MYSQL : DatabaseVendor.H2);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }

        @Override
        public Optional<SequenceRenderer> sequences(EntityManagerFactory emf) {
            return Optional.of((cb, sequence) -> cb.literal(7L));
        }
    }

    private static final TableField<SequenceRow, SequenceRow> ROOT = TableField.root(SequenceRow.class);
    private static final ColumnField<Row, SequenceRow, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ExpressionField<Row, Long> NEXT = Expr.nextval("s");

    private static SessionFactory h2;
    private static SessionFactory mysql;

    @BeforeAll
    static void seed() {
        h2 = factory(H2, "sequence-h2");
        mysql = factory(MYSQL, "sequence-mysql");
        for (SessionFactory factory : List.of(h2, mysql)) {
            try (EntityManager em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new SequenceRow(1L));
                em.getTransaction().commit();
            }
        }
    }

    @AfterAll
    static void close() {
        h2.close();
        mysql.close();
    }

    @Test
    void ac_col_33_the_executor_renders_a_sequence_value_through_the_providers_renderer() {
        assertThat(read(h2)).containsExactly(new Row(1L, 7L));
    }

    @Test
    void ac_col_33_a_vendor_whose_profile_has_no_sequences_is_mq1509_though_the_provider_renders_one() {
        assertThatThrownBy(() -> read(mysql))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1509))
                .hasMessageContaining("no sequence support");
    }

    private static List<Row> read(SessionFactory factory) {
        ModelQuery<SequenceRow, ?, Row> q = ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(NEXT)))
                .select(SelectSet.of(ID, NEXT))
                .build();
        try (EntityManager em = factory.createEntityManager()) {
            return ModelQueryExecutor.create(em, SequenceRow.class, ModelQueryConfig.defaults()).list(q,
                    Limit.unlimited());
        }
    }

    private static SessionFactory factory(String marker, String name) {
        return new Configuration().addAnnotatedClass(SequenceRow.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .applySetting(marker, "true")
                        .build());
    }
}
