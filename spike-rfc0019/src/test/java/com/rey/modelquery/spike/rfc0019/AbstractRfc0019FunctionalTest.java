package com.rey.modelquery.spike.rfc0019;

import static org.assertj.core.api.Assertions.assertThat;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.SpikeListPath;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Cashier;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Sale;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.SaleLine;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Store;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Terminal;
import com.rey.modelquery.spike.rfc0019.LitePosModels.FlatSaleProjection;
import com.rey.modelquery.spike.rfc0019.LitePosModels.GroupedCountModel;
import com.rey.modelquery.spike.rfc0019.LitePosModels.SaleSummaryModel;
import com.rey.modelquery.spike.rfc0019.LitePosModels.TodaySaleModel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Parameter;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractRfc0019FunctionalTest {

    private final String vendor;
    protected SpikeHarness harness;
    protected SessionFactory sessions;

    protected AbstractRfc0019FunctionalTest(String vendor) {
        this.vendor = vendor;
    }

    @BeforeAll
    void init() throws Exception {
        harness = new SpikeHarness(vendor);
        sessions = harness.factory("litepos",
                Map.of("hibernate.generate_statistics", "true"),
                Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
        try (Connection conn = harness.dataSource().getConnection()) {
            LitePosDataset.seed(conn, false);
        }
    }

    protected RenderOptions renderOptions(EntityManager em) {
        return SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults()).renderOptions();
    }

    private String lastStatement() {
        List<SpikeHarness.Stmt> all = harness.statements();
        return all.get(all.size() - 1).toString();
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

    @Test
    void functional_seamless_path_matches_ra1() {
        harness.cell("A.1/path", "SpikeListPath seam-less path matches executor.list", () -> {
            try (EntityManager em = sessions.createEntityManager()) {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                harness.clearStatements();
                List<TodaySaleModel> execRows = executor.list(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                String execSql = harness.lastSql();
                int execStatements = harness.statements().size();

                harness.clearStatements();
                var path = SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults());
                List<TodaySaleModel> pathRows = path.listRa1(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                String pathSql = harness.lastSql();
                int pathStatements = harness.statements().size();

                assertThat(pathRows).isEqualTo(execRows);
                assertThat(pathSql).isEqualTo(execSql);
                assertThat(pathStatements).isEqualTo(execStatements);
                return "SpikeListPath returns identical rows (" + pathRows.size() + "), SQL, and statement count ("
                        + pathStatements + ") as executor.list";
            }
        });
    }

    @Test
    void functional_same_rows_and_sql_as_ra1() {
        harness.cell("A.1", "today read rows and SQL parity", () -> {
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

            // RA.3: one built CriteriaQuery reused through SealedBuildCache within executor envelope
            List<TodaySaleModel> ra3Rows;
            String ra3Sql;
            try (EntityManager em = sessions.createEntityManager()) {
                harness.clearStatements();
                ra3Rows = SpikeRoutes.executeRa3List(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), options);
                ra3Sql = harness.lastSql();
            }
            assertThat(ra3Rows).isEqualTo(ra1Rows);
            assertThat(ra3Sql).isEqualTo(ra1Sql);

            // RA.5: HQL provider route within executor envelope
            List<TodaySaleModel> ra5Rows;
            String ra5Sql;
            try (EntityManager em = sessions.createEntityManager()) {
                harness.clearStatements();
                ra5Rows = SpikeRoutes.executeRa5Today(em, options);
                ra5Sql = harness.lastSql();
            }
            assertThat(ra5Rows).isEqualTo(ra1Rows);
            assertThat(ra5Sql).isEqualTo(ra1Sql);

            return "RA.1=" + ra1Rows.size() + ", RA.3=" + ra3Rows.size() + ", RA.5=" + ra5Rows.size();
        });
    }

    @Test
    void functional_every_value_a_bind_under_bind_and_inline_mode() {
        harness.cell("A.1", "bind and inline value handling mode", () -> {
            for (String mode : List.of("bind", "inline")) {
                Map<String, String> noPlanSettings = new HashMap<>();
                noPlanSettings.put("hibernate.criteria.value_handling_mode", mode);
                noPlanSettings.put("hibernate.hbm2ddl.auto", "none");
                if (SpikeHarness.HIB.startsWith("7.")) {
                    noPlanSettings.put("hibernate.criteria.plan_cache_enabled", "false");
                }
                SessionFactory noPlanFactory = harness.factory("mode_noplan_" + mode, noPlanSettings,
                        Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
                try {
                    List<TodaySaleModel> ra1Rows;
                    String ra1Sql;
                    try (EntityManager em = noPlanFactory.createEntityManager()) {
                        harness.clearStatements();
                        var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                        ra1Rows = executor.list(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                        ra1Sql = harness.lastSql();
                        assertThat(ra1Rows).hasSize(50);
                        assertNoLiteralsAndExactlyThreeBinds(ra1Sql, "RA.1 " + mode);
                    }

                    try (EntityManager em = noPlanFactory.createEntityManager()) {
                        harness.clearStatements();
                        RenderOptions opts = renderOptions(em);
                        List<TodaySaleModel> ra3Rows =
                                SpikeRoutes.executeRa3List(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                        String ra3Sql = harness.lastSql();
                        assertThat(ra3Rows).isEqualTo(ra1Rows);
                        assertThat(ra3Sql).isEqualTo(ra1Sql);
                        assertNoLiteralsAndExactlyThreeBinds(ra3Sql, "RA.3 " + mode);
                    }

                    try (EntityManager em = noPlanFactory.createEntityManager()) {
                        harness.clearStatements();
                        RenderOptions opts = renderOptions(em);
                        List<TodaySaleModel> ra5Rows = SpikeRoutes.executeRa5Today(em, opts);
                        String ra5Sql = harness.lastSql();
                        assertThat(ra5Rows).isEqualTo(ra1Rows);
                        assertThat(ra5Sql).isEqualTo(ra1Sql);
                        assertNoLiteralsAndExactlyThreeBinds(ra5Sql, "RA.5 " + mode);
                    }
                } finally {
                    noPlanFactory.close();
                }

                if (SpikeHarness.HIB.startsWith("7.")) {
                    Map<String, String> planSettings = new HashMap<>();
                    planSettings.put("hibernate.criteria.value_handling_mode", mode);
                    planSettings.put("hibernate.hbm2ddl.auto", "none");
                    planSettings.put("hibernate.criteria.plan_cache_enabled", "true");
                    SessionFactory planFactory = harness.factory("mode_plan_" + mode, planSettings,
                            Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
                    try {
                        List<TodaySaleModel> ra1Rows;
                        String ra1Sql;
                        try (EntityManager em = planFactory.createEntityManager()) {
                            harness.clearStatements();
                            var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                            ra1Rows = executor.list(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                            ra1Sql = harness.lastSql();
                        }

                        try (EntityManager em = planFactory.createEntityManager()) {
                            harness.clearStatements();
                            List<TodaySaleModel> ra4Rows = SpikeRoutes.executeRa4List(
                                    em, Sale.class, LitePosQueries.TODAY_QUERY, Limit.unlimited(),
                                    ModelQueryConfig.defaults());
                            String ra4Sql = harness.lastSql();
                            assertThat(ra4Rows).isEqualTo(ra1Rows);
                            assertThat(ra4Sql).isEqualTo(ra1Sql);
                            assertNoLiteralsAndExactlyThreeBinds(ra4Sql, "RA.4 " + mode);
                        }

                        try (EntityManager em = planFactory.createEntityManager()) {
                            harness.clearStatements();
                            RenderOptions opts = renderOptions(em);
                            List<TodaySaleModel> ra4bRows = SpikeRoutes.executeRa4bList(
                                    em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                            String ra4bSql = harness.lastSql();
                            assertThat(ra4bRows).isEqualTo(ra1Rows);
                            assertThat(ra4bSql).isEqualTo(ra1Sql);
                            assertNoLiteralsAndExactlyThreeBinds(ra4bSql, "RA.4b " + mode);
                        }
                    } finally {
                        planFactory.close();
                    }
                }
            }
            return "all routes passed under bind and inline with 0 literals and 3 binds, parity with RA.1 verified";
        });
    }

    private static void assertNoLiteralsAndExactlyThreeBinds(String sql, String context) {
        assertThat(sql).as(context + " should not contain literal timestamp")
                .doesNotContain("2025-12-31").doesNotContain("2026-01-01");
        assertThat(sql.replaceAll("\\s+", "")).as(context + " should not contain literal store id")
                .doesNotContain("store_id=1").doesNotContain(".id=1");
        int qCount = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                qCount++;
            }
        }
        assertThat(qCount).as(context + " ? parameter count").isEqualTo(3);
    }

    @Test
    void functional_copy_tree_true_false_per_session() {
        harness.cell("A.3", "copy_tree session property override", () -> {
            try (EntityManager em1 = sessions.createEntityManager();
                 EntityManager em2 = sessions.createEntityManager()) {
                em1.setProperty("hibernate.criteria.copy_tree", false);
                boolean em1Copies = SealedBuildCache.copiesOnCreate(em1);
                boolean em2Copies = SealedBuildCache.copiesOnCreate(em2);

                assertThat(em1Copies).isFalse();
                assertThat(em2Copies).isTrue();

                RenderOptions opts = renderOptions(em1);
                List<TodaySaleModel> fallbackRows =
                        SpikeRoutes.executeRa3List(em1, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                assertThat(fallbackRows).hasSize(50);

                return "em1=" + em1Copies + ", em2=" + em2Copies + ", fallbackRows=" + fallbackRows.size();
            }
        });
    }

    @Test
    void functional_sealed_build_cache_hit_count() {
        harness.cell("A.3/hits", "SealedBuildCache hit count is calls - 1 after N calls", () -> {
            SealedBuildCache.clear();
            int calls = 10;
            RenderOptions opts;
            try (EntityManager em = sessions.createEntityManager()) {
                opts = renderOptions(em);
            }
            for (int i = 0; i < calls; i++) {
                try (EntityManager em = sessions.createEntityManager()) {
                    SpikeRoutes.executeRa3List(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                }
            }
            assertThat(SealedBuildCache.hits()).isEqualTo(calls - 1);
            assertThat(SealedBuildCache.misses()).isEqualTo(1);
            return "calls=" + calls + ", hits=" + SealedBuildCache.hits() + ", misses=" + SealedBuildCache.misses();
        });
    }

    @Test
    void functional_one_ra3_by_key() {
        harness.cell("A.3/oneKey", "oneRa3 carries key as parameter expression and clears order", () -> {
            try (EntityManager em = sessions.createEntityManager()) {
                var path = SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults());
                SaleSummaryModel found = path.oneRa3(LitePosQueries.ONE_QUERY, 500L).orElseThrow();
                assertThat(found.id()).isEqualTo(500L);

                Optional<SaleSummaryModel> missing = path.oneRa3(LitePosQueries.ONE_QUERY, 999999L);
                assertThat(missing).isEmpty();

                return "found id=" + found.id() + ", missing=" + missing.isPresent();
            }
        });
    }

    @Test
    void functional_16_threads_concurrent_bindings_to_stored_query() throws Exception {
        harness.cell("A.3", "16 threads concurrent binding isolation", () -> {
            var scope = SealedBuildCache.scopeFor(sessions);
            RenderOptions opts;
            try (EntityManager em = sessions.createEntityManager()) {
                opts = renderOptions(em).withInlinesValues(true);
                scope.getOrBuild(em, LitePosQueries.TODAY_QUERY, "LIST", opts);
            }

            int threadCount = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final Long targetStoreId = (long) ((i % 5) + 1);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try (EntityManager em = sessions.createEntityManager()) {
                        for (int iter = 0; iter < 10; iter++) {
                            // Run on RA.3 SealedBuildCache entry with targetStoreId bound
                            var entry = scope.getOrBuild(
                                    em, LitePosQueries.TODAY_QUERY, "LIST", opts);
                            if (entry == null) {
                                return false;
                            }
                            @SuppressWarnings("unchecked")
                            CriteriaQuery<Tuple> cq = (CriteriaQuery<Tuple>) entry.criteriaQuery();
                            TypedQuery<Tuple> tq = em.createQuery(cq);
                            entry.builtQuery().joins().statementParameters().forEach((param, val) -> {
                                if (val.equals(1L)) {
                                    tq.setParameter((Parameter) param, targetStoreId);
                                } else {
                                    tq.setParameter((Parameter) param, val);
                                }
                            });
                            List<Tuple> tuples = tq.getResultList();
                            List<TodaySaleModel> ra3Rows = new ArrayList<>(tuples.size());
                            for (Tuple t : tuples) {
                                ra3Rows.add(entry.builtQuery().map(entry.builtQuery().selection().row(t)));
                            }

                            // Run on RA.5 HQL provider route with targetStoreId
                            List<TodaySaleModel> ra5Rows = HqlProviderRoute.listToday(
                                    em, LitePosDataset.TODAY_START, LitePosDataset.TODAY_END,
                                    targetStoreId, opts);

                            if (ra3Rows.size() != ra5Rows.size()) {
                                return false;
                            }
                            if (ra3Rows.isEmpty()) {
                                return false;
                            }
                            for (TodaySaleModel r : ra3Rows) {
                                if (!r.storeName().equals("Store " + targetStoreId)) {
                                    return false;
                                }
                            }
                            for (TodaySaleModel r : ra5Rows) {
                                if (!r.storeName().equals("Store " + targetStoreId)) {
                                    return false;
                                }
                            }
                            if (targetStoreId == 1L && ra3Rows.size() != 50) {
                                return false;
                            }
                        }
                    }
                    return true;
                }));
            }

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            for (Future<Boolean> f : futures) {
                assertThat(f.get()).isTrue();
            }
            return "16 threads passed without parameter leakage on RA.3 and RA.5";
        });
    }

    @Test
    void functional_two_sessions_filter_tenant_each_see_own_rows() {
        harness.cell("A.3", "two-session filter tenant isolation", () -> {
            try (EntityManager em1 = sessions.createEntityManager();
                 EntityManager em2 = sessions.createEntityManager()) {
                em1.unwrap(Session.class).enableFilter("tenantFilter").setParameter("storeId", 1L);
                em2.unwrap(Session.class).enableFilter("tenantFilter").setParameter("storeId", 2L);

                RenderOptions opts = renderOptions(em1);

                // 1. SealedBuildCache entry (RA.3)
                List<TodaySaleModel> ra3Session1 =
                        SpikeRoutes.executeRa3List(em1, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                List<TodaySaleModel> ra3Session2 =
                        SpikeRoutes.executeRa3List(em2, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);

                assertThat(ra3Session1).hasSize(50);
                assertThat(ra3Session2).isEmpty();

                // 2. RA.5 provider route
                List<TodaySaleModel> ra5Session1 = SpikeRoutes.executeRa5Today(em1, opts);
                List<TodaySaleModel> ra5Session2 = SpikeRoutes.executeRa5Today(em2, opts);
                assertThat(ra5Session1).hasSize(50);
                assertThat(ra5Session2).isEmpty();

                // 3. RA.4b on 7.4
                if (SpikeHarness.HIB.startsWith("7.")) {
                    List<TodaySaleModel> ra4bSession1 =
                            SpikeRoutes.executeRa4bList(em1, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                    List<TodaySaleModel> ra4bSession2 =
                            SpikeRoutes.executeRa4bList(em2, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                    assertThat(ra4bSession1).hasSize(50);
                    assertThat(ra4bSession2).isEmpty();
                }

                return "filter tenant isolated across RA.3, RA.5, and RA.4b";
            }
        });
    }

    @Test
    void functional_heap_check_after_closing_50_factories() {
        harness.cell("A.3", "heap check after closing 50 factories", () -> {
            List<WeakReference<SessionFactory>> refs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                SessionFactory sf = harness.factory("temp" + i,
                        Map.of("hibernate.hbm2ddl.auto", "none"),
                        Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
                try (EntityManager em = sf.createEntityManager()) {
                    RenderOptions opts = renderOptions(em);
                    SealedBuildCache.getOrBuild(em, LitePosQueries.TODAY_QUERY, "LIST", opts);
                }
                refs.add(new WeakReference<>(sf));
                sf.close();
            }

            for (int attempt = 0; attempt < 5; attempt++) {
                System.gc();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
                SealedBuildCache.sweepClosed();
            }

            long liveCount = refs.stream().filter(r -> r.get() != null).count();
            assertThat(liveCount).isLessThanOrEqualTo(1L);
            return "live factories=" + liveCount;
        });
    }

    @Test
    void functional_statistics_plan_cache_hit_ratio_at_least_99_percent() {
        harness.cell("A.5", "statistics plan-cache hit ratio >= 99%", () -> {
            SessionFactory freshFactory = harness.factory("fresh_stats",
                    Map.of("hibernate.generate_statistics", "true",
                            "hibernate.hbm2ddl.auto", "none"),
                    Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
            try {
                Statistics stats = freshFactory.getStatistics();
                RenderOptions opts;
                try (EntityManager em = freshFactory.createEntityManager()) {
                    opts = renderOptions(em);
                    SpikeRoutes.executeRa1List(em, Sale.class, LitePosQueries.TODAY_QUERY, Limit.unlimited(),
                            ModelQueryConfig.defaults());
                    SpikeRoutes.executeRa3List(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                    SpikeRoutes.executeRa5Today(em, opts);
                }

                stats.clear();

                try (EntityManager em = freshFactory.createEntityManager()) {
                    for (int i = 0; i < 100; i++) {
                        SpikeRoutes.executeRa5Today(em, opts);
                    }
                }

                long hits = stats.getQueryPlanCacheHitCount();
                long misses = stats.getQueryPlanCacheMissCount();
                long total = hits + misses;
                double hitRatio = total > 0 ? (double) hits / total : 0.0;

                assertThat(hits).isGreaterThanOrEqualTo(99L);
                assertThat(hitRatio).isGreaterThanOrEqualTo(0.99);
                return "hits=" + hits + ", misses=" + misses + ", ratio=" + String.format("%.3f", hitRatio);
            } finally {
                freshFactory.close();
            }
        });
    }

    @Test
    void functional_ra4h_66_statistics() {
        if (!SpikeHarness.HIB.startsWith("7.")) {
            harness.cell("A.4h", "criteria_plan_cache_hint", () -> {
                SessionFactory freshFactory = harness.factory("fresh_ra4h",
                        Map.of("hibernate.generate_statistics", "true",
                                "hibernate.hbm2ddl.auto", "none"),
                        Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
                try {
                    Statistics stats = freshFactory.getStatistics();
                    RenderOptions opts;
                    try (EntityManager em = freshFactory.createEntityManager()) {
                        opts = renderOptions(em);
                        SpikeRoutes.executeRa4hList(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                    }
                    stats.clear();
                    try (EntityManager em = freshFactory.createEntityManager()) {
                        SpikeRoutes.executeRa4hList(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                        SpikeRoutes.executeRa4hList(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                    }
                    long hits = stats.getQueryPlanCacheHitCount();
                    long misses = stats.getQueryPlanCacheMissCount();
                    assertThat(hits).isEqualTo(0L);
                    assertThat(misses).isGreaterThanOrEqualTo(2L);
                    return "hits=" + hits + ", misses=" + misses;
                } finally {
                    freshFactory.close();
                }
            });
        } else {
            harness.notRun("A.4h", "criteria_plan_cache_hint",
                    "6.6 statistics cell only; 7.4 uses hibernate.criteria.plan_cache_enabled");
        }
    }

    @Test
    void functional_grouped_rows_and_sql_ra1_vs_ra5() {
        // RA.5 on the grouped read is faster than RA.0 (JDBC on RA.1's SQL), so record whether it sends other SQL.
        harness.cell("A.1/grouped", "grouped read rows and SQL, RA.1 against RA.5", () -> {
            List<GroupedCountModel> ra1Rows;
            String ra1;
            RenderOptions options;
            try (EntityManager em = sessions.createEntityManager()) {
                options = renderOptions(em);
                harness.clearStatements();
                ra1Rows = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults())
                        .list(LitePosQueries.GROUPED_QUERY, Limit.unlimited());
                ra1 = lastStatement();
            }
            List<GroupedCountModel> ra5Rows;
            String ra5;
            try (EntityManager em = sessions.createEntityManager()) {
                harness.clearStatements();
                ra5Rows = SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults())
                        .groupedRa5(LitePosQueries.GROUPED_QUERY, options);
                ra5 = lastStatement();
            }
            assertThat(ra5Rows).isEqualTo(ra1Rows);
            return "rows=" + ra1Rows.size() + ", sameSqlAndBinds=" + ra1.equals(ra5)
                    + ", RA.1: " + ra1 + " ;; RA.5: " + ra5;
        });
    }

    @Test
    void functional_ra4_74_rows_and_sql_parity() {
        if (!SpikeHarness.HIB.startsWith("7.")) {
            harness.notRun("A.4/parity", "RA.4 and RA.4b rows and SQL parity with RA.1", "7.4 only");
            return;
        }
        SessionFactory planCacheFactory = harness.factory("parity_plan_cache",
                Map.of("hibernate.criteria.plan_cache_enabled", "true",
                        "hibernate.generate_statistics", "true",
                        "hibernate.hbm2ddl.auto", "none"),
                Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
        try {
            harness.cell("A.4/parity", "RA.4 and RA.4b rows and SQL parity with RA.1", () -> {
                List<TodaySaleModel> ra1Rows;
                String ra1Sql;
                try (EntityManager em = sessions.createEntityManager()) {
                    harness.clearStatements();
                    ra1Rows = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults())
                            .list(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                    ra1Sql = harness.lastSql();
                }
                List<TodaySaleModel> ra4Rows;
                String ra4Sql;
                List<TodaySaleModel> ra4bRows;
                String ra4bSql;
                try (EntityManager em = planCacheFactory.createEntityManager()) {
                    harness.clearStatements();
                    ra4Rows = SpikeRoutes.executeRa4List(em, Sale.class, LitePosQueries.TODAY_QUERY,
                            Limit.unlimited(), ModelQueryConfig.defaults());
                    ra4Sql = harness.lastSql();
                    harness.clearStatements();
                    ra4bRows = SpikeRoutes.executeRa4bList(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(),
                            renderOptions(em));
                    ra4bSql = harness.lastSql();
                }
                assertThat(ra4Rows).isEqualTo(ra1Rows);
                assertThat(ra4Sql).isEqualTo(ra1Sql);
                assertThat(ra4bRows).isEqualTo(ra1Rows);
                assertThat(ra4bSql).isEqualTo(ra1Sql);
                return "RA.1=" + ra1Rows.size() + ", RA.4=" + ra4Rows.size() + ", RA.4b=" + ra4bRows.size();
            });

            // The A.4/stats cell below counts RA.4b; rule 1 is about RA.4 (the executor unchanged), so count it too.
            Statistics stats = planCacheFactory.getStatistics();
            try (EntityManager em = planCacheFactory.createEntityManager()) {
                SpikeRoutes.executeRa4List(em, Sale.class, LitePosQueries.TODAY_QUERY, Limit.unlimited(),
                        ModelQueryConfig.defaults());
                stats.clear();
                for (int i = 0; i < 100; i++) {
                    SpikeRoutes.executeRa4List(em, Sale.class, LitePosQueries.TODAY_QUERY, Limit.unlimited(),
                            ModelQueryConfig.defaults());
                }
            }
            long hits = stats.getQueryPlanCacheHitCount();
            long misses = stats.getQueryPlanCacheMissCount();
            String value = "hits=" + hits + ", misses=" + misses;
            if (hits >= 99) {
                harness.cell("A.4/stats-executor", "RA.4 executor plan-cache hit ratio", () -> value);
            } else {
                harness.fail("A.4/stats-executor", "RA.4 executor plan-cache hit ratio", value);
            }
        } finally {
            planCacheFactory.close();
        }
    }

    @Test
    void functional_ra4_74_plan_cache_statistics() {
        if (SpikeHarness.HIB.startsWith("7.")) {
            SessionFactory planCacheFactory = harness.factory("fresh_plan_cache",
                    Map.of("hibernate.criteria.plan_cache_enabled", "true",
                            "hibernate.generate_statistics", "true",
                            "hibernate.hbm2ddl.auto", "none"),
                    Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
            try {
                Statistics stats = planCacheFactory.getStatistics();
                RenderOptions opts;
                try (EntityManager em = planCacheFactory.createEntityManager()) {
                    opts = renderOptions(em);
                    SpikeRoutes.executeRa4bList(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                }
                stats.clear();
                try (EntityManager em = planCacheFactory.createEntityManager()) {
                    for (int i = 0; i < 100; i++) {
                        SpikeRoutes.executeRa4bList(em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), opts);
                    }
                }
                long hits = stats.getQueryPlanCacheHitCount();
                long misses = stats.getQueryPlanCacheMissCount();
                long total = hits + misses;
                double hitRatio = total > 0 ? (double) hits / total : 0.0;
                String value = "hits=" + hits + ", misses=" + misses + ", ratio=" + String.format("%.3f", hitRatio);
                if (total == 0) {
                    harness.observed("A.4/stats", "criteria_plan_cache_enabled_stats", value);
                } else if (hits >= 99) {
                    harness.cell("A.4/stats", "criteria_plan_cache_enabled_stats", () -> value);
                } else {
                    harness.fail("A.4/stats", "criteria_plan_cache_enabled_stats", value);
                }
            } finally {
                planCacheFactory.close();
            }
        } else {
            harness.notRun("A.4/stats", "criteria_plan_cache_enabled_stats", "7.4 only");
        }
    }

    @Test
    void functional_rb3_single_item_projection_yields_array() {
        harness.cell("B.3", "single-item projection yields Object[]", () -> {
            try (EntityManager em = sessions.createEntityManager()) {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
                Root<Sale> root = cq.from(Sale.class);
                cq.select(cb.array(root.get("id")));

                List<Object[]> rows = em.createQuery(cq).setMaxResults(5).getResultList();
                assertThat(rows).isNotEmpty();
                for (Object[] r : rows) {
                    assertThat(r).isNotNull().hasSize(1);
                    assertThat(r[0]).isInstanceOf(Long.class);
                }
                return "rows=" + rows.size() + ", item[0]=" + rows.get(0)[0];
            }
        });
    }

    @Test
    void functional_all_routes_as_code() {
        harness.cell("A/B", "all routes as code", () -> {
            try (EntityManager em = sessions.createEntityManager()) {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                RenderOptions options = renderOptions(em);
                var path = SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults());

                // ---- Part A Routes ----
                // RA.1
                List<TodaySaleModel> ra1 = SpikeRoutes.executeRa1List(
                        em, Sale.class, LitePosQueries.TODAY_QUERY, Limit.unlimited(), ModelQueryConfig.defaults());
                assertThat(ra1).hasSize(50);
                String loggedSql = harness.lastSql();

                // RA.0: JdbcClient baseline
                JdbcClient jdbc = JdbcClient.create(harness.dataSource());
                List<TodaySaleModel> ra0 = SpikeRoutes.executeRa0Today(
                        jdbc, loggedSql, LitePosDataset.TODAY_START, LitePosDataset.TODAY_END, 1L);
                assertThat(ra0).hasSize(50);

                // RA.2: buildQuery alone
                BuiltQuery<TodaySaleModel> ra2 = SpikeRoutes.executeRa2Build(em, LitePosQueries.TODAY_QUERY, options);
                assertThat(ra2).isNotNull();

                // RA.3: SealedBuildCache
                List<TodaySaleModel> ra3 = SpikeRoutes.executeRa3List(
                        em, LitePosQueries.TODAY_QUERY, Limit.unlimited(), options);
                assertThat(ra3).isEqualTo(ra1);

                // RA.4 & RA.4b (7.4 only with plan cache enabled)
                if (SpikeHarness.HIB.startsWith("7.")) {
                    SessionFactory planCacheFactory = harness.factory("litepos_plan_cache",
                            Map.of("hibernate.criteria.plan_cache_enabled", "true",
                                    "hibernate.generate_statistics", "true",
                                    "hibernate.hbm2ddl.auto", "none"),
                            Store.class, Terminal.class, Cashier.class, Sale.class, SaleLine.class);
                    try (EntityManager emPlan = planCacheFactory.createEntityManager()) {
                        List<TodaySaleModel> ra4 = SpikeRoutes.executeRa4List(
                                emPlan, Sale.class, LitePosQueries.TODAY_QUERY, Limit.unlimited(),
                                ModelQueryConfig.defaults());
                        assertThat(ra4).isEqualTo(ra1);

                        List<TodaySaleModel> ra4b = SpikeRoutes.executeRa4bList(
                                emPlan, LitePosQueries.TODAY_QUERY, Limit.unlimited(), options);
                        assertThat(ra4b).isEqualTo(ra1);
                    } finally {
                        planCacheFactory.close();
                    }
                } else {
                    harness.notRun("A.4", "plan_cache_enabled", "7.4 only (key does not exist on 6.6)");
                    harness.notRun("A.4b", "build+plan_cache", "7.4 only (key does not exist on 6.6)");
                }

                // RA.5: HQL provider route
                List<TodaySaleModel> ra5 = SpikeRoutes.executeRa5Today(em, options);
                assertThat(ra5).isEqualTo(ra1);

                // RA.6: Native SQL string mapped through SpikeListPath (Item 7)
                List<TodaySaleModel> ra6 = SpikeRoutes.executeRa6Today(
                        em, loggedSql, LitePosDataset.TODAY_START, LitePosDataset.TODAY_END, 1L, options);
                assertThat(ra6).isEqualTo(ra1);

                // Fixed queries and other read shapes on RA.1, RA.3 and RA.5 (one, page, grouped) (M2)
                SaleSummaryModel one = executor.one(LitePosQueries.ONE_QUERY, 1L).orElseThrow();
                assertThat(one.id()).isEqualTo(1L);

                Slice<SaleSummaryModel> page = executor.page(
                        LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20), CountMode.COUNT);
                assertThat(page.content()).hasSize(20);
                assertThat(page.total()).isPresent();

                List<GroupedCountModel> grouped = executor.list(LitePosQueries.GROUPED_QUERY, Limit.unlimited());
                assertThat(grouped).isNotEmpty();

                // RA.3 on one, page, grouped
                SaleSummaryModel ra3One = path.oneRa3(LitePosQueries.ONE_QUERY, 1L).orElseThrow();
                assertThat(ra3One).isEqualTo(one);

                Slice<SaleSummaryModel> ra3Page = path.pageRa3(
                        LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20), CountMode.COUNT);
                assertThat(ra3Page.content()).isEqualTo(page.content());
                assertThat(ra3Page.total()).isEqualTo(page.total());

                List<GroupedCountModel> ra3Grouped = path.listRa3(LitePosQueries.GROUPED_QUERY, Limit.unlimited());
                assertThat(ra3Grouped).isEqualTo(grouped);

                // RA.5 on one, page, grouped (M2: inside SpikeListPath)
                SaleSummaryModel ra5One = path.oneRa5(LitePosQueries.ONE_QUERY, 1L, options).orElseThrow();
                assertThat(ra5One).isEqualTo(one);

                Slice<SaleSummaryModel> ra5Page = path.pageRa5(
                        LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20), CountMode.COUNT, options);
                assertThat(ra5Page.content()).isEqualTo(page.content());
                assertThat(ra5Page.total()).isEqualTo(page.total());

                List<GroupedCountModel> ra5Grouped = path.groupedRa5(LitePosQueries.GROUPED_QUERY, options);
                assertThat(ra5Grouped).isEqualTo(grouped);

                // ---- Part B Routes ----
                // RB.4: today's list
                List<FlatSaleProjection> rb4 = SpikeRoutes.executeRb4List(
                        em, LitePosQueries.FLAT_QUERY, Limit.of(50), ModelQueryConfig.defaults());
                assertThat(rb4).hasSize(50);
                String flatSql = harness.lastSql();

                // RB.0: JdbcClient + RowMapper
                List<FlatSaleProjection> rb0 = SpikeRoutes.executeRb0Flat(jdbc, flatSql, 50);
                assertThat(rb0).hasSize(50);

                BuiltQuery<FlatSaleProjection> builtFlat =
                        LitePosQueries.FLAT_QUERY.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options);

                // RB.1: Tuple.get(String)
                List<FlatSaleProjection> rb1 = SpikeRoutes.executeRb1TupleString(em, builtFlat.query(), 50);
                assertThat(rb1).isEqualTo(rb4);

                // RB.2: Tuple.get(int)
                List<FlatSaleProjection> rb2 = SpikeRoutes.executeRb2TupleInt(em, builtFlat.query(), 50);
                assertThat(rb2).isEqualTo(rb4);

                // RB.3: CriteriaQuery<Object[]> (cb.array)
                CriteriaBuilder cb = em.getCriteriaBuilder();
                CriteriaQuery<Object[]> cqArray = cb.createQuery(Object[].class);
                Root<Sale> root = cqArray.from(Sale.class);
                Join<Sale, Store> storeJoin = root.join("store");
                Join<Sale, Terminal> termJoin = root.join("terminal");
                Join<Sale, Cashier> cashJoin = root.join("cashier");
                cqArray.select(cb.array(
                        root.get("id"), root.get("receiptNumber"), root.get("saleTime"),
                        root.get("status"), root.get("totalAmount"), root.get("taxAmount"),
                        root.get("discountAmount"), root.get("note"), storeJoin.get("id"),
                        termJoin.get("id"), cashJoin.get("id"), root.get("itemCount")
                ));
                List<FlatSaleProjection> rb3 = SpikeRoutes.executeRb3Array(em, cqArray, 50);
                assertThat(rb3).isEqualTo(rb4);

                // RB.5: IndexResolvedValueRow inside list envelope
                List<FlatSaleProjection> rb5 = SpikeRoutes.executeRb5List(
                        em, LitePosQueries.FLAT_QUERY, Limit.of(50), options);
                assertThat(rb5).isEqualTo(rb4);

                // RB.6: FlatRowMapper inside list envelope
                List<FlatSaleProjection> rb6 = SpikeRoutes.executeRb6List(
                        em, LitePosQueries.FLAT_QUERY, Limit.of(50), options);
                assertThat(rb6).isEqualTo(rb4);

                // RB.7: Stream & Export
                long streamed = SpikeRoutes.executeRb7Stream(
                        em, LitePosQueries.FLAT_QUERY, ModelQueryConfig.defaults());
                assertThat(streamed).isEqualTo(5);

                List<FlatSaleProjection> exported = new ArrayList<>();
                SpikeRoutes.executeRb7Export(
                        em, LitePosQueries.FLAT_QUERY, ModelQueryConfig.defaults(), exported::add);
                assertThat(exported).hasSize(800);

                return "all Part A (RA.0-RA.6) and Part B (RB.0-RB.7) routes executed successfully";
            }
        });
    }
}
