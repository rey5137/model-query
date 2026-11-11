package com.rey.modelquery.core;

import static jakarta.persistence.criteria.JoinType.LEFT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rey.modelquery.annotations.JoinKind;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * A nested model's own joins read through a {@code @Join}: the scoped row stops at joined roots and maps them through
 * a checked {@link JoinScope} (spec api/10 §5, R-COL-15, D-214, D-216). The "generated" constants are hand-written,
 * as the RFC 0024 spike's were: {@code Buyer} declares {@code region} and {@code totals}; {@code OrderView} registers
 * them under {@code customer} and {@code referrer}.
 */
class JoinScopeTest {

    static final class Order {}

    static final class Customer {}

    static final class Region {}

    static final class Country {}

    static final class Address {}

    static final class City {}

    static final class Tier {}

    static final class Totals {}

    static final class OrderView {}

    static final class Buyer {}

    static final class AddressView {}

    // Totals, the source Buyer joins, keyed by customerId.
    private static final TableField<Customer, Customer> TOTALS_ROOT = TableField.root(Customer.class);
    private static final ColumnField<Totals, Customer, Long> T_CUSTOMER_ID =
            ColumnField.of(Totals.class, TOTALS_ROOT, "customerId", Long.class).named("customerId");
    private static final ColumnField<Totals, Customer, BigDecimal> T_SPENT =
            ColumnField.of(Totals.class, TOTALS_ROOT, "spent", BigDecimal.class).named("spent");
    private static final ColumnField<Totals, Customer, Long> T_TIER_ID =
            ColumnField.of(Totals.class, TOTALS_ROOT, "tierId", Long.class).named("tierId");

    // AddressView: root Address, its own entity join city.
    private static final TableField<Address, Address> A_ROOT = TableField.root(Address.class);
    private static final ColumnField<AddressView, Address, Long> A_ID =
            ColumnField.of(AddressView.class, A_ROOT, "id", Long.class);
    private static final ColumnField<AddressView, Address, Long> A_CITY_ID =
            ColumnField.of(AddressView.class, A_ROOT, "cityId", Long.class);
    private static final EntityJoin<AddressView, City> A_CITY = EntityJoin.of("city", City.class, JoinKind.LEFT,
            List.of(EntityJoin.on(A_CITY_ID, "id")));
    private static final ColumnField<AddressView, City, String> A_CITY_NAME =
            ColumnField.of(AddressView.class, A_CITY.root(), "name", String.class);

