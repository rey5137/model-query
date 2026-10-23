package com.rey.modelquery.spike.rfc0019.benchmarks;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.IndexResolvedValueRow;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.SpikeListPath;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Cashier;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Sale;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Store;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Terminal;
import com.rey.modelquery.spike.rfc0019.LitePosModels.FlatSaleProjection;
import com.rey.modelquery.spike.rfc0019.LitePosQueries;
import com.rey.modelquery.spike.rfc0019.SpikeRoutes;
import com.rey.modelquery.spike.rfc0019.benchmarks.BenchmarkDatabase.StatementCapture;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class PartBBenchmark {

    @State(Scope.Benchmark)
    public static class PartBState {
        public HikariDataSource ds;
        public SessionFactory sessions;
        public TransactionTemplate txTemplate;
        public JdbcClient jdbc;
        public RenderOptions renderOptions;
        public BuiltQuery<FlatSaleProjection> builtFlat;
        public CriteriaQuery<Object[]> cqArray;
        public SpikeListPath<Sale> pathCopy;
        public IndexResolvedValueRow.IndexResolver flatResolver;
        public String flatSql;

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

            txTemplate = new TransactionTemplate(new DataSourceTransactionManager(ds));
            jdbc = JdbcClient.create(ds);

            try (EntityManager em = sessions.createEntityManager()) {
                pathCopy = SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults());
                renderOptions = pathCopy.renderOptions();

                var executor = ModelQueryExecutor.create(em, Sale.class, ModelQueryConfig.defaults());
                capture.clear();
                executor.list(LitePosQueries.FLAT_QUERY, Limit.unlimited());
                flatSql = capture.last();
                if (flatSql.isEmpty()) {
                    throw new IllegalStateException("flatSql empty");
                }
            }

            flatResolver = SpikeRoutes.flatResolver();

            CriteriaBuilder cb = sessions.getCriteriaBuilder();
            builtFlat = LitePosQueries.FLAT_QUERY.buildQuery(cb, Phase.MODEL, renderOptions);

            cqArray = cb.createQuery(Object[].class);
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
        }

        @TearDown
        public void tearDown() {
            if (sessions != null) sessions.close();
            if (ds != null) ds.close();
            SpikeListPath.clearContexts();
        }
    }

    @Benchmark
    public List<FlatSaleProjection> rb0_jdbcClientRowMapper(PartBState state) {
        return state.txTemplate.execute(status -> SpikeRoutes.executeRb0Flat(
                state.jdbc, state.flatSql, Integer.MAX_VALUE));
    }

    @Benchmark
    public List<FlatSaleProjection> rb1_criteriaTupleString(PartBState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<FlatSaleProjection> res = SpikeRoutes.executeRb1TupleString(
                        em, state.builtFlat.query(), Integer.MAX_VALUE);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<FlatSaleProjection> rb2_criteriaTupleInt(PartBState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<FlatSaleProjection> res = SpikeRoutes.executeRb2TupleInt(
                        em, state.builtFlat.query(), Integer.MAX_VALUE);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<FlatSaleProjection> rb3_criteriaArray(PartBState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<FlatSaleProjection> res = SpikeRoutes.executeRb3Array(
                        em, state.cqArray, Integer.MAX_VALUE);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<FlatSaleProjection> rb4_todayList(PartBState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<FlatSaleProjection> res = SpikeRoutes.executeRb4List(
                        em, LitePosQueries.FLAT_QUERY, Limit.unlimited(), ModelQueryConfig.defaults());
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<FlatSaleProjection> rb5_indexResolvedValueRow(PartBState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<FlatSaleProjection> res = state.pathCopy.withEntityManager(em)
                        .listRb5(LitePosQueries.FLAT_QUERY, Limit.unlimited(), state.flatResolver);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public List<FlatSaleProjection> rb6_flatRowMapper(PartBState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                List<FlatSaleProjection> res = state.pathCopy.withEntityManager(em)
                        .listRb6(LitePosQueries.FLAT_QUERY, Limit.unlimited(), state.flatResolver);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public long rb7_stream(PartBState state) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                long res = SpikeRoutes.executeRb7Stream(
                        em, LitePosQueries.FLAT_QUERY, ModelQueryConfig.defaults(), Stream::count);
                em.getTransaction().commit();
                return res;
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }

    @Benchmark
    public void rb7_export(PartBState state, Blackhole bh) {
        try (EntityManager em = state.sessions.createEntityManager()) {
            em.getTransaction().begin();
            try {
                SpikeRoutes.executeRb7Export(
                        em, LitePosQueries.FLAT_QUERY, ModelQueryConfig.defaults(), bh::consume);
                em.getTransaction().commit();
            } catch (Throwable t) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
                throw t;
            }
        }
    }
}
