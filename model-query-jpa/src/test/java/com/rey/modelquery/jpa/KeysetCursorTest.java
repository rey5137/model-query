package com.rey.modelquery.jpa;

import static com.rey.modelquery.core.RenderOptions.portable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.KeysetCursorCodec;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The keyset cursor paths a unit test can reach: {@link Keyset#reversed()}, {@link Keyset#resolve}, {@link
 * Keyset#primaryKeyOf}, {@link Keyset#encode}, {@link Keyset#unsupportedColumn}, {@link Keyset#fingerprint} and the
 * byte[] comparison of {@code R-PAG-17} that binds through a parameter (R-PAG-17, R-PAG-18, R-PAG-19, R-PAG-20,
 * R-PAG-22, R-QA-10).
 */
class KeysetCursorTest {

    record Row3(Long id, Integer a, Integer b, Integer c) {}

    /** A value class no cursor codec carries, the R-PAG-17 example of a column that cannot be paged. */
    record Unsupported(String text) {}

    enum Colour {
        RED, GREEN
    }

    private static final TableField<KeysetRowEntity, KeysetRowEntity> ROOT = TableField.root(KeysetRowEntity.class);
    private static final ColumnField<Row3, KeysetRowEntity, Long> ID =
            ColumnField.of(Row3.class, ROOT, "id", Long.class);
    private static final ColumnField<Row3, KeysetRowEntity, Integer> A =
            ColumnField.of(Row3.class, ROOT, "a", Integer.class);
    private static final ColumnField<Row3, KeysetRowEntity, Integer> B =
            ColumnField.of(Row3.class, ROOT, "b", Integer.class);
    private static final ColumnField<Row3, KeysetRowEntity, Integer> C =
            ColumnField.of(Row3.class, ROOT, "c", Integer.class);
    private static final ColumnField<Row3, KeysetRowEntity, String> S1 =
            ColumnField.of(Row3.class, ROOT, "s1", String.class);
    private static final ColumnField<Row3, KeysetRowEntity, String> S2 =
            ColumnField.of(Row3.class, ROOT, "s2", String.class);
    private static final ColumnField<Row3, KeysetRowEntity, Colour> COLOUR =
            ColumnField.of(Row3.class, ROOT, "colour", Colour.class);
    private static final ColumnField<Row3, KeysetRowEntity, Unsupported> UNSUPPORTED =
            ColumnField.of(Row3.class, ROOT, "unsupported", Unsupported.class);

    private static final RowMapper<Row3> MAPPER = row -> new Row3(row.get(ID), row.get(A), row.get(B), row.get(C));

    record CursorRow(Long id, byte[] payload) {}

    private static final TableField<KeysetCursorEntity, KeysetCursorEntity> CURSOR_ROOT =
            TableField.root(KeysetCursorEntity.class);
    private static final ColumnField<CursorRow, KeysetCursorEntity, Long> CURSOR_ID =
            ColumnField.of(CursorRow.class, CURSOR_ROOT, "id", Long.class);
    private static final ColumnField<CursorRow, KeysetCursorEntity, byte[]> PAYLOAD =
            ColumnField.of(CursorRow.class, CURSOR_ROOT, "payload", byte[].class);
    private static final RowMapper<CursorRow> CURSOR_MAPPER =
            row -> new CursorRow(row.get(CURSOR_ID), row.get(PAYLOAD));

    private static SessionFactory sessions;

    @BeforeAll
    static void seed() {
        sessions = new Configuration()
                .addAnnotatedClass(KeysetRowEntity.class)
                .addAnnotatedClass(KeysetCursorEntity.class)
                .buildSessionFactory(new StandardServiceRegistryBuilder()
                        .applySetting(AvailableSettings.JAKARTA_JDBC_URL,
                                "jdbc:h2:mem:keyset_cursor;DB_CLOSE_DELAY=-1")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_USER, "sa")
                        .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, "")
                        .applySetting(AvailableSettings.HBM2DDL_AUTO, "create-drop")
                        .build());
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            for (int i = 1; i <= 3; i++) {
                em.persist(new KeysetCursorEntity(i, new byte[] {(byte) i}));
            }
            em.persist(new KeysetCursorEntity(4, null));
            em.getTransaction().commit();
        }
    }

    @AfterAll
    static void close() {
        sessions.close();
    }

    // ---- helpers

    @SafeVarargs
    private static ModelQuery<KeysetRowEntity, Long, Row3> readQuery(OrderField<Row3, ?>... order) {
        return ModelQuery.builder(ROOT, MAPPER)
                .select(SelectSet.of(ID, A, B, C))
                .primaryKey(PrimaryKey.of(ID))
                .orderBy(order)
                .keyset()
                .build();
    }

    private static Keyset<Row3> keyset(ModelQuery<?, ?, Row3> q, NullOrdering ordering, KeysetNullKeys nullKeys) {
        return keyset(q, ordering, Optional.empty(), nullKeys);
    }

    private static Keyset<Row3> keyset(ModelQuery<?, ?, Row3> q, NullOrdering ordering,
            Optional<NullPrecedence> providerNulls, KeysetNullKeys nullKeys) {
        return Keyset.of(q, q.primaryKey().orElseThrow(), ordering, providerNulls, nullKeys);
    }

    private static List<Order> ordersOf(ModelQuery<KeysetRowEntity, Long, Row3> q, Keyset<Row3> keyset) {
        try (EntityManager em = sessions.createEntityManager()) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            BuiltQuery<Row3> built = q.buildQuery(cb, Phase.MODEL, portable());
            keyset.applyOrder(built, cb);
            return List.copyOf(built.query().getOrderList());
        }
    }

    private static Keyset.Beyond beyondOf(ModelQuery<KeysetRowEntity, Long, Row3> q, Keyset<Row3> keyset,
            Object[] cursor) {
        try (EntityManager em = sessions.createEntityManager()) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            BuiltQuery<Row3> built = q.buildQuery(cb, Phase.MODEL, portable());
            return keyset.after(cursor, built.joins(), cb);
        }
    }

    private static void assertMalformed(Keyset<Row3> keyset, Object[] decoded) {
        assertThatThrownBy(() -> keyset.resolve(decoded, "query"))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2208))
                .hasMessageContaining("the keyset cursor is not one this library issued");
    }

    private static Row rowOf(SelectField<?, ?>[] columns, Object[] values) {
        return new Row() {
            @Override
            @SuppressWarnings("unchecked")
            public <C> C get(SelectField<?, C> column) {
                return (C) raw(column);
            }

            @Override
            public Object raw(SelectField<?, ?> column) {
                for (int i = 0; i < columns.length; i++) {
                    if (columns[i] == column) {
                        return values[i];
                    }
                }
                throw new AssertionError("unexpected column " + column);
            }

            @Override
            public boolean isSelected(SelectField<?, ?> column) {
                return true;
            }

            @Override
            public Row scoped(TableField<?, ?> join) {
                throw new UnsupportedOperationException("scoped");
            }
        };
    }

    private static byte[] firstEightOfSha256(String canonical) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(hash, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- R-PAG-22, reversed()

    @Test
    void r_pag_22_reversed_flips_an_explicit_precedence_and_its_direction_and_renders_it_flipped() {
        var q = readQuery(A.asc().nullsLast());
        Keyset<Row3> forward = keyset(q, NullOrdering.UNKNOWN, KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        // a nullsLast ascending: the null key descends, a ascends; the id tie-breaker ascends.
        assertThat(ordersOf(q, forward)).extracting(Order::isAscending).containsExactly(false, true, true);
        // Reversed, a is nullsFirst descending: the null key ascends, a descends; the id descends.
        assertThat(ordersOf(q, forward.reversed())).extracting(Order::isAscending).containsExactly(true, false, false);
    }

    @Test
    void r_pag_22_reversed_renders_a_database_default_key_bare_because_the_database_flips_it() {
        var q = readQuery(A.asc());
        Keyset<Row3> forward = keyset(q, NullOrdering.NULLS_FIRST, KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        // a's nulls come from the database's ascending ordering, so reversed it renders bare and only flips.
        assertThat(ordersOf(q, forward.reversed())).extracting(Order::isAscending).containsExactly(false, false);
    }

    // ---- R-PAG-19, fingerprint()

    @Test
    void r_pag_19_the_fingerprint_is_the_first_eight_bytes_of_the_canonical_forms_sha256() {
        var q = readQuery(A.asc().nullsFirst(), B.asc());
        Keyset<Row3> keyset = keyset(q, NullOrdering.UNKNOWN, KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        String canonical = "com.rey.modelquery.jpa.KeysetRowEntity\n"
                + "a\u0000java.lang.Integer\u0000ASC\u0000FIRST\n"
                + "b\u0000java.lang.Integer\u0000ASC\u0000REFUSING\n"
                + "id\u0000java.lang.Long\u0000ASC\u0000REFUSING\n";
        assertThat(keyset.fingerprint()).containsExactly(firstEightOfSha256(canonical));
    }

    // ---- R-PAG-17, unsupportedColumn()

    @Test
    void r_pag_17_unsupported_column_names_the_first_column_no_codec_carries() {
        Keyset<Row3> keyset = keyset(readQuery(A.asc().nullsFirst(), UNSUPPORTED.asc()),
                NullOrdering.UNKNOWN, KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(keyset.unsupportedColumn()).contains(UNSUPPORTED);
    }

    @Test
    void r_pag_17_unsupported_column_is_empty_when_every_column_has_a_codec() {
        Keyset<Row3> keyset = keyset(readQuery(A.asc().nullsFirst(), COLOUR.desc()),
                NullOrdering.UNKNOWN, KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(keyset.unsupportedColumn()).isEmpty();
    }

    // ---- R-PAG-17/R-PAG-18, resolve()

    @Test
    void r_pag_17_resolve_reads_every_key_and_copies_a_cursor_of_the_orders_length() {
        Keyset<Row3> keyset = keyset(readQuery(A.asc().nullsFirst()), NullOrdering.UNKNOWN,
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(keyset.resolve(new Object[] {5, 9L}, "query")).containsExactly(5, 9L);
    }

    @Test
    void r_pag_17_resolve_refuses_a_cursor_that_names_the_wrong_number_of_keys() {
        Keyset<Row3> keyset = keyset(readQuery(A.asc().nullsFirst()), NullOrdering.UNKNOWN,
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertMalformed(keyset, new Object[] {5});
        assertMalformed(keyset, new Object[] {5, 9L, 1L});
    }

    @Test
    void r_pag_17_resolve_allows_a_null_only_where_the_column_does_not_refuse_one() {
        var q = readQuery(A.asc());
        Keyset<Row3> honoured = keyset(q, NullOrdering.NULLS_FIRST, KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(honoured.resolve(new Object[] {null, 9L}, "query")).containsExactly(null, 9L);
        Keyset<Row3> failing = keyset(q, NullOrdering.UNKNOWN, KeysetNullKeys.FAIL);
        assertMalformed(failing, new Object[] {null, 9L});
        assertMalformed(honoured, new Object[] {5, null});
    }

    @Test
    void r_pag_17_resolve_reads_an_enum_constant_by_name_and_refuses_anything_else() {
        Keyset<Row3> keyset = keyset(readQuery(COLOUR.asc().nullsFirst()), NullOrdering.UNKNOWN,
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(keyset.resolve(new Object[] {"RED", 9L}, "query")).containsExactly(Colour.RED, 9L);
        assertMalformed(keyset, new Object[] {5, 9L});
        assertMalformed(keyset, new Object[] {"BLUE", 9L});
    }

    @Test
    void r_pag_17_resolve_refuses_a_value_of_another_type() {
        Keyset<Row3> keyset = keyset(readQuery(A.asc().nullsFirst()), NullOrdering.UNKNOWN,
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertMalformed(keyset, new Object[] {5, "9"});
    }

    // ---- R-PAG-18, primaryKeyOf()

    @Test
    void r_pag_18_primary_key_of_returns_the_single_value_and_null_without_a_key() {
        Keyset<Row3> keyset = keyset(readQuery(A.asc().nullsFirst()), NullOrdering.UNKNOWN,
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(keyset.primaryKeyOf(new Object[] {5, 9L})).isEqualTo(9L);
        Keyset<Row3> keyOnly = Keyset.ofKey(PrimaryKey.of(ID));
        assertThat(keyOnly.primaryKeyOf(new Object[] {7L})).isNull();
    }

    @Test
    void r_pag_18_primary_key_of_returns_a_composite_key_in_its_declaration_order() {
        var q = ModelQuery.builder(ROOT, MAPPER)
                .select(SelectSet.of(ID, A, B, C))
                .primaryKey(PrimaryKey.composite(ID, A))
                .orderBy(B.asc().nullsFirst())
                .build();
        Keyset<Row3> keyset = Keyset.of(q, q.primaryKey().orElseThrow(), NullOrdering.UNKNOWN, Optional.empty(),
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        // Keys in order: b, then the appended id and a.
        assertThat(keyset.primaryKeyOf(new Object[] {10, 9L, 5})).isEqualTo(List.of(9L, 5));
    }

    // ---- R-PAG-18, encode()

    @Test
    void r_pag_18_encode_returns_the_codecs_cursor_when_it_fits() {
        Keyset<Row3> keyset = keyset(readQuery(S1.asc(), S2.asc()), NullOrdering.NULLS_LAST,
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        String cursor = keyset.encode("query", rowOf(new SelectField<?, ?>[] {S1, S2, ID},
                new Object[] {"a", "b", 7L}));
        assertThat(cursor).isNotEmpty()
                .isEqualTo(KeysetCursorCodec.encode(keyset.fingerprint(), new Object[] {"a", "b", 7L}));
    }

    @Test
    void r_pag_18_encode_names_the_column_whose_value_pushed_the_cursor_over_the_cap() {
        Keyset<Row3> keyset = keyset(readQuery(S1.asc(), S2.asc()), NullOrdering.NULLS_LAST,
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThatThrownBy(() -> keyset.encode("query", rowOf(new SelectField<?, ?>[] {S1, S2, ID},
                new Object[] {"s".repeat(50), "l".repeat(9000), 1L})))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2210))
                .hasMessageContaining("keyset column s2");
    }

    @Test
    void r_pag_18_encode_names_the_first_column_on_a_tie_of_the_longest_value() {
        Keyset<Row3> keyset = keyset(readQuery(S1.asc(), S2.asc()), NullOrdering.NULLS_LAST,
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThatThrownBy(() -> keyset.encode("query", rowOf(new SelectField<?, ?>[] {S1, S2, ID},
                new Object[] {"x".repeat(9000), "y".repeat(9000), 1L})))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2210))
                .hasMessageContaining("keyset column s1");
    }

    @Test
    void r_pag_18_encode_accepts_a_cursor_at_the_length_cap_and_refuses_one_over_it() {
        Keyset<Row3> keyset = keyset(readQuery(S1.asc(), S2.asc()), NullOrdering.NULLS_LAST,
                KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        String atCap = keyset.encode("query", rowOf(new SelectField<?, ?>[] {S1, S2, ID},
                new Object[] {"x".repeat(6105), "s", 1L}));
        assertThat(atCap).hasSize(KeysetCursorCodec.MAX_CURSOR_LENGTH);
        assertThatThrownBy(() -> keyset.encode("query", rowOf(new SelectField<?, ?>[] {S1, S2, ID},
                new Object[] {"x".repeat(6106), "s", 1L})))
                .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ2210));
    }

    // ---- R-PAG-17, the byte[] comparison that binds through a parameter (R-FLT-08, AC-PAG-28)

    @Test
    void r_pag_17_an_exact_type_comparison_needs_no_parameter() {
        var q = readQuery(A.asc().nullsFirst());
        Keyset<Row3> keyset = keyset(q, NullOrdering.UNKNOWN, KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        assertThat(beyondOf(q, keyset, new Object[] {5, 1L}).parameters()).isEmpty();
    }

    @Test
    void r_pag_17_a_byte_array_cursor_value_binds_through_a_parameter_and_pages() {
        var q = ModelQuery.builder(CURSOR_ROOT, CURSOR_MAPPER)
                .select(SelectSet.of(CURSOR_ID, PAYLOAD))
                .primaryKey(PrimaryKey.of(CURSOR_ID))
                .orderBy(PAYLOAD.asc().nullsFirst())
                .keyset()
                .build();
        Keyset<CursorRow> keyset = Keyset.of(q, q.primaryKey().orElseThrow(), NullOrdering.UNKNOWN,
                Optional.empty(), KeysetNullKeys.HONOUR_NULL_PRECEDENCE);
        try (EntityManager em = sessions.createEntityManager()) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            BuiltQuery<CursorRow> built = q.buildQuery(cb, Phase.MODEL, portable());
            Keyset.Beyond beyond = keyset.after(new Object[] {new byte[] {2}, 2L}, built.joins(), cb);
            // payload is not Comparable: it is compared as an expression bound through a parameter (R-FLT-08).
            assertThat(beyond.parameters()).hasSize(1);
            assertThat((byte[]) beyond.parameters().values().iterator().next()).containsExactly((byte) 2);
            built.query().where(beyond.predicate());
            keyset.applyOrder(built, cb);
            TypedQuery<Tuple> typed = em.createQuery(built.query());
            beyond.bindTo(typed);
            List<Long> ids = new ArrayList<>();
            for (Tuple tuple : typed.getResultList()) {
                ids.add(built.map(tuple).id());
            }
            // payload 3 alone: payload 2 and below, and the NULL that sorts first, are before the cursor.
            assertThat(ids).containsExactly(3L);
        }
    }
}
