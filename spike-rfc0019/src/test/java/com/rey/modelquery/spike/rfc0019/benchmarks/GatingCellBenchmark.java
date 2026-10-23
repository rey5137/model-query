package com.rey.modelquery.spike.rfc0019.benchmarks;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.SpikeListPath;
import com.rey.modelquery.spike.rfc0019.LitePosEntities.Sale;
import com.rey.modelquery.spike.rfc0019.LitePosModels.TodaySaleModel;
import com.rey.modelquery.spike.rfc0019.LitePosQueries;
import com.rey.modelquery.spike.rfc0019.SealedBuildCache;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Parameter;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@Threads(16)
public class GatingCellBenchmark {

    @State(Scope.Benchmark)
    public static class GatingBenchmarkState {
        public HikariDataSource ds;
        public SessionFactory sessions;
        public RenderOptions renderOptions;
        public SealedBuildCache.ScopeCache buildScope;

        @Setup
        public void setup() throws Exception {
            String url = BenchmarkDatabase.pgJdbcUrl();
            String user = BenchmarkDatabase.pgUsername();
            String pass = BenchmarkDatabase.pgPassword();

            ds = BenchmarkDatabase.createDataSource(url, user, pass, 20);
            sessions = BenchmarkDatabase.createSessionFactory(ds, Map.of(
                    "hibernate.criteria.copy_tree", "true"
            ));

            try (EntityManager em = sessions.createEntityManager()) {
                var path = SpikeListPath.of(em, Sale.class, ModelQueryConfig.defaults());
                renderOptions = path.renderOptions();
                buildScope = path.buildScope();
                // Pre-populate the cache entry so all 16 threads hit the cache
                buildScope.getOrBuild(em, LitePosQueries.TODAY_QUERY, "LIST", null, null, renderOptions);
            }
        }

        @TearDown
        public void tearDown() {
            if (sessions != null) sessions.close();
            if (ds != null) ds.close();
            SpikeListPath.clearContexts();
        }
    }

    @State(Scope.Thread)
    public static class GatingThreadState {
        public EntityManager em;

        @Setup
        public void setup(GatingBenchmarkState benchState) {
            em = benchState.sessions.createEntityManager();
        }

        @TearDown
        public void tearDown() {
            if (em != null && em.isOpen()) {
                em.close();
            }
        }
    }

    @Benchmark
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Query gating_freshBuild(GatingBenchmarkState benchState, GatingThreadState threadState) {
        BuiltQuery<TodaySaleModel> built = LitePosQueries.TODAY_QUERY.buildQuery(
                threadState.em.getCriteriaBuilder(), Phase.MODEL, benchState.renderOptions);
        TypedQuery<Tuple> q = threadState.em.createQuery(built.query());
        if (built.joins() != null) {
            built.joins().statementParameters().forEach((p, v) -> {
                q.setParameter((Parameter) p, v);
            });
        }
        q.getParameters();
        return q;
    }

    @Benchmark
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Query gating_buildCache(GatingBenchmarkState benchState, GatingThreadState threadState) {
        SealedBuildCache.CacheEntry<TodaySaleModel> entry = benchState.buildScope.getOrBuild(
                threadState.em, LitePosQueries.TODAY_QUERY, "LIST", null, null, benchState.renderOptions);
        TypedQuery<Tuple> q = threadState.em.createQuery((CriteriaQuery<Tuple>) entry.criteriaQuery());
        if (entry.builtQuery().joins() != null) {
            entry.builtQuery().joins().statementParameters().forEach((p, v) -> {
                q.setParameter((Parameter) p, v);
            });
        }
        q.getParameters();
        return q;
    }
}
