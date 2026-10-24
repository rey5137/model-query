package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.col.OrderItemEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import jakarta.persistence.criteria.JoinType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/** Request-bound join filters on entity joins and LEFT to-one joins, on every Tier-1 target (R-COL-39). */
class JoinFilterExecutionTest {

    static final class Keys {}

    record Detail(Long id, String joined) {}

    private static final String ENTITY_LIMIT = "Customer 1000";
    private static final String TO_ONE_LIMIT = "Customer 0500";
    private static final String STATIC_LIMIT = "Customer 0500";
    private static final String WINDOW = "o.id between 990 and 1030";

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<Keys, OrderEntity, Long> S_ID =
            ColumnField.of(Keys.class, ORDERS, "id", Long.class).named("id");
    private static final OrderedColumnField<Detail, OrderEntity, Long> ID =
            ColumnField.of(Detail.class, ORDERS, "id", Long.class).named("id");
    private static final SourceJoin<Detail, Keys> SOURCE = SourceJoin.of("keys", Keys.class, JoinKind.LEFT,
            List.of(SourceJoin.on(ID, S_ID)));
    private static final SourceQuery<Keys> KEYS = SourceQuery.builder(Keys.class, ORDERS, SelectSet.of(S_ID),
            PrimaryKey.of(S_ID)).where(f -> f.gte(S_ID, 990L).lte(S_ID, 1020L)).build();
    private static final EntityJoin<Detail, CustomerEntity> MANAGER = EntityJoin.of("manager", CustomerEntity.class,
            JoinKind.LEFT, List.of(EntityJoin.on(ColumnField.over(Detail.class, SOURCE.root(), S_ID), "id")));
    private static final ColumnField<Detail, CustomerEntity, String> MANAGER_NAME =
            ColumnField.of(Detail.class, MANAGER.root(), "name", String.class).named("manager");

    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER =
            TableField.join(ORDERS, "customer", JoinType.LEFT);
    private static final ColumnField<Detail, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(Detail.class, CUSTOMER, "name", String.class).named("customer");
    private static final ColumnField<Detail, OrderEntity, Long> CUSTOMER_FK =
            ColumnField.of(Detail.class, ORDERS, "customer", Long.class).named("customerFk");

