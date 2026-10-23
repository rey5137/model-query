package com.rey.modelquery.spike.rfc0019.benchmarks;

import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.SpikeListPath;
import com.rey.modelquery.spike.rfc0019.LitePosDataset;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Sale;
import com.rey.modelquery.spike.rfc0019.LitePosModels.TodaySaleModel;
import com.rey.modelquery.spike.rfc0019.LitePosQueries;
import com.rey.modelquery.spike.rfc0019.SpikeRoutes;
import com.rey.modelquery.spike.rfc0019.benchmarks.BenchmarkDatabase.StatementCapture;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
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
public class MySqlInformationalBenchmark {

    @State(Scope.Benchmark)
    public static class MySqlState {
        public HikariDataSource ds;
        public SessionFactory sessions;
        public TransactionTemplate txTemplate;
        public JdbcClient jdbc;
        public RenderOptions renderOptions;
        public SpikeListPath<Sale> pathMySql;
        public String todaySql;

        @Setup
        public void setup() throws Exception {
            String url = BenchmarkDatabase.mysqlJdbcUrl();
            String user = BenchmarkDatabase.mysqlUsername();
            String pass = BenchmarkDatabase.mysqlPassword();

            ds = BenchmarkDatabase.createDataSource(url, user, pass, 10);
            BenchmarkDatabase.warmPool(ds, 10);

            StatementCapture capture = new StatementCapture();
            sessions = BenchmarkDatabase.createSessionFactory(ds, Map.of(
                    "hibernate.criteria.copy_tree", "true"
            ), capture);

            txTemplate = new TransactionTemplate(new DataSourceTransactionManager(ds));
            jdbc = JdbcClient.create(ds);

            try (EntityManager em = sessions.createEntityManager()) {
                pathMySql = SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults());
                renderOptions = pathMySql.renderOptions();

                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                capture.clear();
                executor.list(LitePosQueries.TODAY_QUERY, Limit.unlimited());
                todaySql = capture.last();
                if (todaySql.isEmpty()) {
                    throw new IllegalStateException("todaySql empty on MySQL");
                }
            }
        }

        @TearDown
        public void tearDown() {
            if (sessions != null) sessions.close();
            if (ds != null) ds.close();
            SpikeListPath.clearContexts();
        }
    }

    @Benchmark
    public List<TodaySaleModel> mysql_today_RA0_jdbc(MySqlState state) {
        return state.txTemplate.execute(status -> SpikeRoutes.executeRa0Today(
                state.jdbc, state.todaySql, LitePosDataset.TODAY_START, LitePosDataset.TODAY_END, 1L));
    }

    @Benchmark
    public List<TodaySaleModel> mysql_today_RA1_executor(MySqlState state) {
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
    public List<TodaySaleModel> mysql_today_RA3_copyTree(MySqlState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathMySql.withEntityManager(em)
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
    public List<TodaySaleModel> mysql_today_RA5_hqlProvider(MySqlState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<TodaySaleModel> res = state.pathMySql.withEntityManager(em).listRa5Today(
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