    // Buyer: root Customer, its own joins region (with a to-one country below it), totals, and tier on totals.
    private static final TableField<Customer, Customer> B_ROOT = TableField.root(Customer.class);
    private static final ColumnField<Buyer, Customer, Long> B_ID =
            ColumnField.of(Buyer.class, B_ROOT, "id", Long.class);
    private static final PrimaryKey<Buyer, Long> B_KEY = PrimaryKey.of(B_ID);
    private static final ColumnField<Buyer, Customer, String> B_NAME =
            ColumnField.of(Buyer.class, B_ROOT, "name", String.class);
    private static final ColumnField<Buyer, Customer, Long> B_REGION_ID =
            ColumnField.of(Buyer.class, B_ROOT, "regionId", Long.class);
    private static final ColumnField<Buyer, Customer, Long> B_BILLING_REGION_ID =
            ColumnField.of(Buyer.class, B_ROOT, "billingRegionId", Long.class);
    private static final EntityJoin<Buyer, Region> B_REGION = EntityJoin.of("region", Region.class, JoinKind.LEFT,
            List.of(EntityJoin.on(B_REGION_ID, "id")));
    private static final EntityJoin<Buyer, Region> B_BILLING = EntityJoin.of("billing", Region.class,
            JoinKind.LEFT, List.of(EntityJoin.on(B_BILLING_REGION_ID, "id")));
    private static final ColumnField<Buyer, Region, String> B_REGION_NAME =
            ColumnField.of(Buyer.class, B_REGION.root(), "name", String.class);
    private static final TableField<Region, Country> B_COUNTRY = TableField.join(B_REGION.root(), "country", LEFT);
    private static final ColumnField<Buyer, Country, String> B_COUNTRY_NAME =
            ColumnField.of(Buyer.class, B_COUNTRY, "name", String.class);
    private static final SourceJoin<Buyer, Totals> B_TOTALS = SourceJoin.of("totals", Totals.class, JoinKind.LEFT,
            List.of(SourceJoin.on(B_ID, T_CUSTOMER_ID)));
    private static final ColumnField<Buyer, Totals, BigDecimal> B_SPENT =
            ColumnField.over(Buyer.class, B_TOTALS.root(), T_SPENT);
    private static final ColumnField<Buyer, Totals, Long> B_TIER_ID =
            ColumnField.over(Buyer.class, B_TOTALS.root(), T_TIER_ID);
    private static final EntityJoin<Buyer, Tier> B_TIER = EntityJoin.of("tier", Tier.class, JoinKind.LEFT,
            List.of(EntityJoin.on(B_TIER_ID, "id")));
    private static final ColumnField<Buyer, Tier, String> B_TIER_NAME =
            ColumnField.of(Buyer.class, B_TIER.root(), "name", String.class);
    // Buyer's @Join address, with AddressView's city registered under it.
    private static final TableField<Customer, Address> B_ADDRESS = TableField.<Customer, Address>join(B_ROOT,
            "address", LEFT).presentBy(PrimaryKey.of(A_ID));
    private static final EntityJoin<Buyer, City> B_ADDRESS_CITY = EntityJoin.of("address_city", City.class,
            JoinKind.LEFT, List.of(EntityJoin.on(A_CITY_ID.withTable(Buyer.class, B_ADDRESS), "id")))
            .within(B_ADDRESS);
    static final JoinScope B_ADDRESS_SCOPE = JoinScope.of(B_ADDRESS).entity(A_CITY, B_ADDRESS_CITY);

    // OrderView: root Order, @Join customer and @Join referrer, both Buyer.
    private static final TableField<Order, Order> ROOT = TableField.root(Order.class);
    private static final ColumnField<OrderView, Order, Long> ID =
            ColumnField.of(OrderView.class, ROOT, "id", Long.class);
    static final TableField<Order, Customer> CUSTOMER =
            TableField.<Order, Customer>join(ROOT, "customer", LEFT).presentBy(B_KEY);
    static final TableField<Order, Customer> REFERRER =
            TableField.<Order, Customer>join(ROOT, "referrer", LEFT).presentBy(B_KEY);
    static final ColumnField<OrderView, Customer, Long> CUSTOMER_ID = B_ID.withTable(OrderView.class, CUSTOMER);
    static final ColumnField<OrderView, Customer, String> CUSTOMER_NAME =
            B_NAME.withTable(OrderView.class, CUSTOMER);
    private static final ColumnField<OrderView, Customer, Long> CUSTOMER_REGION_ID =
            B_REGION_ID.withTable(OrderView.class, CUSTOMER);
    private static final ColumnField<OrderView, Customer, Long> CUSTOMER_BILLING_REGION_ID =
            B_BILLING_REGION_ID.withTable(OrderView.class, CUSTOMER);
    private static final ColumnField<OrderView, Customer, Long> REFERRER_REGION_ID =
            B_REGION_ID.withTable(OrderView.class, REFERRER);
    static final EntityJoin<OrderView, Region> CUSTOMER_REGION = EntityJoin.of("customer_region", Region.class,
            JoinKind.LEFT, List.of(EntityJoin.on(CUSTOMER_REGION_ID, "id"))).within(CUSTOMER);
    private static final EntityJoin<OrderView, Region> CUSTOMER_BILLING = EntityJoin.of("customer_billing",
            Region.class, JoinKind.LEFT, List.of(EntityJoin.on(CUSTOMER_BILLING_REGION_ID, "id"))).within(CUSTOMER);
    static final EntityJoin<OrderView, Region> REFERRER_REGION = EntityJoin.of("referrer_region", Region.class,
            JoinKind.LEFT, List.of(EntityJoin.on(REFERRER_REGION_ID, "id"))).within(REFERRER);
    static final SourceJoin<OrderView, Totals> CUSTOMER_TOTALS = SourceJoin.of("customer_totals", Totals.class,
            JoinKind.LEFT, List.of(SourceJoin.on(CUSTOMER_ID, T_CUSTOMER_ID))).within(CUSTOMER);
    private static final ColumnField<OrderView, Totals, Long> CUSTOMER_TIER_ID =
            B_TIER_ID.withTable(OrderView.class, CUSTOMER_TOTALS.root().named("customer"));
    private static final EntityJoin<OrderView, Tier> CUSTOMER_TIER = EntityJoin.of("customer_tier", Tier.class,
            JoinKind.LEFT, List.of(EntityJoin.on(CUSTOMER_TIER_ID, "id"))).within(CUSTOMER);
    private static final TableField<Customer, Address> CUSTOMER_ADDRESS = B_ADDRESS.withParent(CUSTOMER);
    private static final EntityJoin<OrderView, City> CUSTOMER_ADDRESS_CITY = EntityJoin.of("customer_address_city",
            City.class, JoinKind.LEFT, List.of(EntityJoin.on(A_CITY_ID.withTable(OrderView.class, CUSTOMER_ADDRESS),
                    "id"))).within(CUSTOMER_ADDRESS);

