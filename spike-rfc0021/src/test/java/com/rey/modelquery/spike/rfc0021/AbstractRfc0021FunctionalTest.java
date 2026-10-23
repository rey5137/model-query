package com.rey.modelquery.spike.rfc0021;

import java.sql.Connection;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The RFC 0021 functional cells on one vendor. A cell records PASS, FAIL or NOT_RUN to
 * {@code rfc/0021-spike-data/rfc0021-<hibernate>.tsv} and never fails the test, since a refusal or a vendor limit is
 * data. Setup failures (container, schema, seed) do fail the class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractRfc0021FunctionalTest {

    protected SpikeHarness harness;
    protected SessionFactory sessions;

    protected abstract String vendor();

    @BeforeAll
    void setUp() throws Exception {
        harness = new SpikeHarness(vendor());
        sessions = harness.factory(Rfc0021Entities.ALL);
        long start = System.nanoTime();
        try (Connection conn = harness.raw().getConnection()) {
            Rfc0021Dataset.seed(conn);
        }
        System.out.println("RFC0021 " + vendor() + " seed ms=" + (System.nanoTime() - start) / 1_000_000);
    }

    @AfterAll
    void tearDown() {
        System.out.println("RFC0021 " + harness.counts());
        if (sessions != null) {
            sessions.close();
        }
        harness.close();
    }

    @Test
    @Order(1)
    void a_signaturesRenderingKindsPaths() {
        PartAProbes.run(harness, sessions);
    }

    @Test
    @Order(2)
    void a5_parity() {
        ParityProbes.run(harness, sessions);
    }

    @Test
    @Order(3)
    void b1_joinFilters() {
        PartBProbes.run(harness, sessions);
    }

    @Test
    @Order(4)
    void a4_plansAndTimings() throws Exception {
        if (Boolean.parseBoolean(System.getProperty("rfc0021.plans", "true"))) {
            PlanProbes.run(harness, sessions);
        }
    }
}
