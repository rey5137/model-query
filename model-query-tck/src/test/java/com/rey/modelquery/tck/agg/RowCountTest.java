package com.rey.modelquery.tck.agg;

import static jakarta.persistence.criteria.JoinType.INNER;
import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Correlated;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Op;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.SubSelect;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.tck.col.CompositeNodeEntity;
import com.rey.modelquery.tck.col.CustomerEntity;
import com.rey.modelquery.tck.col.JoinTestSupport;
import com.rey.modelquery.tck.col.OrderEntity;
import com.rey.modelquery.tck.exe.EmbeddedNodeEntity;
import com.rey.modelquery.tck.harness.TckDatabase;
import com.rey.modelquery.tck.harness.TckFixture;
import com.rey.modelquery.tck.harness.TckTest;
import com.rey.modelquery.tck.sql.SqlSnapshots;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;

/**
 * A count of an identity no row of the statement can leave null is the row count, {@code count(*)}, on every target,
 * and returns what counting the identity returns; every other count keeps counting the identity (spec api/13 R-AGG-19,
 * AC-AGG-27 to AC-AGG-29). Eligibility by chain shape, treated nodes, {@code rootedAt} contexts and the default
 * renderer, which keeps today's count, are unit-tested in the hibernate module's {@code RowCountEligibilityTest}; a
 * LEFT-joined source's count arrives with joined sources (M32.6).
 */
class RowCountTest {

    /** The one-cell row of the single-aggregate queries. */
    record Box(Object value) {}

    /** One status, its orders and their customers, the row of the grouped count. */
    record Group(String status, Long orders, Long customers) {}

    /** One order and a count, the row of the correlated count and of the count grouped by order. */
    record Line(Long id, Long count, Long paid) {}

    record Counts(Long id, Long rows, Long ids, Long paid, Long distinct) {}

    record NodeCounts(Integer tenant, Long count, Long filtered) {}

    /** The rows a sub-select reads. */
    static final class Inner {}

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final TableField<OrderEntity, CustomerEntity> CUSTOMER = TableField.join(ORDERS, "customer", INNER);
    private static final TableField<OrderEntity, CustomerEntity> REFERRER = TableField.join(ORDERS, "referrer", LEFT);

    private static final OrderedColumnField<Box, OrderEntity, Long> ID =
            ColumnField.of(Box.class, ORDERS, "id", Long.class);
    private static final ColumnField<Box, OrderEntity, String> STATUS =
            ColumnField.of(Box.class, ORDERS, "status", String.class);

    // ---- AC-AGG-27

    @TckTest
    void ac_agg_27_a_count_of_the_root_or_an_inner_joined_table_is_the_row_count(TckDatabase db) {
        var status = ColumnField.of(Group.class, ORDERS, "status", String.class);
        var orders = Agg.<Group>count(ORDERS);
        var customers = Agg.<Group>count(CUSTOMER);
        var grouped = ModelQuery.builder(ORDERS,
                        row -> new Group(row.get(status), row.get(orders), row.get(customers)))
                .select(SelectSet.of(status, orders, customers)).groupBy(status).orderBy(status.asc()).build();

        List<String> sql = SqlSnapshots.assertMatches(db, "agg-27-row-count", ds -> executor(ds, executor -> {
            value(executor, Agg.count(ORDERS));
            value(executor, Agg.count(ID));
            value(executor, Agg.count(CUSTOMER));
            executor.list(grouped, Limit.unlimited());
        }));
        assertThat(sql).hasSize(4).allSatisfy(statement -> assertThat(statement).contains("count(*)")
                .doesNotContain(".id)"));
        executor(db, executor -> {
            Object[] counts = oracle(db, "select count(o.id), count(c.id) from orders o "
                    + "join customers c on c.id = o.customer_id").get(0);
            assertThat(value(executor, Agg.count(ORDERS))).isEqualTo((long) TckFixture.ORDERS);
            assertThat(value(executor, Agg.count(ID))).isEqualTo((long) TckFixture.ORDERS);
            assertThat(value(executor, Agg.count(CUSTOMER))).isEqualTo(((Number) counts[1]).longValue());
            List<Group> expected = oracle(db, "select o.status, count(o.id), count(c.id) from orders o "
                    + "join customers c on c.id = o.customer_id group by o.status order by o.status").stream()
                    .map(r -> new Group((String) r[0], ((Number) r[1]).longValue(), ((Number) r[2]).longValue()))
                    .toList();
            assertThat(executor.list(grouped, Limit.unlimited())).hasSizeGreaterThan(1)
                    .containsExactlyElementsOf(expected);
        });
    }

