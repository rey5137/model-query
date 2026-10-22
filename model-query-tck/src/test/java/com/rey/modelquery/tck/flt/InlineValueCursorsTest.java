package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.KeysetTypeEntity;
import com.rey.modelquery.tck.harness.InlineValueHandling;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;

/**
 * Keyset cursor values under {@code hibernate.criteria.value_handling_mode=inline}: a page after or before a cursor
 * binds each value, the comparison and every earlier key's equality, as a parameter typed by its column's mapping,
 * so no input reaches the SQL text, and reads what it reads under the default setting; a provider with no operand
 * binder binds a decimal and a binary cursor by their class and refuses a {@code UUID} or a timestamp cursor with
 * {@code MQ1502} before any statement, a keyset export's before its first page (spec api/12 R-FLT-08, R-FLT-21,
 * engine/21 R-PAG-17, D-177; TCK AC-FLT-35, AC-PAG-28).
 */
class InlineValueCursorsTest {

    record T(Long id) {}

    private static final TableField<KeysetTypeEntity, KeysetTypeEntity> TYPES =
            TableField.root(KeysetTypeEntity.class);
    private static final ColumnField<T, KeysetTypeEntity, Long> ID = ColumnField.of(T.class, TYPES, "id", Long.class);
    private static final ColumnField<T, KeysetTypeEntity, BigDecimal> AMOUNT =
            ColumnField.of(T.class, TYPES, "amount", BigDecimal.class);
    private static final ColumnField<T, KeysetTypeEntity, Timestamp> STAMP =
            ColumnField.of(T.class, TYPES, "stamp", Timestamp.class);
    /** A {@code UUID} stored as {@code varchar(36)}, so its value binds only typed by the mapping. */
    private static final ColumnField<T, KeysetTypeEntity, UUID> TOKEN =
            ColumnField.of(T.class, TYPES, "token", UUID.class);
    private static final ColumnField<T, KeysetTypeEntity, byte[]> PAYLOAD =
            ColumnField.of(T.class, TYPES, "payload", byte[].class);
    private static final ModelQuery.Builder<KeysetTypeEntity, Long, T> TYPE_ROWS =
            ModelQuery.builder(TYPES, row -> new T(row.get(ID)))
                    .select(SelectSet.of(ID, AMOUNT, STAMP, TOKEN, PAYLOAD)).primaryKey(PrimaryKey.of(ID))
                    .keyset();

    /** One cursor path: its keyset query, each key's value compared with the cursor's, then {@code id}'s. */
    private record CursorCase(String path, ModelQuery<KeysetTypeEntity, ?, T> query) {}

    private static final CursorCase UUID_CURSOR = new CursorCase("UUID cursor",
            TYPE_ROWS.orderBy(TOKEN.asc()).build());
    private static final CursorCase DECIMAL_CURSOR = new CursorCase("decimal cursor",
            TYPE_ROWS.orderBy(AMOUNT.asc()).build());
    private static final CursorCase TIMESTAMP_CURSOR = new CursorCase("timestamp cursor",
            TYPE_ROWS.orderBy(STAMP.desc()).build());
    private static final CursorCase BINARY_CURSOR = new CursorCase("binary cursor",
            TYPE_ROWS.orderBy(PAYLOAD.desc()).build());
    /** A composite primary key: the later key's branch holds the token's equality (R-PAG-06). */
    private static final CursorCase KEY_PREFIX_CURSOR = new CursorCase("primary-key equality-prefix cursor",
            TYPE_ROWS.primaryKey(PrimaryKey.composite(TOKEN, AMOUNT)).orderBy(TOKEN.asc(), AMOUNT.asc()).build());
    private static final List<CursorCase> CASES = List.of(UUID_CURSOR, DECIMAL_CURSOR, TIMESTAMP_CURSOR,
            BINARY_CURSOR, KEY_PREFIX_CURSOR);

    /** Page 2 of 5 rows: its cursor is past the first page, its values none a statement holds otherwise. */
    private static final int SIZE = 5;

    // ---- AC-FLT-35, AC-PAG-28: cursors

