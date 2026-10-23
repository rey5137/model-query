package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.IndexResolvedValueRow;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.TreatChecks;
import com.rey.modelquery.jpa.spi.LockSupport;
import com.rey.modelquery.jpa.vendor.ResolvedVendor;
import com.rey.modelquery.spike.rfc0019.FlatRowMapper;
import com.rey.modelquery.spike.rfc0019.HqlProviderRoute;
import com.rey.modelquery.spike.rfc0019.LitePosDataset;
import com.rey.modelquery.spike.rfc0019.LitePosEntities;
import com.rey.modelquery.spike.rfc0019.LitePosQueries;
import com.rey.modelquery.spike.rfc0019.LitePosModels.TodaySaleModel;
import com.rey.modelquery.spike.rfc0019.SealedBuildCache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Parameter;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.PluralAttribute;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-scope split-package class reproducing {@link DefaultModelQueryExecutor#list}'s path
 * with exactly one seam swapped per route (Items 5, 6, N4).
 */
public final class SpikeListPath<E> {

    private static final Set<ModelQuery<?, ?, ?>> FIRST_RUN_CHECKED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    // ---- Reflection moved to setup (N4 note) ----
    private static final Field RO_FIELD;
    private static final Field VENDOR_FIELD;
    private static final Field TIMEOUT_FIELD;
    private static final Method LOCK_CHECKS_METHOD;
    private static final Class<?> LOADED_CLASS;
    private static final Constructor<?> LOADED_CTOR;
    private static final Method MODELS_METHOD;

    static {
        try {
            RO_FIELD = DefaultModelQueryExecutor.class.getDeclaredField("renderOptions");
            RO_FIELD.setAccessible(true);

            VENDOR_FIELD = DefaultModelQueryExecutor.class.getDeclaredField("vendor");
            VENDOR_FIELD.setAccessible(true);

            TIMEOUT_FIELD = DefaultModelQueryExecutor.class.getDeclaredField("queryTimeout");
            TIMEOUT_FIELD.setAccessible(true);

            LOCK_CHECKS_METHOD = DefaultModelQueryExecutor.class.getDeclaredMethod(
                    "lockChecks", ModelQuery.class, BuiltQuery.class, String.class);
            LOCK_CHECKS_METHOD.setAccessible(true);

            LOADED_CLASS = Class.forName("com.rey.modelquery.jpa.DefaultModelQueryExecutor$Loaded");
            LOADED_CTOR = LOADED_CLASS.getDeclaredConstructor(Object.class, Row.class, boolean.class);
            LOADED_CTOR.setAccessible(true);

            MODELS_METHOD = DefaultModelQueryExecutor.class.getDeclaredMethod("models", ModelQuery.class, List.class);
            MODELS_METHOD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to initialize reflective handles for DefaultModelQueryExecutor", e);
        }
    }

    public record FactoryContext(
            RenderOptions renderOptions,
            ResolvedVendor vendor,
            Optional<Duration> queryTimeout,
            SealedBuildCache.ScopeCache buildScope,
            HqlProviderRoute.ScopeCache hqlScope
    ) {}

    private static final Map<EntityManagerFactory, FactoryContext> CONTEXTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void sweepClosed() {
        synchronized (CONTEXTS) {
            Iterator<Map.Entry<EntityManagerFactory, FactoryContext>> it = CONTEXTS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<EntityManagerFactory, FactoryContext> e = it.next();
                if (e.getKey() == null || !e.getKey().isOpen()) {
                    it.remove();
                }
            }
        }
    }

    public static void clearContexts() {
        synchronized (CONTEXTS) {
            CONTEXTS.clear();
        }
    }

    private final EntityManager em;
    private final DefaultModelQueryExecutor<E> executor;
    private final FactoryContext ctx;

    private record Loaded<M>(M model, Row row, boolean filterOnlyToManyJoin) {}

    private SpikeListPath(EntityManager em, DefaultModelQueryExecutor<E> executor, FactoryContext ctx) {
        this.em = em;
        this.executor = executor;
        this.ctx = ctx;
    }

    public static <E> FactoryContext contextOf(EntityManager em, Class<E> rootEntity, ModelQueryConfig config) {
        EntityManagerFactory emf = em.getEntityManagerFactory();
        return CONTEXTS.computeIfAbsent(emf, f -> {
            try {
                var exec = (DefaultModelQueryExecutor<E>) ModelQueryExecutor.create(em, rootEntity, config);
                RenderOptions ro = (RenderOptions) RO_FIELD.get(exec);
                ResolvedVendor v = (ResolvedVendor) VENDOR_FIELD.get(exec);
                Optional<Duration> timeout = (Optional<Duration>) TIMEOUT_FIELD.get(exec);
                return new FactoryContext(
                        ro,
                        v,
                        timeout,
                        SealedBuildCache.scopeFor(f),
                        HqlProviderRoute.scopeFor(f)
                );
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to inspect DefaultModelQueryExecutor", e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    public static <E> SpikeListPath<E> of(EntityManager em, Class<E> rootEntity, ModelQueryConfig config) {
        FactoryContext context = contextOf(em, rootEntity, config);
        var exec = (DefaultModelQueryExecutor<E>) ModelQueryExecutor.create(em, rootEntity, config);
        return new SpikeListPath<>(em, exec, context);
    }

    public SpikeListPath<E> withEntityManager(EntityManager newEm) {
        return new SpikeListPath<>(newEm, executor, ctx);
    }

    public FactoryContext context() {
        return ctx;
    }

    public SealedBuildCache.ScopeCache buildScope() {
        return ctx.buildScope();
    }

    public HqlProviderRoute.ScopeCache hqlScope() {
        return ctx.hqlScope();
    }

    public RenderOptions renderOptions() {
        return ctx.renderOptions();
    }

    // ---- Copied helper methods from DefaultModelQueryExecutor (Items 5, 6) ----

    private void checkFirstRun(ModelQuery<?, ?, ?> q) {
        ModelQuery<?, ?, ?> definition = q.definition();
        if (!FIRST_RUN_CHECKED.contains(definition)) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            q.checkPhases(cb, ctx.renderOptions());
            q.checkFetch(cb, ctx.renderOptions());
            FIRST_RUN_CHECKED.add(definition);
        }
    }

    private void withinBindLimit(Object label, int binds, int cursorBinds) {
        int max = ctx.renderOptions().maxBindParameters();
        if (binds > max) {
            throw new ModelQueryDefinitionException(MqCode.MQ1307, label + ": a statement binds " + binds
                    + " values, more than the " + max + " bind parameters one statement takes");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bindValues(Query query, JoinContext joins, Set<ParameterExpression<?>> kept) {
        if (joins != null) {
            joins.statementParameters().forEach((parameter, value) -> {
                if (kept == null || kept.contains(parameter)) {
                    query.setParameter((Parameter) parameter, value);
                }
            });
        }
    }

    private <T> TypedQuery<T> checked(
            Object label,
            TypedQuery<T> typed,
            Keyset<?> keyset,
            Object[] cursor,
            JoinContext joins
    ) {
        int binds = typed.getParameters().size() + (joins == null ? 0 : joins.repeatedExpressionBinds());
        withinBindLimit(label, binds, 0);
        ctx.queryTimeout().ifPresent(timeout -> ctx.vendor().profile().applyTimeout(typed, timeout));
        return typed;
    }

    private TypedQuery<Tuple> create(Object label, BuiltQuery<?> built) {
        TypedQuery<Tuple> typed = em.createQuery(built.query());
        bindValues(typed, built.joins(), built.query().getParameters());
        return checked(label, typed, null, null, built.joins());
    }

    private TypedQuery<Tuple> limited(Object label, BuiltQuery<?> built, Limit limit) {
        TypedQuery<Tuple> query = create(label, built);
        limit.maxRows().ifPresent(query::setMaxResults);
        return query;
    }

    private List<Tuple> rows(Object label, BuiltQuery<?> built, TypedQuery<Tuple> query) {
        try {
            return query.getResultList();
        } catch (PersistenceException e) {
            throw DefaultModelQueryExecutor.timeoutCancellation(e, ctx.queryTimeout().isPresent());
        }
    }

    // ---- filterOnlyToManyJoin (item 5) ----

    private static Optional<Join<?, ?>> filterOnlyToManyJoin(BuiltQuery<?> built) {
        Set<Join<?, ?>> selectedThrough = Collections.newSetFromMap(new IdentityHashMap<>());
        selectedThrough.addAll(selectedToManyJoins(built));
        return toManyJoinOutside(built.query().getRoots().iterator().next(), selectedThrough);
    }

    private static List<Join<?, ?>> selectedToManyJoins(BuiltQuery<?> built) {
        List<Join<?, ?>> joins = new ArrayList<>();
        for (SelectField<?, ?> field : built.selection().fields()) {
            for (ColumnField<?, ?, ?> column : selectedColumns(field)) {
                Join<?, ?> join = toManyJoin(column, built.joins());
                if (join != null) {
                    joins.add(join);
                }
            }
        }
        return joins;
    }

    @SuppressWarnings("unchecked")
    private static List<ColumnField<?, ?, ?>> selectedColumns(SelectField<?, ?> field) {
        if (field instanceof ColumnField<?, ?, ?> column) {
            return List.of(column);
        }
        if (field instanceof ExpressionField<?, ?> expression) {
            return (List<ColumnField<?, ?, ?>>) (List<?>) expression.columns();
        }
        return List.of();
    }

    private static Join<?, ?> toManyJoin(ColumnField<?, ?, ?> column, JoinContext joins) {
        for (Path<?> path = column.path(joins); path != null; path = path.getParentPath()) {
            if (path instanceof Join<?, ?> join && join.getAttribute() instanceof PluralAttribute<?, ?, ?>) {
                return join;
            }
        }
        return null;
    }

    private static Optional<Join<?, ?>> toManyJoinOutside(From<?, ?> from, Set<Join<?, ?>> selectedThrough) {
        for (Join<?, ?> join : from.getJoins()) {
            if (join.getAttribute() instanceof PluralAttribute<?, ?, ?> && !selectedThrough.contains(join)) {
                return Optional.of(join);
            }
            Optional<Join<?, ?>> nested = toManyJoinOutside(join, selectedThrough);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private static <M> Loaded<M> loaded(BuiltQuery<M> built, Tuple tuple) {
        Row row = built.selection().row(tuple);
        return new Loaded<>(built.map(row), row, filterOnlyToManyJoin(built).isPresent());
    }

    private <M> List<Loaded<M>> mapAll(Object label, BuiltQuery<M> built, TypedQuery<Tuple> query) {
        List<Tuple> tuples = rows(label, built, query);
        var models = new ArrayList<Loaded<M>>(tuples.size());
        for (Tuple tuple : tuples) {
            models.add(loaded(built, tuple));
        }
        return models;
    }

    // ---- models delegates to DefaultModelQueryExecutor.models (Items 5, 6) ----

    @SuppressWarnings("unchecked")
    private <M> List<M> models(ModelQuery<?, ?, M> q, List<Loaded<M>> rows) {
        try {
            List<Object> execLoadedList = new ArrayList<>(rows.size());
            for (Loaded<M> l : rows) {
                execLoadedList.add(LOADED_CTOR.newInstance(l.model(), l.row(), l.filterOnlyToManyJoin()));
            }
            return (List<M>) MODELS_METHOD.invoke(executor, q, execLoadedList);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke DefaultModelQueryExecutor.models", e);
        }
    }

    // ---- Lock & Preflight steps (Items 5, 6) ----

    @SuppressWarnings("unchecked")
    private <M> Optional<LockSupport> lockChecks(ModelQuery<?, ?, M> q, BuiltQuery<M> built, String read) {
        try {
            return (Optional<LockSupport>) LOCK_CHECKS_METHOD.invoke(executor, q, built, read);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke lockChecks", e);
        }
    }

    private boolean preflights(ModelQuery<?, ?, ?> q) {
        return ctx.renderOptions().treatRenderer().isPresent() && TreatChecks.reachesTreat(q);
    }

    private void preflight(ModelQuery<?, ?, ?> q, String statement, StatementShape shape, BuiltQuery<?> built) {
        StatementShape actual = q.isGrouped() ? StatementShape.GROUPED : shape;
        ctx.renderOptions().treatRenderer().orElseThrow()
                .preflight(em, built.query(), actual, built.expectedNarrowings())
                .ifPresent(reason -> {
                    throw new ModelQueryConfigurationException(MqCode.MQ4016, q + ": the preflight refuses the "
                            + statement + " (" + actual + " statement): " + reason);
                });
    }

    private static void applyLock(Optional<LockSupport> support, ModelQuery<?, ?, ?> q, TypedQuery<?> statement) {
        support.ifPresent(locks -> locks.apply(statement, q.lock().get()));
    }

    // ---- Swapped Seams for Routes ----

    /** RA.1-equivalent seam-less list path. */
    public <M> List<M> listRa1(ModelQuery<?, ?, M> q, Limit limit) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        checkFirstRun(q);
        BuiltQuery<M> built = null;
        Optional<LockSupport> lock = Optional.empty();
        if (q.lock().isPresent()) {
            built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, ctx.renderOptions());
            lock = lockChecks(q, built, "list(query, limit)");
        }
        if (preflights(q)) {
            if (built == null) {
                built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, ctx.renderOptions());
            }
            preflight(q, "list", StatementShape.CONTENT, built);
        }
        if (limit.maxRows().orElse(1) == 0) return List.of();
        if (built == null) {
            built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, ctx.renderOptions());
        }
        TypedQuery<Tuple> query = limited(q, built, limit);
        applyLock(lock, q, query);
        return models(q, mapAll(q, built, query));
    }

    /** RA.3 / RA.4b: BuiltQuery source swapped to SealedBuildCache lookup. */
    @SuppressWarnings("unchecked")
    public <M> List<M> listRa3(ModelQuery<?, ?, M> q, Limit limit) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        checkFirstRun(q);
        BuiltQuery<M> built = null;
        Optional<LockSupport> lock = Optional.empty();
        var entry = ctx.buildScope().getOrBuild(em, q, "LIST", null, null, ctx.renderOptions());
        if (entry != null) {
            built = (BuiltQuery<M>) entry.builtQuery();
        } else {
            built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, ctx.renderOptions());
        }
        if (q.lock().isPresent()) {
            lock = lockChecks(q, built, "list(query, limit)");
        }
        if (preflights(q)) {
            preflight(q, "list", StatementShape.CONTENT, built);
        }
        if (limit.maxRows().orElse(1) == 0) return List.of();
        TypedQuery<Tuple> query = limited(q, built, limit);
        applyLock(lock, q, query);
        return models(q, mapAll(q, built, query));
    }

    /** RA.3 on one query by key (B2: key as parameter, order cleared). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <M> Optional<M> oneRa3(ModelQuery<?, ?, M> q, Object key) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(key, "key");
        checkFirstRun(q);
        var entry = ctx.buildScope().getOrBuild(em, q, "ONE_KEY", null, null, ctx.renderOptions());
        BuiltQuery<M> built;
        List<ParameterExpression<?>> keyParams;
        if (entry != null) {
            built = (BuiltQuery<M>) entry.builtQuery();
            keyParams = entry.keyParameters();
        } else {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            built = q.buildQuery(cb, Phase.MODEL, ctx.renderOptions());
            built.query().orderBy(List.of());
            PrimaryKey pk = q.primaryKey().orElseThrow();
            keyParams = new ArrayList<>();
            Predicate[] preds = new Predicate[pk.columns().size()];
            for (int i = 0; i < pk.columns().size(); i++) {
                ColumnField col = (ColumnField) pk.columns().get(i);
                var p = cb.parameter(col.attributeType(), "oneKey_" + i);
                keyParams.add(p);
                preds[i] = cb.equal(col.path(built.joins()), p);
            }
            Predicate byKey = preds.length == 1 ? preds[0] : cb.and(preds);
            Predicate own = built.query().getRestriction();
            built.query().where((Predicate) (own == null ? byKey : cb.and(own, byKey)));
        }
        TypedQuery<Tuple> query = create(q, built);
        query.setMaxResults(2);

        PrimaryKey pk = q.primaryKey().orElseThrow();
        Object attrKey = pk.attributeKey(key);
        if (keyParams.size() == 1) {
            query.setParameter((Parameter) keyParams.get(0), attrKey);
        } else {
            List<?> list = (List<?>) attrKey;
            for (int i = 0; i < keyParams.size(); i++) {
                query.setParameter((Parameter) keyParams.get(i), list.get(i));
            }
        }

        List<Tuple> tuples = rows(q, built, query);
        if (tuples.isEmpty()) return Optional.empty();
        if (tuples.size() > 1) {
            throw new ModelQueryExecutionException(MqCode.MQ2003, q + ": oneRa3 found more than one row");
        }
        return Optional.of(built.map(built.selection().row(tuples.get(0))));
    }

    /** RA.3 on page query with COUNT (M2). */
    @SuppressWarnings("unchecked")
    public <M> Slice<M> pageRa3(ModelQuery<?, ?, M> q, PageSpec page, CountMode mode) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(mode, "mode");
        checkFirstRun(q);
        int offset = page.offset();
        int size = page.pageSize();

        long total = 0;
        if (mode == CountMode.COUNT || mode == CountMode.INLINE_COUNT) {
            var countEntry = ctx.buildScope().getOrBuild(em, q, "COUNT", null, null, ctx.renderOptions());
            BuiltQuery<M> countBuilt;
            if (countEntry != null) {
                countBuilt = (BuiltQuery<M>) countEntry.builtQuery();
            } else {
                CriteriaBuilder cb = em.getCriteriaBuilder();
                countBuilt = q.buildQuery(cb, Phase.MODEL, ctx.renderOptions());
                countBuilt.query().orderBy(List.of());
                From<?, ?> root = countBuilt.query().getRoots().iterator().next();
                countBuilt.query().select(cb.tuple(cb.count(root)));
            }
            TypedQuery<Tuple> countQuery = create(q, countBuilt);
            total = ((Number) countQuery.getSingleResult().get(0)).longValue();
        }

        var entry = ctx.buildScope().getOrBuild(em, q, "PAGE", null, null, ctx.renderOptions());
        BuiltQuery<M> built;
        if (entry != null) {
            built = (BuiltQuery<M>) entry.builtQuery();
        } else {
            built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, ctx.renderOptions());
        }
        TypedQuery<Tuple> query = create(q, built);
        query.setFirstResult(offset);
        query.setMaxResults(mode == CountMode.NO_COUNT && size != Integer.MAX_VALUE ? size + 1 : size);
        List<M> content = models(q, mapAll(q, built, query));

        if (mode == CountMode.COUNT || mode == CountMode.INLINE_COUNT) {
            return new Slice<>(content, page.pageNumber(), size, offset + (long) content.size() < total,
                    OptionalLong.of(total));
        } else {
            boolean hasNext = content.size() > size;
            List<M> paged = hasNext ? content.subList(0, size) : content;
            return new Slice<>(paged, page.pageNumber(), size, hasNext, OptionalLong.empty());
        }
    }

    /** RA.5 Today. */
    public List<TodaySaleModel> listRa5Today(
            Instant start,
            Instant end,
            Long storeId,
            RenderOptions options
    ) {
        var entry = ctx.hqlScope().getOrBuild(em, LitePosQueries.TODAY_QUERY, "TODAY", options);
        Map<String, Object> params = Map.of("p0", start, "p1", end, "p2", storeId);
        return listRa5(LitePosQueries.TODAY_QUERY, Limit.unlimited(), entry.hql(), entry.builtQuery(), params);
    }

    /** RA.5 Today Ordered. */
    public List<TodaySaleModel> listRa5TodayOrdered(
            Instant start,
            Instant end,
            Long storeId,
            RenderOptions options
    ) {
        var entry = ctx.hqlScope().getOrBuild(em, LitePosQueries.ORDERED_BY_QUERY, "ORDERED", options);
        Map<String, Object> params = Map.of("p0", start, "p1", end, "p2", storeId);
        return listRa5(LitePosQueries.ORDERED_BY_QUERY, Limit.unlimited(), entry.hql(), entry.builtQuery(), params);
    }

    /** RA.5 One by key (M2). */
    public <M> Optional<M> oneRa5(ModelQuery<?, ?, M> q, Object key, RenderOptions options) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(key, "key");
        checkFirstRun(q);
        var entry = ctx.hqlScope().getOrBuild(em, q, "ONE", options);
        TypedQuery<Tuple> typed = em.createQuery(entry.hql(), Tuple.class);
        typed.setParameter("id", key);
        typed.setMaxResults(2);
        List<Tuple> tuples = typed.getResultList();
        if (tuples.isEmpty()) return Optional.empty();
        return Optional.of(entry.builtQuery().map(entry.builtQuery().selection().row(tuples.get(0))));
    }

    /** RA.5 Page with COUNT (M2). */
    public <M> Slice<M> pageRa5(
            ModelQuery<?, ?, M> q,
            PageSpec page,
            CountMode mode,
            RenderOptions options
    ) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(mode, "mode");
        checkFirstRun(q);
        int offset = page.offset();
        int size = page.pageSize();

        long total = 0;
        if (mode == CountMode.COUNT || mode == CountMode.INLINE_COUNT) {
            var countEntry = ctx.hqlScope().getOrBuild(em, q, "COUNT", options);
            TypedQuery<Long> countQ = em.createQuery(countEntry.hql(), Long.class);
            total = countQ.getSingleResult();
        }

        var entry = ctx.hqlScope().getOrBuild(em, q, "PAGE", options);
        TypedQuery<Tuple> typed = em.createQuery(entry.hql(), Tuple.class);
        typed.setFirstResult(offset);
        typed.setMaxResults(mode == CountMode.NO_COUNT && size != Integer.MAX_VALUE ? size + 1 : size);
        List<M> content = models(q, mapAll(q, entry.builtQuery(), typed));

        if (mode == CountMode.COUNT || mode == CountMode.INLINE_COUNT) {
            return new Slice<>(content, page.pageNumber(), size, offset + (long) content.size() < total,
                    OptionalLong.of(total));
        } else {
            boolean hasNext = content.size() > size;
            List<M> paged = hasNext ? content.subList(0, size) : content;
            return new Slice<>(paged, page.pageNumber(), size, hasNext, OptionalLong.empty());
        }
    }

    /** RA.5 Grouped (M2). */
    public <M> List<M> groupedRa5(ModelQuery<?, ?, M> q, RenderOptions options) {
        Objects.requireNonNull(q, "q");
        checkFirstRun(q);
        var entry = ctx.hqlScope().getOrBuild(em, q, "GROUPED", options);
        TypedQuery<Tuple> typed = em.createQuery(entry.hql(), Tuple.class);
        return models(q, mapAll(q, entry.builtQuery(), typed));
    }

    /** RA.5 general list. */
    public <M> List<M> listRa5(
            ModelQuery<?, ?, M> q,
            Limit limit,
            String hql,
            BuiltQuery<M> templateBuilt,
            Map<String, Object> params
    ) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        checkFirstRun(q);
        if (limit.maxRows().orElse(1) == 0) return List.of();
        TypedQuery<Tuple> typed = em.createQuery(hql, Tuple.class);
        if (params != null) {
            params.forEach(typed::setParameter);
        }
        int binds = typed.getParameters().size();
        withinBindLimit(q, binds, 0);
        ctx.queryTimeout().ifPresent(timeout -> ctx.vendor().profile().applyTimeout(typed, timeout));
        limit.maxRows().ifPresent(typed::setMaxResults);
        return models(q, mapAll(q, templateBuilt, typed));
    }

    private static Tuple nativeTuple(Tuple t) {
        return new Tuple() {
            @Override
            public <X> X get(TupleElement<X> tupleElement) { return t.get(tupleElement); }
            @Override
            public <X> X get(String alias, Class<X> type) { return type.cast(get(alias)); }
            @Override
            public Object get(String alias) {
                if (alias != null && alias.length() > 1 && alias.charAt(0) == 'c') {
                    try {
                        int idx = Integer.parseInt(alias.substring(1));
                        Object val = t.get(idx);
                        if (val instanceof Timestamp ts) return ts.toInstant();
                        if (val instanceof java.time.OffsetDateTime odt) return odt.toInstant();
                        // Hibernate 6+ returns a native TIMESTAMP as LocalDateTime; the spike runs in UTC.
                        if (val instanceof java.time.LocalDateTime ldt) return ldt.toInstant(java.time.ZoneOffset.UTC);
                        if (val instanceof String s) {
                            for (LitePosEntities.SaleStatus st : LitePosEntities.SaleStatus.values()) {
                                if (st.name().equals(s)) return st;
                            }
                        }
                        return val;
                    } catch (NumberFormatException ignored) {}
                }
                return t.get(alias);
            }
            @Override
            public <X> X get(int i, Class<X> type) { return t.get(i, type); }
            @Override
            public Object get(int i) { return t.get(i); }
            @Override
            public Object[] toArray() { return t.toArray(); }
            @Override
            public List<TupleElement<?>> getElements() { return t.getElements(); }
        };
    }

    private static final Map<RenderOptions, BuiltQuery<TodaySaleModel>> PREBUILT_TODAY =
            new ConcurrentHashMap<>();

    public static BuiltQuery<TodaySaleModel> prebuiltToday(EntityManager em, RenderOptions options) {
        return PREBUILT_TODAY.computeIfAbsent(options, opts ->
                LitePosQueries.TODAY_QUERY.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, opts));
    }

    /** RA.6: Native SQL string mapped through row path with prebuilt query (Nm2). */
    public List<TodaySaleModel> listRa6Native(
            String sql,
            BuiltQuery<TodaySaleModel> built,
            Instant start,
            Instant end,
            Long storeId
    ) {
        Query q = em.createNativeQuery(sql, Tuple.class);
        q.setParameter(1, Timestamp.from(start));
        q.setParameter(2, Timestamp.from(end));
        q.setParameter(3, storeId);
        @SuppressWarnings("unchecked")
        List<Tuple> tuples = q.getResultList();
        List<Loaded<TodaySaleModel>> loaded = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            loaded.add(loaded(built, nativeTuple(t)));
        }
        return models(LitePosQueries.TODAY_QUERY, loaded);
    }

    /** RA.6: Native SQL string mapped through row path (Item 7, Nm2). */
    public List<TodaySaleModel> listRa6Native(
            String sql,
            Instant start,
            Instant end,
            Long storeId,
            RenderOptions options
    ) {
        return listRa6Native(sql, prebuiltToday(em, options), start, end, storeId);
    }

    /** RB.5: Row decoding seam swapped to IndexResolvedValueRow. */
    public <M> List<M> listRb5(
            ModelQuery<?, ?, M> q,
            Limit limit,
            IndexResolvedValueRow.IndexResolver resolver
    ) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        checkFirstRun(q);
        if (limit.maxRows().orElse(1) == 0) return List.of();
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, ctx.renderOptions());
        TypedQuery<Tuple> query = limited(q, built, limit);
        List<Tuple> tuples = rows(q, built, query);
        List<Loaded<M>> loadedList = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            IndexResolvedValueRow row = resolver.row(t);
            loadedList.add(new Loaded<>(built.map(row), row, filterOnlyToManyJoin(built).isPresent()));
        }
        return models(q, loadedList);
    }

    /** RB.6: Batch mapping seam swapped to FlatRowMapper. */
    public <M> List<M> listRb6(
            ModelQuery<?, ?, M> q,
            Limit limit,
            IndexResolvedValueRow.IndexResolver resolver
    ) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        checkFirstRun(q);
        if (limit.maxRows().orElse(1) == 0) return List.of();
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, ctx.renderOptions());
        TypedQuery<Tuple> query = limited(q, built, limit);
        List<Tuple> tuples = rows(q, built, query);
        return FlatRowMapper.mapAll(tuples, resolver, row -> built.map(row), limit);
    }
}
