package com.rey.modelquery.tck.src;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ChildField;
import com.rey.modelquery.core.ColumnConverter;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.PrimaryKeyFirst;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CompositeNodeEntity;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.exe.EmbeddedNodeEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.portability.LegacyRecursiveProviderSupport;
import com.rey.modelquery.tck.portability.NoEntityJoinProviderSupport;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import com.rey.modelquery.tck.vnd.VendorAggregates;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.JoinType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

/** Entity joins off joined sources on every Tier-1 target (R-SRC-23, R-SRC-24, AC-SRC-53 to AC-SRC-58). */
class EntityJoinExecutionTest {

    static final class Keys {}

    record Detail(Long id, String manager, String customer, String viaOrder) {}

    record Counted(Long id, Long count) {}

    record Parent(Long id, List<Detail> orders) {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<Keys, OrderEntity, Long> S_ID =
            ColumnField.of(Keys.class, ORDERS, "id", Long.class).named("id");
    private static final ColumnField<Keys, ?, Long> S_CUSTOMER = ColumnField.of(Keys.class,
            TableField.join(ORDERS, "customer", JoinType.INNER), "id", Long.class).named("customerId");
    private static final ColumnField<Keys, OrderEntity, Integer> S_VERSION =
            ColumnField.of(Keys.class, ORDERS, "version", Integer.class).named("version");
    private static final OrderedColumnField<Detail, OrderEntity, Long> ID =
            ColumnField.of(Detail.class, ORDERS, "id", Long.class).named("id");
    private static final SourceJoin<Detail, Keys> SOURCE = SourceJoin.of("keys", Keys.class, JoinKind.LEFT,
            List.of(SourceJoin.on(ID, S_ID)));
    private static final ColumnField<Detail, Keys, Long> ANCHOR = ColumnField.over(Detail.class, SOURCE.root(), S_ID);
    private static final ColumnField<Detail, Keys, Long> CUSTOMER_KEY =
            ColumnField.over(Detail.class, SOURCE.root(), S_CUSTOMER);
    private static final EntityJoin<Detail, CustomerEntity> MANAGER = customer("manager", JoinKind.LEFT, ANCHOR);
    private static final EntityJoin<Detail, CustomerEntity> CUSTOMER =
            customer("customer", JoinKind.LEFT, CUSTOMER_KEY);
    private static final EntityJoin<Detail, OrderEntity> COPY = EntityJoin.of("copy", OrderEntity.class,
            JoinKind.LEFT, List.of(EntityJoin.on(ANCHOR, "id")));
    private static final TableField<OrderEntity, CustomerEntity> COPY_CUSTOMER =
            TableField.join(COPY.root(), "customer", JoinType.LEFT);
    private static final OrderedColumnField<Detail, CustomerEntity, String> NAME =
            ColumnField.of(Detail.class, MANAGER.root(), "name", String.class).named("manager");
    private static final ColumnField<Detail, CustomerEntity, String> CUSTOMER_NAME =
            ColumnField.of(Detail.class, CUSTOMER.root(), "name", String.class).named("customer");
    private static final ColumnField<Detail, CustomerEntity, String> VIA_ORDER =
            ColumnField.of(Detail.class, COPY_CUSTOMER, "name", String.class).named("viaOrder");
    private static final SourceQuery<Keys> KEYS = SourceQuery.builder(Keys.class, ORDERS,
            SelectSet.of(S_ID, S_CUSTOMER, S_VERSION), PrimaryKey.of(S_ID))
            .where(f -> f.gte(S_ID, 990L).lte(S_ID, 1020L)).build();

