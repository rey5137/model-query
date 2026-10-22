package com.rey.modelquery.tck.flt;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Filters;
import com.rey.modelquery.core.InsertColumns;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.KeysetTypeEntity;
import com.rey.modelquery.tck.col.OrderArchiveEntity;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.col.PlainOrderItemEntity;
import com.rey.modelquery.tck.harness.InlineValueHandling;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.hibernate.SessionFactory;

/**
 * Delete and insert inputs under {@code hibernate.criteria.value_handling_mode=inline}: a delete's filter values and
 * chosen keys, whole, chunked, through entities and inside the {@code EXISTS} a joined filter renders, and an
 * insert-select's source filter values, whole and chunked, bind as explicit parameters, so no input reaches the SQL
 * text, and each deletes or writes the rows it does under the default setting; an insert-values' typed operands and an
 * insert-select's {@code set} constants keep binding through their mapping, as they always did (spec api/12 R-FLT-08,
 * D-177; TCK AC-FLT-35's {@code delete-filter}, {@code insert-select-filter} and {@code typed-insert-binds}).
 */
class InlineValueWritesTest {

    record I(Long id) {}

    record Line(Long orderId, Long itemId) {}

    record ArchiveRow(Long id, Long orderId, String status) {}

    record TypedRow(Long id, Integer tie, BigDecimal amount, byte[] payload, Timestamp stamp, UUID token,
            KeysetTypeEntity.Shape shape) {}

    private static final TableField<PlainOrderItemEntity, PlainOrderItemEntity> ITEMS =
            TableField.root(PlainOrderItemEntity.class);
    private static final ColumnField<I, PlainOrderItemEntity, Long> ITEM_ID =
            ColumnField.of(I.class, ITEMS, "id", Long.class);
    private static final ColumnField<I, PlainOrderItemEntity, String> PRODUCT =
            ColumnField.of(I.class, ITEMS, "productCode", String.class);
    private static final ColumnField<I, PlainOrderItemEntity, Integer> QUANTITY =
            ColumnField.of(I.class, ITEMS, "quantity", Integer.class);

    private static final TableField<OrderItemEntity, OrderItemEntity> LINKED =
            TableField.root(OrderItemEntity.class);
    private static final TableField<OrderItemEntity, OrderEntity> LINKED_ORDER =
            TableField.join(LINKED, "order", JoinType.INNER);
    private static final ColumnField<I, OrderItemEntity, Long> LINKED_ID =
            ColumnField.of(I.class, LINKED, "id", Long.class);
    private static final ColumnField<I, OrderItemEntity, String> LINKED_PRODUCT =
            ColumnField.of(I.class, LINKED, "productCode", String.class);
    private static final ColumnField<I, OrderEntity, String> LINKED_STATUS =
            ColumnField.of(I.class, LINKED_ORDER, "status", String.class);

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER =
            TableField.join(ORDERS, "customer", JoinType.INNER);
    private static final TableField<OrderEntity, OrderItemEntity> ORDER_ITEMS =
            TableField.join(ORDERS, "items", JoinType.INNER);
    private static final ColumnField<Line, OrderEntity, Long> LINE_ORDER_ID =
            ColumnField.of(Line.class, ORDERS, "id", Long.class);
    private static final ColumnField<Line, OrderItemEntity, Long> LINE_ITEM_ID =
            ColumnField.of(Line.class, ORDER_ITEMS, "id", Long.class);
    private static final ColumnField<Line, OrderEntity, String> LINE_STATUS =
            ColumnField.of(Line.class, ORDERS, "status", String.class);
    private static final ColumnField<Line, CustomerEntity, String> LINE_COUNTRY =
            ColumnField.of(Line.class, CUSTOMER, "country", String.class);

    private static final TableField<OrderArchiveEntity, OrderArchiveEntity> ARCHIVE =
            TableField.root(OrderArchiveEntity.class);
    private static final ColumnField<ArchiveRow, OrderArchiveEntity, Long> ARCHIVE_ID =
            ColumnField.of(ArchiveRow.class, ARCHIVE, "id", Long.class);
    private static final ColumnField<ArchiveRow, OrderArchiveEntity, Long> ARCHIVE_ORDER_ID =
            ColumnField.of(ArchiveRow.class, ARCHIVE, "orderId", Long.class);
    private static final ColumnField<ArchiveRow, OrderArchiveEntity, String> ARCHIVE_STATUS =
            ColumnField.of(ArchiveRow.class, ARCHIVE, "status", String.class);
    private static final ColumnField<OrderArchiveEntity, OrderArchiveEntity, String> ARCHIVED_BY =
            ColumnField.of(OrderArchiveEntity.class, ARCHIVE, "archivedBy", String.class);
    private static final InsertColumns<ArchiveRow, OrderArchiveEntity> ARCHIVE_COLUMNS =
            InsertColumns.<ArchiveRow, OrderArchiveEntity>of(ARCHIVE)
                    .addKey(ARCHIVE_ID, ArchiveRow::id)
                    .add(ARCHIVE_ORDER_ID, ArchiveRow::orderId)
                    .add(ARCHIVE_STATUS, ArchiveRow::status);