    private static final TableField<OrderEntity, OrderItemEntity> ITEMS =
            TableField.join(ORDERS, "items", JoinType.INNER);
    private static final ColumnField<Detail, OrderItemEntity, String> ITEM_CODE =
            ColumnField.of(Detail.class, ITEMS, "productCode", String.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER_INNER =
            TableField.join(ORDERS, "customer", JoinType.INNER);
    private static final ColumnField<Detail, CustomerEntity, String> CUSTOMER_INNER_NAME =
            ColumnField.of(Detail.class, CUSTOMER_INNER, "name", String.class);

    private static final TableField<OrderEntity, CustomerEntity> VIP =
            TableField.<OrderEntity, CustomerEntity>join(ORDERS, "referrer", JoinType.LEFT).as("vipReferrer")
                    .on((from, cb) -> cb.isTrue(from.get("vip")));
    private static final ColumnField<Detail, CustomerEntity, String> VIP_NAME =
            ColumnField.of(Detail.class, VIP, "name", String.class).named("vip");

    private static ModelQuery.Builder<OrderEntity, Long, Detail> entity() {
        return ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), row.get(MANAGER_NAME)))
                .joinSource(SOURCE, KEYS).joinEntity(MANAGER).select(SelectSet.of(ID, MANAGER_NAME))
                .primaryKey(PrimaryKey.of(ID)).where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
    }

    private static ModelQuery.Builder<OrderEntity, Long, Detail> toOne() {
        return ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), row.get(CUSTOMER_NAME)))
                .select(SelectSet.of(ID, CUSTOMER_NAME)).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
    }

    private static ModelQuery.Builder<OrderEntity, Long, Detail> staticOn() {
        return ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), row.get(VIP_NAME)))
                .select(SelectSet.of(ID, VIP_NAME)).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
    }

    private static final String ENTITY_SQL = """
            select o.id, m.name from orders o
            left join (select id from orders where id between 990 and 1020) k on k.id = o.id
            left join customers m on m.id = k.id %s
            where %s %s order by o.id""";

    private static List<String> entityRows(TckDatabase db, String on, String where) throws SQLException {
        return SourceJoinExecutionTest.rows(db, ENTITY_SQL.formatted(on, WINDOW, where));
    }

    private static List<String> toOneRows(TckDatabase db, String on, String where) throws SQLException {
        return SourceJoinExecutionTest.rows(db, """
                select o.id, c.name from orders o left join customers c on c.id = o.customer_id %s
                where %s %s order by o.id""".formatted(on, WINDOW, where));
    }

    private static List<String> staticRows(TckDatabase db, String on, String where) throws SQLException {
        return SourceJoinExecutionTest.rows(db, """
                select o.id, r.name from orders o
                left join customers r on r.id = o.referrer_id and r.vip = true %s
                where %s %s order by o.id""".formatted(on, WINDOW, where));
    }

    @TckTest
    void ac_col_54_entity_join_filter_blanks_the_joined_columns_and_keeps_every_row(TckDatabase db)
            throws SQLException {
        var expected = entityRows(db, "and m.name < '" + ENTITY_LIMIT + "'", "");
        var unfiltered = entityRows(db, "", "");
        assertThat(expected).hasSize(41).isNotEqualTo(unfiltered);
        assertThat(expected.stream().filter(row -> !row.endsWith("|null"))).hasSize(10);
        List<String> sql = everyPhase(db, entity().joinFilter(MANAGER, f -> f.lt(MANAGER_NAME, ENTITY_LIMIT)),
                expected);
        assertThat(sql).hasSizeGreaterThan(10).allSatisfy(statement -> {
            assertThat(SourceJoinExecutionTest.occurrences(statement, "left join customers")).as(statement)
                    .isEqualTo(1);
            assertThat(statement.substring(statement.indexOf("left join customers")))
                    .as(statement)
                    .containsPattern("\\bon\\s*\\w+\\.id\\s*=\\s*\\w+\\.\\w+\\s+and\\s+\\w+\\.name\\s*<\\s*\\?");
        });
    }

    @TckTest
    void ac_col_54_to_one_join_filter_blanks_the_joined_columns_and_keeps_every_row(TckDatabase db)
            throws SQLException {
        var expected = toOneRows(db, "and c.name < '" + TO_ONE_LIMIT + "'", "");
        assertThat(expected).hasSize(41).isNotEqualTo(toOneRows(db, "", ""));
        assertThat(expected.stream().filter(row -> row.endsWith("|null"))).isNotEmpty();
        List<String> sql = everyPhase(db, toOne().joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT)),
                expected);
        assertThat(sql).hasSizeGreaterThan(10).allSatisfy(statement -> {
            assertThat(SourceJoinExecutionTest.occurrences(statement, "left join customers")).as(statement)
                    .isEqualTo(1);
            assertThat(statement.substring(statement.indexOf("left join customers")))
                    .as(statement).containsPattern("and\\s+\\w+\\.name\\s*<\\s*\\?");
        });
    }

    @TckTest
    void ac_col_55_filter_ands_with_the_static_on_in_one_join(TckDatabase db) throws SQLException {
        var expected = staticRows(db, "and r.name < '" + STATIC_LIMIT + "'", "");
        var staticOnly = staticRows(db, "", "");
        assertThat(expected).hasSize(41).isNotEqualTo(staticOnly);
        assertThat(staticOnly.stream().filter(row -> !row.endsWith("|null"))).isNotEmpty();
        List<String> sql = everyPhase(db, staticOn().joinFilter(VIP, f -> f.lt(VIP_NAME, STATIC_LIMIT)), expected);
        assertThat(sql).allSatisfy(statement -> {
            assertThat(SourceJoinExecutionTest.occurrences(statement, "left join customers")).as(statement)
                    .isEqualTo(1);
            assertThat(statement.substring(statement.indexOf("left join customers")))
                    .as(statement).containsPattern("\\.vip\\b[^?]*\\sand\\s+\\w+\\.name\\s*<\\s*\\?");
        });
    }

    @TckTest
    void ac_col_55_skipped_filters_add_nothing(TckDatabase db) throws SQLException {
        Optional<String> none = Optional.empty();
        for (var pair : List.of(
                List.of(entity(), entity().joinFilter(MANAGER, f -> f.lt(MANAGER_NAME, none))),
                List.of(toOne(), toOne().joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, none))),
                List.of(staticOn(), staticOn().joinFilter(VIP, f -> f.lt(VIP_NAME, none).eq(VIP_NAME, none))))) {
            var plain = pair.get(0).build();
            var skipped = pair.get(1).build();
            List<Detail> plainRows = new ArrayList<>();
            List<Detail> skippedRows = new ArrayList<>();
            var plainSql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
                plainRows.addAll(x.list(plain, Limit.unlimited()));
                assertThat(x.count(plain)).isEqualTo(41L);
            }));
            var skippedSql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
                skippedRows.addAll(x.list(skipped, Limit.unlimited()));
                assertThat(x.count(skipped)).isEqualTo(41L);
            }));
            assertThat(skippedRows).hasSize(41).isEqualTo(plainRows);
            assertThat(skippedSql).isEqualTo(plainSql);
        }
    }

    @TckTest
    void ac_col_55_where_filter_on_a_blanked_column_drops_the_row(TckDatabase db) throws SQLException {
        var entityKept = entityRows(db, "and m.name < '" + ENTITY_LIMIT + "'", "and m.name is not null");
        assertThat(entityKept).hasSize(10);
        everyPhase(db, entity().joinFilter(MANAGER, f -> f.lt(MANAGER_NAME, ENTITY_LIMIT))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L).isNotNull(MANAGER_NAME)), entityKept);
        var toOneKept = toOneRows(db, "and c.name < '" + TO_ONE_LIMIT + "'", "and c.name is not null");
        assertThat(toOneKept).hasSizeBetween(8, 40);
        everyPhase(db, toOne().joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L).isNotNull(CUSTOMER_NAME)), toOneKept);
    }

    @TckTest
    void ac_col_55_semi_joined_where_reads_the_filtered_join_like_the_outer_statement(TckDatabase db)
            throws SQLException {
        // The to-many term moves every term that joins into one EXISTS over a second root (R-EXE-16, D-181).
        String items = " and exists (select 1 from order_items i where i.order_id = o.id and i.product_code < 'P025')";
        var kept = toOneRows(db, "and c.name < '" + TO_ONE_LIMIT + "'", "and c.name is not null" + items);
        var unfiltered = toOneRows(db, "", "and c.name is not null" + items);
        assertThat(kept).isNotEmpty().isNotEqualTo(unfiltered);
        assertThat(kept.stream().filter(row -> row.endsWith("|null"))).isEmpty();
        List<String> sql = everyPhase(db, toOne().joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L).lt(ITEM_CODE, "P025").isNotNull(CUSTOMER_NAME)), kept);
        assertThat(sql).anySatisfy(statement -> assertThat(statement.toLowerCase(java.util.Locale.ROOT))
                .containsPattern("exists\\s*\\(select[^)]*left join customers"));
        // The filtered join read only inside the sub-query still counts as applied, and still blanks the name.
        var idsOnly = ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), null)).select(SelectSet.of(ID))
                .primaryKey(PrimaryKey.of(ID)).orderBy(ID.asc())
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L).lt(ITEM_CODE, "P025").isNotNull(CUSTOMER_NAME))
                .joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT));
        everyPhase(db, idsOnly, kept.stream().map(row -> row.substring(0, row.indexOf('|')) + "|null").toList());
    }

    @TckTest
    void ac_col_56_inner_key_resolving_to_the_filtered_join_inside_or_is_refused(TckDatabase db) {
        var filter = toOne().select(SelectSet.of(ID));
        var innerFirst = filter.joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT))
                .where(f -> f.or(g -> g.eq(CUSTOMER_INNER_NAME, "Customer 0001"),
                        g -> g.eq(CUSTOMER_NAME, "Customer 0002"))).build();
        var filteredFirst = filter.joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT))
                .where(f -> f.or(g -> g.eq(CUSTOMER_NAME, "Customer 0002"),
                        g -> g.eq(CUSTOMER_INNER_NAME, "Customer 0001"))).build();
        var sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            for (var query : List.of(innerFirst, filteredFirst)) {
                mq1912(() -> x.list(query, Limit.unlimited()));
                mq1912(() -> x.count(query));
            }
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_col_56_id_form_column_reading_the_filtered_join_is_refused_before_any_statement(TckDatabase db) {
        var read = ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), row.get(CUSTOMER_NAME)))
                .select(SelectSet.of(ID, CUSTOMER_NAME, CUSTOMER_FK)).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
        var plain = read.build();
        var filtered = read.joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT)).build();
        var sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            assertThat(x.list(plain, Limit.unlimited())).hasSize(41);
            x.count(plain);
        }));
        assertThat(sql).isNotEmpty();
        var refused = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            mq1912(() -> x.list(filtered, Limit.unlimited()));
            mq1912(() -> x.count(filtered));
            mq1912(() -> x.page(filtered, PageSpec.of(0, 7), CountMode.COUNT));
        }));
        assertThat(refused).isEmpty();
    }

    @TckTest
    void ac_col_56_ac_col_57_filter_on_a_join_the_content_never_creates_is_refused_and_creates_none(
            TckDatabase db) {
        var alias = CUSTOMER.as("other");
        var aliasName = ColumnField.of(Detail.class, alias, "name", String.class);
        var inner = TableField.<OrderEntity, CustomerEntity>join(ORDERS, "customer", JoinType.INNER);
        var innerName = ColumnField.of(Detail.class, inner, "name", String.class).named("customer");
        var unregistered = EntityJoin.of("elsewhere", CustomerEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(ColumnField.over(Detail.class, SOURCE.root(), S_ID), "id")));
        var unregisteredName = ColumnField.of(Detail.class, unregistered.root(), "name", String.class);
        var idsOnly = ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), null)).select(SelectSet.of(ID))
                .primaryKey(PrimaryKey.of(ID)).where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
        List<ModelQuery<OrderEntity, Long, Detail>> refused = List.of(
                idsOnly.joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT)).build(),
                toOne().joinFilter(alias, f -> f.lt(aliasName, TO_ONE_LIMIT)).build(),
                toOne().select(SelectSet.of(ID, innerName))
                        .joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT)).build());
        mq1912(() -> entity().joinFilter(unregistered, f -> f.lt(unregisteredName, ENTITY_LIMIT)).build());
        var sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            for (var query : refused) {
                mq1912(() -> x.list(query, Limit.unlimited()));
                mq1912(() -> x.count(query));
            }
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_col_56_a_count_only_first_call_still_gets_the_first_resolution_refusal(TckDatabase db) {
        var idsOnly = ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), null)).select(SelectSet.of(ID))
                .primaryKey(PrimaryKey.of(ID)).where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
        var idForm = ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), row.get(CUSTOMER_NAME)))
                .select(SelectSet.of(ID, CUSTOMER_NAME, CUSTOMER_FK)).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
        var unused = idsOnly.joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT)).build();
        var read = idForm.joinFilter(CUSTOMER, f -> f.lt(CUSTOMER_NAME, TO_ONE_LIMIT)).build();
        var sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            // The first call of each definition is a count, with no content call before it (D-194).
            mq1912(() -> x.count(unused));
            mq1912(() -> x.count(read));
        }));
        assertThat(sql).isEmpty();
    }

    record ItemRow(Long id, String orderStatus) {}

    @TckTest
    void ac_col_58_an_inner_declared_join_rendered_left_inside_or_below_a_filtered_join_keeps_the_row(
            TckDatabase db) throws SQLException {
        var root = TableField.root(OrderItemEntity.class);
        var id = ColumnField.of(ItemRow.class, root, "id", Long.class).named("id");
        var order = TableField.<OrderItemEntity, OrderEntity>join(root, "order", JoinType.LEFT);
        var status = ColumnField.of(ItemRow.class, order, "status", String.class).named("status");
        var customer = TableField.<OrderEntity, CustomerEntity>join(order, "customer", JoinType.INNER);
        var customerName = ColumnField.of(ItemRow.class, customer, "name", String.class);
        var query = ModelQuery.builder(root, row -> new ItemRow(row.get(id), row.get(status)))
                .select(SelectSet.of(id, status)).primaryKey(PrimaryKey.of(id))
                .where(f -> f.gte(id, 1L).lte(id, 40L)
                        .or(g -> g.eq(customerName, "Customer 0001"), g -> g.gte(id, 0L)))
                .orderBy(id.asc()).joinFilter(order, f -> f.eq(status, "PAID")).build();
        var expected = SourceJoinExecutionTest.rows(db, """
                select i.id, o.status from order_items i
                left join orders o on o.id = i.order_id and o.status = 'PAID'
                left join customers c on c.id = o.customer_id
                where i.id between 1 and 40 and (c.name = 'Customer 0001' or i.id >= 0) order by i.id""");
        assertThat(expected).hasSize(40).anyMatch(row -> row.endsWith("|null"))
                .anyMatch(row -> row.endsWith("|PAID"));
        List<ItemRow> rows = new ArrayList<>();
        try (var sessions = JoinTestSupport.sessionFactory(JoinTestSupport.dataSource(db))) {
            sessions.inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderItemEntity.class,
                    ModelQueryConfig.defaults()).list(query, Limit.unlimited())));
        }
        assertThat(rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.orderStatus())))
                .containsExactlyElementsOf(expected);
    }

    private static final EntityJoin<Detail, OrderEntity> COPY = EntityJoin.of("copy", OrderEntity.class,
            JoinKind.LEFT, List.of(EntityJoin.on(ColumnField.over(Detail.class, SOURCE.root(), S_ID), "id")));
    private static final ColumnField<Detail, OrderEntity, String> COPY_STATUS =
            ColumnField.of(Detail.class, COPY.root(), "status", String.class).named("copyStatus");

    private static ModelQuery.Builder<OrderEntity, Long, Detail> belowCopy(JoinType type) {
        var customer = TableField.<OrderEntity, CustomerEntity>join(COPY.root(), "customer", type);
        var name = ColumnField.of(Detail.class, customer, "name", String.class).named("copyCustomer");
        return ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), row.get(name)))
                .joinSource(SOURCE, KEYS).joinEntity(COPY).select(SelectSet.of(ID, COPY_STATUS, name))
                .primaryKey(PrimaryKey.of(ID)).where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc())
                .joinFilter(COPY, f -> f.eq(COPY_STATUS, "PAID"));
    }

    @TckTest
    void ac_col_58_an_inner_to_one_below_a_filtered_entity_join_is_refused_and_a_left_one_keeps_the_row(
            TckDatabase db) throws SQLException {
        var inner = belowCopy(JoinType.INNER).build();
        var refused = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> assertThatThrownBy(
                () -> x.list(inner, Limit.unlimited())).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(MqCode.MQ1912);
                            assertThat(e.getMessage()).contains("declare it LEFT");
                        })));
        assertThat(refused).isEmpty();
        var expected = SourceJoinExecutionTest.rows(db, """
                select o.id, c.name from orders o
                left join (select id from orders where id between 990 and 1020) k on k.id = o.id
                left join orders cp on cp.id = k.id and cp.status = 'PAID'
                left join customers c on c.id = cp.customer_id
                where o.id between 990 and 1030 order by o.id""");
        assertThat(expected).hasSize(41).anyMatch(row -> row.endsWith("|null"))
                .anyMatch(row -> !row.endsWith("|null"));
        var left = belowCopy(JoinType.LEFT).build();
        List<Detail> rows = new ArrayList<>();
        try (var sessions = JoinTestSupport.sessionFactory(JoinTestSupport.dataSource(db))) {
            sessions.inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults()).list(left, Limit.unlimited())));
        }
        assertThat(texts(rows)).containsExactlyElementsOf(expected);
    }

    record PresentRow(Long id, boolean present, String name) {}

    private static final TableField<CustomerEntity, CustomerEntity> NESTED_ROOT =
            TableField.root(CustomerEntity.class);
    private static final ColumnField<PresentRow, CustomerEntity, Long> NESTED_ID =
            ColumnField.of(PresentRow.class, NESTED_ROOT, "id", Long.class);
    private static final ColumnField<PresentRow, CustomerEntity, String> NESTED_NAME =
            ColumnField.of(PresentRow.class, NESTED_ROOT, "name", String.class);
    private static final TableField<OrderEntity, CustomerEntity> NESTED =
            TableField.<OrderEntity, CustomerEntity>join(ORDERS, "customer", JoinType.LEFT)
                    .presentBy(PrimaryKey.of(NESTED_ID));
    private static final ColumnField<PresentRow, OrderEntity, Long> PRESENT_ID =
            ColumnField.of(PresentRow.class, ORDERS, "id", Long.class).named("id");
    private static final ColumnField<PresentRow, CustomerEntity, String> PRESENT_NAME =
            NESTED_NAME.withTable(PresentRow.class, NESTED);

    private static ModelQuery.Builder<OrderEntity, Long, PresentRow> nested() {
        return ModelQuery.builder(ORDERS, row -> {
            var customer = row.scoped(NESTED);
            return new PresentRow(row.get(PRESENT_ID), customer.get(NESTED_ID) != null, customer.get(NESTED_NAME));
        }).select(SelectSet.of(PRESENT_ID, PRESENT_NAME)).primaryKey(PrimaryKey.of(PRESENT_ID))
                .where(f -> f.gte(PRESENT_ID, 990L).lte(PRESENT_ID, 1030L)).orderBy(PRESENT_ID.asc());
    }

    @TckTest
    void ac_col_54_a_present_by_model_over_a_filtered_join_is_absent_when_the_filter_fails(TckDatabase db)
            throws SQLException {
        String failing = "Customer 0500";
        var failed = nested().joinFilter(NESTED, f -> f.lt(PRESENT_NAME, failing)).build();
        var passed = nested().joinFilter(NESTED, f -> f.lt(PRESENT_NAME, "Customer 9999")).build();
        List<PresentRow> failedRows = new ArrayList<>();
        List<PresentRow> passedRows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            failedRows.addAll(x.list(failed, Limit.unlimited()));
            passedRows.addAll(x.list(passed, Limit.unlimited()));
        }));
        var expectedFailed = SourceJoinExecutionTest.rows(db, """
                select o.id, case when c.id is null then 'absent' else 'present' end, c.name from orders o
                left join customers c on c.id = o.customer_id and c.name < '%s'
                where %s order by o.id""".formatted(failing, WINDOW));
        var expectedPassed = SourceJoinExecutionTest.rows(db, """
                select o.id, case when c.id is null then 'absent' else 'present' end, c.name from orders o
                left join customers c on c.id = o.customer_id and c.name < 'Customer 9999'
                where %s order by o.id""".formatted(WINDOW));
        assertThat(failedRows).hasSize(41);
        assertThat(failedRows).filteredOn(row -> !row.present()).isNotEmpty()
                .allSatisfy(row -> assertThat(row.name()).isNull());
        assertThat(failedRows).filteredOn(PresentRow::present).isNotEmpty()
                .allSatisfy(row -> assertThat(row.name()).isNotNull());
        assertThat(passedRows).filteredOn(PresentRow::present).hasSizeGreaterThan(
                (int) failedRows.stream().filter(PresentRow::present).count());
        assertThat(describe(failedRows)).containsExactlyElementsOf(expectedFailed);
        assertThat(describe(passedRows)).containsExactlyElementsOf(expectedPassed);
    }

    private static List<String> describe(List<PresentRow> rows) {
        return rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.present() ? "present" : "absent",
                r.name())).toList();
    }

    @TckTest
    void ac_col_57_static_constants_are_unchanged_after_a_filtered_build(TckDatabase db) throws SQLException {
        var plain = staticOn().build();
        var before = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> x.list(plain, Limit.unlimited())));
        var staticOnly = staticRows(db, "", "");
        var filtered = everyPhase(db, staticOn().joinFilter(VIP, f -> f.lt(VIP_NAME, STATIC_LIMIT)),
                staticRows(db, "and r.name < '" + STATIC_LIMIT + "'", ""));
        assertThat(filtered).isNotEmpty();
        List<Detail> rows = new ArrayList<>();
        var after = SqlSnapshots.capture(db, ds ->
                withExecutor(ds, x -> rows.addAll(x.list(plain, Limit.unlimited()))));
        assertThat(after).isEqualTo(before);
        assertThat(rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.joined())))
                .containsExactlyElementsOf(staticOnly);
    }

    /** Runs the query's content, count, both primary-key-first steps, keyset and export, all expecting the rows. */
    private static List<String> everyPhase(TckDatabase db, ModelQuery.Builder<OrderEntity, Long, Detail> builder,
            List<String> expected) {
        var plain = builder.build();
        var keyset = builder.keyset().build();
        var pkFirst = builder.primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0L)).build();
        int size = expected.size();
        return SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            assertThat(texts(x.list(plain, Limit.unlimited()))).containsExactlyElementsOf(expected);
            assertThat(x.count(plain)).isEqualTo(size);
            assertThat(x.page(plain, PageSpec.of(0, 7), CountMode.COUNT).total()).hasValue((long) size);
            assertThat(texts(x.page(pkFirst, PageSpec.ofOffset(7, 7), CountMode.NO_COUNT).content()))
                    .containsExactlyElementsOf(expected.subList(7, Math.min(14, size)));
            assertThat(x.page(pkFirst, PageSpec.ofOffset(7, 7), CountMode.COUNT).total()).hasValue((long) size);
            List<Detail> paged = new ArrayList<>();
            KeysetSpec next = KeysetSpec.first(7);
            while (true) {
                var page = x.page(keyset, next);
                paged.addAll(page.content());
                if (!page.hasNext()) {
                    break;
                }
                next = KeysetSpec.after(page.nextCursor().orElseThrow(), 7);
            }
            assertThat(texts(paged)).containsExactlyElementsOf(expected);
            for (var query : List.of(plain, keyset, pkFirst)) {
                List<Detail> exported = new ArrayList<>();
                x.export(query, ExportOptions.of(13), p -> p, exported::add);
                assertThat(texts(exported)).containsExactlyElementsOf(expected);
            }
        }));
    }

    private static List<String> texts(List<Detail> rows) {
        return rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.joined())).toList();
    }

    private static void mq1912(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1912));
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }
}
