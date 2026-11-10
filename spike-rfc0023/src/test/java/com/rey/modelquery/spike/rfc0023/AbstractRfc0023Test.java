package com.rey.modelquery.spike.rfc0023;

import java.sql.Connection;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractRfc0023Test {
    private Harness harness;
    private SessionFactory sessions;
    private Probes probes;

    protected abstract String vendor();

    @BeforeAll
    void setUp() throws Exception {
        harness = new Harness(vendor());
        try {
            harness.grantMonitoring();
            int rows = vendor().equals("H2") ? 100 : 20_000;
            try (Connection c = harness.raw("").getConnection()) {
                long start = System.currentTimeMillis();
                Events.seed(c, vendor(), rows);
                try (var st = c.createStatement(); var rs = st.executeQuery("select count(*) from event_log")) {
                    rs.next();
                    org.junit.jupiter.api.Assertions.assertEquals((long) rows * Events.MONTHS, rs.getLong(1));
                }
                harness.cell("0", "setup", "PASS", "rows=" + (long) rows * Events.MONTHS + " seedMs="
                        + (System.currentTimeMillis() - start) + " " + harness.version());
            }
            sessions = harness.factory("");
            probes = new Probes(harness, sessions, rows);
        } catch (Throwable failure) {
            tearDown();
            throw failure;
        }
    }

    @AfterAll
    void tearDown() {
        try {
            if (sessions != null && sessions.isOpen()) {
                sessions.close();
            }
        } finally {
            if (harness != null) {
                harness.close();
            }
        }
    }

    @Test
    @Order(1)
    void item1_renderingAndBinds() {
        probes.rendering();
    }

    @Test
    @Order(2)
    void item2_pruning() throws Exception {
        Assumptions.assumeTrue(!vendor().equals("H2"), "H2 asserts correctness only");
        probes.pruning();
    }

    @Test
    @Order(3)
    void item3_cost() {
        Assumptions.assumeTrue(!vendor().equals("H2"), "H2 asserts correctness only");
        Assumptions.assumeTrue(!"false".equals(System.getProperty("rfc0023.cost")), "cost skipped");
        probes.cost(Integer.getInteger("rfc0023.runs", 7));
    }
}
