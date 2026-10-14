package com.rey.modelquery.tck.portability;

import static com.rey.modelquery.jpa.spi.DatabaseVendor.H2;
import static com.rey.modelquery.jpa.spi.DatabaseVendor.MYSQL;
import static com.rey.modelquery.jpa.spi.DatabaseVendor.POSTGRESQL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Expr;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.RowLock.Strength;
import com.rey.modelquery.core.RowLock.Wait;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.portability.internal.CallResult;
import com.rey.modelquery.portability.internal.DryCall;
import com.rey.modelquery.portability.internal.DryRun;
import com.rey.modelquery.portability.internal.OfflineUnit;
import com.rey.modelquery.portability.internal.Outcome;
import com.rey.modelquery.tck.col.ConstantRowEntity;
import com.rey.modelquery.tck.col.CounterRowEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.SkuOrderLineEntity;
import com.rey.modelquery.tck.fch.OrderLines;
import com.rey.modelquery.tck.fch.QOrderLines;
import com.rey.modelquery.tck.secondary.CardExtraEntity;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.ins.InsArbiterEntity;
import com.rey.modelquery.tck.vnd.ins.InsAssignedEntity;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.AbstractStringAssert;
import org.hibernate.Version;
import org.junit.jupiter.api.Test;

/**
 * AC-INS-11: every vendor-dependent refusal of M18 to M22 reproduces under the dry run on each Tier-1 target, with no
 * container, on the Hibernate line on the classpath (the build runs both); and on each target that serves a call, the
 * call's first statement equals that call's statement in the TCK's committed SQL snapshot for that vendor and line.
 *
 * <p>The definitions restate those of the live tests that cover each AC (named per section), so the snapshots they
 * name are the ones those tests committed against a real database.
 */
class DryRunConformanceTest {

    /** The Tier-1 targets at the versions the default TCK matrix runs ({@code TckTarget.defaultTargets}). */
    private static final List<Target> TARGETS = List.of(Target.of(H2, "2.5.252"), Target.of(POSTGRESQL, "17.0"),
            Target.of(MYSQL, "8.4.0"));

    private static final List<DatabaseVendor> VENDORS = List.of(H2, POSTGRESQL, MYSQL);

    // ---- AC-VND-14, AC-WRT-42/43 (wrt.BulkWriteTest)

    record ItemRow(Long id, Integer quantity, Long orderId) {}

    record CounterRow(Long id, Long nextValue) {}

    record CounterValue(Long nextValue) {}

