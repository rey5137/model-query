package com.rey.modelquery.spike.rfc0019;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.SortSpec;
import com.rey.modelquery.jpa.SpikeListPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * Spike copy of the P-A.3 sealed build cache: bounded access-ordered LRU (default 256),
 * scoped per factory in a weak map, checking {@code copiesOnCreate} via
 * {@link SharedSessionContractImplementor#isCriteriaCopyTreeEnabled} per call.
 */
public final class SealedBuildCache {

    private static final int DEFAULT_CACHE_SIZE = 256;

    public static final class ScopeCache {
        final Map<Key, CacheEntry<?>> entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, CacheEntry<?>> eldest) {
                return size() > DEFAULT_CACHE_SIZE;
            }
        };
        long hits;
        long misses;

        public long hits() {
            synchronized (entries) {
                return hits;
            }
        }

        public long misses() {
            synchronized (entries) {
                return misses;
            }
        }

        public void reset() {
            synchronized (entries) {
                entries.clear();
                hits = 0;
                misses = 0;
            }
        }

        @SuppressWarnings("unchecked")
        public <E, M> CacheEntry<M> getOrBuild(
                EntityManager em,
                ModelQuery<E, ?, M> query,
                String variant,
                RenderOptions options
        ) {
            return getOrBuild(em, query, variant, null, null, options);
        }

        @SuppressWarnings("unchecked")
        public <E, M> CacheEntry<M> getOrBuild(
                EntityManager em,
                ModelQuery<E, ?, M> query,
                String variant,
                SortSpec sortSpec,
                CountMode countMode,
                RenderOptions options
        ) {
            if (!copiesOnCreate(em)) {
                // Unsafe to reuse CriteriaQuery without copy_tree (P-A.3)
                return null;
            }

            Key key = new Key(query, variant, sortSpec, countMode, options);
            synchronized (entries) {
                CacheEntry<?> existing = entries.get(key);
                if (existing != null) {
                    if (recordStats) {
                        hits++;
                    }
                    return (CacheEntry<M>) existing;
                }
                if (recordStats) {
                    misses++;
                }
            }

            // NM1: sweep on miss only
            sweepClosed();

            BuiltQuery<M> built;
            List<ParameterExpression<?>> keyParams = List.of();
            if ("ONE_KEY".equals(variant)) {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                built = query.buildQuery(cb, Phase.MODEL, options);
                built.query().orderBy(List.<Order>of());
                var pkOpt = query.primaryKey();
                if (pkOpt.isPresent()) {
                    var pk = pkOpt.get();
                    List<ParameterExpression<?>> params = new ArrayList<>();
                    Predicate[] preds = new Predicate[pk.columns().size()];
                    for (int i = 0; i < pk.columns().size(); i++) {
                        var col = pk.columns().get(i);
                        var p = cb.parameter(col.attributeType(), "oneKey_" + i);
                        params.add(p);
                        preds[i] = cb.equal(col.path(built.joins()), p);
                    }
                    Predicate byKey = preds.length == 1 ? preds[0] : cb.and(preds);
                    Predicate own = built.query().getRestriction();
                    built.query().where((Predicate) (own == null ? byKey : cb.and(own, byKey)));
                    keyParams = params;
                }
            } else if ("COUNT".equals(variant)) {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                built = query.buildQuery(cb, Phase.MODEL, options);
                built.query().orderBy(List.<Order>of());
                From<?, ?> root = built.query().getRoots().iterator().next();
                built.query().select(cb.tuple(cb.count(root)));
            } else {
                built = query.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, options);
            }
            CacheEntry<M> entry = new CacheEntry<>(built.query(), built, keyParams);
            synchronized (entries) {
                entries.put(key, entry);
                return entry;
            }
        }
    }

    private static final Map<EntityManagerFactory, ScopeCache> SCOPES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean recordStats = true;

    public static void setRecordStats(boolean record) {
        recordStats = record;
    }

    public static long hits() {
        synchronized (SCOPES) {
            long total = 0;
            for (ScopeCache s : SCOPES.values()) {
                synchronized (s.entries) {
                    total += s.hits;
                }
            }
            return total;
        }
    }

    public static long misses() {
        synchronized (SCOPES) {
            long total = 0;
            for (ScopeCache s : SCOPES.values()) {
                synchronized (s.entries) {
                    total += s.misses;
                }
            }
            return total;
        }
    }

    public static void resetStats() {
        synchronized (SCOPES) {
            for (ScopeCache s : SCOPES.values()) {
                synchronized (s.entries) {
                    s.hits = 0;
                    s.misses = 0;
                }
            }
        }
    }

    public static void clear() {
        synchronized (SCOPES) {
            SCOPES.clear();
        }
        SpikeListPath.clearContexts();
        resetStats();
    }

    public record Key(
            ModelQuery<?, ?, ?> queryDefinition,
            String callVariant,
            SortSpec sortSpec,
            CountMode countMode,
            RenderOptions renderOptions
    ) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key o
                    && queryDefinition == o.queryDefinition
                    && Objects.equals(callVariant, o.callVariant)
                    && Objects.equals(sortSpec, o.sortSpec)
                    && countMode == o.countMode
                    && optionsEqual(renderOptions, o.renderOptions);
        }

        private static boolean optionsEqual(RenderOptions a, RenderOptions b) {
            if (a == b) return true;
            if (a == null || b == null) return false;
            return a.maxInListSize() == b.maxInListSize()
                    && a.maxBindParameters() == b.maxBindParameters()
                    && a.inlinesValues() == b.inlinesValues()
                    && Objects.equals(a.constantRenderer(), b.constantRenderer())
                    && Objects.equals(a.treatRenderer(), b.treatRenderer())
                    && Objects.equals(a.operandBinder(), b.operandBinder());
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    System.identityHashCode(queryDefinition),
                    callVariant,
                    sortSpec,
                    countMode,
                    renderOptions == null ? 0 : renderOptions.maxInListSize(),
                    renderOptions == null ? 0 : renderOptions.maxBindParameters(),
                    renderOptions != null && renderOptions.inlinesValues()
            );
        }
    }

    public record CacheEntry<M>(
            CriteriaQuery<?> criteriaQuery,
            BuiltQuery<M> builtQuery,
            List<ParameterExpression<?>> keyParameters
    ) {
        public CacheEntry(CriteriaQuery<?> cq, BuiltQuery<M> bq) {
            this(cq, bq, List.of());
        }
    }

    private SealedBuildCache() {}

    public static boolean copiesOnCreate(EntityManager em) {
        try {
            SharedSessionContractImplementor session =
                    em.unwrap(SharedSessionContractImplementor.class);
            return session.isCriteriaCopyTreeEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public static void sweepClosed() {
        synchronized (SCOPES) {
            Iterator<Map.Entry<EntityManagerFactory, ScopeCache>> it = SCOPES.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<EntityManagerFactory, ScopeCache> e = it.next();
                if (e.getKey() == null || !e.getKey().isOpen()) {
                    it.remove();
                }
            }
        }
        SpikeListPath.sweepClosed();
    }

    public static ScopeCache scopeFor(EntityManagerFactory emf) {
        synchronized (SCOPES) {
            ScopeCache scope = SCOPES.get(emf);
            if (scope == null) {
                scope = new ScopeCache();
                SCOPES.put(emf, scope);
            }
            return scope;
        }
    }

    public static <E, M> CacheEntry<M> getOrBuild(
            EntityManager em,
            ModelQuery<E, ?, M> query,
            String variant,
            RenderOptions options
    ) {
        return scopeFor(em.getEntityManagerFactory()).getOrBuild(em, query, variant, options);
    }

    public static <E, M> CacheEntry<M> getOrBuild(
            EntityManager em,
            ModelQuery<E, ?, M> query,
            String variant,
            SortSpec sortSpec,
            CountMode countMode,
            RenderOptions options
    ) {
        return scopeFor(em.getEntityManagerFactory()).getOrBuild(em, query, variant, sortSpec, countMode, options);
    }

    public static int scopeCount() {
        sweepClosed();
        synchronized (SCOPES) {
            return SCOPES.size();
        }
    }
}
