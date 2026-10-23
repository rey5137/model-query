package com.rey.modelquery.tck.portability;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Agg;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.SelectSet;
import com.rey.modelquery.core.TableField;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.PortabilityCheck;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.tck.col.OrderEntity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AC-AGG-19: {@code Agg.firstBy} is reported per declared target without a database, {@code MQ4015} on MySQL (the
 * vendor first) and {@code MQ4014} where the provider has no renderer (spec api/13 R-AGG-16, vendor/40 R-VND-29).
 */
class FirstByPortabilityTest {

    record Top(String value) {}

    private static final Target H2 = Target.of(DatabaseVendor.H2, "2.3.232");
    private static final Target POSTGRESQL = Target.of(DatabaseVendor.POSTGRESQL, "15.4");
    private static final Target MYSQL = Target.of(DatabaseVendor.MYSQL, "8.0.36");

    private static final TableField<OrderEntity, OrderEntity> ORDERS = TableField.root(OrderEntity.class);
    private static final ColumnField<Top, OrderEntity, String> STATUS =
            ColumnField.of(Top.class, ORDERS, "status", String.class);
    private static final ColumnField<Top, OrderEntity, BigDecimal> TOTAL =
            ColumnField.of(Top.class, ORDERS, "total", BigDecimal.class);

    private static final ModelQuery<OrderEntity, ?, Top> TOP = ModelQuery
            .builder(ORDERS, row -> new Top(row.get(Agg.firstBy(STATUS, TOTAL.desc().nullsLast()))))
            .select(SelectSet.of(Agg.firstBy(STATUS, TOTAL.desc().nullsLast()))).build();

    @Test
    void ac_agg_19_mysql_alone_reports_mq4015_and_the_array_aggregate_vendors_are_reached() {
        List<String> lines = report(check().targets(H2, POSTGRESQL, MYSQL)
                .call("TOP", OrderEntity.class, executor -> executor.list(TOP, Limit.of(10))));

        assertThat(lines).hasSize(4);
        for (Target target : List.of(H2, POSTGRESQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("TOP on " + target + ": REACHED")
                    .contains("array_agg"));
        }
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("TOP on " + MYSQL + ": REFUSED MQ4015")
                .contains("FIRST_BY"));
    }

    @Test
    void ac_agg_19_a_provider_without_the_renderer_reports_mq4014_where_the_vendor_serves_it() {
        List<String> lines = new ArrayList<>();
        // This provider still boots offline; its inherited aggregates() default alone refuses the first-by value.
        NoSourceProviderSupport.serving(() -> lines.addAll(report(check().targets(H2, POSTGRESQL, MYSQL)
                .call("TOP", OrderEntity.class, executor -> executor.list(TOP, Limit.of(10))))));

        assertThat(lines).hasSize(4);
        for (Target target : List.of(H2, POSTGRESQL)) {
            assertThat(lines).anySatisfy(line -> assertThat(line)
                    .startsWith("TOP on " + target + ": REFUSED MQ4014").contains("AggregateRenderer"));
        }
        // The vendor's fact comes first: MySQL is MQ4015 even though the provider has no renderer either.
        assertThat(lines).anySatisfy(line -> assertThat(line).startsWith("TOP on " + MYSQL + ": REFUSED MQ4015"));
    }

    private static PortabilityCheck check() {
        List<Class<?>> classes = OfflineBootTest.TCK_UNIT.stream().<Class<?>>map(FirstByPortabilityTest::load).toList();
        return PortabilityCheck.of(classes, ModelQueryConfig.defaults());
    }

    /** The report lines of a check that fails, the header first. */
    private static List<String> report(PortabilityCheck check) {
        try {
            check.assertPortable();
        } catch (AssertionError e) {
            return Arrays.asList(e.getMessage().split("\n"));
        }
        throw new AssertionError("the check passed");
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name, e);
        }
    }
}
