package com.rey.modelquery.jpa;

import static com.rey.modelquery.core.ColumnField.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.jpa.spi.ForeignKeyColumn;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A write that reads a to-one by its id renders the foreign-key column, so the executor asks the provider whether it
 * is a local column before any statement and refuses {@code MQ1004} unless it says so, for the filter, a set clause and
 * a sub-select, and with no provider that can tell (spec api/10 R-COL-29, D-136). The real mappings are the TCK's.
 */
class ForeignKeyColumnExecutionTest {

    record Row(Long id, Long child) {}

    record ChildRow(Long id) {}

    private static final String ANSWERING = "model-query.test.fk-answering";

    private static final TableField<LockParentEntity, LockParentEntity> PARENTS =
            TableField.root(LockParentEntity.class);
    private static final ColumnField<Row, LockParentEntity, Long> ID = of(Row.class, PARENTS, "id", Long.class);
    private static final ColumnField<Row, LockParentEntity, Long> CHILD = of(Row.class, PARENTS, "child", Long.class);
    private static final TableField<LockChildEntity, LockChildEntity> CHILDREN =
            TableField.root(LockChildEntity.class);
    private static final ColumnField<ChildRow, LockChildEntity, Long> CHILD_ID =
            of(ChildRow.class, CHILDREN, "id", Long.class);

    private static final ModelQueryConfig H2 = ModelQueryConfig.defaults().vendor(DatabaseVendor.H2);

    /** Every statement any factory of this test ran, so a refusal can be shown to run before any. */
    private static final List<String> SQL = Collections.synchronizedList(new ArrayList<>());

    private static SessionFactory answering;
    private static SessionFactory silent;

    @BeforeAll
    static void open() {
        answering = factory("answering", ANSWERING);
        silent = factory("silent", null);
    }

    @AfterAll
    static void close() {
        answering.close();
        silent.close();
    }

    @BeforeEach
    void forget() {
        SQL.clear();
        Answering.ASKED.clear();
    }

    @Test
    void ac_col_34_with_no_provider_that_can_tell_a_write_reading_the_id_throws_mq1004_before_any_statement() {
        var inFilter = ModelUpdate.builder(PARENTS).primaryKey(PrimaryKey.of(ID)).setNull(CHILD)
                .where(f -> f.isNull(CHILD)).build();
        var inSet = ModelUpdate.builder(PARENTS).primaryKey(PrimaryKey.of(ID))
                .setExpression(CHILD, (path, cb) -> path).where(f -> f.eq(ID, 1L)).build();

        inTransaction(silent, em -> {
            var executor = ModelQueryExecutor.create(em, LockParentEntity.class, H2);
            for (var update : List.of(inFilter, inSet)) {
                assertThatThrownBy(() -> executor.update(update))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1004))
                        .hasMessageContaining("Row.child").hasMessageContaining("the provider cannot tell");
            }
        });
        assertThat(SQL).isEmpty();
    }

    @Test
    void ac_col_34_a_provider_answer_decides_a_write_reading_the_id_in_its_filter_or_set_clause() {
        var inFilter = ModelUpdate.builder(PARENTS).primaryKey(PrimaryKey.of(ID)).setNull(CHILD)
                .where(f -> f.isNull(CHILD)).build();
        var inSet = ModelUpdate.builder(PARENTS).primaryKey(PrimaryKey.of(ID))
                .setExpression(CHILD, (path, cb) -> path).where(f -> f.eq(ID, 1L)).build();

        Answering.answer = ForeignKeyColumn.LOCAL_COLUMN;
        inTransaction(answering, em -> {
            var executor = ModelQueryExecutor.create(em, LockParentEntity.class, H2);
            assertThat(executor.update(inFilter)).isZero();
            assertThat(executor.update(inSet)).isZero();
        });
        assertThat(Answering.ASKED).containsOnly("LockParentEntity.child");
        assertThat(SQL).hasSize(2).allSatisfy(sql -> assertThat(sql).startsWith("update").doesNotContain("join"));

        for (var refused : List.of(ForeignKeyColumn.JOINS, ForeignKeyColumn.UNKNOWN)) {
            var again = ModelUpdate.builder(PARENTS).primaryKey(PrimaryKey.of(ID)).setNull(CHILD)
                    .where(f -> f.isNull(CHILD)).build();
            Answering.answer = refused;
            SQL.clear();
            inTransaction(answering, em -> assertThatThrownBy(() -> ModelQueryExecutor
                    .create(em, LockParentEntity.class, H2).update(again))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1004))
                    .hasMessageContaining(refused == ForeignKeyColumn.JOINS
                            ? "the provider reports that it is not" : "the provider cannot tell"));
            assertThat(SQL).isEmpty();
        }
    }

    @Test
    void ac_col_34_the_entity_asked_about_is_the_owner_of_a_to_one_read_in_a_sub_select() {
        var delete = ModelDelete.builder(CHILDREN).primaryKey(PrimaryKey.of(CHILD_ID))
                .where(f -> f.in(CHILD_ID, SubSelect.of(CHILD).where(g -> g.isNull(CHILD)))).build();

        Answering.answer = ForeignKeyColumn.LOCAL_COLUMN;
        inTransaction(answering, em -> ModelQueryExecutor.create(em, LockChildEntity.class, H2).delete(delete));

        // The sub-select reads the parents' child: the parent, not the deleted child, owns the attribute
        assertThat(Answering.ASKED).containsExactly("LockParentEntity.child");
    }

    private static void inTransaction(SessionFactory factory, Consumer<EntityManager> work) {
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            try {
                work.accept(em);
            } finally {
                em.getTransaction().rollback();
            }
        }
    }

    private static SessionFactory factory(String name, String marker) {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:foreign-key-column-" + name)
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
        return new Configuration().addAnnotatedClass(LockParentEntity.class).addAnnotatedClass(LockChildEntity.class)
                .buildSessionFactory(registry.build());
    }

    /** A provider that answers {@link #answer} for every to-one and records which it was asked about. */
    public static final class Answering implements ProviderSupport {

        /** The {@code Entity.attribute} of each question, in the order asked. */
        static final List<String> ASKED = Collections.synchronizedList(new ArrayList<>());

        static volatile ForeignKeyColumn answer = ForeignKeyColumn.UNKNOWN;

        @Override
        public boolean supports(EntityManagerFactory emf) {
            return emf.getProperties().containsKey(ANSWERING);
        }

        @Override
        public <T> Stream<T> resultStream(TypedQuery<T> query, int fetchSize) {
            return query.getResultStream();
        }

        @Override
        public ForeignKeyColumn foreignKeyColumn(EntityManagerFactory emf, Class<?> entity, String toOne) {
            ASKED.add(entity.getSimpleName() + "." + toOne);
            return answer;
        }
    }
}
