package com.rey.modelquery.spike.rfc0019;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.SpikeListPath;
import com.rey.modelquery.spike.rfc0019.LitePosModels.GroupedCountModel;
import com.rey.modelquery.spike.rfc0019.LitePosModels.SaleSummaryModel;
import com.rey.modelquery.spike.rfc0019.LitePosModels.TodaySaleModel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.criteria.CriteriaQuery;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spike copy of the RA.5 provider route: renders SQM once to HQL with named parameters,
 * cached per (factory, renderOptions, query, variant) (M3), executed via
 * {@code em.createQuery(hql)} within the executor envelope.
 */
public final class HqlProviderRoute {

    public record HqlCacheKey(
            int maxInListSize,
            int maxBindParameters,
            boolean inlinesValues,
            ModelQuery<?, ?, ?> query,
            String variant
    ) {}

    public record HqlEntry<M>(String hql, BuiltQuery<M> builtQuery) {}

    public static final class ScopeCache {
        final Map<HqlCacheKey, HqlEntry<?>> entries = new ConcurrentHashMap<>();

        public void clear() {
            entries.clear();
        }

        @SuppressWarnings("unchecked")
        public <E, M> HqlEntry<M> getOrBuild(
                EntityManager em,
                ModelQuery<E, ?, M> query,
                String variant,
                RenderOptions options
        ) {
            HqlCacheKey key = new HqlCacheKey(
                    options.maxInListSize(),
                    options.maxBindParameters(),
                    options.inlinesValues(),
                    query,
                    variant
            );
            HqlEntry<?> existing = entries.get(key);
            if (existing != null) {
                return (HqlEntry<M>) existing;
            }

            sweepClosed();

            return (HqlEntry<M>) entries.computeIfAbsent(key, k -> buildHqlEntry(em, query, variant, options));
        }
    }

    private static final Map<EntityManagerFactory, ScopeCache> SCOPES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private HqlProviderRoute() {}

    public static ScopeCache scopeFor(EntityManagerFactory emf) {
        synchronized (SCOPES) {
            return SCOPES.computeIfAbsent(emf, f -> new ScopeCache());
        }
    }

    public static void clearCache() {
        synchronized (SCOPES) {
            SCOPES.clear();
        }
        SpikeListPath.clearContexts();
    }

