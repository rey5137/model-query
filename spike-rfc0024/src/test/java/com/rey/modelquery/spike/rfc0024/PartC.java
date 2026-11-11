package com.rey.modelquery.spike.rfc0024;

import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.EntityJoin;
import com.rey.modelquery.annotations.JoinKind;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.OrderedColumnField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowMapper;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.SpikeCore;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.spike.rfc0024.Entities.SpCountry;
import com.rey.modelquery.spike.rfc0024.Entities.SpCustomer;
import com.rey.modelquery.spike.rfc0024.Entities.SpOrder;
import com.rey.modelquery.spike.rfc0024.Entities.SpRegion;
import java.util.List;
import java.util.Optional;

/**
 * Part C by hand: entity joins chained by column anchors, order → customer (on {@code customerId}) → region (on the
 * customer join's {@code regionId}) → country (on the region join's {@code countryId}). The anchors of the second and
 * third joins read another entity join: production refuses them with MQ1911 (R-SRC-25); the spike's shadow of
 * {@code EntityJoin} lets them through when {@link SpikeCore#chains} is on. Everything else is the production engine.
 */
final class PartC {

    record Chain(Long id, String customer, String region, String country) {}

    final TableField<SpOrder, SpOrder> root = TableField.root(SpOrder.class);
    final OrderedColumnField<Chain, SpOrder, Long> id = ColumnField.of(Chain.class, root, "id", Long.class).named("id");
    final OrderedColumnField<Chain, SpOrder, Long> customerId =
            ColumnField.of(Chain.class, root, "customerId", Long.class).named("customerId");
    final EntityJoin<Chain, SpCustomer> customer;
    final OrderedColumnField<Chain, SpCustomer, String> customerName;
    final OrderedColumnField<Chain, SpCustomer, Long> customerRegionId;
    final EntityJoin<Chain, SpRegion> region;
    final OrderedColumnField<Chain, SpRegion, String> regionName;
    final OrderedColumnField<Chain, SpRegion, Long> regionCountryId;
    final EntityJoin<Chain, SpCountry> country;
    final OrderedColumnField<Chain, SpCountry, String> countryName;
    final SelectSet<Chain> all;
    final PrimaryKey<Chain, Long> key = PrimaryKey.of(id);
    final List<JoinKind> kinds;

    /** {@code kinds} has two (customer, region) or three (customer, region, country) entries. */
    PartC(List<JoinKind> kinds) {
        this.kinds = kinds;
        customer = EntityJoin.of("c", SpCustomer.class, kinds.get(0), List.of(EntityJoin.on(customerId, "id")));
        customerName = ColumnField.of(Chain.class, customer.root(), "name", String.class).named("customer");
        customerRegionId = ColumnField.of(Chain.class, customer.root(), "regionId", Long.class);
        region = EntityJoin.of("r", SpRegion.class, kinds.get(1), List.of(EntityJoin.on(customerRegionId, "id")));
        regionName = ColumnField.of(Chain.class, region.root(), "name", String.class).named("region");
        regionCountryId = ColumnField.of(Chain.class, region.root(), "countryId", Long.class);
        if (kinds.size() > 2) {
            country = EntityJoin.of("n", SpCountry.class, kinds.get(2),
                    List.of(EntityJoin.on(regionCountryId, "id")));
            countryName = ColumnField.of(Chain.class, country.root(), "name", String.class).named("country");
            all = SelectSet.of(id, customerName, regionName, countryName);
        } else {
            country = null;
            countryName = null;
            all = SelectSet.of(id, customerName, regionName);
        }
    }

    String label() {
        var text = new StringBuilder();
        kinds.forEach(kind -> text.append(kind.name().charAt(0)));
        return text.toString();
    }

    /** The joins in creation order, or reversed (the anchored join first). */
    ModelQuery.Builder<SpOrder, Long, Chain> query(boolean reversed) {
        var builder = ModelQuery.builder(root, (RowMapper<Chain>) this::map).primaryKey(key);
        var joins = new java.util.ArrayList<EntityJoin<Chain, ?>>(country == null ? List.of(customer, region)
                : List.of(customer, region, country));
        if (reversed) {
            java.util.Collections.reverse(joins);
        }
        for (EntityJoin<Chain, ?> join : joins) {
            builder = builder.joinEntity(join);
        }
        return builder;
    }

    private Chain map(Row row) {
        return new Chain(row.get(id), row.get(customerName), row.get(regionName),
                countryName == null ? null : row.get(countryName));
    }

    String text(Chain chain) {
        return countryName == null ? Harness.row(chain.id(), chain.customer(), chain.region())
                : Harness.row(chain.id(), chain.customer(), chain.region(), chain.country());
    }

    /** The native SQL the chain should mean, with the same join kinds and an optional filter on the first ON. */
    String nativeSql(String customerFilter) {
        var sql = new StringBuilder("select o.id, c.name, r.name");
        if (country != null) {
            sql.append(", n.name");
        }
        sql.append(" from sp_order o ").append(join(0)).append(" sp_customer c on c.id = o.customer_id");
        if (customerFilter != null) {
            sql.append(" and ").append(customerFilter);
        }
        sql.append(' ').append(join(1)).append(" sp_region r on r.id = c.region_id");
        if (country != null) {
            sql.append(' ').append(join(2)).append(" sp_country n on n.id = r.country_id");
        }
        return sql.append(" order by o.id").toString();
    }

