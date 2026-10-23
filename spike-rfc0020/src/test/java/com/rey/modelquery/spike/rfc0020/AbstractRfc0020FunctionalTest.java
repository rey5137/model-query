package com.rey.modelquery.spike.rfc0020;

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
 * The RFC 0020 functional cells on one vendor. Each test runs one Part's cells; a cell records PASS, FAIL or NOT_RUN
 * to {@code rfc/0020-spike-data/rfc0020-<hibernate>.tsv} and never fails the test, since a refusal or a vendor limit
 * is data. Setup failures (container, schema, seed) do fail the class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractRfc0020FunctionalTest {

    protected SpikeHarness harness;
    protected SessionFactory sessions;

    protected abstract String vendor();

    @BeforeAll
    void setUp() throws Exception {
        harness = new SpikeHarness(vendor());
        sessions = harness.factory(Rfc0020Entities.ALL);
        try (Connection conn = harness.raw().getConnection()) {
            Rfc0020Dataset.seed(conn);
        }
    }

    @AfterAll
    void tearDown() {
        System.out.println("RFC0020 " + harness.counts());
        if (sessions != null) {
            sessions.close();
        }
        harness.close();
    }

    @Test
    @Order(1)
    void e1_nullSafeEquality() {
        PartEProbes.run(harness, sessions);
    }

    @Test
    @Order(2)
    void d1_d2_exactAverageAndRounding() {
        PartDProbes.run(harness, sessions);
    }

    @Test
    @Order(3)
    void c1_c2_filteredAggregates() {
        PartCProbes.run(harness, sessions);
    }

    @Test
    @Order(4)
    void a2_joinedSource() {
        PartAProbes.run(harness, sessions);
    }

    @Test
    @Order(5)
    void b1_b2_firstBy() {
        PartBProbes.run(harness, sessions);
    }

    @Test
    @Order(6)
    void f1_rowCount() {
        PartFProbes.run(harness, sessions);
    }

    /** A.1, E.1 plans, B.2 against B.1, F.1 timings: after the functional cells, which keep B's and F's statements. */
    @Test
    @Order(7)
    void plansAndTimings() throws Exception {
        if (Boolean.parseBoolean(System.getProperty("rfc0020.plans", "true"))) {
            PlanProbes.run(harness, sessions);
        }
    }
}
