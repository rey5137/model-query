package com.rey.modelquery.spike.rfc0024;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.AggregateField;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SourceJoin;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.SpikeCore;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.spike.rfc0024.Entities.SpCustomer;
import com.rey.modelquery.spike.rfc0024.Entities.SpOrder;
import com.rey.modelquery.spike.rfc0024.Entities.SpPayment;
import com.rey.modelquery.spike.rfc0024.Entities.SpRegion;
import jakarta.persistence.criteria.JoinType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Part B by hand: what the processor would generate for
 *
 * <pre>
 * &#64;QueryModel(root = SpCustomer.class)
 * &#64;JoinSource(name = "totals", source = CustomerTotals.class,
 *         on = @JoinSource.On(column = "id", key = "customerId"))
 * &#64;JoinEntity(name = "region", entity = SpRegion.class, on = @JoinEntity.On(column = "regionId"))
 * record BuyerB(@PrimaryKey Long id, String name, Long regionId,
 *         &#64;Column(entityJoin = "region", attribute = "name") String regionName,
 *         &#64;SourceColumn(join = "totals", field = "spent") BigDecimal spent) {}
 *
 * &#64;QueryModel(root = SpOrder.class)
 * record OrderView(@PrimaryKey Long id, @Join Optional&lt;BuyerB&gt; customer,
 *         &#64;Join Optional&lt;BuyerB&gt; referrer) {}
 * </pre>
 *
 * The outer class registers the nested joins under the field prefixes ({@code customer_region},
 * {@code referrer_totals}, ...), anchored on the {@code @Join} paths, and keeps one {@link SpikeCore.NameMap} per
 * field. Emulated: the name maps and {@link SpikeCore#scoped}; everything else is the production engine.
 */
final class PartB {
    private PartB() {}

    record CustomerTotals(Long customerId, BigDecimal spent, Long payments) {}

    record BuyerB(Long id, String name, Long regionId, String regionName, BigDecimal spent) {}

    record OrderView(Long id, Optional<BuyerB> customer, Optional<BuyerB> referrer) {}

    record OrderSpend(BigDecimal customerSpent, BigDecimal referrerSpent, Long orders) {}

    /** {@code QCustomerTotals}: a grouped source over payments. */
    static final class QCustomerTotals {
        static final TableField<SpPayment, SpPayment> ROOT = TableField.root(SpPayment.class);
        static final OrderedColumnField<CustomerTotals, SpPayment, Long> CUSTOMER_ID =
                ColumnField.of(CustomerTotals.class, ROOT, "customerId", Long.class).named("customerId");
        static final OrderedColumnField<CustomerTotals, SpPayment, BigDecimal> AMOUNT =
                ColumnField.of(CustomerTotals.class, ROOT, "amount", BigDecimal.class);
        static final AggregateField<CustomerTotals, BigDecimal> SPENT =
                Agg.sum(ColumnField.of(CustomerTotals.class, ROOT, "amount", BigDecimal.class)).named("spent");
        static final AggregateField<CustomerTotals, Long> PAYMENTS =
                Agg.<CustomerTotals>count(ROOT).named("payments");
        static final SelectSet<CustomerTotals> ALL = SelectSet.of(CUSTOMER_ID, SPENT, PAYMENTS);
        static final SelectSet<CustomerTotals> GROUP_KEYS = SelectSet.of(CUSTOMER_ID);
        static final PrimaryKey<CustomerTotals, Long> KEY = PrimaryKey.of(CUSTOMER_ID);

        static SourceQuery.Builder<SpPayment, CustomerTotals, CustomerTotals> source() {
            return SourceQuery.builder(CustomerTotals.class, ROOT, ALL, KEY, GROUP_KEYS);
        }

        private QCustomerTotals() {}
    }

    /** {@code QBuyerB}: the nested model, with its own entity join and source join. */
    static final class QBuyerB {
        static final TableField<SpCustomer, SpCustomer> ROOT = TableField.root(SpCustomer.class);
        static final OrderedColumnField<BuyerB, SpCustomer, Long> ID =
                ColumnField.of(BuyerB.class, ROOT, "id", Long.class).named("id");
        static final OrderedColumnField<BuyerB, SpCustomer, String> NAME =
                ColumnField.of(BuyerB.class, ROOT, "name", String.class).named("name");
        static final OrderedColumnField<BuyerB, SpCustomer, Long> REGION_ID =
                ColumnField.of(BuyerB.class, ROOT, "regionId", Long.class).named("regionId");
        static final EntityJoin<BuyerB, SpRegion> REGION =
                EntityJoin.of("region", SpRegion.class, JoinKind.LEFT, List.of(EntityJoin.on(REGION_ID, "id")));
        static final OrderedColumnField<BuyerB, SpRegion, String> REGION_NAME =
                ColumnField.of(BuyerB.class, REGION.root(), "name", String.class).named("regionName");
        static final SourceJoin<BuyerB, CustomerTotals> TOTALS = SourceJoin.of("totals", CustomerTotals.class,
                JoinKind.LEFT, List.of(SourceJoin.on(ID, QCustomerTotals.CUSTOMER_ID)));
        static final OrderedColumnField<BuyerB, CustomerTotals, BigDecimal> SPENT =
                ColumnField.over(BuyerB.class, TOTALS.root(), QCustomerTotals.SPENT).named("spent");
        static final SelectSet<BuyerB> ALL = SelectSet.of(ID, NAME, REGION_ID, REGION_NAME, SPENT);
        static final PrimaryKey<BuyerB, Long> KEY = PrimaryKey.of(ID);
        static final RowMapper<BuyerB> MAPPER = row -> new BuyerB(row.get(ID), row.get(NAME), row.get(REGION_ID),
                row.get(REGION_NAME), row.get(SPENT));

        /** The nested model run on its own, as today: its joins register through its own factory. */
        static ModelQuery.Builder<SpCustomer, Long, BuyerB> query(SourceQuery<CustomerTotals> totals) {
            return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY).joinSource(TOTALS, totals).joinEntity(REGION);
        }

        private QBuyerB() {}
    }

    /** {@code QOrderView}: the outer model, nested joins registered under the field prefixes. */
    static final class QOrderView {
        static final TableField<SpOrder, SpOrder> ROOT = TableField.root(SpOrder.class);
        static final OrderedColumnField<OrderView, SpOrder, Long> ID =
                ColumnField.of(OrderView.class, ROOT, "id", Long.class).named("id");

        static final TableField<SpOrder, SpCustomer> CUSTOMER_TABLE = TableField.<SpOrder, SpCustomer>join(ROOT,
                "customer", JoinType.LEFT).presentBy(QBuyerB.KEY).named("customer");
        static final OrderedColumnField<OrderView, SpCustomer, Long> CUSTOMER_ID =
                QBuyerB.ID.withTable(OrderView.class, CUSTOMER_TABLE);
        static final OrderedColumnField<OrderView, SpCustomer, String> CUSTOMER_NAME =
                QBuyerB.NAME.withTable(OrderView.class, CUSTOMER_TABLE);
        static final OrderedColumnField<OrderView, SpCustomer, Long> CUSTOMER_REGION_ID =
                QBuyerB.REGION_ID.withTable(OrderView.class, CUSTOMER_TABLE);
        static final EntityJoin<OrderView, SpRegion> CUSTOMER_REGION = EntityJoin.of("customer_region",
                SpRegion.class, JoinKind.LEFT, List.of(EntityJoin.on(CUSTOMER_REGION_ID, "id")));
        static final OrderedColumnField<OrderView, SpRegion, String> CUSTOMER_REGION_NAME =
                QBuyerB.REGION_NAME.withTable(OrderView.class, CUSTOMER_REGION.root());
        static final SourceJoin<OrderView, CustomerTotals> CUSTOMER_TOTALS = SourceJoin.of("customer_totals",
                CustomerTotals.class, JoinKind.LEFT, List.of(SourceJoin.on(CUSTOMER_ID, QCustomerTotals.CUSTOMER_ID)));
        static final OrderedColumnField<OrderView, CustomerTotals, BigDecimal> CUSTOMER_SPENT =
                ColumnField.over(OrderView.class, CUSTOMER_TOTALS.root(), QCustomerTotals.SPENT).named("spent");

        static final TableField<SpOrder, SpCustomer> REFERRER_TABLE = TableField.<SpOrder, SpCustomer>join(ROOT,
                "referrer", JoinType.LEFT).presentBy(QBuyerB.KEY).named("referrer");
        static final OrderedColumnField<OrderView, SpCustomer, Long> REFERRER_ID =
                QBuyerB.ID.withTable(OrderView.class, REFERRER_TABLE);
        static final OrderedColumnField<OrderView, SpCustomer, String> REFERRER_NAME =
                QBuyerB.NAME.withTable(OrderView.class, REFERRER_TABLE);
        static final OrderedColumnField<OrderView, SpCustomer, Long> REFERRER_REGION_ID =
                QBuyerB.REGION_ID.withTable(OrderView.class, REFERRER_TABLE);
        static final EntityJoin<OrderView, SpRegion> REFERRER_REGION = EntityJoin.of("referrer_region",
                SpRegion.class, JoinKind.LEFT, List.of(EntityJoin.on(REFERRER_REGION_ID, "id")));
        static final OrderedColumnField<OrderView, SpRegion, String> REFERRER_REGION_NAME =
                QBuyerB.REGION_NAME.withTable(OrderView.class, REFERRER_REGION.root());
        static final SourceJoin<OrderView, CustomerTotals> REFERRER_TOTALS = SourceJoin.of("referrer_totals",
                CustomerTotals.class, JoinKind.LEFT, List.of(SourceJoin.on(REFERRER_ID, QCustomerTotals.CUSTOMER_ID)));
        static final OrderedColumnField<OrderView, CustomerTotals, BigDecimal> REFERRER_SPENT =
                ColumnField.over(OrderView.class, REFERRER_TOTALS.root(), QCustomerTotals.SPENT).named("spent");

        static final SelectSet<OrderView> ALL = SelectSet.of(ID, CUSTOMER_ID, CUSTOMER_NAME, CUSTOMER_REGION_ID,
                CUSTOMER_REGION_NAME, CUSTOMER_SPENT, REFERRER_ID, REFERRER_NAME, REFERRER_REGION_ID,
                REFERRER_REGION_NAME, REFERRER_SPENT);
        static final PrimaryKey<OrderView, Long> KEY = PrimaryKey.of(ID);

        static final SpikeCore.NameMap CUSTOMER_NAMES = SpikeCore.nameMap(CUSTOMER_TABLE,
                QBuyerB.REGION, CUSTOMER_REGION, QBuyerB.TOTALS, CUSTOMER_TOTALS);
        static final SpikeCore.NameMap REFERRER_NAMES = SpikeCore.nameMap(REFERRER_TABLE,
                QBuyerB.REGION, REFERRER_REGION, QBuyerB.TOTALS, REFERRER_TOTALS);

        /** The RFC's mapper: scoped rows that stop at the nested joined roots. */
        static final RowMapper<OrderView> MAPPER = row -> new OrderView(row.get(ID),
                nested(SpikeCore.scoped(row, CUSTOMER_NAMES)), nested(SpikeCore.scoped(row, REFERRER_NAMES)));

        /** Today's {@code Row.scoped} with the same mapper: the INV-5 hazard the RFC names. */
        static final RowMapper<OrderView> TODAY = row -> new OrderView(row.get(ID),
                nested(row.scoped(CUSTOMER_TABLE)), nested(row.scoped(REFERRER_TABLE)));

        static Optional<BuyerB> nested(Row scoped) {
            return scoped.get(QBuyerB.ID) == null ? Optional.empty() : Optional.of(QBuyerB.MAPPER.map(scoped));
        }

        /** The generated factory: the nested source parameters after the outer's own, depth-first, prefixed. */
        static ModelQuery.Builder<SpOrder, Long, OrderView> query(SourceQuery<CustomerTotals> customerTotals,
                SourceQuery<CustomerTotals> referrerTotals) {
            return query(MAPPER, customerTotals, referrerTotals);
        }

        static ModelQuery.Builder<SpOrder, Long, OrderView> query(RowMapper<OrderView> mapper,
                SourceQuery<CustomerTotals> customerTotals, SourceQuery<CustomerTotals> referrerTotals) {
            return ModelQuery.builder(ROOT, mapper).primaryKey(KEY)
                    .joinSource(CUSTOMER_TOTALS, customerTotals).joinSource(REFERRER_TOTALS, referrerTotals)
                    .joinEntity(CUSTOMER_REGION).joinEntity(REFERRER_REGION);
        }

        private QOrderView() {}
    }

    /** A grouped outer model reading through both prefixed source joins (item 3, D-192). */
    static final class QOrderSpend {
        static final AggregateField<OrderSpend, Long> ORDERS = Agg.<OrderSpend>count(QOrderView.ROOT).named("orders");
        static final OrderedColumnField<OrderSpend, SpCustomer, Long> CUSTOMER_ID =
                QBuyerB.ID.withTable(OrderSpend.class, QOrderView.CUSTOMER_TABLE);
        static final OrderedColumnField<OrderSpend, SpCustomer, Long> REFERRER_ID =
                QBuyerB.ID.withTable(OrderSpend.class, QOrderView.REFERRER_TABLE);
        static final SourceJoin<OrderSpend, CustomerTotals> CUSTOMER_TOTALS = SourceJoin.of("customer_totals",
                CustomerTotals.class, JoinKind.LEFT, List.of(SourceJoin.on(CUSTOMER_ID, QCustomerTotals.CUSTOMER_ID)));
        static final SourceJoin<OrderSpend, CustomerTotals> REFERRER_TOTALS = SourceJoin.of("referrer_totals",
                CustomerTotals.class, JoinKind.LEFT, List.of(SourceJoin.on(REFERRER_ID, QCustomerTotals.CUSTOMER_ID)));
        static final OrderedColumnField<OrderSpend, CustomerTotals, BigDecimal> CUSTOMER_SPENT =
                ColumnField.over(OrderSpend.class, CUSTOMER_TOTALS.root(), QCustomerTotals.SPENT)
                        .named("customerSpent");
        static final OrderedColumnField<OrderSpend, CustomerTotals, BigDecimal> REFERRER_SPENT =
                ColumnField.over(OrderSpend.class, REFERRER_TOTALS.root(), QCustomerTotals.SPENT)
                        .named("referrerSpent");
        static final SelectSet<OrderSpend> GROUP_KEYS = SelectSet.of(CUSTOMER_SPENT, REFERRER_SPENT);
        static final SelectSet<OrderSpend> ALL = SelectSet.of(CUSTOMER_SPENT, REFERRER_SPENT, ORDERS);
        static final RowMapper<OrderSpend> MAPPER = row -> new OrderSpend(row.get(CUSTOMER_SPENT),
                row.get(REFERRER_SPENT), row.get(ORDERS));

        static ModelQuery.Builder<SpOrder, Object, OrderSpend> query(SourceQuery<CustomerTotals> customerTotals,
                SourceQuery<CustomerTotals> referrerTotals) {
            return ModelQuery.builder(QOrderView.ROOT, MAPPER).groupBy(GROUP_KEYS)
                    .joinSource(CUSTOMER_TOTALS, customerTotals).joinSource(REFERRER_TOTALS, referrerTotals);
        }

        private QOrderSpend() {}
    }
}