    private static final TableField<KeysetTypeEntity, KeysetTypeEntity> TYPES =
            TableField.root(KeysetTypeEntity.class);

    /** The archived-by constant an insert-select writes, a bound column of its select (R-WRT-14). */
    private static final String ARCHIVER = "inline-archiver";

    /** One delete, the input values that must not reach the SQL text, and the entity it deletes through. */
    private record DeleteCase(String path, ModelDelete<?, ?> delete, Class<?> entity, List<Object> inputs) {}

    private static final List<DeleteCase> DELETES = List.of(
            new DeleteCase("delete-filter", ModelDelete.builder(ITEMS).primaryKey(PrimaryKey.of(ITEM_ID))
                    .where(f -> f.eq(PRODUCT, "P007").between(QUANTITY, 7, 8)).build(),
                    PlainOrderItemEntity.class, List.of("P007", 7, 8)),
            new DeleteCase("delete-filter keys", ModelDelete.builder(ITEMS).primaryKey(PrimaryKey.of(ITEM_ID))
                    .whereKeys(List.of(4243L, 4244L, 4293L, 4343L)).where(f -> f.ne(PRODUCT, "P043")).build(),
                    PlainOrderItemEntity.class, List.of(4243, 4244, 4293, 4343, "P043")),
            new DeleteCase("delete-filter chunked", ModelDelete.builder(ITEMS).primaryKey(PrimaryKey.of(ITEM_ID))
                    .where(f -> f.eq(PRODUCT, "P011").in(QUANTITY, List.of(6, 7, 8)))
                    .chunked(ChunkOptions.size(3)).build(),
                    PlainOrderItemEntity.class, List.of("P011", 6, 7, 8)),
            new DeleteCase("delete-filter through entities", ModelDelete.builder(ITEMS)
                    .primaryKey(PrimaryKey.of(ITEM_ID)).where(f -> f.eq(PRODUCT, "P019").gte(QUANTITY, 8))
                    .throughEntities().build(),
                    PlainOrderItemEntity.class, List.of("P019", 8)),
            new DeleteCase("delete-filter joined", ModelDelete.builder(LINKED).primaryKey(PrimaryKey.of(LINKED_ID))
                    .where(f -> f.eq(LINKED_STATUS, "PAID").eq(LINKED_PRODUCT, "P023")).build(),
                    OrderItemEntity.class, List.of("PAID", "P023")));

    /** One insert-select's source filter and the input values that must not reach the SQL text. */
    private record SelectCase(String path, UnaryOperator<ModelInsert.SelectOptions<OrderArchiveEntity, ArchiveRow>>
            options, List<Object> inputs) {}

    private static final List<SelectCase> SELECTS = List.of(
            new SelectCase("insert-select-filter", options -> options, List.of("PAID", "DE", 101, ARCHIVER)),
            new SelectCase("insert-select-filter chunked", options -> options.chunked(ChunkOptions.size(5)),
                    List.of("PAID", "DE", 101, ARCHIVER)));

    // ---- AC-FLT-35: delete-filter

