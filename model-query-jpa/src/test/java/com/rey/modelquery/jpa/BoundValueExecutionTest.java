package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The executor binds the statement parameters an {@code Expr.value} expression records, so a filter or a function
 * argument carrying one runs against the bound value (R-COL-26).
 */
class BoundValueExecutionTest {

    @Entity
    @Table(name = "bound_value_rows")
    public static class ValueRow {
        @Id
        Long id;

        @Column(name = "status")
        String status;

        protected ValueRow() {}

        ValueRow(long id, String status) {
            this.id = id;
            this.status = status;
        }
    }

    record Row(Long id, String status) {}

    private static final TableField<ValueRow, ValueRow> ROOT = TableField.root(ValueRow.class);
    private static final ColumnField<Row, ValueRow, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, ValueRow, String> STATUS =
            ColumnField.of(Row.class, ROOT, "status", String.class);

    private static SessionFactory sessions;

    @BeforeAll
    static void seed() {
        sessions = new Configuration().addAnnotatedClass(ValueRow.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL,
                                "jdbc:h2:mem:bound-value;DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .build());
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new ValueRow(1L, "paid"));
            em.persist(new ValueRow(2L, "void"));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    @Test
    void ac_col_31_a_value_operand_is_bound_on_the_statement() {
        ModelQuery<ValueRow, ?, Row> q = ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(STATUS)))
                .select(SelectSet.of(ID, STATUS))
                .where(f -> f.compare(STATUS, Op.EQ, Expr.value("paid")))
                .build();
        try (EntityManager em = sessions.createEntityManager()) {
            assertThat(ModelQueryExecutor.create(em, ValueRow.class, ModelQueryConfig.defaults())
                    .list(q, Limit.unlimited())).containsExactly(new Row(1L, "paid"));
        }
    }

    @Test
    void ac_col_31_a_value_function_argument_is_bound_on_the_statement() {
        ModelQuery<ValueRow, ?, Row> q = ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(STATUS)))
                .select(SelectSet.of(ID, STATUS))
                .where(f -> f.compare(Expr.function("lower", String.class, STATUS), Op.EQ, Expr.value("paid")))
                .build();
        try (EntityManager em = sessions.createEntityManager()) {
            List<Row> rows = ModelQueryExecutor.create(em, ValueRow.class, ModelQueryConfig.defaults())
                    .list(q, Limit.unlimited());
            assertThat(rows).containsExactly(new Row(1L, "paid"));
        }
    }
}