    @TckTest
    void ac_agg_27_a_correlated_count_of_the_sub_select_root_is_the_row_count_and_zero_for_no_match(TckDatabase db) {
        var innerId = ColumnField.of(Inner.class, ORDERS, "id", Long.class);
        var innerStatus = ColumnField.of(Inner.class, ORDERS, "status", String.class);
        var id = ColumnField.of(Line.class, ORDERS, "id", Long.class);
        var status = ColumnField.of(Line.class, ORDERS, "status", String.class);
        var sameStatus = Correlated.count(Line.class,
                SubSelect.of(innerId).where(f -> f.lte(innerId, 6L).ne(innerStatus, "NEW")),
                (f, outer) -> f.compare(innerStatus, Op.EQ, outer.column(status)));
        var query = ModelQuery.builder(ORDERS, row -> new Line(row.get(id), row.get(sameStatus), null))
                .select(SelectSet.of(id, sameStatus)).where(f -> f.lte(id, 8L)).orderBy(id.asc()).build();

        List<String> sql = SqlSnapshots.assertMatches(db, "agg-27-correlated-row-count", ds ->
                executor(ds, executor -> executor.list(query, Limit.unlimited())));
        assertThat(sql).singleElement().satisfies(statement -> assertThat(statement).contains("count(*)"));
        executor(db, executor -> {
            List<Line> expected = oracle(db, "select o.id, (select count(i.id) from orders i where i.id <= 6 "
                    + "and i.status <> 'NEW' and i.status = o.status) from orders o where o.id <= 8 order by o.id")
                    .stream().map(r -> new Line(((Number) r[0]).longValue(), ((Number) r[1]).longValue(), null))
                    .toList();
            List<Line> lines = executor.list(query, Limit.unlimited());
            assertThat(lines).hasSize(8).containsExactlyElementsOf(expected);
            assertThat(lines).extracting(Line::count).contains(0L).anyMatch(count -> count > 1);
        });
    }

    // ---- AC-AGG-28

    @TckTest
    void ac_agg_28_a_count_that_can_meet_a_null_identity_keeps_counting_it(TckDatabase db) {
        var referrers = Agg.<Box>count(REFERRER);
        var foreignKey = Agg.count(ColumnField.of(Box.class, ORDERS, "referrer", Long.class));
        var nonId = Agg.count(ColumnField.of(Box.class, ORDERS, "referrerId", Long.class));
        var distinct = Agg.countDistinct(ID);
        var rows = Agg.<Box>count(ORDERS);
        var customized = ModelQuery.builder(ORDERS, row -> new Box(row.get(rows))).select(SelectSet.of(rows))
                .customize((spec, joins, criteria, cb, phase) -> {}).build();
        // The page total over a to-many join counts the distinct roots (R-EXE-04).
        var throughItems = ModelQuery.builder(ORDERS, row -> new Box(row.get(ID))).select(SelectSet.of(ID))
                .customize((spec, joins, criteria, cb, phase) -> criteria.where(cb.gt(
                        criteria.getRoots().iterator().next().join("items").<Integer>get("quantity"), 4))).build();

        // A customizer that adds only a predicate keeps count(t.id) for the page total (D-185).
        var predicateOnly = ModelQuery.builder(ORDERS, row -> new Box(row.get(ID))).select(SelectSet.of(ID))
                .customize((spec, joins, criteria, cb, phase) -> criteria.where(cb.equal(
                        criteria.getRoots().iterator().next().<String>get("status"), "PAID"))).build();

        List<String> sql = SqlSnapshots.assertMatches(db, "agg-28-identity-count", ds -> executor(ds, executor -> {
            value(executor, referrers);
            value(executor, foreignKey);
            value(executor, nonId);
            value(executor, distinct);
            executor.list(customized, Limit.unlimited());
            executor.count(throughItems);
            executor.count(predicateOnly);
        }));
        assertThat(sql).hasSize(7).allSatisfy(statement -> assertThat(statement).doesNotContain("count(*)"));
        executor(db, executor -> {
            long referred = ((Number) oracle(db, "select count(referrer_id) from orders").get(0)[0]).longValue();
            assertThat(referred).isPositive().isLessThan(TckFixture.ORDERS);
            assertThat(value(executor, referrers)).isEqualTo(referred);
            assertThat(value(executor, foreignKey)).isEqualTo(referred);
            assertThat(value(executor, nonId)).isEqualTo(referred);
            assertThat(value(executor, distinct)).isEqualTo((long) TckFixture.ORDERS);
            assertThat(executor.list(customized, Limit.unlimited())).containsExactly(
                    new Box((long) TckFixture.ORDERS));
            assertThat(executor.count(throughItems)).isEqualTo(((Number) oracle(db,
                    "select count(distinct order_id) from order_items where quantity > 4").get(0)[0]).longValue());
            assertThat(executor.count(predicateOnly)).isPositive().isEqualTo(((Number) oracle(db,
                    "select count(*) from orders where status = 'PAID'").get(0)[0]).longValue());
        });
    }

