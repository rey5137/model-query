package com.rey.modelquery.hibernate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Condition;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.spi.ConflictClause;
import com.rey.modelquery.jpa.spi.ReturningShape;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.hibernate.Session;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * R-WRT-52's arbiter predicate on the provider side: the SQL it is spliced into on PostgreSQL, alone and composed
 * with a returning clause, its values as literals through the attribute's mapping; the statement wrapper that keeps
 * the predicate through the calls the engine makes; and the refusals of an unrenderable value ({@code MQ1508}) and of
 * a Hibernate line no adapter serves ({@code MQ4011}), which leaves a plain upsert running.
 */
class ArbiterUpsertTest {

    enum State { OPEN, CLOSED }

    @Entity(name = "Stock")
    static class Stock {
        @Id
        Long id;

        String sku;

        @Enumerated(EnumType.STRING)
        State state;

        Integer qty;

        Double weight;
    }

    record Row(Long id, String sku, State state, Integer qty, Double weight) {}

    private static final TableField<Stock, Stock> STOCK = TableField.root(Stock.class);
    private static final ColumnField<Row, Stock, Long> ID = ColumnField.of(Row.class, STOCK, "id", Long.class);
    private static final ColumnField<Row, Stock, String> SKU = ColumnField.of(Row.class, STOCK, "sku", String.class);
    private static final ColumnField<Row, Stock, State> STATE =
            ColumnField.of(Row.class, STOCK, "state", State.class);
    private static final ColumnField<Row, Stock, Integer> QTY =
            ColumnField.of(Row.class, STOCK, "qty", Integer.class);
    private static final ColumnField<Row, Stock, Double> WEIGHT =
            ColumnField.of(Row.class, STOCK, "weight", Double.class);
    private static final InsertColumns<Row, Stock> COLUMNS = InsertColumns.<Row, Stock>of(STOCK)
            .addKey(ID, Row::id).add(SKU, Row::sku).add(STATE, Row::state).add(QTY, Row::qty)
            .add(WEIGHT, Row::weight);
    private static final List<String> ATTRIBUTES = List.of("id", "sku", "state", "qty", "weight");
    private static final List<Object> ROW = Arrays.asList(1L, "s1", State.OPEN, 2, 1.5);

    private static final String PG_ARBITER = "insert into Stock as s1_0(id,sku,state,qty,weight) values (?,?,?,?,?) "
            + "on conflict(sku) where state <> 'CLOSED' and sku in ('a', 'it''s') and (qty > 3 or weight is null) "
            + "do update set qty=excluded.qty";

    @Test
    void ac_wrt_45_the_arbiter_is_spliced_after_the_conflict_target_with_literals_and_composes_with_returning() {
        List<Condition> arbiter = arbiter(f -> f.ne(STATE, State.CLOSED).in(SKU, List.of("a", "it's"))
                .or(g -> g.gt(QTY, 3), g -> g.isNull(WEIGHT)));
        try (SessionFactoryImplementor sf = factory("arbiter-pg", new PostgreSQLDialect());
                Session session = sf.openSession()) {
            Query statement = insert(session, arbiter);

            assertThat(HibernateMutations.translated(statement, Stock.class, List.of(), null)).isEqualTo(PG_ARBITER);
            assertThat(HibernateMutations.translated(statement, Stock.class, List.of("id", "qty"),
                    ReturningShape.SUFFIX)).isEqualTo(PG_ARBITER + " returning id,qty");
        }
    }

    @Test
    void ac_wrt_45_the_wrapped_statement_keeps_its_arbiter_through_fluent_calls_and_unwrap() {
        try (SessionFactoryImplementor sf = factory("arbiter-proxy", new PostgreSQLDialect());
                Session session = sf.openSession()) {
            Query statement = insert(session, arbiter(f -> f.ne(STATE, State.CLOSED)));

            assertThat(statement.setHint("jakarta.persistence.query.timeout", 3_000)).isSameAs(statement);
            assertThat(((DomainQueryExecutionContext) statement).getQueryOptions().getTimeout())
                    .as("the hint reached the wrapped statement").isEqualTo(3);
            assertThat(statement.unwrap(Query.class)).isSameAs(statement);
            assertThat(statement.unwrap(DomainQueryExecutionContext.class)).isSameAs(statement);
            assertThat(statement).isEqualTo(statement).hasSameHashCodeAs(statement);
            assertThat(ArbiterQuery.of(statement)).map(ArbiterQuery::arbiter).contains("state <> 'CLOSED'");
            assertThat(ArbiterQuery.of(insert(session, List.of()))).as("a plain upsert is not wrapped").isEmpty();
        }
    }

    @Test
    void ac_wrt_45_a_value_with_no_sql_literal_is_mq1508() {
        try (SessionFactoryImplementor sf = factory("arbiter-nan", new PostgreSQLDialect());
                Session session = sf.openSession()) {
            assertThatThrownBy(() -> insert(session, arbiter(f -> f.eq(WEIGHT, Double.NaN))))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1508))
                    .hasMessageContaining("weight: the arbiter predicate's value NaN");
        }
    }

    @Test
    void ac_wrt_45_a_line_no_adapter_serves_refuses_the_arbiter_with_mq4011_and_runs_a_plain_upsert() {
        try (SessionFactoryImplementor sf = factory("arbiter-unserved", new H2Dialect());
                Session session = sf.openSession()) {
            HibernateMutations.unserved(sf);

            assertThatThrownBy(() -> insert(session, arbiter(f -> f.ne(STATE, State.CLOSED))))
                    .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ4011))
                    .hasMessageContaining("a returning write or a conflict arbiter is refused");
            session.beginTransaction();
            int written = insert(session, List.of()).executeUpdate();
            session.getTransaction().commit();
            assertThat(written).isEqualTo(1);
            assertThat(session.createQuery("select s.qty from Stock s where s.sku = 's1'", Integer.class)
                    .getResultList()).containsExactly(2);
        }
    }

    /** The arbiter {@code where} records, as the engine hands it to the provider. */
    private static List<Condition> arbiter(UnaryOperator<Filters<Row>> where) {
        return ValuesInsert.builder(COLUMNS, Long.class, List.of(new Row(1L, "s1", State.OPEN, 2, 1.5)))
                .onConflict(SKU).where(where).doNothing().build().conflictArbiter();
    }

    /** The upsert of {@link #ROW} on {@code sku}, updating {@code qty}, with {@code arbiter} on its target. */
    private static Query insert(Session session, List<Condition> arbiter) {
        return new HibernateInsertSupport().insertValues(session, Stock.class, ATTRIBUTES, List.of(ROW),
                Optional.of(new ConflictClause<Stock>() {
                    @Override
                    public List<String> keyAttributes() {
                        return List.of("sku");
                    }

                    @Override
                    public List<Condition> arbiter() {
                        return arbiter;
                    }

                    @Override
                    public boolean doNothing() {
                        return false;
                    }

                    @Override
                    public Optional<Predicate> update(Root<Stock> target, Root<Stock> excluded, CriteriaBuilder cb,
                            Assignments assign) {
                        assign.expression(target.get("qty"), excluded.get("qty"));
                        return Optional.empty();
                    }
                }));
    }

    private static SessionFactoryImplementor factory(String name, Dialect dialect) {
        StandardServiceRegistryBuilder registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                .applySetting(AvailableSettings.DIALECT, dialect)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop");
        return (SessionFactoryImplementor) new Configuration().addAnnotatedClass(Stock.class)
                .buildSessionFactory(registry.build());
    }
}
