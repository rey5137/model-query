package com.rey.modelquery.spike.rfc0025;

import com.rey.modelquery.jpa.spi.DatabaseVendor;
import com.rey.modelquery.portability.Target;
import com.rey.modelquery.portability.internal.OfflineFactory;
import com.rey.modelquery.portability.internal.OfflineUnit;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The RFC 0025 spike: Part C's three questions on four targets, each under bound and inline value handling (D-177),
 * on factories booted offline on the stub; then R-INS-16's naming item. A test fails if any of its cells did.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Rfc0025Test {
    private static final List<Target> TARGETS = List.of(Target.of(DatabaseVendor.H2, "2.3.232"),
            Target.of(DatabaseVendor.POSTGRESQL, "16.4"), Target.of(DatabaseVendor.MYSQL, "8.0.36"),
            Target.of(DatabaseVendor.MARIADB, "11.4"));
    private static final OfflineUnit BOUND = OfflineUnit.ofClasses(Entities.ALL, List.of(), Map.of());
    private static final OfflineUnit INLINE = OfflineUnit.ofClasses(Entities.ALL, List.of(),
            Map.of("hibernate.criteria.value_handling_mode", "inline"));

    private Cells cells;
    private final List<Probes> probes = new ArrayList<>();

    @BeforeAll
    void setUp() {
        cells = new Cells();
        for (Target target : TARGETS) {
            for (boolean inline : new boolean[] {false, true}) {
                EntityManagerFactory emf = OfflineFactory.boot(inline ? INLINE : BOUND, target);
                probes.add(new Probes(cells, target, emf, inline ? "inline" : ""));
            }
        }
        cells.record("ANY", "0", "setup", true, "Hibernate " + org.hibernate.Version.getVersionString() + ", "
                + probes.size() + " factories booted offline; engine entry by reflection on DefaultModelQueryExecutor");
    }

    @Test
    @Order(1)
    void item1_every_child_load_reached_on_placeholders() {
        check(Probes::item1);
    }

    @Test
    @Order(2)
    void item2_placeholder_key_types() {
        check(Probes::item2);
    }

    @Test
    @Order(3)
    void item3_child_stage_refusals() {
        check(Probes::item3);
    }

    @Test
    @Order(4)
    void item4_naming_strategies_from_a_booted_unit() {
        int before = cells.failures();
        new Naming(cells).run();
        Assertions.assertEquals(before, cells.failures(), "failed cells: see rfc/0025-spike-data");
    }

    private void check(Consumer<Probes> item) {
        int before = cells.failures();
        probes.forEach(item);
        Assertions.assertEquals(before, cells.failures(), "failed cells: see rfc/0025-spike-data");
    }
}