    public static void sweepClosed() {
        synchronized (SCOPES) {
            Iterator<Map.Entry<EntityManagerFactory, ScopeCache>> it =
                    SCOPES.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<EntityManagerFactory, ScopeCache> e = it.next();
                if (e.getKey() == null || !e.getKey().isOpen()) {
                    it.remove();
                }
            }
        }
    }

    public static String renderSqmToHql(CriteriaQuery<?> cq) {
        StringBuilder sb = new StringBuilder();
        try {
            try {
                Method m = cq.getClass().getMethod("appendHqlString", StringBuilder.class);
                m.invoke(cq, sb);
            } catch (NoSuchMethodException e) {
                Class<?> ctxClass = Class.forName("org.hibernate.query.sqm.internal.SimpleSqmRenderContext");
                Object ctx = ctxClass.getConstructor().newInstance();
                Method m = cq.getClass().getMethod(
                        "appendHqlString", StringBuilder.class,
                        Class.forName("org.hibernate.query.sqm.tree.SqmRenderContext"));
                m.invoke(cq, sb, ctx);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to render HQL from SQM", ex);
        }
        return sb.toString();
    }

    public static String normalizeHql(String raw) {
        raw = raw.replace("count()", "count(*)");
        if (!raw.contains(" as c0") && raw.toLowerCase().startsWith("select ")) {
            int fromIdx = raw.toLowerCase().indexOf(" from ");
            if (fromIdx > 0) {
                String selectPart = raw.substring(7, fromIdx);
                String rest = raw.substring(fromIdx);
                String[] items = selectPart.split(",");
                StringBuilder sb = new StringBuilder("select ");
                for (int i = 0; i < items.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(items[i].trim()).append(" as c").append(i);
                }
                sb.append(rest);
                return sb.toString();
            }
        }
        return raw;
    }

    private static String replaceTodayParameters(String hql) {
        if (hql.contains(":__param_") || hql.contains(":null")) {
            return hql.replaceFirst(":(__param_\\d+|null)", ":p0")
                    .replaceFirst(":(__param_\\d+|null)", ":p1")
                    .replaceFirst(":(__param_\\d+|null)", ":p2");
        }
        return hql.replaceFirst("'?" + LitePosDataset.TODAY_START + "'?", ":p0")
                .replaceFirst("'?" + LitePosDataset.TODAY_END + "'?", ":p1")
                .replaceAll("=\\s*1\\b", "= :p2");
    }

    private static String extractRootAlias(String hql) {
        int fromIdx = hql.toLowerCase().indexOf(" from ");
        if (fromIdx < 0) {
            return "s";
        }
        String fromPart = hql.substring(fromIdx + 6).trim();
        String[] tokens = fromPart.split("\\s+");
        return tokens.length >= 2 ? tokens[1] : "s";
    }

    public static <E, M> HqlEntry<M> getOrBuild(
            EntityManager em,
            ModelQuery<E, ?, M> query,
            String variant,
            RenderOptions options
    ) {
        return scopeFor(em.getEntityManagerFactory()).getOrBuild(em, query, variant, options);
    }

    private static <E, M> HqlEntry<M> buildHqlEntry(
            EntityManager em,
            ModelQuery<E, ?, M> query,
            String variant,
            RenderOptions options
    ) {
        BuiltQuery<M> built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options);
        String hql;
        switch (variant) {
            case "TODAY", "ORDERED" -> {
                String raw = renderSqmToHql(built.query());
                hql = replaceTodayParameters(normalizeHql(raw));
            }
            case "ONE" -> {
                String baseHql = normalizeHql(renderSqmToHql(built.query()));
                hql = baseHql + " where " + extractRootAlias(baseHql) + ".id = :id";
            }
            case "PAGE" -> {
                hql = normalizeHql(renderSqmToHql(built.query()));
            }
            case "COUNT" -> {
                String entityName = query.rootEntity().getName();
                hql = "select count(s) from " + entityName + " s";
            }
            case "GROUPED" -> {
                hql = normalizeHql(renderSqmToHql(built.query()));
            }
            default -> throw new IllegalArgumentException("Unknown variant: " + variant);
        }
        return new HqlEntry<>(hql, built);
    }

    public static List<TodaySaleModel> listToday(
            EntityManager em,
            Instant start,
            Instant end,
            Long storeId,
            RenderOptions options
    ) {
        var path = SpikeListPath.of(em, LitePosEntities.Sale.class, ModelQueryConfig.defaults());
        return path.listRa5Today(start, end, storeId, options);
    }

    public static List<TodaySaleModel> listTodayOrdered(
            EntityManager em,
            Instant start,
            Instant end,
            Long storeId,
            RenderOptions options
    ) {
        var path = SpikeListPath.of(em, LitePosEntities.Sale.class, ModelQueryConfig.defaults());
        return path.listRa5TodayOrdered(start, end, storeId, options);
    }

    public static SaleSummaryModel one(EntityManager em, Long id, RenderOptions options) {
        var path = SpikeListPath.of(em, LitePosEntities.Sale.class, ModelQueryConfig.defaults());
        return path.oneRa5(LitePosQueries.ONE_QUERY, id, options).orElse(null);
    }

    public static List<SaleSummaryModel> page(
            EntityManager em,
            int pageNumber,
            int pageSize,
            RenderOptions options
    ) {
        var path = SpikeListPath.of(em, LitePosEntities.Sale.class, ModelQueryConfig.defaults());
        Slice<SaleSummaryModel> slice = path.pageRa5(
                LitePosQueries.PAGE_QUERY, PageSpec.of(pageNumber, pageSize), CountMode.COUNT, options);
        return slice.content();
    }

    public static List<SaleSummaryModel> pageOffset(
            EntityManager em,
            int offset,
            int maxResults,
            RenderOptions options
    ) {
        var path = SpikeListPath.of(em, LitePosEntities.Sale.class, ModelQueryConfig.defaults());
        Slice<SaleSummaryModel> slice = path.pageRa5(
                LitePosQueries.PAGE_QUERY, PageSpec.ofOffset(offset, maxResults), CountMode.COUNT, options);
        return slice.content();
    }

    public static List<GroupedCountModel> grouped(EntityManager em, RenderOptions options) {
        var path = SpikeListPath.of(em, LitePosEntities.Sale.class, ModelQueryConfig.defaults());
        return path.groupedRa5(LitePosQueries.GROUPED_QUERY, options);
    }
}
