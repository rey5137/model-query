package com.rey.modelquery.spike.rfc0024;

import com.rey.modelquery.core.SpikeCore;
import java.sql.Connection;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/** One vendor's run of the four RFC 0024 items; every cell is recorded, and a test fails if any of its cells did. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractRfc0024Test {
    private Harness harness;
    private SessionFactory sessions;
    private Probes probes;

    protected abstract String vendor();

    @BeforeAll
    void setUp() throws Exception {
        harness = new Harness(vendor());
        try {
            Assertions.assertTrue(SpikeCore.shadowLoaded(), "the Part C EntityJoin shadow is not on the classpath");
            sessions = harness.factory();
            try (Connection c = harness.raw().getConnection()) {
                Entities.seed(c);
            }
            harness.record("0", "setup", "PASS", harness.version() + "; shadow EntityJoin loaded");
            probes = new Probes(harness, sessions);
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
    void item1_partA_presence() {
        check(probes::item1);
    }

    @Test
    @Order(2)
    void item2_partB_rerooting() {
        check(probes::item2);
    }

    @Test
    @Order(3)
    void item3_partB_sources() {
        check(probes::item3);
    }

    @Test
    @Order(4)
    void item4_partC_chains() {
        check(probes::item4);
    }

    private void check(Runnable item) {
        int before = harness.failures();
        item.run();
        Assertions.assertEquals(before, harness.failures(), "failed cells: see rfc/0024-spike-data");
    }
}
