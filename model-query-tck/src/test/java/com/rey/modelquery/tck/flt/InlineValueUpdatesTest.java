package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.KeysetTypeEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.harness.InlineValueHandling;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.vnd.ins.InsUuidEntity;
import com.rey.modelquery.tck.vnd.ins.InsertProbes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;

/**
 * Update inputs under {@code hibernate.criteria.value_handling_mode=inline}: each assigned value, a to-one written by
 * id and the expected version bind as explicit parameters, typed by the column's mapping where the value needs it, so
 * no input reaches the SQL text, and the update writes what it writes under the default setting; a provider with no
 * operand binder still writes a mapping-independent value, a reference and the version check, and refuses a
 * {@code UUID} assignment, and a chunked write over a {@code UUID} key, with {@code MQ1502} before any statement
 * (spec api/12 R-FLT-08, R-FLT-21, D-177; TCK AC-FLT-35).
 */
class InlineValueUpdatesTest {

    record O(Long id) {}

    record K(Long id) {}

    record U(UUID id) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<O, OrderEntity, Long> ORDER_ID = ColumnField.of(O.class, ORDERS, "id", Long.class);
    private static final ColumnField<O, OrderEntity, String> STATUS =
            ColumnField.of(O.class, ORDERS, "status", String.class);
    private static final ColumnField<O, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(O.class, ORDERS, "total", BigDecimal.class);
    /** The to-one {@code referrer}, written by id: the update binds a reference to the customer (R-WRT-14). */
    private static final ColumnField<O, OrderEntity, Long> REFERRER_ID =
            ColumnField.of(O.class, ORDERS, "referrer", Long.class);

    private static final TableField<KeysetTypeEntity, KeysetTypeEntity> TYPES =
            TableField.root(KeysetTypeEntity.class);
    private static final ColumnField<K, KeysetTypeEntity, Long> TYPE_ID =
            ColumnField.of(K.class, TYPES, "id", Long.class);
    private static final ColumnField<K, KeysetTypeEntity, BigDecimal> AMOUNT =
            ColumnField.of(K.class, TYPES, "amount", BigDecimal.class);
    private static final ColumnField<K, KeysetTypeEntity, Timestamp> STAMP =
            ColumnField.of(K.class, TYPES, "stamp", Timestamp.class);
    /** A {@code UUID} stored as {@code varchar(36)}, so its value binds only typed by the mapping. */
    private static final ColumnField<K, KeysetTypeEntity, UUID> TOKEN =
            ColumnField.of(K.class, TYPES, "token", UUID.class);
    /** A value class through a JPA {@code AttributeConverter}. */
    private static final ColumnField<K, KeysetTypeEntity, KeysetTypeEntity.Shape> SHAPE =
            ColumnField.of(K.class, TYPES, "shape", KeysetTypeEntity.Shape.class);

    private static final TableField<InsUuidEntity, InsUuidEntity> UUIDS = TableField.root(InsUuidEntity.class);
    private static final ColumnField<U, InsUuidEntity, UUID> UUID_ID = ColumnField.of(U.class, UUIDS, "id", UUID.class);

    private static final long ORDER = 5L;
    private static final long ROW = 6L;
    private static final String NEW_STATUS = "inline-status";
    private static final BigDecimal NEW_TOTAL = new BigDecimal("4321.98");
    private static final BigDecimal NEW_AMOUNT = new BigDecimal("12.3456");
    private static final UUID NEW_TOKEN = new UUID(0, 4242);
    private static final Timestamp NEW_STAMP = Timestamp.valueOf("2031-07-14 13:57:21.123456");
    private static final KeysetTypeEntity.Shape NEW_SHAPE = new KeysetTypeEntity.Shape("shape-updated");

    private static final String STORED_ORDER = "select status, total, referrer_id, version from orders where id = ?1";
    private static final String STORED_ROW = "select amount, stamp, token, shape from keyset_types where id = ?1";

    // ---- AC-FLT-35: update assignments and the version check

