package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * Entity joins anchored on the root, an earlier entity join and their to-one paths, with no joined source, on every
 * Tier-1 target (R-SRC-25, D-197, D-215, AC-SRC-59 to AC-SRC-65).
 */
class ColumnEntityJoinExecutionTest {

    record Row(Long id, String manager, String referred, String referrer) {

        Row(Long id, String manager, String referred) {
            this(id, manager, referred, referred);
        }
    }

    record Parent(Long id, List<Row> orders) {}

    record Item(Long id, String referrer) {}

    record Chain(Long id, String referred, Long twin, String twinReferred) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> REFERRER =
            TableField.join(ORDERS, "referrer", JoinType.LEFT);
    private static final OrderedColumnField<Row, OrderEntity, Long> ID =
            ColumnField.of(Row.class, ORDERS, "id", Long.class).named("id");
    private static final ColumnField<Row, OrderEntity, String> STATUS =
            ColumnField.of(Row.class, ORDERS, "status", String.class);
    private static final ColumnField<Row, CustomerEntity, Long> REFERRER_ID =
            ColumnField.of(Row.class, REFERRER, "id", Long.class);
    private static final EntityJoin<Row, CustomerEntity> MANAGER = customer("manager", JoinKind.LEFT, ID);
    private static final EntityJoin<Row, CustomerEntity> REFERRED = customer("referred", JoinKind.LEFT, REFERRER_ID);
    private static final OrderedColumnField<Row, CustomerEntity, String> NAME =
            ColumnField.of(Row.class, MANAGER.root(), "name", String.class).named("manager");
    /** Read, so the anchor's own path join renders: Hibernate reads {@code referrer.id} alone as the foreign key. */
    private static final ColumnField<Row, CustomerEntity, String> REFERRER_NAME =
            ColumnField.of(Row.class, REFERRER, "name", String.class).named("referrer");

    private static final OrderedColumnField<Chain, OrderEntity, Long> C_ID =
            ColumnField.of(Chain.class, ORDERS, "id", Long.class).named("id");
    private static final ColumnField<Chain, OrderEntity, Long> C_REFERRER_ID =
            ColumnField.of(Chain.class, ORDERS, "referrerId", Long.class);

