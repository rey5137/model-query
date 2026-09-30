package com.rey.modelquery.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ColumnSet;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The keyset predicate builder against a real Hibernate {@code CriteriaBuilder} over in-memory H2 (R-QA-01, R-QA-10).
 * Every test executes the predicate against seeded rows and compares the rows it returns with the rows a Java
 * comparator puts after the cursor, so a flipped operator, direction or NULL branch changes the rows returned.
 */
class KeysetTest {

    record Row3(Long id, Integer a, Integer b, Integer c) {}

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row3, KeysetRowEntity, Long> ID =
            ColumnField.of(Row3.class, ROOT, "id", Long.class);
    private static final ColumnField<Row3, KeysetRowEntity, Integer> A =
            ColumnField.of(Row3.class, ROOT, "a", Integer.class);
    private static final ColumnField<Row3, KeysetRowEntity, Integer> B =
            ColumnField.of(Row3.class, ROOT, "b", Integer.class);
    private static final ColumnField<Row3, KeysetRowEntity, Integer> C =
            ColumnField.of(Row3.class, ROOT, "c", Integer.class);

    private static final RowMapper<Row3> MAPPER = row -> new Row3(row.get(ID), row.get(A), row.get(B), row.get(C));

    private static SessionFactory sessions;
    private static List<Row3> rows;