    @TckTest
    void ac_flt_35_ac_pag_28_each_cursor_binds_its_values_and_reads_the_default_settings_pages(TckDatabase db) {
        for (CursorCase c : CASES) {
            Reads defaults;
            try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
                defaults = reads(sf, c, null);
            }
            assertThat(defaults.after()).as(c.path()).hasSize(SIZE).doesNotContainAnyElementsOf(defaults.before());
            assertThat(defaults.before()).as(c.path()).isNotEmpty();
            try (InlineValueHandling inline = InlineValueHandling.open(db)) {
                Reads inlined = reads(inline.factory(), c, inline);
                assertThat(inlined).as(c.path()).isEqualTo(defaults);
            }
        }
    }

    @TckTest
    void ac_flt_35_without_a_binder_a_mapping_independent_cursor_binds_and_a_uuid_or_timestamp_one_is_mq1502(
            TckDatabase db) {
        List<CursorCase> binding = List.of(DECIMAL_CURSOR, BINARY_CURSOR);
        List<Reads> defaults = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            for (CursorCase c : binding) {
                defaults.add(reads(sf, c, null));
            }
            for (CursorCase c : List.of(UUID_CURSOR, TIMESTAMP_CURSOR)) {
                sf.inSession(em -> refused.add(executor(em).page(c.query(), KeysetSpec.first(SIZE)).nextCursor()
                        .orElseThrow()));
            }
        }
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            OperatorProviderSupport.serving(feature -> false, null, () -> {
                for (int i = 0; i < binding.size(); i++) {
                    assertThat(reads(inline.factory(), binding.get(i), inline)).as(binding.get(i).path())
                            .isEqualTo(defaults.get(i));
                }
                inline.factory().inSession(em -> {
                    var executor = executor(em);
                    inline.watch();
                    assertThatThrownBy(() -> executor.page(UUID_CURSOR.query(), KeysetSpec.after(refused.get(0), 5)))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                            .hasMessageContaining("token").hasMessageContaining("the keyset cursor")
                            .hasMessageContaining("UUID");
                    assertThatThrownBy(() -> executor.page(TIMESTAMP_CURSOR.query(),
                            KeysetSpec.before(refused.get(1), 5)))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                            .hasMessageContaining("stamp").hasMessageContaining("Timestamp");
                    assertThat(inline.statements()).as("refused before any statement").isEmpty();
                });
            });
        }
    }

    /**
     * A keyset export binds each later page's cursor from the page before, so without a binder a {@code UUID} key is
     * refused before the first page runs, not after it reached the consumer.
     */
    @TckTest
    void ac_flt_35_without_a_binder_a_keyset_export_over_a_uuid_key_is_mq1502_before_its_first_page(TckDatabase db) {
        List<T> exported = new ArrayList<>();
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            OperatorProviderSupport.serving(feature -> false, null, () -> inline.factory().inSession(em -> {
                var executor = executor(em);
                inline.watch();
                assertThatThrownBy(() -> executor.export(UUID_CURSOR.query(), ExportOptions.of(SIZE), page -> page,
                        exported::add))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                        .hasMessageContaining("token").hasMessageContaining("the keyset cursor")
                        .hasMessageContaining("UUID");
                assertThat(inline.statements()).as("refused before any statement").isEmpty();
            }));
        }
        assertThat(exported).as("the consumer got no row").isEmpty();
    }

    /**
     * The watched markers catch each cursor and key type when it is inlined, so a statement that passes the inspector
     * bound it: a criteria value given without a parameter reaches the SQL text as a literal under the setting.
     */
    @TckTest
    void ac_flt_35_each_watched_marker_catches_its_value_when_inlined(TckDatabase db) {
        record Probe(String attribute, Object value, Object watched) {}
        UUID token = new UUID(0, 417);
        Timestamp stamp = Timestamp.valueOf("2020-01-01 00:06:57.000417");
        byte[] payload = {1, (byte) 161};
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            for (Probe probe : List.of(new Probe("token", token, token.toString()),
                    new Probe("amount", new BigDecimal("0.4329"), new BigDecimal("0.4329")),
                    new Probe("stamp", stamp, "00:06:57"),
                    new Probe("payload", payload, HexFormat.of().formatHex(payload)),
                    new Probe("id", 417L, 417L))) {
                inline.watch(probe.watched());
                assertThatThrownBy(() -> inline.factory().inSession(em -> {
                    var cb = em.getCriteriaBuilder();
                    CriteriaQuery<KeysetTypeEntity> query = cb.createQuery(KeysetTypeEntity.class);
                    Root<KeysetTypeEntity> root = query.from(KeysetTypeEntity.class);
                    em.createQuery(query.where(cb.equal(root.get(probe.attribute()), probe.value())))
                            .getResultList();
                })).as(probe.attribute()).hasStackTraceContaining("reached the SQL text as literals");
            }
        }
    }

    // ---- helpers

    /** The page after the first page's cursor, and the page before it, as ids. */
    private record Reads(List<Long> after, List<Long> before) {}

    /**
     * The first page of {@code c} under the default watch, then, with {@code inline}'s inspector watching the
     * cursor's values when it is given, the pages after and before the cursor.
     */
    private static Reads reads(SessionFactory sf, CursorCase c, InlineValueHandling inline) {
        List<Reads> reads = new ArrayList<>();
        sf.inSession(em -> {
            var executor = executor(em);
            String cursor = executor.page(c.query(), KeysetSpec.first(SIZE)).nextCursor().orElseThrow();
            if (inline != null) {
                inline.watch(Arrays.stream(KeysetSpec.after(cursor, SIZE).values())
                        .map(InlineValueCursorsTest::watched).toList(), List.of());
            }
            KeysetSlice<T> after = executor.page(c.query(), KeysetSpec.after(cursor, SIZE));
            KeysetSlice<T> before = executor.page(c.query(), KeysetSpec.before(cursor, SIZE));
            if (inline != null) {
                // Each statement since the watch ran: no cursor value reached it (the inspector checked).
                assertThat(inline.statements()).as(c.path()).hasSize(2);
            }
            reads.add(new Reads(ids(after), ids(before)));
        });
        return reads.get(0);
    }

    /** How {@code value}, a decoded cursor value, appears in a statement's text when it is inlined. */
    private static Object watched(Object value) {
        if (value instanceof UUID token) {
            return token.toString();
        }
        if (value instanceof Timestamp stamp) {
            return stamp.toLocalDateTime().toLocalTime().truncatedTo(ChronoUnit.SECONDS).toString();
        }
        return value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value;
    }

    private static List<Long> ids(KeysetSlice<T> slice) {
        return slice.content().stream().map(T::id).toList();
    }

    private static ModelQueryExecutor<KeysetTypeEntity> executor(EntityManager em) {
        return ModelQueryExecutor.create(em, KeysetTypeEntity.class, ModelQueryConfig.defaults());
    }
}
