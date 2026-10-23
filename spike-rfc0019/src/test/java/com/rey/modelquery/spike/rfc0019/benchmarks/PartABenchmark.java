package com.rey.modelquery.spike.rfc0019.benchmarks;

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
import com.rey.modelquery.spike.rfc0019.LitePosDataset;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Sale;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.SaleStatus;
import com.rey.modelquery.spike.rfc0019.LitePosModels.GroupedCountModel;
import com.rey.modelquery.spike.rfc0019.LitePosModels.SaleSummaryModel;
import com.rey.modelquery.spike.rfc0019.LitePosModels.TodaySaleModel;
import com.rey.modelquery.spike.rfc0019.LitePosQueries;
import com.rey.modelquery.spike.rfc0019.SpikeHarness;
import com.rey.modelquery.spike.rfc0019.SpikeRoutes;
import com.rey.modelquery.spike.rfc0019.benchmarks.BenchmarkDatabase.StatementCapture;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class PartABenchmark {

    @State(Scope.Benchmark)
    public static class PartAState {
        public HikariDataSource ds;
        public SessionFactory sessions;
        public SessionFactory sessionsNoCopy;
        public SessionFactory sessionsPlanCache;
        public TransactionTemplate txTemplate;
        public JdbcClient jdbc;
        public RenderOptions renderOptions;
        public BuiltQuery<TodaySaleModel> prebuiltToday;

        public SpikeListPath<Sale> pathCopy;
        public SpikeListPath<Sale> pathNoCopy;
        public SpikeListPath<Sale> pathPlan;

        public String todaySql;
        public String oneSql;
        public String pageContentSql;
        public String pageCountSql;
        public String groupedSql;
        public String orderedBySql;

        @Setup
        public void setup() throws Exception {
            String url = BenchmarkDatabase.pgJdbcUrl();
            String user = BenchmarkDatabase.pgUsername();
            String pass = BenchmarkDatabase.pgPassword();

            ds = BenchmarkDatabase.createDataSource(url, user, pass, 10);
            BenchmarkDatabase.warmPool(ds, 10);

            StatementCapture capture = new StatementCapture();
            sessions = BenchmarkDatabase.createSessionFactory(ds, Map.of(
                    "hibernate.criteria.copy_tree", "true"
            ), capture);

            sessionsNoCopy = BenchmarkDatabase.createSessionFactory(ds, Map.of(
                    "hibernate.criteria.copy_tree", "false"
            ));

            if (SpikeHarness.HIB.startsWith("7.")) {
                sessionsPlanCache = BenchmarkDatabase.createSessionFactory(ds, Map.of(
                        "hibernate.criteria.plan_cache_enabled", "true",
                        "hibernate.criteria.copy_tree", "true"
                ));
            }

            txTemplate = new TransactionTemplate(new DataSourceTransactionManager(ds));
            jdbc = JdbcClient.create(ds);

            try (EntityManager em = sessions.createEntityManager()) {
                pathCopy = SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults());
                renderOptions = pathCopy.renderOptions();
                prebuiltToday = SpikeListPath.prebuiltToday(em, renderOptions);

                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());

                // Capture exact SQL statements logged by Hibernate RA.1
                capture.clear();
                executor.list(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                todaySql = capture.last();
                if (todaySql.isEmpty()) throw new IllegalStateException("todaySql empty");

                capture.clear();
                executor.one(LitePosQueries.ONE_QUERY, 1L);
                oneSql = capture.last();
                if (oneSql.isEmpty()) throw new IllegalStateException("oneSql empty");

                capture.clear();
                executor.page(LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20), CountMode.COUNT);
                for (String s : capture.captured()) {
                    if (s.toLowerCase(Locale.ROOT).contains("count")) {
                        pageCountSql = s;
                    } else {
                        pageContentSql = s;
                    }
                }
                if (pageCountSql == null || pageCountSql.isEmpty()) {
                    throw new IllegalStateException("pageCountSql empty");
                }
                if (pageContentSql == null || pageContentSql.isEmpty()) {
                    throw new IllegalStateException("pageContentSql empty");
                }

                capture.clear();
                executor.list(LitePosQueries.GROUPED_QUERY, Limit.unlimited());
                groupedSql = capture.last();
                if (groupedSql.isEmpty()) throw new IllegalStateException("groupedSql empty");

                capture.clear();
                executor.list(LitePosQueries.ORDERED_BY_QUERY, Limit.unlimited());
                orderedBySql = capture.last();
                if (orderedBySql.isEmpty()) throw new IllegalStateException("orderedBySql empty");
            }

            try (EntityManager emNoCopy = sessionsNoCopy.createEntityManager()) {
                pathNoCopy = SpikeListPath.of(emNoCopy, Sale.class, ModelQueryConfig.defaults());
            }

            if (sessionsPlanCache != null) {
                try (EntityManager emPlan = sessionsPlanCache.createEntityManager()) {
                    pathPlan = SpikeListPath.of(emPlan, Sale.class, ModelQueryConfig.defaults());
                }
            }
        }

        @TearDown
        public void tearDown() {
            if (sessions != null) sessions.close();
            if (sessionsNoCopy != null) sessionsNoCopy.close();
            if (sessionsPlanCache != null) sessionsPlanCache.close();
            if (ds != null) ds.close();
            SpikeListPath.clearContexts();
        }
    }

    // ==========================================
    // Query 1: "today" read (50 rows, 1 join, 2 filters)
    // ==========================================

    @Benchmark
    public List<TodaySaleModel> today_RA0_jdbc(PartAState state) {
        return state.txTemplate.execute(status -> SpikeRoutes.executeRa0Today(
                state.jdbc, state.todaySql, LitePosDataset.TODAY_START, LitePosDataset.TODAY_END, 1L));
    }

    @Benchmark
    public List<TodaySaleModel> today_RA1_executor(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = SpikeRoutes.executeRa1List(
                        em, Sale.class, LitePosQueries.TODAY_QUERY, Limit.unlimited(), ModelQueryConfig.defaults());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public BuiltQuery<TodaySaleModel> today_RA2_buildOnly(PartAState state) {
        return LitePosQueries.TODAY_QUERY.buildQuery(
                state.sessions.getCriteriaBuilder(), Phase.MODEL, state.renderOptions);
    }

    @Benchmark
    public List<TodaySaleModel> today_RA3_copyTree(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathCopy.withEntityManager(em)
                        .listRa3(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<TodaySaleModel> today_RA3_noCopyTree(PartAState state) {
        try (EntityManager em = state.sessionsNoCopy.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathNoCopy.withEntityManager(em)
                        .listRa3(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<TodaySaleModel> today_RA4_planCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = SpikeRoutes.executeRa4List(
                        em, Sale.class, LitePosQueries.TODAY_QUERY, Limit.unlimited(), ModelQueryConfig.defaults());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<TodaySaleModel> today_RA4b_copyAndPlanCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathPlan.withEntityManager(em)
                        .listRa3(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<TodaySaleModel> today_RA5_hqlProvider(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathCopy.withEntityManager(em).listRa5Today(
                        LitePosDataset.TODAY_START, LitePosDataset.TODAY_END, 1L, state.renderOptions);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<TodaySaleModel> today_RA6_nativeSql(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathCopy.withEntityManager(em)
                        .listRa6Native(state.todaySql, state.prebuiltToday,
                                LitePosDataset.TODAY_START, LitePosDataset.TODAY_END, 1L);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    // ==========================================
    // Query 2: by-key "one"
    // ==========================================

    @Benchmark
    public Optional<SaleSummaryModel> one_RA0_jdbc(PartAState state) {
        // one() renders "where id=? fetch first ? rows only" and binds 2 to detect duplicate keys.
        return state.txTemplate.execute(status -> state.jdbc.sql(state.oneSql)
                .param(1, 1L)
                .param(2, 2)
                .query((rs, rowNum) -> new SaleSummaryModel(
                        rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)))
                .optional());
    }

    @Benchmark
    public Optional<SaleSummaryModel> one_RA1_executor(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                Optional<SaleSummaryModel> res = executor.one(LitePosQueries.ONE_QUERY, 1L);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public BuiltQuery<SaleSummaryModel> one_RA2_buildOnly(PartAState state) {
        return LitePosQueries.ONE_QUERY.buildQuery(
                state.sessions.getCriteriaBuilder(), Phase.MODEL, state.renderOptions);
    }

    @Benchmark
    public Optional<SaleSummaryModel> one_RA3_copyTree(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Optional<SaleSummaryModel> res = state.pathCopy.withEntityManager(em)
                        .oneRa3(LitePosQueries.ONE_QUERY, 1L);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public Optional<SaleSummaryModel> one_RA3_noCopyTree(PartAState state) {
        try (EntityManager em = state.sessionsNoCopy.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Optional<SaleSummaryModel> res = state.pathNoCopy.withEntityManager(em)
                        .oneRa3(LitePosQueries.ONE_QUERY, 1L);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public Optional<SaleSummaryModel> one_RA4_planCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                Optional<SaleSummaryModel> res = executor.one(LitePosQueries.ONE_QUERY, 1L);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public Optional<SaleSummaryModel> one_RA4b_copyAndPlanCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Optional<SaleSummaryModel> res = state.pathPlan.withEntityManager(em)
                        .oneRa3(LitePosQueries.ONE_QUERY, 1L);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public Optional<SaleSummaryModel> one_RA5_hqlProvider(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Optional<SaleSummaryModel> res = state.pathCopy.withEntityManager(em)
                        .oneRa5(LitePosQueries.ONE_QUERY, 1L, state.renderOptions);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    // ==========================================
    // Query 3: offset "page" with COUNT
    // ==========================================

    @Benchmark
    public Slice<SaleSummaryModel> page_RA0_jdbc(PartAState state) {
        return state.txTemplate.execute(status -> {
            long total = state.jdbc.sql(state.pageCountSql).query(Long.class).single();
            // Bind whatever limit/offset placeholders Hibernate rendered: "fetch first ?" alone, or
            // "offset ? rows fetch first ?" when the offset is also a parameter.
            var content0 = state.jdbc.sql(state.pageContentSql);
            content0 = state.pageContentSql.chars().filter(c -> c == '?').count() == 2
                    ? content0.param(1, 0).param(2, 20)
                    : content0.param(1, 20);
            List<SaleSummaryModel> content = content0
                    .query((rs, rowNum) -> new SaleSummaryModel(
                            rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)))
                    .list();
            return new Slice<>(content, 0, 20, 20L < total, OptionalLong.of(total));
        });
    }

    @Benchmark
    public Slice<SaleSummaryModel> page_RA1_executor(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                Slice<SaleSummaryModel> res = executor.page(
                        LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20), CountMode.COUNT);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public BuiltQuery<SaleSummaryModel> page_RA2_buildOnly(PartAState state) {
        return LitePosQueries.PAGE_QUERY.buildQuery(
                state.sessions.getCriteriaBuilder(), Phase.MODEL, state.renderOptions);
    }

    @Benchmark
    public Slice<SaleSummaryModel> page_RA3_copyTree(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Slice<SaleSummaryModel> res = state.pathCopy.withEntityManager(em)
                        .pageRa3(LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20), CountMode.COUNT);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public Slice<SaleSummaryModel> page_RA3_noCopyTree(PartAState state) {
        try (EntityManager em = state.sessionsNoCopy.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Slice<SaleSummaryModel> res = state.pathNoCopy.withEntityManager(em)
                        .pageRa3(LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20), CountMode.COUNT);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public Slice<SaleSummaryModel> page_RA4_planCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                Slice<SaleSummaryModel> res = executor.page(
                        LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20), CountMode.COUNT);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public Slice<SaleSummaryModel> page_RA4b_copyAndPlanCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Slice<SaleSummaryModel> res = state.pathPlan.withEntityManager(em)
                        .pageRa3(LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20), CountMode.COUNT);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public Slice<SaleSummaryModel> page_RA5_hqlProvider(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                Slice<SaleSummaryModel> res = state.pathCopy.withEntityManager(em)
                        .pageRa5(LitePosQueries.PAGE_QUERY, PageSpec.of(0, 20),
                                CountMode.COUNT, state.renderOptions);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    // ==========================================
    // Query 4: 365-day "grouped" count
    // ==========================================

    @Benchmark
    public List<GroupedCountModel> grouped_RA0_jdbc(PartAState state) {
        return state.txTemplate.execute(status -> state.jdbc.sql(state.groupedSql)
                .query((rs, rowNum) -> new GroupedCountModel(
                        SaleStatus.valueOf(rs.getString(1)), rs.getLong(2)))
                .list());
    }

    @Benchmark
    public List<GroupedCountModel> grouped_RA1_executor(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                List<GroupedCountModel> res = executor.list(LitePosQueries.GROUPED_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public BuiltQuery<GroupedCountModel> grouped_RA2_buildOnly(PartAState state) {
        return LitePosQueries.GROUPED_QUERY.buildQuery(
                state.sessions.getCriteriaBuilder(), Phase.MODEL, state.renderOptions);
    }

    @Benchmark
    public List<GroupedCountModel> grouped_RA3_copyTree(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<GroupedCountModel> res = state.pathCopy.withEntityManager(em)
                        .listRa3(LitePosQueries.GROUPED_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<GroupedCountModel> grouped_RA3_noCopyTree(PartAState state) {
        try (EntityManager em = state.sessionsNoCopy.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<GroupedCountModel> res = state.pathNoCopy.withEntityManager(em)
                        .listRa3(LitePosQueries.GROUPED_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<GroupedCountModel> grouped_RA4_planCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                List<GroupedCountModel> res = executor.list(LitePosQueries.GROUPED_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<GroupedCountModel> grouped_RA4b_copyAndPlanCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<GroupedCountModel> res = state.pathPlan.withEntityManager(em)
                        .listRa3(LitePosQueries.GROUPED_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<GroupedCountModel> grouped_RA5_hqlProvider(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<GroupedCountModel> res = state.pathCopy.withEntityManager(em)
                        .groupedRa5(LitePosQueries.GROUPED_QUERY, state.renderOptions);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    // ==========================================
    // Query 5: compiled "orderedBy" call
    // ==========================================

    @Benchmark
    public List<TodaySaleModel> orderedBy_RA0_jdbc(PartAState state) {
        return state.txTemplate.execute(status -> SpikeRoutes.executeRa0Today(
                state.jdbc, state.orderedBySql, LitePosDataset.TODAY_START, LitePosDataset.TODAY_END, 1L));
    }

    @Benchmark
    public List<TodaySaleModel> orderedBy_RA1_executor(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                List<TodaySaleModel> res = executor.list(LitePosQueries.ORDERED_BY_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public BuiltQuery<TodaySaleModel> orderedBy_RA2_buildOnly(PartAState state) {
        return LitePosQueries.ORDERED_BY_QUERY.buildQuery(
                state.sessions.getCriteriaBuilder(), Phase.MODEL, state.renderOptions);
    }

    @Benchmark
    public List<TodaySaleModel> orderedBy_RA3_copyTree(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathCopy.withEntityManager(em)
                        .listRa3(LitePosQueries.ORDERED_BY_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<TodaySaleModel> orderedBy_RA3_noCopyTree(PartAState state) {
        try (EntityManager em = state.sessionsNoCopy.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathNoCopy.withEntityManager(em)
                        .listRa3(LitePosQueries.ORDERED_BY_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<TodaySaleModel> orderedBy_RA4_planCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                List<TodaySaleModel> res = executor.list(LitePosQueries.ORDERED_BY_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<TodaySaleModel> orderedBy_RA4b_copyAndPlanCache(PartAState state) {
        try (EntityManager em = state.sessionsPlanCache.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathPlan.withEntityManager(em)
                        .listRa3(LitePosQueries.ORDERED_BY_QUERY, Limit.unlimited());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<TodaySaleModel> orderedBy_RA5_hqlProvider(PartAState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathCopy.withEntityManager(em).listRa5TodayOrdered(
                        LitePosDataset.TODAY_START, LitePosDataset.TODAY_END, 1L, state.renderOptions);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }
}