    static ModelQuery.Builder<OrderEntity, Long, Detail> details() {
        return ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), row.get(NAME), row.get(CUSTOMER_NAME),
                        row.get(VIA_ORDER)))
                .joinSource(SOURCE, KEYS).joinEntity(MANAGER).joinEntity(CUSTOMER).joinEntity(COPY)
                .select(SelectSet.of(ID, NAME, CUSTOMER_NAME, VIA_ORDER)).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
    }

    private static ModelQuery.Builder<OrderEntity, Long, Detail> ids() {
        return ModelQuery.builder(ORDERS, row -> new Detail(row.get(ID), null, null, null))
                .joinSource(SOURCE, KEYS).joinEntity(MANAGER).joinEntity(CUSTOMER).joinEntity(COPY)
                .select(SelectSet.of(ID)).primaryKey(PrimaryKey.of(ID))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).orderBy(ID.asc());
    }

    @TckTest
    void ac_src_53_left_entity_joins_and_their_to_one_paths_match_native_sql(TckDatabase db) throws SQLException {
        List<Detail> rows = new ArrayList<>();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, x ->
                rows.addAll(x.list(details().build(), Limit.unlimited()))));
        assertThat(rows.stream().map(r ->
                SourceJoinExecutionTest.text(r.id(), r.manager(), r.customer(), r.viaOrder())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db, """
                        select o.id, m.name, c.name, t.name from orders o
                        left join (select id, customer_id from orders where id between 990 and 1020) k
                            on k.id = o.id
                        left join customers m on m.id = k.id
                        left join customers c on c.id = k.customer_id
                        left join orders cp on cp.id = k.id
                        left join customers t on t.id = cp.customer_id
                        where o.id between 990 and 1030 order by o.id"""));
        assertThat(rows).hasSize(41).anySatisfy(r -> {
            assertThat(r.id()).isEqualTo(1001L);
            assertThat(r.manager()).isNull();
            assertThat(r.customer()).isNotNull();
        }).anySatisfy(r -> {
            assertThat(r.id()).isEqualTo(1021L);
            assertThat(r.manager()).isNull();
            assertThat(r.customer()).isNull();
        });
    }

    @TckTest
    void ac_src_54_every_read_phase_keeps_unreferenced_entity_joins_after_the_source(TckDatabase db) {
        var plain = ids().build();
        var keyset = ids().keyset().build();
        var pkFirst = ids().primaryKeyFirst(PrimaryKeyFirst.whenOffsetAbove(0L)).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                sf.inTransaction(em -> {
                    var x = ModelQueryExecutor.create(em, OrderEntity.class,
                            ModelQueryConfig.defaults());
                    assertThat(x.page(plain, PageSpec.of(0, 7), CountMode.COUNT).total()).hasValue(41L);
                    assertThat(x.count(plain)).isEqualTo(41L);
                    assertThat(x.page(pkFirst, PageSpec.ofOffset(7, 7), CountMode.NO_COUNT).content())
                            .extracting(Detail::id).containsExactly(997L, 998L, 999L, 1000L, 1001L, 1002L, 1003L);
                    assertThat(x.page(keyset, KeysetSpec.first(7)).content()).hasSize(7);
                    assertThat(x.byKeys(plain, List.of(990L, 1030L))).containsOnlyKeys(990L, 1030L);
                    assertThat(x.one(plain, 1001L)).get().extracting(Detail::id).isEqualTo(1001L);
                    assertThat(x.one(ids().where(f -> f.eq(ID, 1001L)).build())).isPresent();
                    for (var query : List.of(plain, keyset, pkFirst)) {
                        List<Detail> exported = new ArrayList<>();
                        x.export(query, ExportOptions.of(13), p -> p, exported::add);
                        assertThat(exported).hasSize(41);
                    }
                    x.stream(plain, Limit.unlimited(), stream -> assertThat(stream.count()).isEqualTo(41L));
                });
            }
        });
        assertThat(sql).hasSizeGreaterThan(20).allSatisfy(statement -> {
            assertThat(SourceJoinExecutionTest.occurrences(statement, "left join customers"))
                    .as(statement).isEqualTo(2);
            int first = statement.indexOf("left join customers");
            assertThat(statement.substring(0, first)).contains("left join");
            assertThat(statement.substring(first)).contains("join orders");
        });
    }

    @TckTest
    void ac_src_54_fetch_parent_and_through_child_load_keep_entity_joins(TckDatabase db) {
        var id = ColumnField.of(Parent.class, ORDERS, "id", Long.class);
        var bound = SourceJoin.of("keys", Keys.class, JoinKind.LEFT, List.of(SourceJoin.on(id, S_ID)));
        var anchor = ColumnField.over(Parent.class, bound.root(), S_ID);
        var manager = EntityJoin.of("manager", CustomerEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(anchor, "id")));
        var through = TableField.join(TableField.join(ORDERS, "customer", JoinType.INNER), "orders", JoinType.INNER);
        ChildField<Parent, Detail> children = new ChildField<>() {
            @Override
            public String name() {
                return "orders";
            }

            @Override
            public ColumnField<Parent, ?, ?> key() {
                return id;
            }

            @Override
            public Optional<ColumnField<Detail, ?, ?>> foreignKey() {
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
            public ModelQuery.Builder<?, ?, Detail> query() {
                return details();
            }

            @Override
            public Parent with(Parent parent, List<Detail> rows) {
                return new Parent(parent.id(), rows);
            }
        };
        var plan = FetchPlan.of(SelectSet.of(id)).child(children,
                FetchPlan.of(SelectSet.of(ID, NAME, CUSTOMER_NAME, VIA_ORDER)));
        var query = ModelQuery.builder(ORDERS, row -> new Parent(row.get(id), List.of()))
                .joinSource(bound, KEYS).joinEntity(manager).select(SelectSet.of(id)).primaryKey(PrimaryKey.of(id))
                .where(f -> f.eq(id, 990L)).fetch(plan).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x ->
                assertThat(x.list(query, Limit.unlimited())).singleElement().satisfies(parent -> {
                    assertThat(parent.id()).isEqualTo(990L);
                    assertThat(parent.orders()).containsExactly(
                            new Detail(990L, "Customer 0990", "Customer 0631", "Customer 0631"),
                            new Detail(1990L, null, null, null), new Detail(2990L, null, null, null),
                            new Detail(3990L, null, null, null), new Detail(4990L, null, null, null));
                })));
        assertThat(sql).hasSize(2).allSatisfy(statement -> assertThat(statement).contains("left join customers"));
    }

    @TckTest
    void ac_src_55_inner_drops_missing_entities_and_missing_left_sources_in_every_page(TckDatabase db) {
        var inner = customer("inner", JoinKind.INNER, ANCHOR);
        var query = details().joinEntity(inner).build();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            assertThat(x.list(query, Limit.unlimited())).extracting(Detail::id)
                    .containsExactly(990L, 991L, 992L, 993L, 994L, 995L, 996L, 997L, 998L, 999L, 1000L);
            assertThat(x.count(query)).isEqualTo(11L);
            assertThat(x.page(query, PageSpec.ofOffset(7, 7), CountMode.COUNT).total()).hasValue(11L);
            List<Detail> exported = new ArrayList<>();
            x.export(query, ExportOptions.of(7), p -> p, exported::add);
            assertThat(exported).hasSize(11);
        }));
    }

    @TckTest
    void ac_src_55_nullable_joined_keyset_with_nulls_last_loses_no_row(TckDatabase db) {
        var query = details().orderBy(NAME.asc().nullsLast(), ID.asc()).keyset().build();
        SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            var listed = x.list(query, Limit.unlimited());
            List<Detail> paged = new ArrayList<>();
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
            assertThat(paged.subList(11, 41)).extracting(Detail::manager).containsOnlyNulls();
        }));
    }

    @TckTest
    void ac_src_56_definition_checks_need_no_metamodel(TckDatabase db) {
        var other = SourceJoin.of("other", Keys.class, JoinKind.LEFT, List.of(SourceJoin.on(ID, S_ID)));
        var otherKey = ColumnField.over(Detail.class, other.root(), S_ID);
        var converted = ColumnField.of(Keys.class, ORDERS, "status", Long.class, String.class,
                new ColumnConverter<Long, String>() {
                    @Override
                    public Long toModel(String value) {
                        return Long.valueOf(value);
                    }

                    @Override
                    public String toAttribute(Long value) {
                        return value.toString();
                    }
                }).named("converted");
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            mq1911(() -> details().joinEntity(MANAGER).build());
            mq1911(() -> details().joinEntity(customer("keys", JoinKind.LEFT, ANCHOR)).build());
            mq1911(() -> details().joinEntity(customer("unbound", JoinKind.LEFT, otherKey)).build());
            mq1911(() -> details().joinSource(other, KEYS).joinEntity(EntityJoin.of("mixed", CustomerEntity.class,
                    JoinKind.LEFT, List.of(EntityJoin.on(ANCHOR, "id"), EntityJoin.on(otherKey, "id")))).build());
            mq1911(() -> details().joinEntity(customer("converted", JoinKind.LEFT,
                    ColumnField.over(Detail.class, SOURCE.root(), converted))).build());
            mq1911(() -> details().joinEntity(customer("root", JoinKind.LEFT, ID)).build());
        });
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_56_id_errors_and_to_many_paths_fail_at_first_resolution_before_sql(TckDatabase db) {
        var wrongType = ColumnField.over(Detail.class, SOURCE.root(), S_VERSION);
        List<EntityJoin<Detail, ?>> invalid = List.of(
                EntityJoin.of("partial", CompositeNodeEntity.class, JoinKind.LEFT,
                        List.of(EntityJoin.on(ANCHOR, "id"))),
                EntityJoin.of("extra", CustomerEntity.class, JoinKind.LEFT,
                        List.of(EntityJoin.on(ANCHOR, "id"), EntityJoin.on(ANCHOR, "name"))),
                EntityJoin.of("nonId", CustomerEntity.class, JoinKind.LEFT, List.of(EntityJoin.on(ANCHOR, "name"))),
                EntityJoin.of("duplicate", CustomerEntity.class, JoinKind.LEFT,
                        List.of(EntityJoin.on(ANCHOR, "id"), EntityJoin.on(ANCHOR, "id"))),
                EntityJoin.of("wrongType", CustomerEntity.class, JoinKind.LEFT,
                        List.of(EntityJoin.on(wrongType, "id"))));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            for (var join : invalid) {
                var query = details().joinEntity(join).build();
                mq1911(() -> x.list(query, Limit.unlimited()));
            }
            var many = TableField.join(MANAGER.root(), "orders", JoinType.LEFT);
            var manyId = ColumnField.of(Detail.class, many, "id", Long.class);
            var query = ids().select(SelectSet.of(ID, manyId)).build();
            mq1911(() -> x.list(query, Limit.unlimited()));
            mq1911(() -> x.list(details().where(f -> f.exists(many)).build(), Limit.unlimited()));
            var missing = customer("unregistered", JoinKind.LEFT, ANCHOR);
            var name = ColumnField.of(Detail.class, missing.root(), "name", String.class);
            assertThatThrownBy(() -> x.list(ids().select(SelectSet.of(ID, name)).build(), Limit.unlimited()))
                    .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                            e -> assertThat(e.code()).isEqualTo(MqCode.MQ1003));
        }));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_57_locks_still_refuse_before_any_statement(TckDatabase db) {
        var locked = details().build().withLock(RowLock.exclusive(RowLock.Wait.SKIP_LOCKED));
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x ->
                assertThatThrownBy(() -> x.list(locked, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1903))));
        assertThat(sql).isEmpty();
    }

    @TckTest
    void ac_src_57_idclass_and_embedded_id_join_every_attribute(TckDatabase db) throws SQLException {
        var nodes = TableField.root(CompositeNodeEntity.class);
        var sourceId = ColumnField.of(Keys.class, nodes, "id", Long.class).named("id");
        var sourceTenant = ColumnField.of(Keys.class, nodes, "tenantId", Integer.class).named("tenant");
        var source = SourceQuery.builder(Keys.class, nodes, SelectSet.of(sourceId, sourceTenant),
                PrimaryKey.composite(sourceTenant, sourceId)).build();
        var id = ColumnField.of(Detail.class, nodes, "id", Long.class);
        var tenant = ColumnField.of(Detail.class, nodes, "tenantId", Integer.class);
        var bound = SourceJoin.of("nodes", Keys.class, JoinKind.LEFT,
                List.of(SourceJoin.on(id, sourceId), SourceJoin.on(tenant, sourceTenant)));
        var joinedId = ColumnField.over(Detail.class, bound.root(), sourceId);
        var joinedTenant = ColumnField.over(Detail.class, bound.root(), sourceTenant);
        var idClass = EntityJoin.of("idClass", CompositeNodeEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(joinedId, "id"), EntityJoin.on(joinedTenant, "tenantId")));
        var embedded = EntityJoin.of("embedded", EmbeddedNodeEntity.class, JoinKind.LEFT,
                List.of(EntityJoin.on(joinedId, "key.id"), EntityJoin.on(joinedTenant, "key.tenantId")));
        var first = ColumnField.of(Detail.class, idClass.root(), "name", String.class);
        var second = ColumnField.of(Detail.class, embedded.root(), "name", String.class);
        var count = Agg.<Detail>count(embedded.root()).named("matched");
        var query = ModelQuery.builder(nodes, row -> new Detail(row.get(id), row.get(first), row.get(second), null))
                .joinSource(bound, source).joinEntity(idClass).joinEntity(embedded)
                .select(SelectSet.of(id, tenant, first, second)).orderBy(id.asc()).build();
        List<Detail> rows = new ArrayList<>();
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = nodeFactory(ds)) {
                sf.inSession(em -> {
                    var x = ModelQueryExecutor.create(em, CompositeNodeEntity.class,
                            ModelQueryConfig.defaults());
                    rows.addAll(x.list(query, Limit.unlimited()));
                    var grouped = ModelQuery.builder(nodes, row ->
                            new Detail(null, null, null, row.get(count).toString())).joinSource(bound, source)
                            .joinEntity(embedded).select(SelectSet.of(count)).build();
                    assertThat(x.list(grouped, Limit.unlimited())).extracting(Detail::viaOrder).containsExactly("6");
                    var association = EntityJoin.of("association", AssociationNode.class, JoinKind.LEFT,
                            List.of(EntityJoin.on(joinedId, "node")));
                    mq1911(() -> x.list(ModelQuery.builder(nodes, row ->
                            new Detail(row.get(id), null, null, null))
                            .joinSource(bound, source).joinEntity(association).select(SelectSet.of(id)).build(),
                            Limit.unlimited()));
                });
            }
        });
        assertThat(rows).hasSize(6).allSatisfy(row -> assertThat(row.manager()).isEqualTo(row.customer()));
        assertThat(rows.stream().map(r -> SourceJoinExecutionTest.text(r.id(), r.manager(), r.customer())))
                .containsExactlyElementsOf(SourceJoinExecutionTest.rows(db,
                        "select id, name, name from composite_nodes order by id"));
        assertThat(sql).anySatisfy(statement -> assertThat(statement).contains("count(").doesNotContain("count(("));
    }

    @TckTest
    void ac_src_55_ac_src_58_counts_use_ids_and_group_totals_count_groups(TckDatabase db) {
        var id = ColumnField.of(Counted.class, ORDERS, "id", Long.class);
        var bound = SourceJoin.of("keys", Keys.class, JoinKind.LEFT, List.of(SourceJoin.on(id, S_ID)));
        var anchor = ColumnField.over(Counted.class, bound.root(), S_ID);
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            for (var kind : List.of(JoinKind.LEFT, JoinKind.INNER)) {
                var entity = EntityJoin.of("copy", OrderEntity.class, kind, List.of(EntityJoin.on(anchor, "id")));
                var descendant = TableField.join(entity.root(), "customer", JoinType.INNER);
                for (var table : List.of(entity.root(), descendant)) {
                    for (var count : List.of(Agg.<Counted>count(table),
                            Agg.<Counted>count(table).filter(f -> f.gt(id, 0L)))) {
                        var query = ModelQuery.builder(ORDERS, row -> new Counted(row.get(id), row.get(count)))
                                .joinSource(bound, KEYS).joinEntity(entity).select(SelectSet.of(id, count))
                                .groupBy(id).where(f -> f.gte(id, 990L).lte(id, 1030L)).orderBy(id.asc()).build();
                        long groups = kind == JoinKind.INNER || table == descendant ? 31L : 41L;
                        assertThat(x.list(query, Limit.unlimited())).hasSize((int) groups).allSatisfy(row ->
                                assertThat(row.count()).isEqualTo(row.id() <= 1020L ? 1L : 0L));
                        assertThat(x.count(query)).isEqualTo(groups);
                        assertThat(x.page(query, PageSpec.of(0, 7), CountMode.COUNT).total()).hasValue(groups);
                    }
                }
            }
        }));
        assertThat(sql).allSatisfy(statement -> assertThat(statement)
                .doesNotContain("count(*)", "count(1)", "count(("));
    }

    @TckTest
    void ac_src_58_first_by_reads_a_joined_entity_once_per_group(TckDatabase db) {
        var first = Agg.firstBy(NAME, ID.asc());
        var query = ModelQuery.builder(ORDERS, row -> new Detail(null, row.get(first), null, null))
                .joinSource(SOURCE, KEYS).joinEntity(MANAGER).select(SelectSet.of(first))
                .where(f -> f.gte(ID, 990L).lte(ID, 1030L)).build();
        List<String> sql = SqlSnapshots.capture(db, ds -> withExecutor(ds, x -> {
            if (!VendorAggregates.servesFirstBy(db)) {
                assertThatThrownBy(() -> x.list(query, Limit.unlimited()))
                        .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015));
            } else {
                assertThat(x.list(query, Limit.unlimited())).extracting(Detail::manager)
                        .containsExactly("Customer 0990");
            }
        }));
        if (!VendorAggregates.servesFirstBy(db)) {
            assertThat(sql).isEmpty();
        }
    }

    @TckTest
    void ac_src_58_missing_hooks_refuse_in_vendor_source_entity_order_before_sql(TckDatabase db) {
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            NoEntityJoinProviderSupport.serving(() -> {
                withExecutor(ds, x -> mq4014(() -> x.list(details().build(), Limit.unlimited()), "entityJoins"));
                var config = ModelQueryConfig.defaults()
                        .vendorProfiles(List.of(SourceQueryExecutionTest.withoutSources(db)));
                try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
                    sf.inSession(em -> assertThatThrownBy(() -> ModelQueryExecutor
                            .create(em, OrderEntity.class, config).list(details().build(), Limit.unlimited()))
                            .isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                                    e -> assertThat(e.code()).isEqualTo(MqCode.MQ4015)));
                }
            });
            LegacyRecursiveProviderSupport.serving(false, () -> withExecutor(ds,
                    x -> mq4014(() -> x.list(details().build(), Limit.unlimited()), "chained CTEs")));
            JoinTestSupport.withoutServices(() -> withExecutor(ds,
                    x -> mq4014(() -> x.list(details().build(), Limit.unlimited()), "sources")));
        });
        assertThat(sql).isEmpty();
    }

    private static EntityJoin<Detail, CustomerEntity> customer(String name, JoinKind kind,
            ColumnField<Detail, ?, Long> anchor) {
        return EntityJoin.of(name, CustomerEntity.class, kind, List.of(EntityJoin.on(anchor, "id")));
    }

    private static void mq1911(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1911));
    }

    private static void mq4014(Runnable work, String message) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ModelQueryConfigurationException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ4014)).hasMessageContaining(message);
    }

    private static void withExecutor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(ModelQueryExecutor.create(em, OrderEntity.class,
                    ModelQueryConfig.defaults())));
        }
    }

    private static SessionFactory nodeFactory(DataSource ds) {
        Configuration configuration = new Configuration().addAnnotatedClass(CompositeNodeEntity.class)
                .addAnnotatedClass(EmbeddedNodeEntity.class).addAnnotatedClass(AssociationNode.class);
        configuration.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, ds);
        configuration.setProperty(AvailableSettings.HBM2DDL_AUTO, "none");
        return configuration.buildSessionFactory();
    }

    @Entity(name = "EntityJoinAssociationNode")
    @Table(name = "composite_nodes")
    static class AssociationNode {
        @Id
        @ManyToOne
        @JoinColumns({
            @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id"),
            @JoinColumn(name = "id", referencedColumnName = "id")
        })
        EmbeddedNodeEntity node;
    }
}
