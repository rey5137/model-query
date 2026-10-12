package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ConstantRenderer;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Locale;
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
 * The executor resolves a provider's {@link ConstantRenderer} once per factory and renders a column-typed constant
 * through it, or throws {@code MQ1508} when the provider's renderer cannot store one (R-VND-16, R-COL-27).
 */
class TypedConstantExecutionTest {

    @Entity
    @Table(name = "typed_constant_rows")
    public static class StatusRow {
        @Id
        Long id;

        @Column(name = "status")
        String status;

        protected StatusRow() {}

        StatusRow(long id, String status) {
            this.id = id;
            this.status = status;
        }
    }

    record Row(Long id, String status) {}

    /** Serves only the factory marked with {@link #MARKER}: a renderer that lower-cases the attribute value. */
    private static final String MARKER = "modelquery.test.typed-constant";

    /** Serves only the factory marked with {@link #NO_MARKER}: a renderer that can store no value. */
    private static final String NO_MARKER = "modelquery.test.typed-constant-none";

    public static final class Constants implements ProviderSupport {
        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(MARKER) || emf.getProperties().containsKey(NO_MARKER);
        }

        @Override
        public Optional<DatabaseVendor> detectVendor(EntityManagerFactory emf) {
            return Optional.of(DatabaseVendor.H2);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }

        @Override
        public Optional<ConstantRenderer> constants(EntityManagerFactory emf) {
            if (emf.getProperties().containsKey(NO_MARKER)) {
                return Optional.of((entity, attribute, value) -> Optional.empty());
            }
            return Optional.of((entity, attribute, value) -> Optional.of(value.toString().toLowerCase(Locale.ROOT)));
        }
    }

    private static final TableField<StatusRow, StatusRow> ROOT = TableField.root(StatusRow.class);
    private static final ColumnField<Row, StatusRow, Long> ID =
            ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, StatusRow, String> STATUS =
            ColumnField.of(Row.class, ROOT, "status", String.class);

    private static SessionFactory sessions;
    private static SessionFactory noConstants;

    @BeforeAll
    static void seed() {
        sessions = factory(MARKER, "typed-constant");
        noConstants = factory(NO_MARKER, "typed-constant-none");
        for (SessionFactory factory : List.of(sessions, noConstants)) {
            try (EntityManager em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new StatusRow(1L, "paid"));
                em.persist(new StatusRow(2L, "void"));
                em.getTransaction().commit();
            }
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
        noConstants.close();
    }

    @Test
    void ac_col_32_the_executor_renders_a_typed_constant_through_the_providers_renderer() {
        assertThat(compare(sessions)).containsExactly(new Row(1L, "paid"));
    }

    @Test
    void ac_col_32_a_renderer_that_can_store_no_value_throws_mq1508() {
        assertThatThrownBy(() -> compare(noConstants))
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1508))
                .hasMessageContaining("the provider cannot store it");
    }

    /** The rows whose status equals the inlined {@code "PAID"} literal, which the renderer's lower case matches. */
    private static List<Row> compare(SessionFactory factory) {
        ModelQuery<StatusRow, ?, Row> q = ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(STATUS)))
                .select(SelectSet.of(ID, STATUS))
                .where(f -> f.compare(STATUS, Op.EQ, Expr.constant(STATUS, "PAID")))
                .build();
        try (EntityManager em = factory.createEntityManager()) {
            return ModelQueryExecutor.create(em, StatusRow.class, ModelQueryConfig.defaults()).list(q,
                    Limit.unlimited());
        }
    }

    private static SessionFactory factory(String marker, String name) {
        return new Configuration().addAnnotatedClass(StatusRow.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .applySetting(marker, "true")
                        .build());
    }
}