    @BeforeAll
    static void seed() {
        sessions = new Configuration()
                .addAnnotatedClass(KeysetRowEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:keyset;DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .build());
        // a and b each take two values and NULL, in every combination, twice over: 18 rows, every cursor tied.
        Integer[] as = {1, 2, null};
        Integer[] bs = {10, 20, null};
        List<Row3> all = new ArrayList<>();
        long id = 1;
        for (int copy = 0; copy < 2; copy++) {
            for (Integer a : as) {
                for (Integer b : bs) {
                    all.add(new Row3(id, a, b, (int) (id % 3)));
                    id++;
                }
            }
        }
        rows = List.copyOf(all);
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            rows.forEach(r -> em.persist(new KeysetRowEntity(r.id(), r.a(), r.b(), r.c())));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    // ---- oracle

    private static Object valueOf(Row3 row, ColumnField<Row3, ?, ?> column) {
        if (column == ID) {
            return row.id();
        }
        return column == A ? row.a() : column == B ? row.b() : row.c();
    }

    /** The order Java puts two rows in: the query's keys, then the id in the direction of the last key (R-PAG-04). */
    private static Comparator<Row3> orderOf(List<OrderField<Row3, ?>> order) {
        Comparator<Row3> result = (x, y) -> 0;
        for (OrderField<Row3, ?> field : order) {
            result = result.thenComparing(compare(field));
        }
        if (order.stream().noneMatch(f -> f.column() == ID)) {
            Comparator<Row3> byId = Comparator.comparing(Row3::id);
            result = result.thenComparing(order.get(order.size() - 1).ascending() ? byId : byId.reversed());
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparator<Row3> compare(OrderField<Row3, ?> field) {
        return (x, y) -> {
            Comparable vx = (Comparable) valueOf(x, (ColumnField<Row3, ?, ?>) field.column());
            Comparable vy = (Comparable) valueOf(y, (ColumnField<Row3, ?, ?>) field.column());
            if (vx == null || vy == null) {
                // DEFAULT: the predicate takes a NULL to follow every value, like LAST (its rows are refused later).
                boolean first = field.nulls() == NullPrecedence.FIRST;
                return vx == vy ? 0 : (vx == null) == first ? -1 : 1;
            }
            int c = vx.compareTo(vy);
            return field.ascending() ? c : -c;
        };
    }

    // ---- execution

    @SafeVarargs
    private static ModelQuery<KeysetRowEntity, Long, Row3> query(OrderField<Row3, ?>... order) {
        return ModelQuery.builder(ROOT, MAPPER)
                .columns(ColumnSet.of(ID, A, B, C))
                .primaryKey(PrimaryKey.of(ID))
                .orderBy(order)
                .keyset()
                .build();
    }

    private static Object[] cursorOf(Row3 row, ModelQuery<?, ?, Row3> q) {
        List<Object> values = new ArrayList<>();
        q.orderBy().forEach(order -> values.add(valueOf(row, (ColumnField<Row3, ?, ?>) order.column())));
        if (q.orderBy().stream().noneMatch(order -> order.column() == ID)) {
            values.add(row.id());
        }
        return values.toArray();
    }

    /** The rows after {@code cursor} as the database returns them, in the query's order and tie-breaker's. */
    private static List<Row3> after(ModelQuery<KeysetRowEntity, Long, Row3> q, Keyset<Row3> keyset, Object[] cursor) {
        try (EntityManager em = sessions.createEntityManager()) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            BuiltQuery<Row3> built = q.buildQuery(cb, Phase.MODEL);
            if (cursor != null) {
                built.query().where(keyset.after(cursor, built.joins(), cb));
            }
            keyset.appendOrder(built, cb);
            List<Row3> result = new ArrayList<>();
            for (Tuple tuple : em.createQuery(built.query()).getResultList()) {
                result.add(built.map(tuple));
            }
            return result;
        }
    }

    /**
     * From every row as the cursor, the rows the predicate returns are exactly the rows Java sorts after it. When
     * {@code inOrder}, they also come back in that order, which pins the tie-breaker's direction. NULL cursors under
     * DEFAULT precedence are skipped: the executor refuses them before a predicate is built.
     */
    private static void checkEveryCursor(boolean inOrder, OrderField<Row3, ?>... order) {
        var q = query(order);
        Keyset<Row3> keyset = Keyset.of(q, q.primaryKey().orElseThrow());
        Comparator<Row3> cmp = orderOf(q.orderBy());
        String name = q.orderBy().toString();
        if (inOrder) {
            assertThat(after(q, keyset, null)).as("first page of %s", name)
                    .isEqualTo(rows.stream().sorted(cmp).toList());
        }
        for (Row3 cursor : rows) {
            boolean refused = q.orderBy().stream().anyMatch(f -> f.nulls() == NullPrecedence.DEFAULT
                    && f.column() != ID && valueOf(cursor, (ColumnField<Row3, ?, ?>) f.column()) == null);
            if (refused) {
                continue;
            }
            List<Row3> expected = rows.stream().filter(r -> cmp.compare(r, cursor) > 0).sorted(cmp).toList();
            List<Row3> actual = after(q, keyset, cursorOf(cursor, q));
            if (inOrder) {
                assertThat(actual).as("after %s in %s", cursor, name).isEqualTo(expected);
            } else {
                assertThat(actual).as("after %s in %s", cursor, name).containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }

    private static final List<NullPrecedence> EXPLICIT = List.of(NullPrecedence.FIRST, NullPrecedence.LAST);

    private static OrderField<Row3, ?> of(ColumnField<Row3, ?, ?> column, boolean asc, NullPrecedence nulls) {
        return (asc ? column.asc() : column.desc()).nulls(nulls);
    }

    // ---- tests

    @Test
    void ac_qa_04_keyset_predicate_returns_exactly_the_rows_after_each_cursor() {
        // One nullable column, both directions, both explicit precedences: the last column's ASC/DESC picks > or <,
        // its precedence the NULL branch, and the appended id breaks the ties in the same direction.
        for (boolean asc : List.of(true, false)) {
            for (NullPrecedence nulls : EXPLICIT) {
                checkEveryCursor(true, of(A, asc, nulls));
            }
        }
    }

    @Test
    void ac_qa_04_composite_order_holds_the_earlier_keys_equal_and_moves_the_later_one() {
        // Two nullable columns in every direction/precedence combination: 16 orders, each tried from all 18 cursors.
        for (boolean ascA : List.of(true, false)) {
            for (NullPrecedence nullsA : EXPLICIT) {
                for (boolean ascB : List.of(true, false)) {
                    for (NullPrecedence nullsB : EXPLICIT) {
                        checkEveryCursor(true, of(A, ascA, nullsA), of(B, ascB, nullsB));
                    }
                }
            }
        }
    }

    @Test
    void ac_qa_04_the_tie_breaker_follows_the_direction_of_the_last_column_only() {
        // a ascending then b descending: the id follows b (descending), not a.
        checkEveryCursor(true, of(A, true, NullPrecedence.LAST), of(B, false, NullPrecedence.FIRST));
        checkEveryCursor(true, of(A, false, NullPrecedence.LAST), of(B, true, NullPrecedence.FIRST));
    }

    @Test
    void ac_qa_04_default_precedence_on_a_nullable_column_lets_a_null_follow_a_value() {
        // Under DEFAULT the predicate must still return the NULL rows (INV-5), wherever the database sorts them.
        checkEveryCursor(false, A.asc());
        checkEveryCursor(false, A.desc());
        checkEveryCursor(false, A.asc(), B.desc());
    }

    @Test
    void ac_qa_04_a_primary_key_column_in_the_order_is_not_appended_again() {
        // The id is a key column, so it is neither closed twice nor given NULL branches, in any position.
        checkEveryCursor(true, ID.asc());
        checkEveryCursor(true, ID.desc());
        checkEveryCursor(true, C.asc(), ID.desc());
        checkEveryCursor(true, ID.asc(), C.desc());
        checkEveryCursor(true, C.desc());
        checkEveryCursor(true, C.asc());
    }

    @Test
    void ac_qa_04_cursor_reads_every_key_and_refuses_a_null_without_explicit_precedence() {
        var q = query(A.asc().nullsFirst(), B.desc());
        Keyset<Row3> keyset = Keyset.of(q, q.primaryKey().orElseThrow());
        assertThat(cursorRow(q, keyset, 1)).containsExactly(1, 10, 1L);
        // b is NULL in row 3 and has no explicit precedence.
        assertThatThrownBy(() -> cursorRow(q, keyset, 3))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2202))
                .hasMessageContaining("keyset column b");
        // a is NULL in row 7 but ordered nullsFirst.
        assertThat(cursorRow(q, keyset, 7)).containsExactly(null, 10, 7L);
        // The id is a key column: never refused, and never NULL.
        var byId = query(ID.asc());
        assertThat(cursorRow(byId, Keyset.of(byId, byId.primaryKey().orElseThrow()), 4)).containsExactly(4L);
    }

    private static Object[] cursorRow(ModelQuery<KeysetRowEntity, Long, Row3> q, Keyset<Row3> keyset, long id) {
        try (EntityManager em = sessions.createEntityManager()) {
            BuiltQuery<Row3> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL);
            for (Tuple tuple : em.createQuery(built.query()).getResultList()) {
                Row row = built.selection().row(tuple);
                if (row.get(ID) == id) {
                    return keyset.cursor(row);
                }
            }
        }
        throw new AssertionError("no row " + id);
    }
}