    @TckTest
    void ac_flt_35_update_assignments_and_the_version_check_bind_and_write_the_default_settings_rows(
            TckDatabase db) {
        Written defaults;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            defaults = written(sf, null);
        }
        assertThat(defaults.order().get(0)).isEqualTo(NEW_STATUS);
        assertThat(defaults.stale()).as("the stale version check writes nothing").isTrue();
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            Written inlined = written(inline.factory(), inputs -> inline.watch(inputs, List.of()));
            assertThat(inlined).isEqualTo(defaults);
            assertThat(inline.statements()).as("each update ran, no input inlined").isNotEmpty();
        }
    }

    @TckTest
    void ac_flt_35_without_a_binder_a_reference_a_number_and_the_version_bind_and_a_uuid_assignment_is_mq1502(
            TckDatabase db) {
        Written defaults;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            defaults = written(sf, null);
        }
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            OperatorProviderSupport.serving(feature -> false, null, () -> {
                assertThat(orderWrite(inline.factory(), null)).isEqualTo(defaults.order());
                var token = ModelUpdate.builder(TYPES).primaryKey(PrimaryKey.of(TYPE_ID)).set(TOKEN, NEW_TOKEN)
                        .whereKey(ROW).build();
                inline.factory().inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, KeysetTypeEntity.class, ModelQueryConfig.defaults());
                    inline.watch();
                    em.getTransaction().begin();
                    try {
                        assertThatThrownBy(() -> executor.update(token))
                                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                                .hasMessageContaining("token").hasMessageContaining("the update assignment")
                                .hasMessageContaining("UUID");
                        assertThat(inline.statements()).as("refused before any statement").isEmpty();
                    } finally {
                        em.getTransaction().rollback();
                    }
                });
            });
        }
    }

    /**
     * A chunked write's later rounds bind a cursor read from the key select before, so without a binder a {@code UUID}
     * key is refused before the first key select runs, not after it.
     */
    @TckTest
    void ac_flt_35_without_a_binder_a_chunked_write_over_a_uuid_key_is_mq1502_before_its_first_key_select(
            TckDatabase db) {
        var delete = ModelDelete.builder(UUIDS).primaryKey(PrimaryKey.of(UUID_ID)).all()
                .chunked(ChunkOptions.size(2)).build();
        try (InsertProbes p = InsertProbes.withInlineValues(db)) {
            OperatorProviderSupport.serving(feature -> false, null, () -> {
                p.forget();
                assertThatThrownBy(() -> p.factory().inTransaction(em -> ModelQueryExecutor.create(em,
                        InsUuidEntity.class, ModelQueryConfig.defaults()).delete(delete)))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1502))
                        .hasMessageContaining("id").hasMessageContaining("the keyset cursor")
                        .hasMessageContaining("UUID");
                assertThat(p.statements()).as("refused before any statement").isEmpty();
            });
        }
    }

    // ---- helpers

    /** What the updates wrote, each in a rolled-back transaction: order 5's columns, the stale check, row 6's. */
    private record Written(List<String> order, boolean stale, List<String> row) {}

    /** {@code watch}, when given, is told each update's inputs before it runs. */
    private static Written written(SessionFactory sf, Consumer<List<?>> watch) {
        List<String> order = orderWrite(sf, watch);
        boolean[] stale = new boolean[1];
        List<String> row = new ArrayList<>();
        sf.inSession(em -> inRolledBack(em, () -> {
            long version = Long.parseLong(stored(em, STORED_ORDER, ORDER).get(3));
            var expecting = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ORDER_ID)).set(STATUS, NEW_STATUS)
                    .whereKey(ORDER).expectVersion((int) version + 1).build();
            if (watch != null) {
                watch.accept(List.of(NEW_STATUS, ORDER));
            }
            // The version is not one more yet, so the check matches nothing and fails loudly.
            try {
                orders(em).update(expecting);
            } catch (OptimisticLockException e) {
                stale[0] = true;
            }
        }));
        sf.inSession(em -> inRolledBack(em, () -> {
            var update = ModelUpdate.builder(TYPES).primaryKey(PrimaryKey.of(TYPE_ID)).set(AMOUNT, NEW_AMOUNT)
                    .set(STAMP, NEW_STAMP).set(TOKEN, NEW_TOKEN).set(SHAPE, NEW_SHAPE).whereKey(ROW).build();
            if (watch != null) {
                watch.accept(List.of(NEW_AMOUNT, NEW_TOKEN.toString(), "13:57:21", NEW_SHAPE.name(), ROW));
            }
            assertThat(ModelQueryExecutor.create(em, KeysetTypeEntity.class, ModelQueryConfig.defaults())
                    .update(update)).isEqualTo(1);
            row.addAll(stored(em, STORED_ROW, ROW));
        }));
        return new Written(order, stale[0], row);
    }

    /**
     * Order 5 updated with a status, a total, the highest customer as its referrer and the expected version, in a
     * rolled-back transaction: its stored columns after.
     */
    private static List<String> orderWrite(SessionFactory sf, Consumer<List<?>> watch) {
        List<String> stored = new ArrayList<>();
        sf.inSession(em -> inRolledBack(em, () -> {
            long referrer = ((Number) em.createNativeQuery("select max(id) from customers").getSingleResult())
                    .longValue();
            int version = Integer.parseInt(stored(em, STORED_ORDER, ORDER).get(3));
            // A 1 is the version increment's own literal, never an input, so it is not watched.
            assertThat(referrer).isGreaterThan(1);
            var update = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ORDER_ID)).set(STATUS, NEW_STATUS)
                    .set(TOTAL, NEW_TOTAL).set(REFERRER_ID, referrer).whereKey(ORDER).expectVersion(version)
                    .build();
            if (watch != null) {
                List<Object> inputs = new ArrayList<>(List.of(NEW_STATUS, NEW_TOTAL, referrer, ORDER));
                if (version != 1) {
                    inputs.add(version);
                }
                watch.accept(inputs);
            }
            assertThat(orders(em).update(update)).isEqualTo(1);
            stored.addAll(stored(em, STORED_ORDER, ORDER));
        }));
        return stored;
    }

    /** {@code sql}'s one row for {@code id}, read in the update's transaction, its columns as text. */
    private static List<String> stored(EntityManager em, String sql, long id) {
        Object[] columns = (Object[]) em.createNativeQuery(sql).setParameter(1, id).getSingleResult();
        return Arrays.stream(columns).map(String::valueOf).toList();
    }

    private static void inRolledBack(EntityManager em, Runnable work) {
        em.getTransaction().begin();
        try {
            work.run();
        } finally {
            em.getTransaction().rollback();
        }
    }

    private static ModelQueryExecutor<OrderEntity> orders(EntityManager em) {
        return ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
    }
}