    static final ColumnField<OrderView, Region, String> CUSTOMER_REGION_NAME =
            B_REGION_NAME.withTable(OrderView.class, CUSTOMER_REGION.root().named("customer"));
    static final ColumnField<OrderView, Region, String> REFERRER_REGION_NAME =
            B_REGION_NAME.withTable(OrderView.class, REFERRER_REGION.root().named("referrer"));
    private static final ColumnField<OrderView, Country, String> CUSTOMER_COUNTRY_NAME = B_COUNTRY_NAME.withTable(
            OrderView.class, B_COUNTRY.withParent(CUSTOMER_REGION.root().named("customer")));
    static final ColumnField<OrderView, Totals, BigDecimal> CUSTOMER_SPENT =
            B_SPENT.withTable(OrderView.class, CUSTOMER_TOTALS.root().named("customer"));
    private static final ColumnField<OrderView, Tier, String> CUSTOMER_TIER_NAME =
            B_TIER_NAME.withTable(OrderView.class, CUSTOMER_TIER.root().named("customer"));
    static final ColumnField<OrderView, City, String> CUSTOMER_ADDRESS_CITY_NAME =
            A_CITY_NAME.withTable(OrderView.class, CUSTOMER_ADDRESS_CITY.root().named("customer"));

    static final JoinScope CUSTOMER_SCOPE = JoinScope.of(CUSTOMER).entity(B_REGION, CUSTOMER_REGION)
            .source(B_TOTALS, CUSTOMER_TOTALS).entity(B_TIER, CUSTOMER_TIER)
            .entity(B_ADDRESS_CITY, CUSTOMER_ADDRESS_CITY);
    static final JoinScope REFERRER_SCOPE = JoinScope.of(REFERRER).entity(B_REGION, REFERRER_REGION);

    /** A row of order 1: customer Ann in North with 12.5 spent, referrer Bob in South. */
    private static Row row() {
        var values = new HashMap<SelectField<?, ?>, Object>(Map.of(ID, 1L, CUSTOMER_ID, 10L, CUSTOMER_NAME, "Ann",
                CUSTOMER_REGION_NAME, "North", REFERRER_REGION_NAME, "South", CUSTOMER_COUNTRY_NAME, "Norway",
                CUSTOMER_SPENT, new BigDecimal("12.5"), CUSTOMER_TIER_NAME, "Gold",
                CUSTOMER_ADDRESS_CITY_NAME, "Oslo"));
        return RowSelection.of(List.copyOf(values.keySet())).row(values::get);
    }