    @TckTest
    void ac_flt_35_delete_filters_and_keys_bind_and_delete_the_default_settings_rows(TckDatabase db) {
        List<Written> defaults = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            DELETES.forEach(c -> defaults.add(deleted(sf, c, null)));
        }
        // Each default run deletes rows, so matching them is no accident of an empty delete.
        assertThat(defaults).allSatisfy(written -> assertThat(written.count()).isPositive());
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            for (int i = 0; i < DELETES.size(); i++) {
                DeleteCase c = DELETES.get(i);
                assertThat(deleted(inline.factory(), c, inline)).as(c.path()).isEqualTo(defaults.get(i));
                assertBound(inline, c.path(), "delete");
            }
        }
    }

    // ---- AC-FLT-35: insert-select-filter, typed-insert-binds

    @TckTest
    void ac_flt_35_insert_select_filters_bind_and_write_the_default_settings_rows(TckDatabase db) {
        List<Written> defaults = new ArrayList<>();
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            SELECTS.forEach(c -> defaults.add(archived(sf, c, null)));
        }
        assertThat(defaults).allSatisfy(written -> assertThat(written.count()).isPositive());
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            for (int i = 0; i < SELECTS.size(); i++) {
                SelectCase c = SELECTS.get(i);
                assertThat(archived(inline.factory(), c, inline)).as(c.path()).isEqualTo(defaults.get(i));
                assertBound(inline, c.path(), "insert");
            }
        }
    }

    @TckTest
    void ac_flt_35_typed_insert_values_keep_binding_through_their_mapping(TckDatabase db) {
        UUID token = new UUID(0, 0xabcd);
        var row = new TypedRow(9101L, 4321, new BigDecimal("1.5000"), new byte[] {1, 2},
                Timestamp.valueOf("2031-07-14 13:57:21"), token, new KeysetTypeEntity.Shape("inline-circle"));
        Written defaults;
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            defaults = typedInsert(sf, row, null);
        }
        assertThat(defaults.state()).containsExactly(token + "|inline-circle");
        try (InlineValueHandling inline = InlineValueHandling.open(db)) {
            assertThat(typedInsert(inline.factory(), row, inline)).isEqualTo(defaults);
            assertBound(inline, "typed-insert-binds", "insert");
        }
    }

    // ---- helpers

    /** A write's count and the state it left, read in its own rolled-back transaction. */
    private record Written(long count, List<String> state) {}

    /** {@code c} run in a rolled-back transaction; {@code inline}, when given, watches its inputs. */
    private static Written deleted(SessionFactory sf, DeleteCase c, InlineValueHandling inline) {
        return inRolledBack(sf, em -> {
            if (inline != null) {
                inline.watch(c.inputs(), List.of());
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            long count = ModelQueryExecutor.create(em, (Class) c.entity(), ModelQueryConfig.defaults())
                    .delete((ModelDelete) c.delete());
            return new Written(count, rows(em, "select count(*), sum(id), sum(quantity) from order_items"));
        });
    }

    /** The paid lines of German customers' orders below 101 archived, {@code c}'s options applied, rolled back. */
    private static Written archived(SessionFactory sf, SelectCase c, InlineValueHandling inline) {
        UnaryOperator<Filters<Line>> where =
                f -> f.eq(LINE_STATUS, "PAID").eq(LINE_COUNTRY, "DE").lt(LINE_ORDER_ID, 101L);
        var insert = c.options().apply(ModelInsert.select(ARCHIVE_COLUMNS, ORDERS)
                .map(ARCHIVE_ID, LINE_ITEM_ID)
                .map(ARCHIVE_ORDER_ID, LINE_ORDER_ID)
                .map(ARCHIVE_STATUS, LINE_STATUS)
                .set(ARCHIVED_BY, ARCHIVER)
                .where(where)).build();
        return inRolledBack(sf, em -> {
            if (inline != null) {
                inline.watch(c.inputs(), List.of());
            }
            long count = ModelQueryExecutor.create(em, OrderArchiveEntity.class, ModelQueryConfig.defaults())
                    .insert(insert);
            return new Written(count, rows(em, "select id, order_id, status, archived_by from order_archive"
                    + " order by id"));
        });
    }

    /** {@code row} inserted into {@code keyset_types}, rolled back: its stored token and shape. */
    private static Written typedInsert(SessionFactory sf, TypedRow row, InlineValueHandling inline) {
        var columns = InsertColumns.<TypedRow, KeysetTypeEntity>of(TYPES)
                .addKey(ColumnField.of(TypedRow.class, TYPES, "id", Long.class), TypedRow::id)
                .add(ColumnField.of(TypedRow.class, TYPES, "tie", Integer.class), TypedRow::tie)
                .add(ColumnField.of(TypedRow.class, TYPES, "amount", BigDecimal.class), TypedRow::amount)
                .add(ColumnField.of(TypedRow.class, TYPES, "stamp", Timestamp.class), TypedRow::stamp)
                .add(ColumnField.of(TypedRow.class, TYPES, "payload", byte[].class), TypedRow::payload)
                .add(ColumnField.of(TypedRow.class, TYPES, "token", UUID.class), TypedRow::token)
                .add(ColumnField.of(TypedRow.class, TYPES, "shape", KeysetTypeEntity.Shape.class), TypedRow::shape);
        var insert = ValuesInsert.builder(columns, Long.class, List.of(row)).build();
        return inRolledBack(sf, em -> {
            if (inline != null) {
                inline.watch(List.of(row.id(), row.tie(), row.amount(), row.token().toString(), "13:57:21",
                        row.shape().name()), List.of());
            }
            long count = ModelQueryExecutor.create(em, KeysetTypeEntity.class, ModelQueryConfig.defaults())
                    .insert(insert);
            Object[] stored = (Object[]) em.createNativeQuery("select token, shape from keyset_types where id = ?1")
                    .setParameter(1, row.id()).getSingleResult();
            return new Written(count, List.of(stored[0] + "|" + stored[1]));
        });
    }

    /**
     * The write ran since the last {@code watch}, each of its {@code kind} statements binding a value; the inspector
     * has already failed any statement that carried an input as a literal.
     */
    private static void assertBound(InlineValueHandling inline, String path, String kind) {
        assertThat(inline.statements()).as(path).filteredOn(sql -> sql.startsWith(kind)).isNotEmpty()
                .allSatisfy(sql -> assertThat(sql).as(path).contains("?"));
    }

    private static List<String> rows(EntityManager em, String sql) {
        List<?> rows = em.createNativeQuery(sql).getResultList();
        return rows.stream().map(row -> row instanceof Object[] columns
                ? String.join("|", Arrays.stream(columns).map(String::valueOf).toList()) : String.valueOf(row))
                .toList();
    }

    private static Written inRolledBack(SessionFactory sf, Function<EntityManager, Written> work) {
        return sf.fromSession(session -> {
            session.getTransaction().begin();
            try {
                return work.apply(session);
            } finally {
                session.getTransaction().rollback();
            }
        });
    }
}