    private static final TableField<OrderItemEntity, OrderItemEntity> ITEM_ROWS =
            TableField.root(OrderItemEntity.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Long> ROW_ID =
            ColumnField.of(ItemRow.class, ITEM_ROWS, "id", Long.class);
    private static final ColumnField<ItemRow, OrderItemEntity, Integer> ROW_QUANTITY =
            ColumnField.of(ItemRow.class, ITEM_ROWS, "quantity", Integer.class);
    private static final TableField<OrderItemEntity, OrderEntity> ROW_ORDER =
            TableField.join(ITEM_ROWS, "order", JoinType.INNER);
    private static final ColumnField<ItemRow, OrderEntity, Long> ROW_ORDER_ID =
            ColumnField.of(ItemRow.class, ROW_ORDER, "id", Long.class);
    private static final ModelQuery<OrderItemEntity, Long, ItemRow> ITEM_ROW_RETURNING =
            ModelQuery.builder(ITEM_ROWS, row -> new ItemRow(row.get(ROW_ID), row.get(ROW_QUANTITY),
                    row.get(ROW_ORDER_ID))).select(SelectSet.of(ROW_ID, ROW_QUANTITY, ROW_ORDER_ID))
                    .primaryKey(PrimaryKey.of(ROW_ID)).build();

    private static final TableField<CounterRowEntity, CounterRowEntity> COUNTERS =
            TableField.root(CounterRowEntity.class);
    private static final ColumnField<CounterRow, CounterRowEntity, Long> COUNTER_ID =
            ColumnField.of(CounterRow.class, COUNTERS, "id", Long.class);
    private static final ColumnField<CounterRow, CounterRowEntity, Long> COUNTER_NEXT =
            ColumnField.of(CounterRow.class, COUNTERS, "nextValue", Long.class);
    private static final InsertColumns<CounterRow, CounterRowEntity> COUNTER_COLUMNS =
            InsertColumns.<CounterRow, CounterRowEntity>of(COUNTERS).addKey(COUNTER_ID, CounterRow::id)
                    .add(COUNTER_NEXT, CounterRow::nextValue);
    private static final InsertColumns<CounterRow, CounterRowEntity> COUNTER_KEYS =
            InsertColumns.<CounterRow, CounterRowEntity>of(COUNTERS).addKey(COUNTER_ID, CounterRow::id);
    private static final ModelQuery<CounterRowEntity, Long, CounterRow> COUNTER_RETURNING =
            ModelQuery.builder(COUNTERS, row -> new CounterRow(row.get(COUNTER_ID), row.get(COUNTER_NEXT)))
                    .select(SelectSet.of(COUNTER_ID, COUNTER_NEXT)).primaryKey(PrimaryKey.of(COUNTER_ID)).build();
    private static final ColumnField<CounterValue, CounterRowEntity, Long> COUNTER_VALUE_ID =
            ColumnField.of(CounterValue.class, COUNTERS, "id", Long.class);
    private static final ColumnField<CounterValue, CounterRowEntity, Long> COUNTER_NEW =
            ColumnField.of(CounterValue.class, COUNTERS, "nextValue", Long.class);
    private static final ModelQuery<CounterRowEntity, Long, CounterValue> COUNTER_VALUE_RETURNING =
            ModelQuery.builder(COUNTERS, row -> new CounterValue(row.get(COUNTER_NEW)))
                    .select(SelectSet.of(COUNTER_NEW)).primaryKey(PrimaryKey.of(COUNTER_VALUE_ID)).build();

    // ---- AC-WRT-44, AC-WRT-45 (wrt.InsertConflictTest)

    record Named(Long id, String code, String name) {}

    record ArbiterRow(Long id, String code, String sku, String status, String name) {}

    private static final TableField<InsAssignedEntity, InsAssignedEntity> ASSIGNED =
            TableField.root(InsAssignedEntity.class);
    private static final ColumnField<Named, InsAssignedEntity, Long> NAMED_ID =
            ColumnField.of(Named.class, ASSIGNED, "id", Long.class);
    private static final ColumnField<Named, InsAssignedEntity, String> NAMED_CODE =
            ColumnField.of(Named.class, ASSIGNED, "code", String.class);
    private static final ColumnField<Named, InsAssignedEntity, String> NAMED_NAME =
            ColumnField.of(Named.class, ASSIGNED, "name", String.class);
    private static final InsertColumns<Named, InsAssignedEntity> NAMED =
            InsertColumns.<Named, InsAssignedEntity>of(ASSIGNED).addKey(NAMED_ID, Named::id)
                    .add(NAMED_CODE, Named::code).add(NAMED_NAME, Named::name);

    private static final TableField<InsArbiterEntity, InsArbiterEntity> ARBITER =
            TableField.root(InsArbiterEntity.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, Long> ARBITER_ID =
            ColumnField.of(ArbiterRow.class, ARBITER, "id", Long.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, String> ARBITER_CODE =
            ColumnField.of(ArbiterRow.class, ARBITER, "code", String.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, String> ARBITER_SKU =
            ColumnField.of(ArbiterRow.class, ARBITER, "sku", String.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, String> ARBITER_STATUS =
            ColumnField.of(ArbiterRow.class, ARBITER, "status", String.class);
    private static final ColumnField<ArbiterRow, InsArbiterEntity, String> ARBITER_NAME =
            ColumnField.of(ArbiterRow.class, ARBITER, "name", String.class);
    private static final InsertColumns<ArbiterRow, InsArbiterEntity> ARBITER_COLUMNS =
            InsertColumns.<ArbiterRow, InsArbiterEntity>of(ARBITER).addKey(ARBITER_ID, ArbiterRow::id)
                    .add(ARBITER_CODE, ArbiterRow::code).add(ARBITER_SKU, ArbiterRow::sku)
                    .add(ARBITER_STATUS, ArbiterRow::status).add(ARBITER_NAME, ArbiterRow::name);
    private static final List<ArbiterRow> ARBITER_ROWS = List.of(new ArbiterRow(3L, "k3", "s1", "OPEN", "X1"),
            new ArbiterRow(4L, "k4", "s2", "OPEN", "X2"));

    // ---- AC-COL-33 (vnd.SequenceNextvalTest), AC-WRT-46 (vnd.SequenceWriteTest)

    record Numbered(Long id, Long next) {}

    private static final TableField<ConstantRowEntity, ConstantRowEntity> CONSTANTS =
            TableField.root(ConstantRowEntity.class);
    private static final ColumnField<Numbered, ConstantRowEntity, Long> CONSTANT_ID =
            ColumnField.of(Numbered.class, CONSTANTS, "id", Long.class);
    private static final ExpressionField<Numbered, Long> NEXT = Expr.nextval("tck_seq");
    private static final ExpressionField<CounterRow, Long> COUNTER_SEQUENCE = Expr.nextval("tck_seq");

    record SourceItem(Long id) {}

    private static final ColumnField<SourceItem, OrderItemEntity, Long> SOURCE_ID =
            ColumnField.of(SourceItem.class, ITEM_ROWS, "id", Long.class);

    // ---- AC-COL-34 (col.ToOneIdTest)

    record Referred(Long id, Long referrerId) {}

    record SkuLine(Long id, Long productId) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<Referred, OrderEntity, Long> ORDER_ID =
            ColumnField.of(Referred.class, ORDERS, "id", Long.class);
    private static final ColumnField<Referred, OrderEntity, Long> REFERRER =
            ColumnField.of(Referred.class, ORDERS, "referrer", Long.class);
    private static final ColumnField<Referred, OrderEntity, String> ORDER_STATUS =
            ColumnField.of(Referred.class, ORDERS, "status", String.class);
    private static final ModelQuery<OrderEntity, Long, Referred> REFERRED =
            ModelQuery.builder(ORDERS, row -> new Referred(row.get(ORDER_ID), row.get(REFERRER)))
                    .select(SelectSet.of(ORDER_ID, REFERRER)).primaryKey(PrimaryKey.of(ORDER_ID)).build();

    private static final TableField<SkuOrderLineEntity, SkuOrderLineEntity> SKU_LINES =
            TableField.root(SkuOrderLineEntity.class);
    private static final ColumnField<SkuLine, SkuOrderLineEntity, Long> SKU_LINE_ID =
            ColumnField.of(SkuLine.class, SKU_LINES, "id", Long.class);
    private static final ColumnField<SkuLine, SkuOrderLineEntity, Long> SKU_LINE_PRODUCT =
            ColumnField.of(SkuLine.class, SKU_LINES, "product", Long.class);
    private static final ColumnField<SkuLine, SkuOrderLineEntity, Integer> SKU_LINE_QUANTITY =
            ColumnField.of(SkuLine.class, SKU_LINES, "quantity", Integer.class);

    // ---- AC-VND-13 (lock.RowLockExecutionTest)

    record CardRow(Long id) {}

    private static final TableField<CardExtraEntity, CardExtraEntity> CARD_EXTRAS =
            TableField.root(CardExtraEntity.class);
    private static final ColumnField<CardRow, CardExtraEntity, Long> CARD_ID =
            ColumnField.of(CardRow.class, CARD_EXTRAS, "id", Long.class);

    /** Every strength x wait a lock can name (vendor/41 §6). */
    private static final List<RowLock> LOCKS = List.of(RowLock.exclusive(), RowLock.exclusive(Wait.NO_WAIT),
            RowLock.exclusive(Wait.SKIP_LOCKED), RowLock.shared(), RowLock.shared(Wait.NO_WAIT),
            RowLock.shared(Wait.SKIP_LOCKED));

    @Test
    void ac_ins_11_returning_writes_reproduce_ac_vnd_14_and_ac_wrt_42_43_and_match_the_returning_snapshot() {
        var update = ModelUpdate.builder(ITEM_ROWS).primaryKey(PrimaryKey.of(ROW_ID))
                .set(ROW_QUANTITY, 42).where(f -> f.eq(ROW_ID, 3L)).build();
        var delete = ModelDelete.builder(ITEM_ROWS).primaryKey(PrimaryKey.of(ROW_ID))
                .where(f -> f.eq(ROW_ID, 5L)).build();
        var plain = ValuesInsert.builder(COUNTER_COLUMNS, Long.class, List.of(new CounterRow(1L, 10L))).build();
        var upsert = ValuesInsert.builder(COUNTER_COLUMNS, Long.class, List.of(new CounterRow(2L, 1L)))
                .onConflict(COUNTER_ID).doUpdate(u -> u.setExpression(COUNTER_NEXT,
                        (stored, incoming, cb) -> cb.sum(stored, 1L))).anyUniqueKey().build();

        Results results = run(
                call("updateReturning", OrderItemEntity.class, ex -> ex.updateReturning(update, ITEM_ROW_RETURNING)),
                call("deleteReturning", OrderItemEntity.class, ex -> ex.deleteReturning(delete, ITEM_ROW_RETURNING)),
                call("insertReturning", CounterRowEntity.class, ex -> ex.insertReturning(plain, COUNTER_RETURNING)),
                call("upsertReturning", CounterRowEntity.class,
                        ex -> ex.insertReturning(upsert, COUNTER_VALUE_RETURNING)));

        List<String> labels = List.of("updateReturning", "deleteReturning", "insertReturning", "upsertReturning");
        for (int i = 0; i < labels.size(); i++) {
            results.matchesSnapshot(labels.get(i), H2, "returning", i);
            results.matchesSnapshot(labels.get(i), POSTGRESQL, "returning", i);
            results.refused(labels.get(i), MYSQL, MqCode.MQ4011);
        }
        assertThat(SqlSnapshots.expected(MYSQL, "returning")).as("MySQL's live run ran no statement").isEmpty();
    }

    @Test
    void ac_ins_11_a_conflict_set_expression_reproduces_ac_wrt_44_on_every_target() {
        var insert = ValuesInsert.builder(NAMED, Long.class, List.of(new Named(1L, "c1", "IN")))
                .onConflict(NAMED_ID).doUpdate(u -> u.set(NAMED_CODE, "x")
                        .setExpression(NAMED_NAME, (stored, incoming, cb) -> cb.concat(stored, incoming)))
                .anyUniqueKey().build();

        Results results = run(call("setExpression", InsAssignedEntity.class, ex -> ex.insert(insert)));

        for (DatabaseVendor vendor : VENDORS) {
            results.matchesSnapshot("setExpression", vendor, "wrt-44-conflict-set-expression", 0);
        }
    }

    @Test
    void ac_ins_11_an_arbiter_or_a_named_constraint_reproduces_ac_wrt_45_mq1804_on_h2_and_mysql() {
        var arbiter = ValuesInsert.builder(ARBITER_COLUMNS, Long.class, ARBITER_ROWS)
                .onConflict(ARBITER_SKU).where(f -> f.ne(ARBITER_STATUS, "CLOSED"))
                .doUpdate(u -> u.setFromRow(ARBITER_NAME)).build();
        var onConstraint = ValuesInsert.builder(ARBITER_COLUMNS, Long.class, List.of(
                        new ArbiterRow(3L, "k1", "sX", "OPEN", "X1"), new ArbiterRow(4L, "k4", "s4", "OPEN", "X2")))
                .onConstraint("ins_arbiter_code_key").doNothing().build();

        Results results = run(
                call("arbiter", InsArbiterEntity.class, ex -> ex.insert(arbiter)),
                call("onConstraint", InsArbiterEntity.class, ex -> ex.insert(onConstraint)));

        results.matchesSnapshot("arbiter", POSTGRESQL, "wrt-45-arbiter", 0);
        results.matchesSnapshot("onConstraint", POSTGRESQL, "wrt-45-on-constraint", 0);
        for (DatabaseVendor vendor : List.of(H2, MYSQL)) {
            results.refused("arbiter", vendor, MqCode.MQ1804).contains("takes no arbiter predicate");
            results.refused("onConstraint", vendor, MqCode.MQ1804).contains("takes no onConstraint");
        }
    }

    @Test
    void ac_ins_11_a_lock_the_provider_would_drop_reproduces_ac_vnd_13_mq4010_per_line() {
        List<DryCall> calls = new ArrayList<>();
        for (RowLock lock : LOCKS) {
            calls.add(call(lock.toString(), OrderEntity.class, ex -> ex.first(locked(lock))));
        }
        var cards = ModelQuery.builder(CARD_EXTRAS, row -> new CardRow(row.get(CARD_ID)))
                .select(SelectSet.of(CARD_ID)).primaryKey(PrimaryKey.of(CARD_ID)).build()
                .withLock(RowLock.exclusive());
        calls.add(call("twoTables", CardExtraEntity.class, ex -> ex.list(cards, Limit.unlimited())));

        Results results = run(calls.toArray(DryCall[]::new));

        for (DatabaseVendor vendor : VENDORS) {
            for (RowLock lock : LOCKS) {
                if (renderedOn(vendor, lock)) {
                    results.reached(lock.toString(), vendor);
                } else {
                    results.refused(lock.toString(), vendor, MqCode.MQ4010).contains("cannot lock " + lock + " on ")
                            .contains("because provider HibernateLockSupport would not render it");
                }
            }
            results.matchesSnapshot(RowLock.exclusive().toString(), vendor, "lock-exec-reads", 1);
            results.refused("twoTables", vendor, MqCode.MQ4010).contains("is mapped to more than one table");
        }
    }

    @Test
    void ac_ins_11_nextval_reproduces_ac_col_33_mq1509_on_mysql_and_matches_the_select_snapshot() {
        var selected = read().orderBy(CONSTANT_ID.asc()).build();
        var plusOne = Expr.plus(NEXT, 1L);
        var highest = Agg.max(NEXT);
        Map<String, ModelQuery<ConstantRowEntity, ?, Numbered>> refusedEverywhere = new LinkedHashMap<>();
        refusedEverywhere.put("distinct", read().customize((spec, joins, q, cb, phase) -> q.distinct(true)).build());
        refusedEverywhere.put("filter", read().where(f -> f.gt(NEXT, 0L)).build());
        refusedEverywhere.put("filterBySelected", ModelQuery.builder(CONSTANTS,
                row -> new Numbered(row.get(CONSTANT_ID), row.get(plusOne))).select(SelectSet.of(CONSTANT_ID, plusOne))
                .where(f -> f.gt(plusOne, 0L)).build());
        refusedEverywhere.put("order", read().orderBy(NEXT.asc()).build());
        refusedEverywhere.put("group", ModelQuery.builder(CONSTANTS, row -> new Numbered(row.get(CONSTANT_ID), null))
                .select(SelectSet.of(CONSTANT_ID)).groupBy(CONSTANT_ID, NEXT).build());
        refusedEverywhere.put("having", ModelQuery.builder(CONSTANTS,
                row -> new Numbered(row.get(CONSTANT_ID), row.get(highest))).select(SelectSet.of(CONSTANT_ID, highest))
                .groupBy(CONSTANT_ID).having(h -> h.gt(highest, 0L)).build());
        List<DryCall> calls = new ArrayList<>();
        calls.add(call("select", ConstantRowEntity.class, ex -> ex.list(selected, Limit.unlimited())));
        refusedEverywhere.forEach((label, query) ->
                calls.add(call(label, ConstantRowEntity.class, ex -> ex.list(query, Limit.unlimited()))));

        Results results = run(calls.toArray(DryCall[]::new));

        results.matchesSnapshot("select", H2, "col-33-select", 0);
        results.matchesSnapshot("select", POSTGRESQL, "col-33-select", 0);
        results.refused("select", MYSQL, MqCode.MQ1509).contains("no sequence support");
        for (DatabaseVendor vendor : VENDORS) {
            for (String label : refusedEverywhere.keySet()) {
                results.refused(label, vendor, MqCode.MQ1509);
            }
        }
    }

    @Test
    void ac_ins_11_every_sequence_write_site_reproduces_ac_wrt_46_mq1509_on_mysql_only() {
        var updated = ModelUpdate.builder(COUNTERS).primaryKey(PrimaryKey.of(COUNTER_ID))
                .setExpression(COUNTER_NEXT, COUNTER_SEQUENCE).all().chunked(ChunkOptions.size(2)).build();
        var values = ValuesInsert.builder(COUNTER_KEYS, Long.class, List.of(new CounterRow(1L, null)))
                .setExpression(COUNTER_NEXT, COUNTER_SEQUENCE).build();
        var upsert = ValuesInsert.builder(COUNTER_COLUMNS, Long.class, List.of(new CounterRow(1L, 1L)))
                .onConflict(COUNTER_ID).doUpdate(u -> u.setExpression(COUNTER_NEXT, COUNTER_SEQUENCE))
                .anyUniqueKey().build();
        var copy = ModelInsert.select(COUNTER_KEYS, ITEM_ROWS).map(COUNTER_ID, SOURCE_ID)
                .setExpression(COUNTER_NEXT, Expr.nextval("tck_seq")).all().chunked(ChunkOptions.size(2)).build();
        var copyByIds = ModelInsert.select(COUNTER_KEYS, ITEM_ROWS).map(COUNTER_ID, SOURCE_ID)
                .setExpression(COUNTER_NEXT, Expr.nextval("tck_seq")).where(f -> f.in(SOURCE_ID, List.of(3L, 5L)))
                .build();

        Results results = run(
                call("update", CounterRowEntity.class, ex -> ex.update(updated)),
                call("values", CounterRowEntity.class, ex -> ex.insert(values)),
                call("upsert", CounterRowEntity.class, ex -> ex.insert(upsert)),
                call("copy", CounterRowEntity.class, ex -> ex.insert(copy)),
                call("copyByIds", CounterRowEntity.class, ex -> ex.insert(copyByIds)));

        for (DatabaseVendor vendor : List.of(H2, POSTGRESQL)) {
            results.matchesSnapshot("update", vendor, "wrt-46-chunked-update", 1);
            results.matchesSnapshot("values", vendor, "wrt-46-insert-values", 2);
            results.reached("upsert", vendor);
            results.reached("copy", vendor);
            results.matchesSnapshot("copyByIds", vendor, "wrt-46-insert-select", 0);
        }
        for (String label : List.of("update", "values", "upsert", "copy", "copyByIds")) {
            results.refused(label, MYSQL, MqCode.MQ1509).contains("no sequence support");
        }
    }

    @Test
    void ac_ins_11_a_non_key_reference_reproduces_the_m22_mq1004_and_mq1809_rows_of_ac_col_34() {
        var marked = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ORDER_ID)).set(ORDER_STATUS, "MARKED")
                .where(f -> f.lt(ORDER_ID, 10L)).build();
        var byProduct = ModelUpdate.builder(SKU_LINES).primaryKey(PrimaryKey.of(SKU_LINE_ID))
                .set(SKU_LINE_QUANTITY, 9).where(f -> f.eq(SKU_LINE_PRODUCT, 1L));
        var byProductThroughEntities = byProduct.throughEntities().build();
        var byProductPlain = ModelUpdate.builder(SKU_LINES).primaryKey(PrimaryKey.of(SKU_LINE_ID))
                .set(SKU_LINE_QUANTITY, 9).where(f -> f.eq(SKU_LINE_PRODUCT, 1L)).build();
        var deleteUnreferenced = ModelDelete.builder(SKU_LINES).primaryKey(PrimaryKey.of(SKU_LINE_ID))
                .where(f -> f.isNull(SKU_LINE_PRODUCT)).build();
        var oneLine = ModelUpdate.builder(SKU_LINES).primaryKey(PrimaryKey.of(SKU_LINE_ID))
                .set(SKU_LINE_QUANTITY, 9).where(f -> f.eq(SKU_LINE_ID, 1L)).build();
        var productReturning = ModelQuery.builder(SKU_LINES, row -> new SkuLine(row.get(SKU_LINE_ID),
                row.get(SKU_LINE_PRODUCT))).select(SelectSet.of(SKU_LINE_ID, SKU_LINE_PRODUCT))
                .primaryKey(PrimaryKey.of(SKU_LINE_ID)).build();

        Results results = run(
                call("referrerReturning", OrderEntity.class, ex -> ex.updateReturning(marked, REFERRED)),
                call("update", SkuOrderLineEntity.class, ex -> ex.update(byProductPlain)),
                call("updateThroughEntities", SkuOrderLineEntity.class, ex -> ex.update(byProductThroughEntities)),
                call("delete", SkuOrderLineEntity.class, ex -> ex.delete(deleteUnreferenced)),
                call("productReturning", SkuOrderLineEntity.class,
                        ex -> ex.updateReturning(oneLine, productReturning)));

        results.matchesSnapshot("referrerReturning", H2, "col-34-returning", 0);
        results.matchesSnapshot("referrerReturning", POSTGRESQL, "col-34-returning", 0);
        results.refused("referrerReturning", MYSQL, MqCode.MQ4011);
        for (DatabaseVendor vendor : VENDORS) {
            for (String label : List.of("update", "updateThroughEntities", "delete")) {
                results.refused(label, vendor, MqCode.MQ1004).contains("MQ1004: SkuLine.product: ");
            }
            results.refused("productReturning", vendor, vendor == MYSQL ? MqCode.MQ4011 : MqCode.MQ1809);
        }
    }

    // ---- helpers

    private static ModelQuery.Builder<ConstantRowEntity, ?, Numbered> read() {
        return ModelQuery.builder(CONSTANTS, row -> new Numbered(row.get(CONSTANT_ID), row.get(NEXT)))
                .select(SelectSet.of(CONSTANT_ID, NEXT));
    }

    private static ModelQuery<OrderEntity, Long, OrderLines> locked(RowLock lock) {
        return QOrderLines.query().select(QOrderLines.ALL).orderBy(QOrderLines.ID.asc()).build().withLock(lock);
    }

    /** vendor/41 §6: H2 drops a shared lock's wait on both lines and an exclusive one's on Hibernate 6.6 only. */
    private static boolean renderedOn(DatabaseVendor vendor, RowLock lock) {
        return vendor != H2 || lock.waitPolicy() == Wait.WAIT
                || (hibernateMajor() >= 7 && lock.strength() == Strength.EXCLUSIVE);
    }

    private static int hibernateMajor() {
        String version = Version.getVersionString();
        return Integer.parseInt(version.substring(0, version.indexOf('.')));
    }

    private static <E> DryCall call(String label, Class<E> root, Function<ModelQueryExecutor<E>, ?> body) {
        return DryCall.of(label, root, body, false);
    }

    /** The TCK unit with the arbiter fixture, which its live test boots on its own factory. */
    private static OfflineUnit unit() {
        List<Class<?>> classes = Stream.concat(OfflineBootTest.TCK_UNIT.stream(),
                Stream.of(InsArbiterEntity.class.getName())).<Class<?>>map(DryRunConformanceTest::load).toList();
        return OfflineUnit.ofClasses(classes, List.of(), Map.of());
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Results run(DryCall... calls) {
        List<CallResult> all = DryRun.run(unit(), TARGETS, ModelQueryConfig.defaults(), List.of(calls));
        assertThat(all).hasSize(calls.length * TARGETS.size());
        return new Results(all);
    }

    /** One dry run's results, looked up by call label and vendor. */
    private record Results(List<CallResult> all) {

        CallResult of(String label, DatabaseVendor vendor) {
            return all.stream().filter(r -> r.label().equals(label) && r.target().vendor() == vendor).findFirst()
                    .orElseThrow(() -> new AssertionError("no result for " + label + " on " + vendor));
        }

        /** The first statement {@code label} reached on {@code vendor}, normalized as a snapshot holds it. */
        String reached(String label, DatabaseVendor vendor) {
            CallResult result = of(label, vendor);
            assertThat(result.outcome()).as("%s on %s: %s", label, vendor, result.detail()).isEqualTo(Outcome.REACHED);
            return SqlSnapshots.normalize(result.detail());
        }

        /** Asserts {@code label} reached on {@code vendor} the statement on {@code line} of snapshot {@code name}. */
        void matchesSnapshot(String label, DatabaseVendor vendor, String name, int line) {
            List<String> expected = SqlSnapshots.expected(vendor, name);
            assertThat(expected).as("snapshot %s for %s", name, vendor).hasSizeGreaterThan(line);
            assertThat(reached(label, vendor)).as("%s on %s against snapshot %s", label, vendor, name)
                    .isEqualTo(expected.get(line));
        }

        /** Asserts {@code label} was refused on {@code vendor} with {@code code}; returns the refusal's message. */
        AbstractStringAssert<?> refused(String label, DatabaseVendor vendor, MqCode code) {
            CallResult result = of(label, vendor);
            assertThat(result.outcome()).as("%s on %s: %s", label, vendor, result.detail()).isEqualTo(Outcome.REFUSED);
            return assertThat(result.detail()).as("%s on %s", label, vendor).startsWith(code.name() + ": ");
        }
    }
}
