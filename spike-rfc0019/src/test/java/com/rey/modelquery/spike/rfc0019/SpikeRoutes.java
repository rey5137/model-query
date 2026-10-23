package com.rey.modelquery.spike.rfc0019;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.IndexResolvedValueRow;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.SpikeListPath;
import com.rey.modelquery.spike.rfc0019.LitePosModels.FlatSaleProjection;
import com.rey.modelquery.spike.rfc0019.LitePosModels.TodaySaleModel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class SpikeRoutes {

    private SpikeRoutes() {}

    // ---- Part A Routes ----

    /** RA.0: JdbcClient baseline running the SQL Hibernate logged. */
    public static List<TodaySaleModel> executeRa0Today(
            JdbcClient jdbc,
            String sql,
            Object... params
    ) {
        RowMapper<TodaySaleModel> mapper = (rs, rowNum) -> new TodaySaleModel(
                rs.getLong(1),
                rs.getString(2),
                rs.getTimestamp(3) != null ? rs.getTimestamp(3).toInstant() : null,
                rs.getString(4),
                rs.getBigDecimal(5),
                LitePosEntities.SaleStatus.valueOf(rs.getString(6))
        );
        Object[] converted = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (params[i] instanceof java.time.Instant inst) {
                converted[i] = java.sql.Timestamp.from(inst);
            } else {
                converted[i] = params[i];
            }
        }
        return jdbc.sql(sql)
                .params(List.of(converted))
                .query(mapper)
                .list();
    }

    /** RA.1: Today's executor (M1: runs on real executor.list). */
    public static <E, M> List<M> executeRa1List(
            EntityManager em,
            Class<E> rootClass,
            ModelQuery<E, ?, M> q,
            Limit limit,
            ModelQueryConfig config
    ) {
        return ModelQueryExecutor.create(em, rootClass, config).list(q, limit);
    }

    /** RA.2: ModelQuery#buildQuery alone. */
    public static <E, M> BuiltQuery<M> executeRa2Build(
            EntityManager em,
            ModelQuery<E, ?, M> q,
            RenderOptions options
    ) {
        return q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options);
    }

    /** RA.3: Reused CriteriaQuery via SealedBuildCache in the executor envelope. */
    public static <E, M> List<M> executeRa3List(
            EntityManager em,
            ModelQuery<E, ?, M> q,
            Limit limit,
            RenderOptions options
    ) {
        Class<E> rootClass = q.rootEntity();
        return SpikeListPath.of(em, rootClass, ModelQueryConfig.defaults()).listRa3(q, limit);
    }

    /** RA.4: RA.1 plus hibernate.criteria.plan_cache_enabled=true (7.4 only). */
    public static <E, M> List<M> executeRa4List(
            EntityManager em,
            Class<E> rootClass,
            ModelQuery<E, ?, M> q,
            Limit limit,
            ModelQueryConfig config
    ) {
        if (!SpikeHarness.HIB.startsWith("7.")) {
            throw new UnsupportedOperationException("RA.4 is 7.4 only; setting does not exist on 6.6");
        }
        return ModelQueryExecutor.create(em, rootClass, config).list(q, limit);
    }

    /** RA.4b: RA.3 plus hibernate.criteria.plan_cache_enabled=true (7.4 only). */
    public static <E, M> List<M> executeRa4bList(
            EntityManager em,
            ModelQuery<E, ?, M> q,
            Limit limit,
            RenderOptions options
    ) {
        if (!SpikeHarness.HIB.startsWith("7.")) {
            throw new UnsupportedOperationException("RA.4b is 7.4 only; setting does not exist on 6.6");
        }
        Class<E> rootClass = q.rootEntity();
        return SpikeListPath.of(em, rootClass, ModelQueryConfig.defaults()).listRa3(q, limit);
    }

    /** RA.4h: RA.3 plus hint hibernate.query.plan.cacheable (6.6 statistics cell). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <E, M> List<M> executeRa4hList(
            EntityManager em,
            ModelQuery<E, ?, M> q,
            Limit limit,
            RenderOptions options
    ) {
        SealedBuildCache.CacheEntry<M> entry = SealedBuildCache.getOrBuild(
                em, q, "LIST", null, null, options);
        BuiltQuery<M> built;
        CriteriaQuery<Tuple> cq;
        if (entry != null) {
            built = entry.builtQuery();
            cq = (CriteriaQuery<Tuple>) entry.criteriaQuery();
        } else {
            built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options);
            cq = built.query();
        }
        TypedQuery<Tuple> tq = em.createQuery(cq);
        tq.setHint("hibernate.query.plan.cacheable", true);
        if (built.joins() != null) {
            built.joins().statementParameters().forEach((param, val) -> {
                tq.setParameter((jakarta.persistence.Parameter) param, val);
            });
        }
        limit.maxRows().ifPresent(tq::setMaxResults);
        List<Tuple> tuples = tq.getResultList();
        List<M> result = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            result.add(built.map(built.selection().row(t)));
        }
        return result;
    }

    /** RA.5: HQL provider route executed within the executor envelope. */
    public static List<TodaySaleModel> executeRa5Today(
            EntityManager em,
            RenderOptions options
    ) {
        return HqlProviderRoute.listToday(
                em, LitePosDataset.TODAY_START, LitePosDataset.TODAY_END, 1L, options);
    }

    /** RA.6: Native SQL string through createNativeQuery with prebuilt query (Nm2). */
    public static List<TodaySaleModel> executeRa6Today(
            EntityManager em,
            String sql,
            BuiltQuery<TodaySaleModel> built,
            Instant start,
            Instant end,
            Long storeId
    ) {
        return SpikeListPath.of(em, LitePosEntities.Sale.class, ModelQueryConfig.defaults())
                .listRa6Native(sql, built, start, end, storeId);
    }

    /** RA.6: Native SQL string through createNativeQuery (Item 7, Nm2). */
    public static List<TodaySaleModel> executeRa6Today(
            EntityManager em,
            String sql,
            Instant start,
            Instant end,
            Long storeId,
            RenderOptions options
    ) {
        return executeRa6Today(em, sql, SpikeListPath.prebuiltToday(em, options), start, end, storeId);
    }

    // ---- Part B Routes ----

    /** RB.0: JdbcClient + RowMapper for flat projection. */
    public static List<FlatSaleProjection> executeRb0Flat(
            JdbcClient jdbc,
            String sql,
            int limit
    ) {
        RowMapper<FlatSaleProjection> mapper = (rs, rowNum) -> new FlatSaleProjection(
                rs.getLong(1),
                rs.getString(2),
                rs.getTimestamp(3) != null ? rs.getTimestamp(3).toInstant() : null,
                LitePosEntities.SaleStatus.valueOf(rs.getString(4)),
                rs.getBigDecimal(5),
                rs.getBigDecimal(6),
                rs.getBigDecimal(7),
                rs.getString(8),
                rs.getLong(9),
                rs.getLong(10),
                rs.getLong(11),
                rs.getLong(12)
        );
        var spec = jdbc.sql(sql);
        if (sql.contains("?")) {
            spec = spec.param(1, limit);
        }
        return spec.query(mapper).list();
    }

    /** RB.1: Raw JPA CriteriaQuery<Tuple>, Tuple.get(String). */
    public static List<FlatSaleProjection> executeRb1TupleString(
            EntityManager em,
            CriteriaQuery<Tuple> cq,
            int limit
    ) {
        TypedQuery<Tuple> q = em.createQuery(cq);
        if (limit > 0 && limit < Integer.MAX_VALUE) {
            q.setMaxResults(limit);
        }
        List<Tuple> tuples = q.getResultList();
        List<FlatSaleProjection> result = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            result.add(new FlatSaleProjection(
                    t.get("c0", Long.class),
                    t.get("c1", String.class),
                    t.get("c2", java.time.Instant.class),
                    (LitePosEntities.SaleStatus) t.get("c3"),
                    t.get("c4", java.math.BigDecimal.class),
                    t.get("c5", java.math.BigDecimal.class),
                    t.get("c6", java.math.BigDecimal.class),
                    t.get("c7", String.class),
                    t.get("c8", Long.class),
                    t.get("c9", Long.class),
                    t.get("c10", Long.class),
                    t.get("c11", Long.class)
            ));
        }
        return result;
    }

    /** RB.2: Raw JPA CriteriaQuery<Tuple>, Tuple.get(int). */
    public static List<FlatSaleProjection> executeRb2TupleInt(
            EntityManager em,
            CriteriaQuery<Tuple> cq,
            int limit
    ) {
        TypedQuery<Tuple> q = em.createQuery(cq);
        if (limit > 0 && limit < Integer.MAX_VALUE) {
            q.setMaxResults(limit);
        }
        List<Tuple> tuples = q.getResultList();
        List<FlatSaleProjection> result = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            result.add(new FlatSaleProjection(
                    (Long) t.get(0),
                    (String) t.get(1),
                    (java.time.Instant) t.get(2),
                    (LitePosEntities.SaleStatus) t.get(3),
                    (java.math.BigDecimal) t.get(4),
                    (java.math.BigDecimal) t.get(5),
                    (java.math.BigDecimal) t.get(6),
                    (String) t.get(7),
                    (Long) t.get(8),
                    (Long) t.get(9),
                    (Long) t.get(10),
                    (Long) t.get(11)
            ));
        }
        return result;
    }

    /** RB.3: Raw JPA CriteriaQuery<Object[]> (cb.array). */
    public static List<FlatSaleProjection> executeRb3Array(
            EntityManager em,
            CriteriaQuery<Object[]> cq,
            int limit
    ) {
        TypedQuery<Object[]> q = em.createQuery(cq);
        if (limit > 0 && limit < Integer.MAX_VALUE) {
            q.setMaxResults(limit);
        }
        List<Object[]> rows = q.getResultList();
        List<FlatSaleProjection> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(new FlatSaleProjection(
                    (Long) r[0],
                    (String) r[1],
                    (java.time.Instant) r[2],
                    (LitePosEntities.SaleStatus) r[3],
                    (java.math.BigDecimal) r[4],
                    (java.math.BigDecimal) r[5],
                    (java.math.BigDecimal) r[6],
                    (String) r[7],
                    (Long) r[8],
                    (Long) r[9],
                    (Long) r[10],
                    (Long) r[11]
            ));
        }
        return result;
    }

    /** RB.4: Today's list (M1: runs on real executor.list). */
    public static List<FlatSaleProjection> executeRb4List(
            EntityManager em,
            ModelQuery<LitePosEntities.Sale, ?, FlatSaleProjection> q,
            Limit limit,
            ModelQueryConfig config
    ) {
        return ModelQueryExecutor.create(em, LitePosEntities.Sale.class, config).list(q, limit);
    }

    public static IndexResolvedValueRow.IndexResolver flatResolver() {
        return IndexResolvedValueRow.resolver(List.of(
                LitePosQueries.FLAT_ID, LitePosQueries.FLAT_RECEIPT, LitePosQueries.FLAT_TIME,
                LitePosQueries.FLAT_STATUS, LitePosQueries.FLAT_TOTAL, LitePosQueries.FLAT_TAX,
                LitePosQueries.FLAT_DISCOUNT, LitePosQueries.FLAT_NOTE, LitePosQueries.FLAT_STORE_ID,
                LitePosQueries.FLAT_TERMINAL_ID, LitePosQueries.FLAT_CASHIER_ID,
                LitePosQueries.FLAT_ITEM_COUNT
        ));
    }

    /** RB.5: List envelope with IndexResolvedValueRow. */
    public static List<FlatSaleProjection> executeRb5List(
            EntityManager em,
            ModelQuery<LitePosEntities.Sale, ?, FlatSaleProjection> q,
            Limit limit,
            RenderOptions options
    ) {
        var path = SpikeListPath.of(em, LitePosEntities.Sale.class, ModelQueryConfig.defaults());
        return path.listRb5(q, limit, flatResolver());
    }

    /** RB.6: List envelope with FlatRowMapper. */
    public static List<FlatSaleProjection> executeRb6List(
            EntityManager em,
            ModelQuery<LitePosEntities.Sale, ?, FlatSaleProjection> q,
            Limit limit,
            RenderOptions options
    ) {
        var path = SpikeListPath.of(em, LitePosEntities.Sale.class, ModelQueryConfig.defaults());
        return path.listRb6(q, limit, flatResolver());
    }

    /** RB.7: Stream with custom stream consumer. */
    public static long executeRb7Stream(
            EntityManager em,
            ModelQuery<LitePosEntities.Sale, ?, FlatSaleProjection> q,
            ModelQueryConfig config,
            Function<java.util.stream.Stream<FlatSaleProjection>, Long> streamConsumer
    ) {
        var tx = em.getTransaction();
        boolean started = false;
        if (!tx.isActive()) {
            tx.begin();
            started = true;
        }
        try {
            var executor = ModelQueryExecutor.create(em, LitePosEntities.Sale.class, config);
            return executor.stream(q, Limit.unlimited(), streamConsumer);
        } finally {
            if (started && tx.isActive()) {
                tx.rollback();
            }
        }
    }

    /** RB.7: Stream. */
    public static long executeRb7Stream(
            EntityManager em,
            ModelQuery<LitePosEntities.Sale, ?, FlatSaleProjection> q,
            ModelQueryConfig config
    ) {
        return executeRb7Stream(em, q, config, s -> s.limit(5).count());
    }

    /** RB.7: Export. */
    public static void executeRb7Export(
            EntityManager em,
            ModelQuery<LitePosEntities.Sale, ?, FlatSaleProjection> q,
            ModelQueryConfig config,
            Consumer<FlatSaleProjection> sink
    ) {
        var executor = ModelQueryExecutor.create(em, LitePosEntities.Sale.class, config);
        executor.export(q, ExportOptions.defaults(), Function.identity(), sink);
    }
}