    // ---- AC-AGG-29

    @TckTest
    void ac_agg_29_the_page_total_of_an_entity_root_is_the_row_count(TckDatabase db) {
        var country = ColumnField.of(Box.class, CUSTOMER, "country", String.class);
        var paid = ModelQuery.builder(ORDERS, row -> new Box(row.get(ID))).select(SelectSet.of(ID))
                .where(f -> f.eq(STATUS, "PAID")).build();
        var inUs = ModelQuery.builder(ORDERS, row -> new Box(row.get(ID))).select(SelectSet.of(ID))
                .where(f -> f.eq(country, "US")).build();

        List<String> sql = SqlSnapshots.assertMatches(db, "agg-29-page-total", ds -> executor(ds, executor -> {
            executor.count(paid);
            executor.count(inUs);
        }));
        assertThat(sql).hasSize(2).allSatisfy(statement -> assertThat(statement).startsWith("select count(*) "));
        executor(db, executor -> {
            assertThat(executor.count(paid)).isPositive().isEqualTo(((Number) oracle(db,
                    "select count(*) from orders where status = 'PAID'").get(0)[0]).longValue());
            assertThat(executor.count(inUs)).isPositive().isEqualTo(((Number) oracle(db,
                    "select count(*) from orders o join customers c on c.id = o.customer_id "
                            + "where c.country = 'US'").get(0)[0]).longValue());
        });
    }

    @TckTest
    void ac_agg_29_a_filtered_row_count_counts_a_constant_under_its_condition(TckDatabase db) {
        AggregateField<Box, Long> rows = Agg.<Box>count(ORDERS).filter(f -> f.eq(STATUS, "PAID"));
        AggregateField<Box, Long> ids = Agg.count(ID).filter(f -> f.eq(STATUS, "PAID"));

        List<String> sql = SqlSnapshots.assertMatches(db, "agg-29-filtered-row-count", ds -> executor(ds, executor -> {
            value(executor, rows);
            value(executor, ids);
        }));
        // FILTER or the CASE form, by vendor: either way it counts 1, never the id (R-AGG-17, R-AGG-19).
        assertThat(sql).hasSize(2).allSatisfy(statement -> assertThat(statement).doesNotContain(".id"));
        executor(db, executor -> {
            long expected = ((Number) oracle(db, "select count(*) from orders where status = 'PAID'").get(0)[0])
                    .longValue();
            assertThat(value(executor, rows)).isEqualTo(expected);
            assertThat(value(executor, ids)).isEqualTo(expected);
        });
    }