    /** Orders 990 to 1030: 990 to 1000 match a customer of the same id; every third has a referrer. */
    private static ModelQuery.Builder<OrderEntity, Long, Row> rows(EntityJoin<Row, CustomerEntity> manager,
            EntityJoin<Row, CustomerEntity> referred) {
        var name = ColumnField.of(Row.class, manager.root(), "name", String.class).named("manager");
        var referredName = ColumnField.of(Row.class, referred.root(), "name", String.class).named("referred");
        return ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), row.get(name), row.get(referredName),
                        row.get(REFERRER_NAME)))
                .joinEntity(manager).joinEntity(referred).select(SelectSet.of(ID, name, referredName, REFERRER_NAME))
                .primaryKey(PrimaryKey.of(ID)).where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
    }

    private static ModelQuery.Builder<OrderEntity, Long, Row> rows() {
        return rows(MANAGER, REFERRED);
    }

    @TckTest
    void ac_src_59_left_column_anchors_match_native_sql_and_keep_orphans(TckDatabase db) throws SQLException {
        List<Row> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x ->
                rows.addAll(x.list(rows().build(), Limit.unlimited()))));
        assertThat(rows.stream().map(r ->
                SourceJoinExecutionTest.text(r.id(), r.manager(), r.referred(), r.referrer())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, m.name, rc.name, r.name from orders o
                        left join customers m on m.id = o.id
                        left join customers r on r.id = o.referrer_id
                        left join customers rc on rc.id = r.id
                        where o.id between 990 and 1030 order by o.id"""));
        assertThat(rows).hasSize(41)
                .anySatisfy(r -> assertThat(r).isEqualTo(new Row(990L, "Customer 0990", "Customer 0891")))
                .anySatisfy(r -> assertThat(r).isEqualTo(new Row(991L, "Customer 0991", null)))
                .anySatisfy(r -> assertThat(r).isEqualTo(new Row(1001L, null, null)));
        assertThat(sql).singleElement().satisfies(statement ->
                assertThat(SourceJoinExecutionTest.occurrences(statement, "left join customers")).isEqualTo(3));
    }

    @TckTest
    void ac_src_59_a_non_id_path_anchor_joins_its_path_before_the_entity(TckDatabase db) throws SQLException {
        var items = TableField.root(OrderItemEntity.class);
        var order = TableField.join(items, "order", JoinType.LEFT);
        var itemId = ColumnField.of(Item.class, items, "id", Long.class).named("id");
        var referrerId = ColumnField.of(Item.class, order, "referrerId", Long.class);
        var referrer = EntityJoin.of("referrer", CustomerEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(referrerId, "id")));
        var name = ColumnField.of(Item.class, referrer.root(), "name", String.class).named("referrer");
        var query = ModelQuery.builder(items, row -> new Item(row.get(itemId), row.get(name)))
                .joinEntity(referrer).select(SelectSet.of(itemId, name)).primaryKey(PrimaryKey.of(itemId))
                .where(f -> f.gte(itemId, 1L).lte(itemId, 60L)).orderBy(itemId.asc()).build();
        List<Item> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> rows.addAll(ModelQueryExecutor.create(em, OrderItemEntity.class,
                        ModelQueryConfig.defaults()).list(query, Limit.unlimited())));
            }
        });
        assertThat(rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.referrer())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select i.id, c.name from order_items i
                        left join orders o on o.id = i.order_id
                        left join customers c on c.id = o.referrer_id
                        where i.id between 1 and 60 order by i.id"""));
        assertThat(rows).hasSize(60).anySatisfy(r -> assertThat(r.referrer()).isNotNull())
                .anySatisfy(r -> assertThat(r.referrer()).isNull());
        assertThat(sql).singleElement().satisfies(statement -> {
            String path = group(statement, "left join orders (\\w+) on");
            int pathJoin = statement.indexOf("left join orders " + path);
            int entityJoin = statement.indexOf("left join customers");
            assertThat(pathJoin).as(statement).isNotNegative().isLessThan(entityJoin);
            assertThat(statement.indexOf(path + ".referrer_id", entityJoin)).as(statement)
                    .isGreaterThan(entityJoin);
        });
    }

    @TckTest
    void ac_src_59_a_vendor_without_ctes_renders_column_anchored_joins(TckDatabase db) {
        var config = ModelQueryConfig.defaults().vendorProfiles(List.of(SourceQueryExecutionTest.withoutSources(db)));
        SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inSession(em -> assertThat(ModelQueryExecutor.create(em, OrderEntity.class, config)
                        .list(rows().build(), Limit.unlimited())).hasSize(41));
            }
        });
    }

    @TckTest
    void ac_src_60_inner_drops_unmatched_and_null_path_anchors_in_every_statement(TckDatabase db) {
        var inner = customer("referred", JoinKind.INNER, REFERRER_ID);
        var plain = rows(MANAGER, inner).build();
        var keyset = rows(MANAGER, inner).keyset().build();
        var pkFirst = rows(MANAGER, inner).primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0L)).build();
        List<Long> expected = LongStream.rangeClosed(990, 1030).filter(id -> id % 3 == 0).boxed().toList();
        List<String> sql = SqlSnapshots.capture(db, ds -> inTransaction(ds, x -> {
            assertThat(x.list(plain, Limit.unlimited())).extracting(Row::id).containsExactlyElementsOf(expected);
            assertThat(x.count(plain)).isEqualTo(14L);
            assertThat(x.page(plain, PageSpec.of(0, 5), CountMode.COUNT).total()).hasValue(14L);
            assertThat(x.page(pkFirst, PageSpec.ofOffset(5, 5), CountMode.NO_COUNT).content()).extracting(Row::id)
                    .containsExactlyElementsOf(expected.subList(5, 10));
            List<Row> paged = new ArrayList<>();
            KeysetSpec next = KeysetSpec.first(5);
            while (true) {
                var page = x.page(keyset, next);
                paged.addAll(page.content());
                if (!page.hasNext()) {
                    break;
                }
                next = KeysetSpec.after(page.nextCursor().orElseThrow(), 5);
            }
            assertThat(paged).extracting(Row::id).containsExactlyElementsOf(expected);
            assertThat(x.byKeys(plain, List.of(990L, 991L))).containsOnlyKeys(990L);
            assertThat(x.one(plain, 991L)).isEmpty();
            assertThat(x.one(plain, 993L)).isPresent();
            for (var query : List.of(plain, keyset, pkFirst)) {
                List<Row> exported = new ArrayList<>();
                x.export(query, ExportOptions.of(4), p -> p, exported::add);
                assertThat(exported).extracting(Row::id).containsExactlyElementsOf(expected);
            }
            x.stream(plain, Limit.unlimited(), stream -> assertThat(stream.count()).isEqualTo(14L));
        }));
        // The INNER join and the unread LEFT manager join are on every statement; the referrer path joins only
        // where its name is selected, since Hibernate reads referrer.id alone as the foreign key.
        assertThat(sql).hasSizeGreaterThan(15).allSatisfy(statement -> {
            String root = group(statement, "from orders (\\w+)");
            int left = SourceJoinExecutionTest.occurrences(statement, "left join customers");
            assertThat(SourceJoinExecutionTest.occurrences(statement, "join customers") - left).as(statement)
                    .isEqualTo(1);
            assertThat(matches(statement, "left join customers (\\w+) on \\1\\.id=" + root + "\\.id\\b"))
                    .as(statement).isEqualTo(1);
            var path = Pattern.compile("left join customers (\\w+) on \\1\\.id=" + root + "\\.referrer_id")
                    .matcher(statement);
            if (path.find()) {
                assertThat(statement.substring(0, statement.indexOf(" from "))).as(statement)
                        .contains(path.group(1) + ".name");
                assertThat(left).as(statement).isEqualTo(2);
            } else {
                assertThat(left).as(statement).isEqualTo(1);
            }
        });
    }

    @TckTest
    void ac_src_60_a_grouped_count_keeps_an_inner_column_anchored_join(TckDatabase db) {
        var inner = customer("referred", JoinKind.INNER, REFERRER_ID);
        var referredName = ColumnField.of(Row.class, inner.root(), "name", String.class).named("referred");
        var query = ModelQuery.builder(ORDERS, row -> new Row(null, null, row.get(referredName)))
                .joinEntity(MANAGER).joinEntity(inner).select(SelectSet.of(referredName)).groupBy(referredName)
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(referredName.asc()).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            assertThat(x.list(query, Limit.unlimited())).hasSize(14).extracting(Row::referred).doesNotContainNull();
            assertThat(x.count(query)).isEqualTo(14L);
            assertThat(x.page(query, PageSpec.of(0, 5), CountMode.COUNT).total()).hasValue(14L);
        }));
        // The provider's copy counts the groups in the database, keeping the INNER join (D-197).
        assertThat(sql.stream().filter(statement -> statement.contains("count("))).hasSize(2)
                .allSatisfy(statement -> assertThat(SourceJoinExecutionTest.occurrences(statement, "join customers")
                        - SourceJoinExecutionTest.occurrences(statement, "left join customers")).as(statement)
                        .isEqualTo(1));
    }

    @TckTest
    void ac_src_60_nullable_joined_keyset_with_nulls_last_loses_no_row(TckDatabase db) {
        var query = rows().orderBy(NAME.asc().nullsLast(), ID.asc()).keyset().build();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            var listed = x.list(query, Limit.unlimited());
            List<Row> paged = new ArrayList<>();
            KeysetSpec next = KeysetSpec.first(7);
            while (true) {
                var page = x.page(query, next);
                paged.addAll(page.content());
                if (!page.hasNext()) {
                    break;
                }
                next = KeysetSpec.after(page.nextCursor().orElseThrow(), 7);
            }
            assertThat(listed).hasSize(41);
            assertThat(paged).containsExactlyElementsOf(listed);
            assertThat(paged.subList(11, 41)).extracting(Row::manager).containsOnlyNulls();
        }));
    }

    @TckTest
    void ac_src_61_anchor_paths_and_id_rules_fail_at_first_resolution_before_sql(TckDatabase db) {
        var items = TableField.join(ORDERS, "items", JoinType.LEFT);
        List<EntityJoin<Row, CustomerEntity>> invalid = List.of(
                customer("toMany", JoinKind.LEFT, ColumnField.of(Row.class, items, "id", Long.class)),
                customer("idForm", JoinKind.LEFT, ColumnField.of(Row.class, ORDERS, "referrer", Long.class)),
                customer("wrongType", JoinKind.LEFT, ColumnField.of(Row.class, ORDERS, "version", Integer.class)),
                EntityJoin.of("nonId", CustomerEntity.class, JoinKind.LEFT, List.of(EntityJoin.on(STATUS, "name"))));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            for (var join : invalid) {
                var query = rows(MANAGER, join).build();
                assertThatThrownBy(() -> x.list(query, Limit.unlimited())).as(join.name())
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1911));
            }
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_61_inner_join_anchored_through_a_join_filter_is_mq1912(TckDatabase db) {
        var country = ColumnField.of(Row.class, REFERRER, "country", String.class);
        var inner = customer("referred", JoinKind.INNER, REFERRER_ID);
        var refused = rows(MANAGER, inner).joinFilter(REFERRER, f -> f.eq(country, "DE")).build();
        var left = rows().joinFilter(REFERRER, f -> f.eq(country, "DE")).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            assertThatThrownBy(() -> x.list(refused, Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1912))
                    .hasMessageContaining("declare the entity join LEFT")
                    // AC-SRC-65: the filtered join is named, not printed as a record.
                    .hasMessageContaining("reads through the joinFilter(...) join 'referrer' (LEFT)")
                    .hasMessageNotContaining("JoinKey[");
            assertThat(x.list(left, Limit.unlimited())).hasSize(41);
        }));
        assertThat(sql).hasSize(1);
    }

    @TckTest
    void ac_src_61_locks_and_writes_refuse_before_any_statement(TckDatabase db) {
        // A root anchor adds no Criteria join of the root; the provider reports the entity join (R-EXE-14).
        var locked = ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), row.get(NAME), null)).joinEntity(MANAGER)
                .select(SelectSet.of(ID, NAME)).primaryKey(PrimaryKey.of(ID)).build()
                .withLock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED));
        var delete = ModelDelete.builder(ORDERS).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.eq(NAME, "Customer 0990")).build();
        var update = ModelUpdate.builder(ORDERS).primaryKey(PrimaryKey.of(ID)).set(STATUS, "PAID")
                .where(f -> f.eq(NAME, "Customer 0990")).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inTransaction(em -> {
                    var x = ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults());
                    assertThatThrownBy(() -> x.list(locked, Limit.unlimited()))
                            .isInstanceOfSatisfying(ModelQueryExecutionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ2211));
                    assertThatThrownBy(() -> x.delete(delete))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1003));
                    assertThatThrownBy(() -> x.update(update))
                            .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ1003));
                });
            }
        });
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_61_a_through_child_load_keeps_column_anchored_joins(TckDatabase db) {
        var id = ColumnField.of(Parent.class, ORDERS, "id", Long.class);
        var through = TableField.join(TableField.join(ORDERS, "customer", JoinType.INNER), "orders", JoinType.INNER);
        ChildField<Parent, Row> children = new ChildField<>() {
            @Override
            public String name() {
                return "orders";
            }

            @Override
            public ColumnField<Parent, ?, ?> key() {
                return id;
            }

            @Override
            public Optional<ColumnField<Row, ?, ?>> foreignKey() {
                return Optional.empty();
            }

            @Override
            public Optional<TableField<?, ?>> through() {
                return Optional.of(through);
            }

            @Override
            public boolean isToMany() {
                return true;
            }

            @Override
            public ModelQuery.Builder<?, ?, Row> query() {
                return rows();
            }

            @Override
            public Parent with(Parent parent, List<Row> rows) {
                return new Parent(parent.id(), rows);
            }
        };
        var referredName = ColumnField.of(Row.class, REFERRED.root(), "name", String.class).named("referred");
        var plan = FetchPlan.of(SelectSet.of(id)).child(children,
                FetchPlan.of(SelectSet.of(ID, NAME, referredName, REFERRER_NAME)));
        var query = ModelQuery.builder(ORDERS, row -> new Parent(row.get(id), List.of()))
                .select(SelectSet.of(id)).primaryKey(PrimaryKey.of(id)).where(f -> f.eq(id, 990L)).fetch(plan).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x ->
                assertThat(x.list(query, Limit.unlimited())).singleElement().satisfies(parent ->
                        assertThat(parent.orders()).containsExactly(
                                new Row(990L, "Customer 0990", "Customer 0891"), new Row(1990L, null, null),
                                new Row(2990L, null, null), new Row(3990L, null, "Customer 0891"),
                                new Row(4990L, null, null)))));
        assertThat(sql).hasSize(2).last().satisfies(statement ->
                assertThat(SourceJoinExecutionTest.occurrences(statement, "left join customers")).isEqualTo(3));
    }

    /** The returned model's id and the key it is chosen by. */
    private static <K> ModelQuery.Builder<OrderEntity, K, Row> pick(ColumnField<Row, ?, K> key,
            EntityJoin<Row, CustomerEntity> join) {
        return ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), null, null)).joinEntity(join)
                .select(SelectSet.of(ID, key)).primaryKey(PrimaryKey.of(key));
    }

    /**
     * {@code first} keys on a column of an entity joined LEFT: it can read NULL whatever the entity declares, so
     * the key sorts NULLs last on every vendor, and a key of an INNER join sorts plain (R-EXE-12, AC-EXE-12).
     */
    @TckTest
    void ac_exe_12_first_by_a_left_entity_join_column_puts_unmatched_rows_last(TckDatabase db) throws SQLException {
        var key = ColumnField.of(Row.class, MANAGER.root(), "id", Long.class);
        // Orders 995 to 1000 match a customer of their own id, 1001 to 1010 match none: NULL must not come first.
        var left = pick(key, MANAGER).where(f -> f.gte(ID, 995L).lte(ID, 1010L)).build();
        var innerReferred = customer("referred", JoinKind.INNER, REFERRER_ID);
        var innerKey = ColumnField.of(Row.class, innerReferred.root(), "id", Long.class);
        var inner = pick(innerKey, innerReferred).where(f -> f.gte(ID, 990L).lte(ID, 1030L)).build();
        List<Long> ids = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            ids.add(x.first(left).map(Row::id).orElse(null));
            ids.add(x.first(inner).map(Row::id).orElse(null));
        }));
        assertThat(ids.get(0)).isEqualTo(Long.valueOf(SourceJoinExecutionTest.rows(db, """
                select o.id from orders o left join customers m on m.id = o.id
                where o.id between 995 and 1010
                order by case when m.id is null then 1 else 0 end, m.id""").get(0)));
        assertThat(ids.get(0)).isEqualTo(995L);
        // Several orders can share the smallest referrer, so the INNER row is checked by its referrer.
        String expected = SourceJoinExecutionTest.rows(db, """
                select c.id from orders o join customers c on c.id = o.referrer_id
                where o.id between 990 and 1030 order by c.id""").get(0);
        assertThat(SourceJoinExecutionTest.rows(db, "select c.id from orders o join customers c on c.id = "
                + "o.referrer_id where o.id = " + ids.get(1))).containsExactly(expected);
        assertThat(sql).hasSize(2);
        assertThat(SourceJoinExecutionTest.occurrences(sql.get(0), "left join customers")).isEqualTo(1);
        assertThat(sql.get(1)).doesNotContainPattern("nulls last|case when");
    }

    /**
     * {@code first} keys on a primitive column read through a LEFT-joined optional to-one: a NULL association reads
     * it as NULL, so the key sorts NULLs last on every vendor (R-EXE-12, AC-EXE-12). The path is a joined node: a
     * dotted attribute cannot cross an association (MQ1002), so the join is the only way to such a column.
     */
    @TckTest
    void ac_exe_12_first_by_a_column_below_an_optional_to_one_puts_orphans_last(TckDatabase db) throws SQLException {
        var key = ColumnField.of(Row.class, REFERRER, "vip", Boolean.class);
        // Of 990 to 992 only 990 has a referrer: NULL keys must come after it, not first.
        var query = ModelQuery.builder(ORDERS, row -> new Row(row.get(ID), null, null))
                .select(SelectSet.of(ID, key)).primaryKey(PrimaryKey.of(key))
                .where(f -> f.gte(ID, 990L).lte(ID, 992L)).build();
        List<Row> first = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x ->
                first.add(x.first(query).orElse(null))));
        assertThat(first).singleElement().satisfies(row -> assertThat(row.id()).isEqualTo(990L));
        assertThat(sql).hasSize(1);
    }

    @TckTest
    void ac_src_63_two_and_three_join_chains_match_native_sql_and_count_in_creation_order(TckDatabase db)
            throws SQLException {
        JoinKind left = JoinKind.LEFT;
        JoinKind inner = JoinKind.INNER;
        List<JoinKind[]> mixes = List.of(new JoinKind[] {left, left}, new JoinKind[] {inner, left},
                new JoinKind[] {inner, inner}, new JoinKind[] {left, left, left}, new JoinKind[] {inner, left, left},
                new JoinKind[] {inner, inner, left}, new JoinKind[] {inner, inner, inner});
        List<List<Chain>> read = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            for (JoinKind[] kinds : mixes) {
                var query = chained(chain(kinds)).build();
                read.add(x.list(query, Limit.unlimited()));
                counts.add(x.count(query));
            }
        }));
        for (int i = 0; i < mixes.size(); i++) {
            JoinKind[] kinds = mixes.get(i);
            assertThat(text(read.get(i), kinds.length)).as(List.of(kinds).toString())
                    .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, nativeChain("", kinds)));
            assertThat(counts.get(i)).as(List.of(kinds).toString()).isEqualTo(read.get(i).size());
        }
        // Each link misses somewhere: LEFT keeps all 41 orders, an INNER first join 14, an INNER third fewer still.
        assertThat(read.get(3)).hasSize(41)
                .anySatisfy(r -> assertThat(r.twinReferred()).isNotNull())
                .anySatisfy(r -> assertThat(r.referred()).isNotNull().isNotEqualTo(r.twinReferred()))
                .anySatisfy(r -> assertThat(r.referred()).isNull());
        assertThat(read.get(4)).hasSize(14).anySatisfy(r -> assertThat(r.twinReferred()).isNull());
        assertThat(read.get(6)).isNotEmpty().hasSizeLessThan(14);
        // The joins render in creation order, each ON reading the join before it, and the count keeps them all.
        List<String> last = sql.subList(sql.size() - 2, sql.size());
        assertThat(last.get(1)).contains("count(");
        assertThat(last).allSatisfy(statement -> {
            String root = group(statement, "from orders (\\w+)");
            String referred = group(statement, "join customers (\\w+) on \\1\\.id=" + root + "\\.referrer_id");
            String twin = group(statement, "join orders (\\w+) on \\1\\.id=" + referred + "\\.id");
            assertThat(statement).containsPattern("join customers " + referred + " on .* join orders " + twin
                    + " on .* join customers (\\w+) on \\1\\.id=" + twin + "\\.referrer_id");
        });
    }

    @TckTest
    void ac_src_63_a_join_filter_on_the_first_join_and_keyset_pages_over_a_chain(TckDatabase db)
            throws SQLException {
        var joins = chain(JoinKind.LEFT, JoinKind.LEFT, JoinKind.LEFT);
        @SuppressWarnings("unchecked")
        var referred = (EntityJoin<Chain, CustomerEntity>) joins.get(0);
        var country = ColumnField.of(Chain.class, referred.root(), "country", String.class);
        var filtered = chained(joins).joinFilter(referred, f -> f.eq(country, "DE")).build();
        var keyset = chained(joins).keyset().build();
        List<Chain> rows = new ArrayList<>();
        List<Chain> listed = new ArrayList<>();
        List<Chain> paged = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            rows.addAll(x.list(filtered, Limit.unlimited()));
            listed.addAll(x.list(keyset, Limit.unlimited()));
            KeysetSpec next = KeysetSpec.first(4);
            while (true) {
                var page = x.page(keyset, next);
                paged.addAll(page.content());
                if (!page.hasNext()) {
                    break;
                }
                next = KeysetSpec.after(page.nextCursor().orElseThrow(), 4);
            }
        }));
        assertThat(text(rows, 3)).containsExactlyElementsOf(SourceJoinExecutionTest.rows(db,
                nativeChain(" and r.country = 'DE'", JoinKind.LEFT, JoinKind.LEFT, JoinKind.LEFT)));
        // The filter blanks most referrers, and the joins after it miss with them.
        assertThat(rows).hasSize(41).anySatisfy(r -> assertThat(r.referred()).isNotNull())
                .filteredOn(r -> r.referred() == null).allSatisfy(r -> assertThat(r.twin()).isNull());
        assertThat(rows.stream().filter(r -> r.referred() != null).count()).isLessThan(14L);
        assertThat(listed).hasSize(41);
        assertThat(paged).containsExactlyElementsOf(listed);
    }

    @TckTest
    void ac_src_63_an_anchor_on_a_to_one_path_below_an_earlier_join(TckDatabase db) throws SQLException {
        var item = EntityJoin.of("item", OrderItemEntity.class, JoinKind.LEFT, List.of(EntityJoin.on(C_ID, "id")));
        var itemOrder = TableField.<OrderItemEntity, OrderEntity>join(item.root(), "order", JoinType.LEFT);
        var buyer = EntityJoin.of("buyer", CustomerEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(ColumnField.of(Chain.class, itemOrder, "referrerId", Long.class), "id")));
        var name = ColumnField.of(Chain.class, buyer.root(), "name", String.class).named("referred");
        var orderId = ColumnField.of(Chain.class, itemOrder, "id", Long.class).named("twin");
        var query = ModelQuery.builder(ORDERS, row -> new Chain(row.get(C_ID), row.get(name), row.get(orderId), null))
                .joinEntity(item).joinEntity(buyer).select(SelectSet.of(C_ID, name, orderId))
                .primaryKey(PrimaryKey.of(C_ID)).where(f -> f.gte(C_ID, 990L).lte(C_ID, 1030L)).orderBy(C_ID.asc())
                .build();
        List<Chain> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> rows.addAll(x.list(query, Limit.unlimited()))));
        assertThat(text(rows, 2)).containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                select o.id, c.name, i.order_id from orders o
                left join order_items i on i.id = o.id
                left join orders io on io.id = i.order_id
                left join customers c on c.id = io.referrer_id
                where o.id between 990 and 1030 order by o.id"""));
        assertThat(rows).hasSize(41).anySatisfy(r -> assertThat(r.referred()).isNotNull())
                .anySatisfy(r -> assertThat(r.referred()).isNull());
    }

    @TckTest
    void ac_src_64_reversed_and_inner_on_left_chains_are_mq1911_before_any_statement(TckDatabase db) {
        JoinKind left = JoinKind.LEFT;
        JoinKind inner = JoinKind.INNER;
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            for (JoinKind[] kinds : List.of(new JoinKind[] {left, inner}, new JoinKind[] {left, left, inner},
                    new JoinKind[] {left, inner, left})) {
                assertThatThrownBy(() -> chained(chain(kinds)).build()).as(List.of(kinds).toString())
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1911))
                        .hasMessageContaining("which is LEFT: this INNER join would drop every row");
            }
            // Registered in reverse, the chain fails when built, not as an unbound join at the first statement.
            List<EntityJoin<Chain, ?>> reversed = new ArrayList<>(chain(left, left, left));
            Collections.reverse(reversed);
            assertThatThrownBy(() -> chained(reversed).build())
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1911))
                    .hasMessageContaining("entity join 'twinReferred' of CustomerEntity: anchor referrerId: reads "
                            + "the entity join 'twin', bound after it")
                    .hasMessageNotContaining("joinEntity(...)");
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_62_when_removes_colliding_rows_and_binds_a_parameter(TckDatabase db) throws SQLException {
        var paid = MANAGER.when(STATUS, "PAID");
        List<Row> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            rows.addAll(x.list(rows(paid, REFERRED).build(), Limit.unlimited()));
            assertThat(x.list(rows(paid, REFERRED).build(), Limit.unlimited())).isEqualTo(rows);
        }));
        assertThat(rows.stream().map(r ->
                SourceJoinExecutionTest.text(r.id(), r.manager(), r.referred(), r.referrer())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, m.name, rc.name, r.name from orders o
                        left join customers m on m.id = o.id and o.status = 'PAID'
                        left join customers r on r.id = o.referrer_id
                        left join customers rc on rc.id = r.id
                        where o.id between 990 and 1030 order by o.id"""));
        assertThat(rows).hasSize(41).allSatisfy(r -> assertThat(r.manager() != null)
                .isEqualTo(r.id() <= 1000L && r.id() % 4 == 1));
        assertThat(sql).hasSize(2).allSatisfy(statement -> {
            assertThat(statement).doesNotContain("'PAID'");
            assertThat(statement.replace(" ", "")).containsPattern("status=\\?");
        });
        assertThat(sql.get(0)).isEqualTo(sql.get(1));
    }

    /**
     * A chain of {@code kinds.length} joins, each anchored on the one before (D-215): an order's referrer, the order
     * whose id is that customer's, and that order's referrer.
     */
    private static List<EntityJoin<Chain, ?>> chain(JoinKind... kinds) {
        var referred = EntityJoin.of("referred", CustomerEntity.class, kinds[0],
                List.of(EntityJoin.on(C_REFERRER_ID, "id")));
        var twin = EntityJoin.of("twin", OrderEntity.class, kinds[1],
                List.of(EntityJoin.on(ColumnField.of(Chain.class, referred.root(), "id", Long.class), "id")));
        if (kinds.length == 2) {
            return List.of(referred, twin);
        }
        return List.of(referred, twin, EntityJoin.of("twinReferred", CustomerEntity.class, kinds[2],
                List.of(EntityJoin.on(ColumnField.of(Chain.class, twin.root(), "referrerId", Long.class), "id"))));
    }

    /** Orders 990 to 1030 over {@code joins}, bound in the order given, reading a column of each. */
    private static ModelQuery.Builder<OrderEntity, Long, Chain> chained(List<EntityJoin<Chain, ?>> joins) {
        var referred = ColumnField.of(Chain.class, joins.get(0).root(), "name", String.class).named("referred");
        var twin = ColumnField.of(Chain.class, joins.get(1).root(), "id", Long.class).named("twin");
        var last = joins.size() < 3 ? null
                : ColumnField.of(Chain.class, joins.get(2).root(), "name", String.class).named("twinReferred");
        var query = ModelQuery.builder(ORDERS, row -> new Chain(row.get(C_ID), row.get(referred), row.get(twin),
                last == null ? null : row.get(last)));
        for (EntityJoin<Chain, ?> join : joins) {
            query = query.joinEntity(join);
        }
        var select = last == null ? SelectSet.of(C_ID, referred, twin) : SelectSet.of(C_ID, referred, twin, last);
        return query.select(select).primaryKey(PrimaryKey.of(C_ID)).where(f -> f.gte(C_ID, 990L).lte(C_ID, 1030L))
                .orderBy(C_ID.asc());
    }

    /** The native SQL of {@link #chain}, with {@code on} ANDed into the first join's ON. */
    private static String nativeChain(String on, JoinKind... kinds) {
        String[] kind = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            kind[i] = kinds[i] == JoinKind.LEFT ? "left join" : "inner join";
        }
        return "select o.id, r.name, t.id" + (kinds.length == 3 ? ", tr.name" : "") + " from orders o "
                + kind[0] + " customers r on r.id = o.referrer_id" + on + " "
                + kind[1] + " orders t on t.id = r.id "
                + (kinds.length == 3 ? kind[2] + " customers tr on tr.id = t.referrer_id " : "")
                + "where o.id between 990 and 1030 order by o.id";
    }

    private static List<String> text(List<Chain> rows, int joins) {
        return rows.stream().map(r -> joins == 3 ? SourceJoinExecutionTest.text(r.id(), r.referred(), r.twin(),
                r.twinReferred()) : SourceJoinExecutionTest.text(r.id(), r.referred(), r.twin())).toList();
    }

    private static String group(String statement, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(statement);
        assertThat(matcher.find()).as(statement).isTrue();
        return matcher.group(1);
    }

    private static int matches(String statement, String regex) {
        return (int) Pattern.compile(regex).matcher(statement).results().count();
    }

    private static EntityJoin<Row, CustomerEntity> customer(String name, JoinKind kind, ColumnField<Row, ?, ?> anchor) {
        return EntityJoin.of(name, CustomerEntity.class, kind, List.of(EntityJoin.on(anchor, "id")));
    }

    private static void inTransaction(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inTransaction(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }
}