    private static void assertMq1107(ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                e -> assertThat(e.code()).isEqualTo(MqCode.MQ1107)).hasMessageContaining(message);
    }

    @Test
    void ac_col_67_a_plain_scope_refuses_a_column_on_a_joined_root_instead_of_reading_the_scopes_own_column() {
        // Today's hazard: the scope's own name ('Ann') was read as the region name.
        Row customer = row().scoped(CUSTOMER);
        assertThat(customer.get(B_NAME)).isEqualTo("Ann");
        String message = "Buyer.name: read through JoinScope[join 'customer' (LEFT)], which maps no outer join for "
                + "its joined root joined entity 'region' (Region)";
        assertMq1107(() -> customer.get(B_REGION_NAME), message);
        assertMq1107(() -> customer.raw(B_REGION_NAME), message);
        assertMq1107(() -> customer.isSelected(B_REGION_NAME), message);
        assertMq1107(() -> customer.get(B_SPENT), "joined source 'totals' (Totals)");
    }

    @Test
    void ac_col_67_a_path_on_a_joined_root_is_never_re_rooted() {
        assertThatThrownBy(() -> B_REGION.root().key().reroot(CUSTOMER.key()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a path on the joined root 'region' cannot be re-rooted; map its join instead (D-216)");
        assertThatThrownBy(() -> B_COUNTRY.key().reroot(CUSTOMER.key())).isInstanceOf(IllegalStateException.class);
        assertThat(B_ADDRESS.key().reroot(CUSTOMER.key())).isEqualTo(CUSTOMER_ADDRESS.key());
    }

    @Test
    void ac_col_68_a_join_scope_reads_each_fields_own_prefixed_joins() {
        Row customer = row().scoped(CUSTOMER_SCOPE);
        assertThat(customer.get(B_ID)).isEqualTo(10L);
        assertThat(customer.get(B_REGION_NAME)).isEqualTo("North");
        assertThat(customer.get(B_COUNTRY_NAME)).isEqualTo("Norway"); // a to-one path below the joined root
        assertThat(customer.get(B_SPENT)).isEqualByComparingTo("12.5");
        assertThat(customer.get(B_TIER_NAME)).isEqualTo("Gold"); // an entity join anchored on the source join
        assertThat(customer.isSelected(B_REGION_NAME)).isTrue();
        assertThat(customer.raw(B_REGION_NAME)).isEqualTo("North");
        Row referrer = row().scoped(REFERRER_SCOPE);
        assertThat(referrer.get(B_REGION_NAME)).isEqualTo("South");
        assertThat(referrer.isSelected(B_COUNTRY_NAME)).isFalse();
    }

    @Test
    void ac_col_68_a_joined_root_the_scope_does_not_map_is_mq1107_never_the_roots_column() {
        Row referrer = row().scoped(REFERRER_SCOPE);
        assertMq1107(() -> referrer.get(B_SPENT), "Buyer.mq_spent: read through JoinScope[join 'referrer' (LEFT): "
                + "region -> referrer_region], which maps no outer join for its joined root joined source 'totals'");
    }

    @Test
    void ac_col_68_scopes_compose_two_levels_deep() {
        Row address = row().scoped(CUSTOMER_SCOPE).scoped(B_ADDRESS_SCOPE);
        assertThat(address.get(A_CITY_NAME)).isEqualTo("Oslo");
        // Buyer's own scope maps city; the referrer's scope maps no address_city of Buyer's, so it is refused.
        assertMq1107(() -> row().scoped(REFERRER_SCOPE).scoped(B_ADDRESS_SCOPE).get(A_CITY_NAME),
                "which maps no outer join for the joined root 'address_city'");
        // A plain scope inside: the nested @Join's joined root is still refused.
        assertMq1107(() -> row().scoped(CUSTOMER_SCOPE).scoped(B_ADDRESS).get(A_CITY_NAME),
                "joined entity 'city' (City)");
    }

    @Test
    void ac_col_69_a_swapped_prefix_is_refused_when_the_scope_is_built() {
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, REFERRER_REGION),
                "JoinScope[join 'customer' (LEFT)]: EntityJoin[referrer_region: LEFT Region] for EntityJoin[region: "
                        + "LEFT Region]: refused, the outer join is registered within join 'referrer' (LEFT), not "
                        + "this scope's");
        // Two joins of one entity in one field, swapped: the anchors tell them apart.
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, CUSTOMER_BILLING),
                "refused, the outer join reads billingRegionId on join 'customer' (LEFT), not regionId on root "
                        + "Customer re-rooted under the scope");
        assertThat(JoinScope.of(CUSTOMER).entity(B_BILLING, CUSTOMER_BILLING)).isNotNull();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ac_col_69_another_target_kind_or_registration_is_refused() {
        EntityJoin wrongTarget = CUSTOMER_TIER;
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, wrongTarget),
                "refused, they join a different entity or kind");
        var inner = EntityJoin.of("customer_region", Region.class, JoinKind.INNER,
                List.of(EntityJoin.on(CUSTOMER_REGION_ID, "id"))).within(CUSTOMER);
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, inner),
                "refused, they join a different entity or kind");
        var unregistered = EntityJoin.of("customer_region", Region.class, JoinKind.LEFT,
                List.of(EntityJoin.on(CUSTOMER_REGION_ID, "id")));
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, unregistered),
                "refused, the outer join is registered within no @Join, not this scope's");
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, CUSTOMER_REGION).entity(B_REGION, CUSTOMER_REGION),
                "refused, one of them is mapped already");
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, CUSTOMER_REGION)
                .entity(B_BILLING, CUSTOMER_REGION), "refused, one of them is mapped already");
        var otherKey = SourceJoin.of("customer_totals", Totals.class, JoinKind.LEFT,
                List.of(SourceJoin.on(CUSTOMER_ID, T_TIER_ID))).within(CUSTOMER);
        assertMq1107(() -> JoinScope.of(CUSTOMER).source(B_TOTALS, otherKey),
                "refused, pair 0 reads key 'tierId', not 'customerId'");
        var otherId = EntityJoin.of("customer_region", Region.class, JoinKind.LEFT,
                List.of(EntityJoin.on(CUSTOMER_REGION_ID, "code"))).within(CUSTOMER);
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, otherId),
                "refused, pair 0 pairs id 'code', not 'id'");
    }

    @Test
    void ac_col_69_an_outer_join_within_an_equal_keyed_copy_without_the_fields_presence_key_is_refused() {
        // Equal join key, no presentBy: selecting only its columns would read no key and map every Buyer as empty.
        TableField<Order, Customer> copy = TableField.join(ROOT, "customer", LEFT);
        var region = EntityJoin.of("customer_region", Region.class, JoinKind.LEFT,
                List.of(EntityJoin.on(B_REGION_ID.withTable(OrderView.class, copy), "id"))).within(copy);
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, region),
                "JoinScope[join 'customer' (LEFT)]: EntityJoin[customer_region: LEFT Region] for EntityJoin[region: "
                        + "LEFT Region]: refused, the outer join is registered within join 'customer' (LEFT) with no "
                        + "presentBy, not presentBy [Buyer.id]; register it within the @Join field's own constant");
        var totals = SourceJoin.of("customer_totals", Totals.class, JoinKind.LEFT,
                List.of(SourceJoin.on(B_ID.withTable(OrderView.class, copy), T_CUSTOMER_ID))).within(copy);
        assertMq1107(() -> JoinScope.of(CUSTOMER).source(B_TOTALS, totals),
                "registered within join 'customer' (LEFT) with no presentBy, not presentBy [Buyer.id]");
        // Another key on the copy is refused the same way.
        var other = EntityJoin.of("customer_region", Region.class, JoinKind.LEFT, List.of(EntityJoin.on(
                CUSTOMER_REGION_ID, "id"))).within(copy.presentBy(PrimaryKey.of(CUSTOMER_REGION_ID)));
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_REGION, other),
                "registered within join 'customer' (LEFT) presentBy [OrderView.regionId], not presentBy [Buyer.id]");
        // A hand-written scope with no presentBy on either side stays legal.
        assertThat(JoinScope.of(copy).entity(B_REGION, region)).isNotNull();
    }

    @Test
    void ac_col_69_a_join_anchored_on_a_nested_join_maps_after_it() {
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(B_TIER, CUSTOMER_TIER),
                "not mq_tierId on joined source 'totals' (Totals) re-rooted under the scope");
        assertThat(JoinScope.of(CUSTOMER).source(B_TOTALS, CUSTOMER_TOTALS).entity(B_TIER, CUSTOMER_TIER))
                .isNotNull();
    }

    @Test
    void ac_col_69_a_when_term_must_match_the_nested_joins() {
        var nested = B_REGION.when(B_NAME, "x");
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(nested, CUSTOMER_REGION),
                "refused, their when(...) terms differ");
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(nested, CUSTOMER_REGION.when(CUSTOMER_NAME, "y")),
                "refused, their when(...) terms differ");
        assertThat(JoinScope.of(CUSTOMER).entity(nested, CUSTOMER_REGION.when(CUSTOMER_NAME, "x"))).isNotNull();
        assertMq1107(() -> JoinScope.of(CUSTOMER).entity(nested, REFERRER_REGION.when(CUSTOMER_NAME, "x")),
                "not read at or below join 'referrer' (LEFT)");
    }

    @Test
    void ac_col_69_within_checks_every_anchor_and_takes_one_join() {
        var onReferrer = EntityJoin.of("customer_region", Region.class, JoinKind.LEFT,
                List.of(EntityJoin.on(REFERRER_REGION_ID, "id")));
        assertMq1107(() -> onReferrer.within(CUSTOMER), "EntityJoin[customer_region: LEFT Region]: regionId on "
                + "join 'referrer' (LEFT) is not read at or below join 'customer' (LEFT), the @Join the join is "
                + "registered within");
        assertMq1107(() -> CUSTOMER_REGION.within(CUSTOMER), "already registered within join 'customer' (LEFT)");
        assertMq1107(() -> SourceJoin.of("t", Totals.class, JoinKind.LEFT, List.of(SourceJoin.on(ID,
                T_CUSTOMER_ID))).within(CUSTOMER), "SourceJoin[t: LEFT Totals]: id on root Order is not read");
        assertMq1107(() -> CUSTOMER_TOTALS.within(CUSTOMER), "already registered within");
        // An anchor on a joined root registered within the @Join reads under it.
        assertThat(CUSTOMER_TIER.root().within()).isEqualTo(CUSTOMER);
        assertThat(CUSTOMER_TIER_ID.table().isReadUnder(CUSTOMER)).isTrue();
        assertThat(CUSTOMER_TIER_ID.table().isReadUnder(REFERRER)).isFalse();
    }

    @Test
    void ac_col_69_within_and_named_keep_the_join_key_and_the_scope_is_a_value() {
        assertThat(CUSTOMER_REGION.root()).isEqualTo(TableField.joinedEntity("customer_region", Region.class))
                .hasSameHashCodeAs(TableField.joinedEntity("customer_region", Region.class));
        assertThat(CUSTOMER_REGION.root().named("customer").within()).isEqualTo(CUSTOMER);
        assertThat(CUSTOMER_REGION.when(CUSTOMER_NAME, "x").root().within()).isEqualTo(CUSTOMER);
        assertThat(JoinScope.of(CUSTOMER).entity(B_REGION, CUSTOMER_REGION))
                .isEqualTo(JoinScope.of(CUSTOMER).entity(B_REGION, CUSTOMER_REGION))
                .hasSameHashCodeAs(JoinScope.of(CUSTOMER).entity(B_REGION, CUSTOMER_REGION))
                .isNotEqualTo(JoinScope.of(CUSTOMER))
                .isNotEqualTo(JoinScope.of(REFERRER).entity(B_REGION, REFERRER_REGION));
        assertThat(JoinScope.of(CUSTOMER).table()).isSameAs(CUSTOMER);
    }

    private static ModelQuery.Builder<Order, ?, OrderView> orders() {
        return ModelQuery.builder(ROOT, row -> new OrderView()).joinEntity(CUSTOMER_REGION)
                .joinEntity(CUSTOMER_ADDRESS_CITY);
    }

    @Test
    void ac_col_70_a_column_set_of_only_nested_joined_columns_selects_the_join_fields_keys() {
        // The spike's finding: without the key, every customer whose region was read mapped as empty.
        assertThat(orders().select(SelectSet.of(ID, CUSTOMER_REGION_NAME)).build().modelColumns())
                .containsExactly(ID, CUSTOMER_REGION_NAME, CUSTOMER_ID);
        // Two levels deep: the address's key, then the customer's above it.
        assertThat(orders().select(SelectSet.of(ID, CUSTOMER_ADDRESS_CITY_NAME)).build().modelColumns())
                .containsExactly(ID, CUSTOMER_ADDRESS_CITY_NAME, A_ID.withTable(OrderView.class, CUSTOMER_ADDRESS),
                        CUSTOMER_ID);
    }

    @Test
    void ac_col_70_a_join_registered_within_no_join_field_selects_no_extra_key() {
        var own = EntityJoin.of("own_region", Region.class, JoinKind.LEFT,
                List.of(EntityJoin.on(CUSTOMER_REGION_ID, "id")));
        var name = B_REGION_NAME.withTable(OrderView.class, own.root());
        assertThat(ModelQuery.builder(ROOT, row -> new OrderView()).joinEntity(own)
                .select(SelectSet.of(ID, name)).build().modelColumns()).containsExactly(ID, name);
        assertThat(ModelQuery.builder(ROOT, row -> new OrderView()).joinEntity(own)
                .select(SelectSet.of(name, Agg.count(ROOT))).groupBy(name).build()).isNotNull();
    }

    @Test
    void ac_col_70_a_grouped_query_needs_the_join_fields_key_in_group_by_mq1409() {
        assertThatThrownBy(() -> orders().select(SelectSet.of(CUSTOMER_REGION_NAME, Agg.count(ROOT)))
                .groupBy(CUSTOMER_REGION_NAME).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1409))
                .hasMessage("MQ1409: OrderView.name: selected on a grouped query under the presentBy join 'customer' "
                        + "(LEFT), whose key column id is not in groupBy; a grouped query adds no presence key, so "
                        + "group by the key or select the column through a join without presentBy");
        assertThat(orders().select(SelectSet.of(CUSTOMER_REGION_NAME, Agg.count(ROOT)))
                .groupBy(CUSTOMER_REGION_NAME, CUSTOMER_ID).build()).isNotNull();
        // Two levels deep, every key on the walk: the customer's alone leaves the address's out.
        assertThatThrownBy(() -> orders().select(SelectSet.of(CUSTOMER_ADDRESS_CITY_NAME, Agg.count(ROOT)))
                .groupBy(CUSTOMER_ADDRESS_CITY_NAME, CUSTOMER_ID).build())
                .isInstanceOfSatisfying(ModelQueryDefinitionException.class,
                        e -> assertThat(e.code()).isEqualTo(MqCode.MQ1409))
                .hasMessageContaining("under the presentBy join 'address' (LEFT), whose key column id");
    }
}
