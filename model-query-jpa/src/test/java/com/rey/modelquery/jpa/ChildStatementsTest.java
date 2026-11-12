package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.ChildQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeyGroups;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.QueryParam;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.vendor.ChildStatements;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
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
 * {@link ChildStatements#run}: one child load of a fetch plan on chosen keys, as the portability dry run renders it
 * (api/16 R-INS-17, D-224), over in-memory H2.
 */
class ChildStatementsTest {

    record Parent(Long id, List<Row> rows) {}

    record Row(Long id, Integer b) {}

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Parent, KeysetRowEntity, Long> PARENT_ID =
            ColumnField.of(Parent.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, KeysetRowEntity, Long> ID = ColumnField.of(Row.class, ROOT, "id", Long.class);
    private static final ColumnField<Row, KeysetRowEntity, Integer> B =
            ColumnField.of(Row.class, ROOT, "b", Integer.class);

    static final QueryParam<Integer> LO = QueryParam.of("bFrom", Integer.class);
    static final QueryParam<Long> MAX_ID = QueryParam.of("maxId", Long.class);
    /** The catch-all group: every {@code b} the seed holds. */
    static final int ALL = 9;

    /** Ids 1 to 3 are group 0, {@code b} from 0; the others group 1, {@code b} from 100. */
    static Integer bucket(long id) {
        return id <= 3 ? 0 : 1;
    }

    static int lo(int group) {
        LO_GROUPS.add(group);
        return group == ALL ? 0 : group * 100;
    }

    private static final List<String> SQL = Collections.synchronizedList(new ArrayList<>());
    /** The groups whose {@link #LO} value a run read: the values it bound. */
    private static final List<Integer> LO_GROUPS = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> RULE_CALLS = Collections.synchronizedList(new ArrayList<>());

    private static SessionFactory sessions;

    /** Ids 1 to 6, each row its own child; {@code b} lies in its id's group. */
    @BeforeAll
    static void seed() {
        sessions = new Configuration()
                .addAnnotatedClass(KeysetRowEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL,
                                "jdbc:h2:mem:child-statements;DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .applySetting(AvailableSettings.STATEMENT_INSPECTOR, (StatementInspector) sql -> {
                            SQL.add(sql);
                            return sql;
                        })
                        .build());
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new KeysetRowEntity(1, 1, 10, 0));
            em.persist(new KeysetRowEntity(2, 2, 20, 0));
            em.persist(new KeysetRowEntity(3, 3, 30, 0));
            em.persist(new KeysetRowEntity(4, 4, 110, 1));
            em.persist(new KeysetRowEntity(5, 5, 120, 1));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    @BeforeEach
    void reset() {
        SQL.clear();
        RULE_CALLS.clear();
        LO_GROUPS.clear();
    }

    @Test
    void ac_ins_17_a_child_run_prepares_the_statement_a_page_of_those_parents_prepares() {
        ChildLoad<Parent, Row> load = load(false, false, q -> q);
        ModelQuery<KeysetRowEntity, Long, Parent> parents = ModelQuery
                .builder(ROOT, row -> new Parent(row.get(PARENT_ID), List.of()))
                .select(SelectSet.of(PARENT_ID))
                .primaryKey(PrimaryKey.of(PARENT_ID))
                .where(f -> f.compare(PARENT_ID, Op.LTE, Expr.param(MAX_ID)))
                .fetch(FetchPlan.of(SelectSet.of(PARENT_ID)).child(load.field(), FetchPlan.of(SelectSet.of(ID, B))))
                .bind(MAX_ID, 2L)
                .build();
        withExecutor(executor -> assertThat(executor.list(parents, Limit.unlimited())).containsExactly(
                new Parent(1L, List.of(new Row(1L, 10))), new Parent(2L, List.of(new Row(2L, 20)))));
        assertThat(SQL).hasSize(2);
        String pageChildStatement = SQL.get(1);
        SQL.clear();

        withExecutor(executor -> ChildStatements.run(executor, load, List.of(1L, 2L)));
        assertThat(SQL).containsExactly(pageChildStatement);
        assertThat(pageChildStatement).contains("in (?,?)");
    }

    @Test
    void ac_ins_17_key_groups_without_a_catch_all_run_every_key_in_the_first_keys_group() {
        KeyGroups<Long, Integer> assume = KeyGroups.by(Long.class, ChildStatementsTest::bucket)
                .bind(LO, ChildStatementsTest::lo).assumeInRange();
        ChildLoad<Parent, Row> load = load(true, true, q -> q.keyGroups(assume));
        // Key 1's group is 0, but the run binds key 4's: one round of both keys, on group 1's values.
        withExecutor(executor -> ChildStatements.run(executor, load, List.of(4L, 1L)));
        assertThat(SQL).singleElement().asString().contains("in (?,?)");
        assertThat(LO_GROUPS).containsExactly(1);
        SQL.clear();
        LO_GROUPS.clear();

        withExecutor(executor -> ChildStatements.run(executor, load, List.of(1L, 4L)));
        assertThat(SQL).singleElement().asString().contains("in (?,?)");
        assertThat(LO_GROUPS).containsExactly(0);
    }

    @Test
    void ac_ins_17_key_groups_with_a_catch_all_run_every_key_on_its_values_never_asking_the_rule() {
        KeyGroups<Long, Integer> groupWhen = KeyGroups.by(Long.class, ChildStatementsTest::bucket)
                .bind(LO, ChildStatementsTest::lo)
                .groupWhen((keys, groups) -> {
                    RULE_CALLS.add(keys + " keys, " + groups + " groups");
                    return true;
                }, ALL).assumeInRange();
        ChildLoad<Parent, Row> toMany = load(true, true, q -> q.keyGroups(groupWhen));
        // The catch-all's values, not key 4's group, in one round of both keys.
        withExecutor(executor -> ChildStatements.run(executor, toMany, List.of(4L, 1L)));
        assertThat(SQL).singleElement().asString().contains("in (?,?)");
        assertThat(LO_GROUPS).containsExactly(ALL);
        assertThat(RULE_CALLS).as("the rule is never asked").isEmpty();
    }

    @Test
    void ac_ins_17_a_group_function_refusing_the_first_key_fails_naming_the_load() {
        KeyGroups<Long, Integer> known = KeyGroups.by(Long.class, (Long id) -> id == 1 ? null : bucket(id))
                .bind(LO, ChildStatementsTest::lo).assumeInRange();
        ChildLoad<Parent, Row> load = load(true, true, q -> q.keyGroups(known));
        withExecutor(executor -> assertThatThrownBy(() -> ChildStatements.run(executor, load, List.of(1L, 4L)))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2607))
                .hasMessageContaining("MQ2607: " + load + ": "));
        assertThat(SQL).isEmpty();

        // Asked only for the first key: a group function failing on a later one is not seen.
        withExecutor(executor -> ChildStatements.run(executor, load, List.of(4L, 1L)));
        assertThat(SQL).hasSize(1);
    }

    @Test
    void ac_ins_17_a_child_run_refuses_another_executor_and_no_or_repeated_keys() {
        ChildLoad<Parent, Row> load = load(true, false, q -> q);
        @SuppressWarnings("unchecked")
        ModelQueryExecutor<Object> other = (ModelQueryExecutor<Object>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ModelQueryExecutor.class}, (proxy, method, args) -> null);
        assertThatThrownBy(() -> ChildStatements.run(other, load, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ModelQueryExecutor.create");
        withExecutor(executor -> {
            assertThatThrownBy(() -> ChildStatements.run(executor, load, List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct keys");
            assertThatThrownBy(() -> ChildStatements.run(executor, load, List.of(1L, 1L)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct keys");
            // Reached straight through the engine interface, the checks still hold.
            ChildStatements.Runner runner = (ChildStatements.Runner) executor;
            assertThatThrownBy(() -> runner.runChildLoad(load, List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct keys");
            assertThatThrownBy(() -> runner.runChildLoad(load, List.of(1L, 1L)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct keys");
            assertThatThrownBy(() -> runner.runChildLoad(load, java.util.Arrays.asList(1L, null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("none null");
        });
        assertThat(SQL).isEmpty();
    }

    /**
     * The one child load of a plan over {@link Parent}: the rows whose id is the parent's, filtered to
     * {@code bFrom} or more when {@code ranged}: a to-many child's group param is a lower bound only.
     */
    private static ChildLoad<Parent, Row> load(boolean toMany, boolean ranged,
            UnaryOperator<ChildQuery<Row>> query) {
        ChildField<Parent, Row> rows = new ChildField<>() {
            @Override
            public String name() {
                return "rows";
            }

            @Override
            public ColumnField<Parent, ?, ?> key() {
                return PARENT_ID;
            }

            @Override
            public Optional<ColumnField<Row, ?, ?>> foreignKey() {
                return Optional.of(ID);
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return Optional.empty();
            }

            @Override
            public boolean isToMany() {
                return toMany;
            }

            @Override
            public ModelQuery.Builder<?, ?, Row> query() {
                return ModelQuery.builder(ROOT, row -> new Row(row.get(ID), row.get(B)))
                        .select(SelectSet.of(ID, B)).primaryKey(PrimaryKey.of(ID));
            }

            @Override
            public Parent with(Parent parent, List<Row> children) {
                return new Parent(parent.id(), children);
            }
        };
        return cast(FetchPlan.of(SelectSet.of(PARENT_ID)).child(rows, FetchPlan.of(SelectSet.of(ID, B)),
                child -> query.apply(ranged ? child.where(f -> f.compare(B, Op.GTE, Expr.param(LO))) : child))
                .childLoads().get(0));
    }

    @SuppressWarnings("unchecked")
    private static ChildLoad<Parent, Row> cast(ChildLoad<Parent, ?> load) {
        return (ChildLoad<Parent, Row>) load;
    }

    private static void withExecutor(Consumer<ModelQueryExecutor<KeysetRowEntity>> work) {
        try (EntityManager em = sessions.createEntityManager()) {
            work.accept(ModelQueryExecutor.create(em, KeysetRowEntity.class, ModelQueryConfig.defaults()));
        }
    }
}