    @TckTest
    void ac_agg_29_a_filtered_count_over_a_left_join_counts_zero_for_an_unmatched_row(TckDatabase db) {
        var id = ColumnField.of(Line.class, ORDERS, "id", Long.class);
        var status = ColumnField.of(Line.class, ORDERS, "status", String.class);
        var referrers = Agg.<Line>count(REFERRER);
        var paidReferrers = Agg.<Line>count(REFERRER).filter(f -> f.eq(status, "PAID"));
        var query = ModelQuery.builder(ORDERS,
                        row -> new Line(row.get(id), row.get(referrers), row.get(paidReferrers)))
                .select(SelectSet.of(id, referrers, paidReferrers)).groupBy(id).where(f -> f.lte(id, 60L))
                .orderBy(id.asc()).build();

        SqlSnapshots.assertMatches(db, "agg-29-filtered-left-count", ds ->
                executor(ds, executor -> executor.list(query, Limit.unlimited())));
        executor(db, executor -> {
            // The case that tells the argument apart: a PAID order with no referrer, which a constant would count.
            assertThat(((Number) oracle(db, "select count(*) from orders where id <= 60 and referrer_id is null "
                    + "and status = 'PAID'").get(0)[0]).longValue()).isPositive();
            List<Line> expected = oracle(db, "select o.id, count(c.id), count(case when o.status = 'PAID' "
                    + "then c.id end) from orders o left join customers c on c.id = o.referrer_id where o.id <= 60 "
                    + "group by o.id order by o.id").stream()
                    .map(r -> new Line(((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                            ((Number) r[2]).longValue()))
                    .toList();
            List<Line> lines = executor.list(query, Limit.unlimited());
            assertThat(lines).hasSize(60).containsExactlyElementsOf(expected);
            assertThat(lines).filteredOn(line -> line.count() == 0).isNotEmpty()
                    .allSatisfy(line -> assertThat(line.paid()).isZero());
        });
    }

    // ---- AC-AGG-31, AC-AGG-32

    @TckTest
    void ac_agg_31_entity_join_counts_match_native_ids_for_both_anchors_and_every_join_kind(TckDatabase db) {
        var id = ColumnField.of(Counts.class, ORDERS, "id", Long.class);
        var referrer = ColumnField.of(Counts.class, ORDERS, "referrerId", Long.class);
        var status = ColumnField.of(Counts.class, ORDERS, "status", String.class);
        var sourceId = ColumnField.of(Inner.class, ORDERS, "id", Long.class).named("id");
        var sourceReferrer = ColumnField.of(Inner.class, ORDERS, "referrerId", Long.class).named("referrer");
        var source = SourceQuery.builder(Inner.class, ORDERS, SelectSet.of(sourceId, sourceReferrer),
                PrimaryKey.of(sourceId)).where(f -> f.lte(sourceId, 1020L)).build();
        var keys = SourceJoin.of("keys", Inner.class, JoinKind.LEFT, List.of(SourceJoin.on(id, sourceId)));
        var anchor = ColumnField.over(Counts.class, keys.root(), sourceReferrer);
        var eligible = new ArrayList<Boolean>();
        List<String> sql = SqlSnapshots.capture(db, ds -> executor(ds, executor -> {
            for (boolean sourceAnchored : List.of(false, true)) {
                for (JoinKind kind : List.of(JoinKind.INNER, JoinKind.LEFT)) {
                    var entity = EntityJoin.of("copy", OrderEntity.class, kind,
                            List.of(EntityJoin.on(sourceAnchored ? anchor : referrer, "id")));
                    var joinedStatus = ColumnField.of(Counts.class, entity.root(), "status", String.class);
                    for (boolean blanked : kind == JoinKind.LEFT ? List.of(false, true) : List.of(false)) {
                        for (boolean descendant : List.of(false, true)) {
                            if (blanked && descendant) {
                                continue; // MQ1912 forbids INNER descendants below a join filter.
                            }
                            var table = descendant ? TableField.join(entity.root(), "customer", INNER) : entity.root();
                            var countedId = ColumnField.of(Counts.class, table, "id", Long.class);
                            var rows = Agg.<Counts>count(table);
                            var ids = Agg.count(countedId);
                            var paid = Agg.<Counts>count(table).filter(f -> f.eq(status, "PAID")).named("paid");
                            var distinct = Agg.countDistinct(countedId);
                            for (boolean grouped : List.of(false, true)) {
                                for (boolean customized : kind == JoinKind.INNER ? List.of(false, true)
                                        : List.of(false)) {
                                    var builder = ModelQuery.builder(ORDERS, row -> new Counts(
                                                    grouped ? row.get(id) : null, row.get(rows), row.get(ids),
                                                    row.get(paid), row.get(distinct)))
                                            .joinEntity(entity).select(SelectSet.of(rows, ids, paid, distinct))
                                            .where(f -> f.gte(id, 990L).lte(id, 1030L));
                                    if (sourceAnchored) {
                                        builder = builder.joinSource(keys, source);
                                    }
                                    if (blanked) {
                                        builder = builder.joinFilter(entity, f -> f.eq(joinedStatus, "PAID"));
                                    }
                                    if (grouped) {
                                        builder = builder.select(SelectSet.of(id, rows, ids, paid, distinct))
                                                .groupBy(id).orderBy(id.asc());
                                    }
                                    if (customized) {
                                        builder = builder.customize((spec, joins, criteria, cb, phase) -> {});
                                    }
                                    String from = " from orders o " + (sourceAnchored
                                            ? "left join (select id, referrer_id from orders where id <= 1020) s "
                                                    + "on s.id = o.id " : "")
                                            + (kind == JoinKind.INNER ? "join" : "left join")
                                            + " orders e on e.id = "
                                            + (sourceAnchored ? "s.referrer_id" : "o.referrer_id")
                                            + (blanked ? " and e.status = 'PAID'" : "")
                                            + (descendant ? " join customers c on c.id = e.customer_id" : "")
                                            + " where o.id >= 990 and o.id <= 1030";
                                    String identity = descendant ? "c.id" : "e.id";
                                    List<Counts> expected = oracle(db, "select " + (grouped ? "o.id, " : "")
                                            + "count(" + identity + "), count(" + identity + "), "
                                            + "count(case when o.status = 'PAID' then " + identity + " end), "
                                            + "count(distinct " + identity + ")" + from
                                            + (grouped ? " group by o.id order by o.id" : "")).stream().map(r -> {
                                                int offset = grouped ? 1 : 0;
                                                return new Counts(grouped ? ((Number) r[0]).longValue() : null,
                                                        ((Number) r[offset]).longValue(),
                                                        ((Number) r[offset + 1]).longValue(),
                                                        ((Number) r[offset + 2]).longValue(),
                                                        ((Number) r[offset + 3]).longValue());
                                            }).toList();
                                    assertThat(executor.list(builder.build(), Limit.unlimited()))
                                            .isNotEmpty().containsExactlyElementsOf(expected);
                                    if (grouped && kind == JoinKind.LEFT && !descendant) {
                                        assertThat(expected).anySatisfy(row -> assertThat(row.rows()).isZero());
                                    }
                                    eligible.add(kind == JoinKind.INNER && !customized);
                                }
                            }
                        }
                    }
                }
            }
        }));
        assertThat(sql).hasSize(eligible.size());
        for (int i = 0; i < sql.size(); i++) {
            assertThat(sql.get(i)).contains("count(distinct", "coalesce(");
            if (eligible.get(i)) {
                assertThat(sql.get(i)).contains("count(*)");
            } else {
                assertThat(sql.get(i)).doesNotContain("count(*)");
            }
        }
    }

    @TckTest
    void ac_agg_31_inner_entity_joins_with_composite_and_embedded_ids_count_one_identity(TckDatabase db) {
        var nodes = TableField.root(CompositeNodeEntity.class);
        var id = ColumnField.of(NodeCounts.class, nodes, "id", Long.class);
        var tenant = ColumnField.of(NodeCounts.class, nodes, "tenantId", Integer.class);
        var sourceId = ColumnField.of(Inner.class, nodes, "id", Long.class).named("id");
        var sourceTenant = ColumnField.of(Inner.class, nodes, "tenantId", Integer.class).named("tenant");
        var source = SourceQuery.builder(Inner.class, nodes, SelectSet.of(sourceId, sourceTenant),
                PrimaryKey.composite(sourceTenant, sourceId)).build();
        var keys = SourceJoin.of("keys", Inner.class, JoinKind.LEFT,
                List.of(SourceJoin.on(id, sourceId), SourceJoin.on(tenant, sourceTenant)));
        List<String> sql = SqlSnapshots.capture(db, ds -> {
            try (SessionFactory sf = JoinTestSupport.sessionFactory(ds, EmbeddedNodeEntity.class)) {
                sf.inSession(em -> {
                    var executor = ModelQueryExecutor.create(em, CompositeNodeEntity.class,
                            ModelQueryConfig.defaults());
                    for (boolean sourceAnchored : List.of(false, true)) {
                        var anchorId = sourceAnchored ? ColumnField.over(NodeCounts.class, keys.root(), sourceId) : id;
                        var anchorTenant = sourceAnchored
                                ? ColumnField.over(NodeCounts.class, keys.root(), sourceTenant) : tenant;
                        var composite = EntityJoin.of("composite", CompositeNodeEntity.class, JoinKind.INNER,
                                List.of(EntityJoin.on(anchorId, "id"), EntityJoin.on(anchorTenant, "tenantId")));
                        var embedded = EntityJoin.of("embedded", EmbeddedNodeEntity.class, JoinKind.INNER,
                                List.of(EntityJoin.on(anchorId, "key.id"),
                                        EntityJoin.on(anchorTenant, "key.tenantId")));
                        for (var entity : List.of(composite, embedded)) {
                            var count = Agg.<NodeCounts>count(entity.root());
                            var filtered = count.filter(f -> f.lte(id, 3L)).named("filtered");
                            for (boolean grouped : List.of(false, true)) {
                                var builder = ModelQuery.builder(nodes, row -> new NodeCounts(
                                                grouped ? row.get(tenant) : null, row.get(count), row.get(filtered)))
                                        .joinEntity(entity).select(SelectSet.of(count, filtered));
                                if (sourceAnchored) {
                                    builder = builder.joinSource(keys, source);
                                }
                                if (grouped) {
                                    builder = builder.select(SelectSet.of(tenant, count, filtered)).groupBy(tenant)
                                            .orderBy(tenant.asc());
                                }
                                List<NodeCounts> expected = oracle(db, "select " + (grouped ? "o.tenant_id, " : "")
                                        + "count(e.id), count(case when o.id <= 3 then e.id end) "
                                        + "from composite_nodes o "
                                        + (sourceAnchored ? "left join composite_nodes s on s.id = o.id "
                                                + "and s.tenant_id = o.tenant_id " : "")
                                        + "join composite_nodes e on e.id = " + (sourceAnchored ? "s" : "o")
                                        + ".id and e.tenant_id = " + (sourceAnchored ? "s" : "o") + ".tenant_id"
                                        + (grouped ? " group by o.tenant_id order by o.tenant_id" : "")).stream()
                                        .map(r -> new NodeCounts(grouped ? ((Number) r[0]).intValue() : null,
                                                ((Number) r[grouped ? 1 : 0]).longValue(),
                                                ((Number) r[grouped ? 2 : 1]).longValue())).toList();
                                assertThat(executor.list(builder.build(), Limit.unlimited()))
                                        .isNotEmpty().containsExactlyElementsOf(expected);
                            }
                        }
                    }
                });
            }
        });
        assertThat(sql).hasSize(8).allSatisfy(statement -> assertThat(statement)
                .contains("count(*)", "coalesce(").doesNotContain("count(("));
    }

    @TckTest
    void ac_agg_32_filtered_counts_over_empty_roots_and_entity_joins_are_zero_and_sums_stay_null(TckDatabase db) {
        var id = ColumnField.of(Counts.class, ORDERS, "id", Long.class);
        var rootCount = Agg.<Counts>count(ORDERS).filter(f -> f.gt(id, 0L));
        var copy = EntityJoin.of("copy", OrderEntity.class, JoinKind.INNER, List.of(EntityJoin.on(id, "id")));
        var joinedCount = Agg.<Counts>count(copy.root()).filter(f -> f.gt(id, 0L));
        var sum = Agg.sumAsLong(id).filter(f -> f.gt(id, 0L));
        var query = ModelQuery.builder(ORDERS,
                        row -> new Counts(null, row.get(rootCount), row.get(joinedCount), row.get(sum), null))
                .joinEntity(copy).select(SelectSet.of(rootCount, joinedCount, sum)).where(f -> f.lt(id, 0L)).build();
        executor(db, executor -> assertThat(executor.list(query, Limit.unlimited()))
                .containsExactly(new Counts(null, 0L, 0L, null, null)));
        Object[] nativeCounts = oracle(db, "select count(case when o.id > 0 then o.id end), "
                + "count(case when o.id > 0 then e.id end), sum(case when o.id > 0 then o.id end) "
                + "from orders o join orders e on e.id = o.id where o.id < 0").get(0);
        assertThat(((Number) nativeCounts[0]).longValue()).isZero();
        assertThat(((Number) nativeCounts[1]).longValue()).isZero();
        assertThat(nativeCounts[2]).isNull();
    }

    // ---- support

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object value(ModelQueryExecutor<OrderEntity> executor, AggregateField<Box, ?> field) {
        var query = ModelQuery.builder(ORDERS, row -> new Box(row.get((AggregateField) field)))
                .select(SelectSet.of((AggregateField) field)).build();
        return ((List<Box>) executor.list(query, Limit.unlimited())).get(0).value();
    }

    private static void executor(TckDatabase db, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(db)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }

    private static void executor(DataSource ds, Consumer<ModelQueryExecutor<OrderEntity>> work) {
        try (SessionFactory sf = JoinTestSupport.sessionFactory(ds)) {
            sf.inSession(em -> work.accept(
                    ModelQueryExecutor.create(em, OrderEntity.class, ModelQueryConfig.defaults())));
        }
    }

    /** Every row of {@code sql}, each as its column values. */
    private static List<Object[]> oracle(TckDatabase db, String sql) {
        var result = new ArrayList<Object[]>();
        try (var connection = db.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            int width = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                Object[] row = new Object[width];
                for (int i = 0; i < width; i++) {
                    row[i] = rows.getObject(i + 1);
                }
                result.add(row);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(sql, e);
        }
        return result;
    }
}