    private String join(int i) {
        return kinds.get(i) == JoinKind.LEFT ? "left join" : "join";
    }

    /**
     * Part A over Part B over Part C: {@code @Join(entityJoin = "buyer") Optional<BuyerR> buyer} where the nested
     * {@code BuyerR} declares its own {@code @JoinEntity region} on {@code regionId}. The outer registers the nested
     * join as {@code buyer_region}, anchored on the {@code buyer} entity join's column: a chain.
     */
    static final class Combined {
        record BuyerR(Long id, String name, String regionName) {}

        record OrderBuyerR(Long id, Optional<BuyerR> buyer) {}

        static final TableField<SpCustomer, SpCustomer> B_ROOT = TableField.root(SpCustomer.class);
        static final OrderedColumnField<BuyerR, SpCustomer, Long> B_ID =
                ColumnField.of(BuyerR.class, B_ROOT, "id", Long.class).named("id");
        static final OrderedColumnField<BuyerR, SpCustomer, String> B_NAME =
                ColumnField.of(BuyerR.class, B_ROOT, "name", String.class).named("name");
        static final OrderedColumnField<BuyerR, SpCustomer, Long> B_REGION_ID =
                ColumnField.of(BuyerR.class, B_ROOT, "regionId", Long.class).named("regionId");
        static final EntityJoin<BuyerR, SpRegion> B_REGION =
                EntityJoin.of("region", SpRegion.class, JoinKind.LEFT, List.of(EntityJoin.on(B_REGION_ID, "id")));
        static final OrderedColumnField<BuyerR, SpRegion, String> B_REGION_NAME =
                ColumnField.of(BuyerR.class, B_REGION.root(), "name", String.class).named("regionName");
        static final PrimaryKey<BuyerR, Long> B_KEY = PrimaryKey.of(B_ID);
        static final RowMapper<BuyerR> B_MAPPER = row -> new BuyerR(row.get(B_ID), row.get(B_NAME),
                row.get(B_REGION_NAME));

        static final TableField<SpOrder, SpOrder> ROOT = TableField.root(SpOrder.class);
        static final OrderedColumnField<OrderBuyerR, SpOrder, Long> ID =
                ColumnField.of(OrderBuyerR.class, ROOT, "id", Long.class).named("id");
        static final OrderedColumnField<OrderBuyerR, SpOrder, Long> CUSTOMER_ID =
                ColumnField.of(OrderBuyerR.class, ROOT, "customerId", Long.class).named("customerId");
        static final EntityJoin<OrderBuyerR, SpCustomer> BUYER = EntityJoin.of("buyer", SpCustomer.class,
                JoinKind.LEFT, List.of(EntityJoin.on(CUSTOMER_ID, "id")));
        static final TableField<SpCustomer, SpCustomer> BUYER_TABLE = SpikeCore.presentBy(BUYER.root(), B_KEY);
        static final OrderedColumnField<OrderBuyerR, SpCustomer, Long> BUYER_ID =
                B_ID.withTable(OrderBuyerR.class, BUYER_TABLE);
        static final OrderedColumnField<OrderBuyerR, SpCustomer, String> BUYER_NAME =
                B_NAME.withTable(OrderBuyerR.class, BUYER_TABLE);
        static final OrderedColumnField<OrderBuyerR, SpCustomer, Long> BUYER_REGION_ID =
                B_REGION_ID.withTable(OrderBuyerR.class, BUYER_TABLE);
        static final EntityJoin<OrderBuyerR, SpRegion> BUYER_REGION = EntityJoin.of("buyer_region", SpRegion.class,
                JoinKind.LEFT, List.of(EntityJoin.on(BUYER_REGION_ID, "id")));
        static final OrderedColumnField<OrderBuyerR, SpRegion, String> BUYER_REGION_NAME =
                B_REGION_NAME.withTable(OrderBuyerR.class, BUYER_REGION.root());
        static final SelectSet<OrderBuyerR> ALL = SelectSet.of(ID, BUYER_ID, BUYER_NAME, BUYER_REGION_NAME);
        static final PrimaryKey<OrderBuyerR, Long> KEY = PrimaryKey.of(ID);
        static final SpikeCore.NameMap BUYER_NAMES = SpikeCore.nameMap(BUYER_TABLE, B_REGION, BUYER_REGION);

        static final RowMapper<OrderBuyerR> MAPPER = row -> {
            Row nested = SpikeCore.scoped(row, BUYER_NAMES);
            return new OrderBuyerR(row.get(ID),
                    nested.get(B_ID) == null ? Optional.empty() : Optional.of(B_MAPPER.map(nested)));
        };

        static ModelQuery.Builder<SpOrder, Long, OrderBuyerR> query() {
            return ModelQuery.builder(ROOT, MAPPER).primaryKey(KEY).joinEntity(BUYER).joinEntity(BUYER_REGION);
        }

        private Combined() {}
    }
}
