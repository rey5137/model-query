package com.rey.modelquery.spike.rfc0019;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.SpikeListPath;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Cashier;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Sale;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.SaleLine;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Store;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Terminal;
import com.rey.modelquery.spike.rfc0019.LitePosModels.TodaySaleModel;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Rfc0019MySqlFunctionalTest {

    private SpikeHarness harness;
    private SessionFactory sessions;

    @BeforeAll
    void init() throws Exception {
        harness = new SpikeHarness("MYSQL");
        sessions = harness.factory("litepos_mysql",
                Map.of("hibernate.generate_statistics", "true"),
                Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
        try (Connection conn = harness.dataSource().getConnection()) {
            LitePosDataset.seed(conn, false);
        }
    }

    @AfterAll
    void tearDown() {
        if (sessions != null) {
            sessions.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    protected RenderOptions renderOptions(EntityManager em) {
        return SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults()).renderOptions();
    }

    @Test
    void functional_mysql_informational_today_read() {
        harness.cell("A.1/MYSQL", "MySQL informational read: RA.1 and chosen routes", () -> {
            List<TodaySaleModel> ra1Rows;
            String ra1Sql;
            RenderOptions options;
            try (EntityManager em = sessions.createEntityManager()) {
                harness.clearStatements();
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                options = renderOptions(em);
                ra1Rows = executor.list(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                ra1Sql = harness.lastSql();
            }

            assertThat(ra1Rows).hasSize(50);
            assertThat(ra1Sql).contains("store").contains("sale_time");

            // RA.3
            List<TodaySaleModel> ra3Rows;
            try (EntityManager em = sessions.createEntityManager()) {
                harness.clearStatements();
                ra3Rows = SpikeRoutes.executeRa3List(
                        em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), options);
            }
            assertThat(ra3Rows).isEqualTo(ra1Rows);

            // RA.5
            List<TodaySaleModel> ra5Rows;
            try (EntityManager em = sessions.createEntityManager()) {
                harness.clearStatements();
                ra5Rows = SpikeRoutes.executeRa5Today(em, options);
            }
            assertThat(ra5Rows).isEqualTo(ra1Rows);

            return "MySQL informational today read matched: RA.1=" + ra1Rows.size()
                    + ", RA.3=" + ra3Rows.size() + ", RA.5=" + ra5Rows.size();
        });
    }
}
