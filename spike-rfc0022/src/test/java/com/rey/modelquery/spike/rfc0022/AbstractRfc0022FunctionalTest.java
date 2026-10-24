package com.rey.modelquery.spike.rfc0022;

import java.sql.Connection;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractRfc0022FunctionalTest {
    protected SpikeHarness harness;
    protected SessionFactory sessions;
    protected abstract String vendor();

    @Test
    @Order(7)
    void c_requestParameters() throws Exception {
        ParameterProbes.run(harness, sessions);
    }

    @Test
    @Order(8)
    void d_joinedCounts() throws Exception {
        JoinedCountProbes.run(harness, sessions);
    }

    @Test
    @Order(3)
    void a3_portableRendererParity() throws Exception {
        RendererParityProbes.run(harness, sessions);
    }

    @Test
    @Order(4)
    void b3_groupedCountCopy() {
        GroupCountProbes.run(harness, sessions);
    }

    @Test
    @Order(99)
    void b2_alternatingTimingsAndPlans() throws Exception {
        CostProbes.run(harness, sessions);
    }

    @Test
    @Order(6)
    void a4_columnCreationAdapterPreservesEngineMapping() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(SpikeHarness.HIB.startsWith("7."),
                "the A.4 adapter requires the API 3.2 Join contract; 6.6 entity joins are only From");
        for (String cell : java.util.List.of("LEFT", "INNER", "to-one-below", "filter", "count",
                "keyset-nullsLast", "export")) {
            EclipseLinkEngineProbe.engineCell(harness, sessions,
                    Q.provider(sessions).entityJoins().orElseThrow(), cell);
        }
    }

    @BeforeAll
    void setUp() throws Exception {
        harness = new SpikeHarness(vendor());
        try {
            sessions = harness.factory(Rfc0022Entities.ALL);
            try (Connection connection = harness.raw().getConnection()) {
                Rfc0022Dataset.seed(connection);
                try (var statement = connection.createStatement(); var result = statement.executeQuery(
                        "select count(*), sum(case when rep_code='ORPHAN' then 1 else 0 end) from invoice")) {
                    result.next();
                    org.junit.jupiter.api.Assertions.assertEquals(50_000, result.getLong(1));
                    org.junit.jupiter.api.Assertions.assertEquals(1_000, result.getLong(2));
                }
            }
        } catch (Throwable failure) {
            tearDown();
            throw failure;
        }
    }

    @AfterAll
    void tearDown() {
        if (harness != null) {
            System.out.println("RFC0022 " + harness.counts());
        }
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

    @Test @Order(1)
    void b1_columnAnchorsStatementKindsAndOrphans() {
        ColumnJoinProbes.rendering(harness, sessions);
    }

    @Test @Order(2)
    void b4_staticTermAndDuplicateExecution() {
        ColumnJoinProbes.staticTerm(harness, sessions);
    }
}
