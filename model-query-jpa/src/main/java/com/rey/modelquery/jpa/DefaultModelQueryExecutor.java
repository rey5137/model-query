package com.rey.modelquery.jpa;

import com.rey.modelquery.annotations.WindowFrame;
import com.rey.modelquery.annotations.WindowFunction;
import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.ChunkOptions;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.Enricher;
import com.rey.modelquery.core.EntityLoad;
import com.rey.modelquery.core.ExpectedNarrowing;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.ExpressionField;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.ForeignKeyLookup;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.JoinField;
import com.rey.modelquery.core.JoinPlan;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryConfigurationException;
import com.rey.modelquery.core.ModelQueryDefinitionException;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OperatorFeature;
import com.rey.modelquery.core.OperatorGate;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.ParameterSink;
import com.rey.modelquery.core.PersistenceContextMode;
import com.rey.modelquery.core.Phase;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.RecursiveCte;
import com.rey.modelquery.core.RecursiveSpec;
import com.rey.modelquery.core.RenderOptions;
import com.rey.modelquery.core.TreatChecks;
import com.rey.modelquery.core.StatementShape;
import com.rey.modelquery.core.Row;
import com.rey.modelquery.core.RowLock;
import com.rey.modelquery.core.ScalarField;
import com.rey.modelquery.core.SelectField;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.SetOperation;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.ValueOperands;
import com.rey.modelquery.core.ValuesInsert;
import com.rey.modelquery.core.SourceRenderer;
import com.rey.modelquery.core.SourceQuery;
import com.rey.modelquery.core.WindowSpec;
import com.rey.modelquery.jpa.spi.ConflictClause;
import com.rey.modelquery.jpa.spi.EntityWriteSupport;
import com.rey.modelquery.jpa.spi.InsertSupport;
import com.rey.modelquery.jpa.spi.InsertTarget;
import com.rey.modelquery.jpa.spi.LockSupport;
import com.rey.modelquery.jpa.spi.ProviderSupport;
import com.rey.modelquery.jpa.spi.ReturningShape;
import com.rey.modelquery.jpa.spi.ReturningSupport;
import com.rey.modelquery.jpa.spi.SourceFeature;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.spi.WriteStatement;
import com.rey.modelquery.jpa.vendor.ResolvedVendor;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.JoinTable;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Parameter;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Query;
import jakarta.persistence.QueryTimeoutException;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.IdentifiableType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import java.lang.reflect.AnnotatedElement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Spliterator;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The executor {@link ModelQueryExecutor#create} returns.
 *
 * @implSpec R-EXE-01, R-EXE-02, R-EXE-03, R-EXE-04, R-EXE-05, R-EXE-06, R-EXE-07, R-EXE-09, R-QRY-09, R-PAG-01,
 *     R-PAG-02, R-PAG-03, R-PAG-04, R-PAG-05, R-PAG-06, R-PAG-07, R-PAG-08, R-PAG-09, R-PAG-10, R-PAG-11, R-PAG-12,
 *     R-PAG-13, R-PAG-14, R-AGG-09, R-EXE-08, R-EXE-11, R-WRT-07, R-WRT-08, R-WRT-14, R-WRT-15, R-WRT-16, R-WRT-17,
 *     R-WRT-18, R-WRT-19, R-WRT-20, R-WRT-23, R-FCH-04, R-FCH-05, R-FCH-06, R-FCH-09, R-FCH-11, R-FCH-12, D-61,
 *     D-62, D-63, D-99, R-WRT-26, R-WRT-33, R-VND-14
 */
final class DefaultModelQueryExecutor<E> implements ModelQueryExecutor<E> {

    private static final System.Logger LOG = System.getLogger(DefaultModelQueryExecutor.class.getName());

    /** The prefix of the parameters an insert-select selects its write assignments' values as (R-WRT-49). */
    private static final String ASSIGNED = "mqAssigned";
    /**
     * The alias an {@code INLINE_COUNT} page selects its total as, after the model's columns and {@code mq_0id}, read
     * by alias only and outside D-146's {@code mq_<property>} space (R-EXE-15).
     */
    private static final String INLINE_TOTAL = "mq_0total";
    private static final System.Logger.Level DEBUG = System.Logger.Level.DEBUG;
    private static final System.Logger.Level TRACE = System.Logger.Level.TRACE;

    /** SQLState a provider reports for a statement its query timeout cancelled, PostgreSQL's {@code 57014}. */
    static final String CANCELLED_SQL_STATE = "57014";

    /**
     * The queries whose phases and fetch-plan columns were checked, by identity. Static, because the checks are once
     * per {@code ModelQuery} whichever executor runs it first (D-21); weak, so a query built per request does not stay
     * reachable.
     */
    private static final Set<ModelQuery<?, ?, ?>> FIRST_RUN_CHECKED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /**
     * The write definitions whose metamodel checks passed, per factory (D-61): a definition is checked once for each
     * factory it runs on, since another factory may map the root differently. Weak on both levels, so neither a
     * closed factory nor a definition built per request stays reachable.
     */
    private static final Map<EntityManagerFactory, Set<Object>> WRITES_CHECKED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Successful no-renderer scans, weak on both levels as {@link #WRITES_CHECKED} is. */
    private static final Map<EntityManagerFactory, Set<Object>> TREATS_CHECKED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The query definitions whose fetch plans' child statements were built and checked for a one-key round, per
     * factory and vendor profile, which together set the render options the builds depend on (D-138). Weak on every
     * level, as {@link #WRITES_CHECKED} is.
     */
    private static final Map<EntityManagerFactory, Map<VendorProfile, Set<Object>>> FETCH_BUILDS_CHECKED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The factories {@code stream} warned of setting no fetch size on, so each is warned of once (R-VND-12). */
    private static final Set<EntityManagerFactory> FETCH_SIZE_UNSET =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /** The calls, per query, that warned of an ignored {@code orderBy}, so each is warned of once (R-EXE-13). */
    private static final Map<ModelQuery<?, ?, ?>, Set<String>> ORDER_IGNORED =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The query definitions warned of a total contradicted by a treated offset page, once each (R-PAG-27). */
    private static final Set<ModelQuery<?, ?, ?>> TOTAL_CONTRADICTED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /** The mapped entity classes of each factory, read from its metamodel once; weak, so a closed factory is freed. */
    private static final Map<EntityManagerFactory, Set<Class<?>>> MAPPED_ENTITIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final EntityManager em;
    private final Class<E> rootEntity;
    /** What a child load's executor is created with (R-FCH-06). */
    private final ModelQueryConfig config;
    /** The profile and provider support of {@code em}'s factory, resolved once per factory (R-VND-02). */
    private final ResolvedVendor vendor;
    /** The profile's facts as every query build of this executor renders by (D-34). */
    private final RenderOptions renderOptions;
    /** The profile's IN-list and bind-parameter limits, as key-based reads and writes clamp to them (D-63). */
    private final Keys keyLimits;
    /** The configured most keys per step-2 statement, or empty for the whole page within the clamp (R-PAG-07). */
    private final OptionalInt primaryKeyFirstBatchSize;
    /** The configured timeout for every statement, or empty for none (R-EXE-11). */
    private final Optional<Duration> queryTimeout;
    /** What keyset paging does with a NULL key ordered with {@code DEFAULT} precedence (R-PAG-05). */
    private final KeysetNullKeys keysetNullKeys;
    /** The configured page size of an {@code export} whose options leave it open (R-QRY-15). */
    private final int exportPageSize;
    /** The configured fetch size {@code stream} hands the profile (R-QRY-15). */
    private final int streamFetchSize;
    /**
     * Where the provider is configured to sort the NULLs of a bare order in both directions, or empty for the
     * database's default (R-PAG-05, D-36).
     */
    private final Optional<NullPrecedence> providerNulls;
    /** What a bulk write does to the persistence context unless it sets its own mode (R-WRT-15, D-62). */
    private final PersistenceContextMode persistenceContextMode;
    /** The configured keys per chunk of a chunked write whose options leave the size open (R-WRT-17, D-62). */
    private final int bulkWriteChunkSize;
    /** The configured callback for {@code commitEachChunk()}, or {@code null} for none (R-WRT-19, D-62). */
    private final ChunkTransactions chunkTransactions;
    /**
     * Whether a conflict update's {@code where} may read two or more of its assigned columns where the vendor reads
     * the stored row (R-WRT-34, D-117).
     */
    private final boolean conflictUpdateWhereOnAssignedColumns;
    /** The root's insert target, which the provider reads reflectively: read on first use, then kept. */
    private volatile InsertTarget insertTarget;
    /**
     * Whether the provider renders a window over the page statement: its {@code inlineCounts} answer, asked once,
     * and only where it also serves {@code sources()} (R-EXE-15 fallback 1, R-VND-27).
     */
    private final boolean inlineCounts;
    /**
     * The queries whose {@code INLINE_COUNT} fallback this executor logged, by identity, so each is logged once per
     * executor: the reason can depend on the factory, and a {@code static final} query is shared between executors
     * (R-EXE-15, INV-9). Weak, so a query built per request does not stay reachable.
     */
    private final Set<ModelQuery<?, ?, ?>> inlineCountFallbacksLogged =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    DefaultModelQueryExecutor(EntityManager em, Class<E> rootEntity, ModelQueryConfig config) {
        this.em = Objects.requireNonNull(em, "em");
        this.rootEntity = Objects.requireNonNull(rootEntity, "rootEntity");
        this.config = Objects.requireNonNull(config, "config");
        EntityManagerFactory emf = em.getEntityManagerFactory();
        this.vendor = VendorResolver.withSupplied(emf, VendorResolver.resolve(emf, config.vendor(),
                config.mysqlStreamingMode()), config.vendorProfiles());
        if (config.targetVendors().isPresent()) {
            TargetVendors.check(config.targetVendors().get(), emf, vendor);
        }
        VendorProfile profile = vendor.profile();
        this.providerNulls = vendor.providerSupport()
                .flatMap(support -> support.defaultNullPrecedence(em.getEntityManagerFactory()));
        RenderOptions options = RenderOptions.of(profile.maxInListSize(), profile.maxBindParameters());
        options = vendor.providerSupport()
                .flatMap(ProviderSupport::nullPrecedence)
                .map(options::withNullPrecedenceRenderer)
                .orElse(options);
        options = vendor.providerSupport()
                .flatMap(support -> support.constants(emf))
                .map(options::withConstantRenderer)
                .orElse(options);
        options = !profile.sequences() ? options : vendor.providerSupport()
                .flatMap(support -> support.sequences(emf))
                .map(options::withSequenceRenderer)
                .orElse(options);
        options = vendor.providerSupport()
                .flatMap(ProviderSupport::subqueries)
                .map(options::withSubqueryRenderer)
                .orElse(options);
        options = vendor.providerSupport()
                .flatMap(support -> support.treats(emf))
                .map(options::withTreatRenderer)
                .orElse(options)
                .withMetamodel(emf.getMetamodel()); // ofType's subtypes (R-FLT-28)
        options = vendor.providerSupport()
                .flatMap(support -> support.operands(emf))
                .map(options::withOperandBinder)
                .orElse(options);
        // Asked once, as the binder is; without a ProviderSupport no Hibernate key is read here (R-VND-28, INV-7).
        options = options.withInlinesValues(vendor.providerSupport()
                .map(support -> support.inlinesValues(emf))
                .orElse(false));
        this.renderOptions = sources(options.withOperatorGate(operatorGate(emf)));
        this.keyLimits = new Keys(profile.maxInListSize(), profile.maxBindParameters());
        this.primaryKeyFirstBatchSize = config.primaryKeyFirstBatchSize();
        this.queryTimeout = config.queryTimeout();
        this.keysetNullKeys = config.keysetNullKeys();
        this.exportPageSize = config.exportPageSize();
        this.streamFetchSize = config.streamFetchSize();
        this.persistenceContextMode = config.persistenceContextMode();
        this.bulkWriteChunkSize = config.bulkWriteChunkSize();
        this.chunkTransactions = config.chunkTransactions().orElse(null);
        this.conflictUpdateWhereOnAssignedColumns = config.conflictUpdateWhereOnAssignedColumns();
        this.inlineCounts = vendor.providerSupport()
                .filter(support -> support.sources().isPresent())
                .map(support -> support.inlineCounts(emf))
                .orElse(false);
    }

    /**
     * The vendor's and the provider's answer for every {@link OperatorFeature}, asked once here so no render asks a
     * profile; with no {@code ProviderSupport} the provider serves none (R-VND-23, D-169).
     */
    private OperatorGate operatorGate(EntityManagerFactory emf) {
        Set<OperatorFeature> vendorFeatures = EnumSet.noneOf(OperatorFeature.class);
        Set<OperatorFeature> providerFeatures = EnumSet.noneOf(OperatorFeature.class);
        for (OperatorFeature feature : OperatorFeature.values()) {
            if (vendor.profile().operators(feature)) {
                vendorFeatures.add(feature);
            }
            if (vendor.providerSupport().map(support -> support.operators(emf, feature)).orElse(false)) {
                providerFeatures.add(feature);
            }
        }
        return OperatorGate.of(String.valueOf(vendor.detectedVendor()), vendorFeatures, providerFeatures);
    }

    /**
     * {@code options} with the factory's CTE and window support for a query rooted at a source, when its provider has
     * one: every statement built over a source then registers the source's own CTE on itself (R-SRC-12) and renders
     * its windows (R-SRC-04). A target whose vendor lacks a feature the source uses — {@link SourceFeature#CTE},
     * {@link SourceFeature#WINDOW}, recursion, a set operation, or {@link SourceFeature#RECURSIVE_COMPOSITE_KEY} for
     * a recursive source over a multi-column node key — gets a renderer that refuses the query with {@code MQ4015} as
     * it is built, and a provider with no
     * {@link SourceRenderer} leaves the options without one, which {@code core} refuses with {@code MQ4014}; both are
     * before any statement (R-SRC-11, D-143).
     */
    private RenderOptions sources(RenderOptions options) {
        Optional<SourceRenderer> support = vendor.providerSupport().flatMap(ProviderSupport::sources);
        if (support.isEmpty()) {
            return options;
        }
        SourceRenderer renderer = support.get();
        return options.withSourceRenderer(new SourceRenderer() {
            @Override
            public Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
                requireCte();
                return renderer.cte(outer, body);
            }

            @Override
            public CteHandle register(CriteriaQuery<?> top, CriteriaQuery<Tuple> body) {
                requireCte();
                return renderer.register(top, body);
            }

            @Override
            public RecursiveCte recursive(CriteriaQuery<?> outer, RecursiveSpec spec) {
                requireRecursive(spec);
                return renderer.recursive(outer, spec);
            }

            @Override
            public RecursiveHandle registerRecursive(CriteriaQuery<?> top, RecursiveSpec spec) {
                requireRecursive(spec);
                return renderer.registerRecursive(top, spec);
            }

            @Override
            public Root<Tuple> from(AbstractQuery<?> query, CteHandle inner) {
                return renderer.from(query, inner);
            }

            private void requireCte() {
                if (!vendor.profile().sources(SourceFeature.CTE)) {
                    throw new ModelQueryConfigurationException(MqCode.MQ4015, rootEntity.getSimpleName()
                            + ": this query reads a source, and " + vendor.detectedVendor()
                            + " has no CTE support (VendorProfile#sources(SourceFeature.CTE))");
                }
            }

            private void requireRecursive(RecursiveSpec spec) {
                if (!vendor.profile().sources(SourceFeature.RECURSIVE)) {
                    throw new ModelQueryConfigurationException(MqCode.MQ4015, rootEntity.getSimpleName()
                            + ": this source recurses, and " + vendor.detectedVendor()
                            + " has no recursive CTE support (VendorProfile#sources(SourceFeature.RECURSIVE))");
                }
                // A node key of more than one column has to reach the cycle clause whole, which H2's emulation rejects
                // (RFC 0014 §7); the fact is the vendor's, so it is answered before any statement (R-VND-21).
                if (spec.keyAliases().size() > 1
                        && !vendor.profile().sources(SourceFeature.RECURSIVE_COMPOSITE_KEY)) {
                    throw new ModelQueryConfigurationException(MqCode.MQ4015, rootEntity.getSimpleName()
                            + ": this source's node key has " + spec.keyAliases().size() + " columns, and "
                            + vendor.detectedVendor() + " cannot recurse over a composite key "
                            + "(VendorProfile#sources(SourceFeature.RECURSIVE_COMPOSITE_KEY)); key the source on one "
                            + "column or target another vendor");
                }
                // The recursion needs a level past maxDepth and the final, empty iteration, which MySQL counts against
                // cte_max_recursion_depth as well; the spike measured maxDepth 998 served and 999 refused there
                // (RFC 0013 §5).
                int needed = spec.maxDepth() + 2;
                int ceiling = vendor.profile().maxRecursionDepth();
                if (needed > ceiling) {
                    throw new ModelQueryConfigurationException(MqCode.MQ4015, rootEntity.getSimpleName()
                            + ": maxDepth(" + spec.maxDepth() + ") recurses through " + needed + " levels, and "
                            + vendor.detectedVendor() + " allows " + ceiling
                            + " (VendorProfile#maxRecursionDepth()); lower maxDepth or target another vendor");
                }
            }

            @Override
            public Expression<?> window(CriteriaBuilder cb, WindowSpec spec) {
                if (!vendor.profile().sources(SourceFeature.WINDOW)) {
                    throw new ModelQueryConfigurationException(MqCode.MQ4015, rootEntity.getSimpleName()
                            + ": this source uses a window field, and " + vendor.detectedVendor()
                            + " has no window support (VendorProfile#sources(SourceFeature.WINDOW))");
                }
                return renderer.window(cb, spec);
            }

            @Override
            public CriteriaQuery<Tuple> combine(CriteriaBuilder cb, SetOperation operation, CriteriaQuery<Tuple> left,
                    CriteriaQuery<Tuple> right) {
                SourceFeature feature = switch (operation) {
                    case UNION, UNION_ALL -> SourceFeature.UNION;
                    case INTERSECT -> SourceFeature.INTERSECT;
                    case EXCEPT -> SourceFeature.EXCEPT;
                };
                if (!vendor.profile().sources(feature)) {
                    throw new ModelQueryConfigurationException(MqCode.MQ4015, rootEntity.getSimpleName()
                            + ": this source combines branches with " + operation + ", and " + vendor.detectedVendor()
                            + " has no " + feature + " support (VendorProfile#sources(SourceFeature." + feature
                            + "))");
                }
                return renderer.combine(cb, operation, left, right);
            }
        });
    }

    @Override
    public <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        checkFirstRun(q);
        LOG.log(DEBUG, () -> "list " + q + ": " + limit);
        BuiltQuery<M> built = null;
        Optional<LockSupport> lock = Optional.empty();
        if (q.lock().isPresent()) {
            // The refusals run before the zero-limit short cut and before any statement (R-EXE-14).
            built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
            lock = lockChecks(q, built, "list(query, limit)");
        }
        if (preflights(q)) {
            // Before the zero-limit short cut too, so a refused statement fails the same way for any limit.
            if (built == null) {
                built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
            }
            preflight(q, "list", StatementShape.CONTENT, built);
        }
        if (zeroLimit(limit)) {
            return List.of(); // no statement runs for a zero limit (R-EXE-06)
        }
        if (built == null) {
            built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        }
        TypedQuery<Tuple> query = limited(q, built, limit);
        applyLock(lock, q, query);
        return models(q, mapAll(q, built, query));
    }

    // ---- single-row reads

    @Override
    public <M> Optional<M> one(ModelQuery<E, ?, M> q) {
        Objects.requireNonNull(q, "q");
        checkFirstRun(q);
        LOG.log(DEBUG, () -> "one " + q);
        q.lock().filter(lock -> lock.waitPolicy() == RowLock.Wait.SKIP_LOCKED).ifPresent(lock -> {
            throw new ModelQueryExecutionException(MqCode.MQ2211, q + ": one(query) cannot lock with SKIP_LOCKED, "
                    + "because it reads LIMIT 2 to check uniqueness and a locked second match would be invisible; "
                    + "use first(query) with SKIP_LOCKED to claim the next unlocked row");
        });
        warnOfIgnoredOrder(q, "one(query)");
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        built.query().orderBy(List.<Order>of()); // at most one row is read, so no order (R-EXE-12)
        if (preflights(q)) {
            preflight(q, "one(query)", StatementShape.CONTENT, built);
        }
        return atMostOne(q, built, "one(query)", null);
    }

    @Override
    public <M> Optional<M> first(ModelQuery<E, ?, M> q) {
        Objects.requireNonNull(q, "q");
        checkFirstRun(q);
        LOG.log(DEBUG, () -> "first " + q);
        List<? extends SelectField<M, ?>> tieBreakers = tieBreakers(q);
        // A grouped query without group keys only aggregates, so it has exactly one row to choose.
        if (q.orderBy().isEmpty() && tieBreakers.isEmpty() && !q.isGrouped()) {
            throw new ModelQueryExecutionException(MqCode.MQ2203, q + ": first(query) needs an orderBy or a "
                    + "primary key to choose the same row on every database");
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL, renderOptions);
        // The key closes the order; a column that can be NULL sorts NULLs last on every vendor (R-EXE-12, INV-6).
        List<Order> orders = new ArrayList<>(built.query().getOrderList());
        for (SelectField<M, ?> column : tieBreakers) {
            if (!isOrdered(q, column)) {
                OrderField<M, ?> order = mayBeNull(column, built.joins()) ? column.asc().nullsLast() : column.asc();
                orders.addAll(order.toOrders(built.joins(), cb, q.isGrouped()));
            }
        }
        built.query().orderBy(orders);
        Optional<LockSupport> lock = lockChecks(q, built, "first(query)");
        if (preflights(q)) {
            preflight(q, "first(query)", StatementShape.CONTENT, built);
        }
        TypedQuery<Tuple> query = create(q, built);
        query.setMaxResults(1);
        applyLock(lock, q, query);
        return modelOf(q, built, rows(q, built, query));
    }

    @Override
    public <K, M> Optional<M> one(ModelQuery<E, K, M> q, K key) {
        Objects.requireNonNull(q, "q");
        if (key == null) {
            throw new IllegalArgumentException(q + ": one(query, key) needs a key, and it is null");
        }
        checkFirstRun(q);
        refuseNonUniqueSource(q, "one(query, key)");
        LOG.log(DEBUG, () -> "one " + q + " by key");
        PrimaryKey<M, K> primaryKey = q.primaryKey().orElseThrow(() -> new ModelQueryExecutionException(
                MqCode.MQ2203, q + ": one(query, key) needs a primary key to filter on, and " + (q.isGrouped()
                        ? "a grouped query has none" : "primaryKey(...) was not set")));
        CriteriaBuilder cb = em.getCriteriaBuilder();
        warnOfIgnoredOrder(q, "one(query, key)");
        BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL, renderOptions);
        built.query().orderBy(List.<Order>of()); // at most one row is read, so no order (R-EXE-12)
        Predicate byKey = primaryKey.equal(key, built.joins(), cb);
        Predicate own = built.query().getRestriction();
        built.query().where(own == null ? byKey : cb.and(own, byKey));
        if (preflights(q)) {
            preflight(q, "one(query, key)", StatementShape.CONTENT, built);
        }
        return atMostOne(q, built, "one(query, key)", primaryKey);
    }

    /** Warns once per query and call that {@code call} ignores {@code q}'s {@code orderBy} (R-EXE-05, R-EXE-13). */
    private void warnOfIgnoredOrder(ModelQuery<?, ?, ?> q, String call) {
        if (q.orderBy().isEmpty()) {
            return;
        }
        boolean first;
        synchronized (ORDER_IGNORED) {
            first = ORDER_IGNORED.computeIfAbsent(q, key -> new HashSet<>()).add(call);
        }
        if (first) {
            LOG.log(System.Logger.Level.WARNING, "{0}: {1} ignores the query''s orderBy{2}", q, call,
                    call.startsWith("byKeys") ? "; the map is in key order" : "");
        }
    }

    /**
     * Why {@code q}'s {@code INLINE_COUNT} page reads its total from {@code COUNT}'s statements instead of a window on
     * {@code first}, its page statement or step 1's key query, or empty where {@code first} carries the window: only
     * where {@code counting}, the count {@code COUNT} would run, is {@code first}'s own rows counted (R-EXE-15
     * fallbacks 1–4). Decided after {@code buildQuery}, so a customizer's {@code distinct()} or join is seen. Where
     * {@code keysFirst}, {@code first} is the {@code PRIMARY_KEY} statement and {@code counting} the {@code MODEL} one,
     * so a to-many join in {@code first} alone, a customizer's {@code LEFT} join that R-PAG-15 admits, repeats keys
     * {@code COUNT} counts once.
     */
    private Optional<String> inlineCountFallback(Counting<?> counting, BuiltQuery<?> first, boolean keysFirst) {
        if (!inlineCounts) {
            return Optional.of("the provider renders no window over the page statement (no ProviderSupport, or its "
                    + "inlineCounts(emf) is false or its sources() is empty)");
        }
        if (first.query().isDistinct() || counting.built().query().isDistinct()) {
            return Optional.of("the statement is DISTINCT, which applies after the window counts");
        }
        if (counting.notWindowed().isPresent()) {
            return counting.notWindowed();
        }
        if (keysFirst && first.query().getRoots().stream().anyMatch(DefaultModelQueryExecutor::hasToManyJoin)) {
            return Optional.of("step 1's key query carries a to-many join, which repeats a key COUNT counts once");
        }
        return Optional.empty();
    }

    /**
     * Selects {@code count(1) over ()} as {@link #INLINE_TOTAL} after {@code built}'s own selection, through the
     * provider's window renderer: after {@code buildQuery}, so the customizer can't drop it, and before any preflight,
     * so the statement checked is the one run (R-EXE-15, R-VND-27).
     */
    private void selectInlineTotal(BuiltQuery<?> built) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        // inlineCounts holds only where the provider serves sources() (R-EXE-15 fallback 1).
        SourceRenderer renderer = vendor.providerSupport().flatMap(ProviderSupport::sources).orElseThrow();
        Expression<?> total = renderer.window(cb, new WindowSpec(WindowFunction.COUNT, cb.literal(1), List.of(),
                List.of(), WindowFrame.NONE));
        CriteriaQuery<Tuple> query = built.query();
        Selection<?> own = query.getSelection();
        var selected = new ArrayList<Selection<?>>(own.isCompoundSelection() ? own.getCompoundSelectionItems()
                : List.of(own));
        selected.add(total.alias(INLINE_TOTAL));
        query.multiselect(selected);
    }

    /** The total {@code tuple}'s {@link #INLINE_TOTAL} window read, by its alias (R-EXE-15). */
    private static long inlineTotal(Tuple tuple) {
        return ((Number) tuple.get(INLINE_TOTAL)).longValue();
    }

    /** Logs {@code reason} at {@code DEBUG} the first time this executor falls back for {@code q} (R-EXE-15). */
    private void logInlineCountFallback(ModelQuery<?, ?, ?> q, String reason) {
        if (inlineCountFallbacksLogged.add(q)) {
            LOG.log(DEBUG, () -> "page " + q + ": INLINE_COUNT runs COUNT's statements for the total, because "
                    + reason + " (R-EXE-15)");
        }
    }

    private void warnOfContradictedTotal(ModelQuery<?, ?, ?> q, long offset, int rows, long total) {
        if (rows == 0 || rows <= total - offset || !TreatChecks.treated(q)) {
            return;
        }
        boolean first;
        synchronized (TOTAL_CONTRADICTED) {
            first = TOTAL_CONTRADICTED.add(q);
        }
        if (first) {
            LOG.log(System.Logger.Level.WARNING, "MQ2214: {0}: offset {1} returned {2} rows with total {3}; "
                    + "a row may have committed between the count and content; use REPEATABLE READ or a snapshot "
                    + "around page()", q, offset, rows, total);
        }
    }

    // ---- reads by many keys

    @Override
    public <K, M> Map<K, M> byKeys(ModelQuery<E, K, M> q, Collection<? extends K> keys) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(keys, "keys");
        checkFirstRun(q);
        refuseNonUniqueSource(q, "byKeys(query, keys)");
        LOG.log(DEBUG, () -> "byKeys " + q + ": " + keys.size() + " keys");
        PrimaryKey<M, K> primaryKey = q.primaryKey().orElseThrow(() -> new ModelQueryExecutionException(
                MqCode.MQ2203, q + ": byKeys(query, keys) needs a primary key to filter on, and " + (q.isGrouped()
                        ? "a grouped query has none" : "primaryKey(...) was not set")));
        CriteriaBuilder cb = em.getCriteriaBuilder();
        // The statement's own binds, before any key is added; primaryKeyFirstBatchSize does not apply (R-EXE-13).
        BuiltQuery<M> counted = q.buildQuery(cb, Phase.MODEL, renderOptions);
        // The lock refusals run before the no-keys short cut, so they fail the same way for any keys (R-EXE-14).
        Optional<LockSupport> lock = lockChecks(q, counted, "byKeys(query, keys)");
        // Each caller key to the attribute value it matches by (R-COL-11); keys converting to one value share a row.
        Map<K, Object> converted = new LinkedHashMap<>();
        for (K key : keys) {
            if (!converted.containsKey(key)) {
                converted.put(key, primaryKey.attributeKey(key));
            }
        }
        boolean preflighted = preflights(q);
        if (converted.isEmpty()) {
            if (preflighted) {
                // Before the no-keys short cut too, so a refused statement fails the same way for any keys.
                preflight(q, "byKeys(query, keys)", StatementShape.CONTENT, counted);
            }
            return Map.of();
        }
        warnOfIgnoredOrder(q, "byKeys(query, keys)");
        Set<Object> requested = new LinkedHashSet<>(converted.values());
        List<Object> distinct = lock.isPresent() ? sortedForLocking(requested) : List.copyOf(requested);
        int keyColumns = primaryKey.columns().size();
        // The first chunk takes the most keys any statement could and sizes the rest from its own binds, so no
        // statement is compiled only to be counted (R-EXE-13, R-PAG-07, D-32).
        int chunk = Math.min(distinct.size(), keyLimits.clamp(0, keyColumns, OptionalInt.empty()));
        boolean sized = false;
        Map<Object, ReadRow<M>> found = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += chunk) {
            List<Object> chunkKeys = distinct.subList(from, Math.min(distinct.size(), from + chunk));
            BuiltQuery<M> built = byKeysChunk(q, primaryKey, cb, lock, chunkKeys);
            if (!sized && preflighted) {
                // The first chunk, before any statement; the others differ only in how many keys their IN holds.
                preflight(q, "byKeys(query, keys)", StatementShape.CONTENT, built);
            }
            TypedQuery<Tuple> statement;
            if (sized) {
                statement = create(q, built);
            } else {
                sized = true;
                TypedQuery<Tuple> probe = em.createQuery(built.query());
                bindValues(probe, built.joins(), built.query().getParameters());
                int ownBinds = Math.max(0, probe.getParameters().size() + built.joins().repeatedExpressionBinds()
                        - chunkKeys.size() * keyColumns);
                int sizedChunk = keyLimits.clamp(ownBinds, keyColumns, OptionalInt.empty());
                if (chunkKeys.size() <= sizedChunk) {
                    statement = checked(q, probe, null, null, built.joins());
                } else {
                    // More keys than the statement's own binds leave room for: the first chunk is built again, smaller,
                    // and that build is the statement that runs, so it is preflighted too.
                    chunkKeys = distinct.subList(from, Math.min(distinct.size(), from + sizedChunk));
                    built = byKeysChunk(q, primaryKey, cb, lock, chunkKeys);
                    if (preflighted) {
                        preflight(q, "byKeys(query, keys)", StatementShape.CONTENT, built);
                    }
                    statement = create(q, built);
                }
                chunk = sizedChunk;
            }
            applyLock(lock, q, statement);
            for (Tuple tuple : rows(q, built, statement)) {
                Row row = built.selection().row(tuple);
                Object rowKey = Keys.keyOf(q, primaryKey, row);
                if (!requested.contains(rowKey)) {
                    throw new ModelQueryExecutionException(MqCode.MQ2005, q + ": byKeys(query, keys) read a row whose "
                            + "key " + rowKey + " equals none of the requested keys, as a case-insensitive or padding "
                            + "collation, or a BigDecimal scale, can match; compare the key as the database does");
                }
                if (found.putIfAbsent(rowKey, new ReadRow<>(built, row)) != null) {
                    if (filterOnlyToManyJoin(built).isEmpty()) {
                        throw duplicateKey(q, rowKey);
                    }
                    throw new ModelQueryExecutionException(MqCode.MQ2003, q + ": byKeys(query, keys) found more than "
                            + "one row for key " + rowKey + filterOnlyToManyJoin(built).map(join -> "; if the to-many "
                                    + "join " + joinName(join) + ", which no selected column reads, repeats the row "
                                    + "once per matching child, filter with Filters.exists(...) instead").orElse(""));
                }
            }
        }
        // Every chunk is read and checked; only now are rows mapped, in key order, and the fetch plan run once.
        List<Object> foundValues = new ArrayList<>(found.size());
        List<Loaded<M>> rows = new ArrayList<>(found.size());
        for (Object value : distinct) {
            ReadRow<M> row = found.get(value);
            if (row != null) {
                foundValues.add(value);
                rows.add(new Loaded<>(row.built().map(row.row()), row.row()));
            }
        }
        List<M> models = models(q, rows);
        Map<Object, M> byValue = new HashMap<>();
        for (int i = 0; i < foundValues.size(); i++) {
            byValue.put(foundValues.get(i), models.get(i));
        }
        Map<K, M> result = new LinkedHashMap<>();
        converted.forEach((key, value) -> {
            M model = byValue.get(value);
            if (model != null) {
                result.put(key, model);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * One chunk of {@code byKeys}: the query built again, filtered to {@code chunkKeys}, ordered for a lock (R-EXE-14).
     */
    private <M> BuiltQuery<M> byKeysChunk(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> primaryKey, CriteriaBuilder cb,
            Optional<LockSupport> lock, List<Object> chunkKeys) {
        BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL, renderOptions);
        if (lock.isPresent()) {
            // The customizer runs again for each chunk and may join only then (R-EXE-14).
            refuseJoin(q, built, lock, "byKeys(query, keys)");
        }
        Predicate byKey = primaryKey.in(chunkKeys, built.joins(), cb, false);
        Predicate own = built.query().getRestriction();
        built.query().where(own == null ? byKey : cb.and(own, byKey));
        // A locked chunk is ordered by primary key, so concurrent callers lock in the same order; without a lock
        // chunks cannot be ordered across statements (R-EXE-13, R-EXE-14).
        built.query().orderBy(lock.isPresent() ? primaryKeyOrders(primaryKey, built, cb) : List.<Order>of());
        return built;
    }

    /**
     * The model of {@code built}'s one row, or empty. It reads at a limit of 2 and checks a second row before mapping,
     * so neither {@code afterMap} nor the fetch plan sees it (R-EXE-12, R-PAG-26, D-160).
     */
    private <M> Optional<M> atMostOne(ModelQuery<E, ?, M> q, BuiltQuery<M> built, String call,
            PrimaryKey<M, ?> uniqueKey) {
        Optional<LockSupport> lock = lockChecks(q, built, call);
        TypedQuery<Tuple> query = create(q, built);
        query.setMaxResults(2);
        applyLock(lock, q, query);
        List<Tuple> tuples = rows(q, built, query);
        if (tuples.size() > 1) {
            if (uniqueKey != null && filterOnlyToManyJoin(built).isEmpty()) {
                throw duplicateKey(q, Keys.keyOf(q, uniqueKey, built.selection().row(tuples.get(0))));
            }
            throw new ModelQueryExecutionException(MqCode.MQ2003, q + ": " + call + " found more than one row"
                    + filterOnlyToManyJoin(built).map(join -> "; if the to-many join " + joinName(join) + ", which no "
                            + "selected column reads, repeats the row once per matching child, filter with "
                            + "Filters.exists(...) instead").orElse(""));
        }
        return modelOf(q, built, tuples);
    }

    /**
     * The model of the first of {@code tuples}, with {@code q}'s fetch plan run on it, or empty for none. The rows
     * come from {@link #rows(Object, BuiltQuery, TypedQuery)}, whose check site has read the source's checks before
     * returning them (R-SRC-08, AC-SRC-25).
     */
    private <M> Optional<M> modelOf(ModelQuery<E, ?, M> q, BuiltQuery<M> built, List<Tuple> tuples) {
        return tuples.isEmpty() ? Optional.empty()
                : Optional.of(models(q, List.of(loaded(built, tuples.get(0)))).get(0));
    }

    /**
     * The R-EXE-14 refusals for a locking read of {@code q}, before any statement and in order: a source's CTE, which
     * a locking clause cannot follow ({@code MQ1903}, R-SRC-11), a join in {@code built} ({@code MQ2211}), a provider
     * that cannot render the lock or a root on several tables ({@code MQ4010}), and no active transaction
     * ({@code MQ2102}). Returns the support to apply on the statement, or empty when {@code q} takes no lock;
     * {@code built} is the statement's criteria, built by the caller and never executed here.
     */
    private <M> Optional<LockSupport> lockChecks(ModelQuery<E, ?, M> q, BuiltQuery<M> built, String read) {
        Optional<RowLock> lock = q.lock();
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        RowLock rowLock = lock.get();
        if (q.source().isPresent()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1903,
                    q + ": " + read + " cannot lock a query that reads a "
                    + "source: its rows come from a common table expression, which a locking clause cannot follow; "
                    + "drop withLock(...) (R-SRC-11)");
        }
        Optional<LockSupport> rendered = vendor.providerSupport().flatMap(ProviderSupport::locks);
        refuseJoin(q, built, rendered, read);
        if (rendered.isEmpty()) {
            throw cannotLock(q, read, rowLock, lockSupportAbsence());
        }
        LockSupport support = rendered.get();
        if (!support.rendered(em.getEntityManagerFactory(), rowLock)) {
            throw cannotLock(q, read, rowLock,
                    "provider " + support.getClass().getSimpleName() + " would not render it");
        }
        if (severalTables()) {
            throw cannotLock(q, read, rowLock, "root entity " + rootEntity.getSimpleName()
                    + " is mapped to more than one table and only the root table would be locked");
        }
        if (!em.isJoinedToTransaction()) {
            throw new ModelQueryExecutionException(MqCode.MQ2102, q + ": " + read + " locks " + rowLock
                    + " and needs an active transaction, and the EntityManager is not joined to one");
        }
        return Optional.of(support);
    }

    /** The {@code MQ4010} refusal of {@code read} locking {@code rowLock}, for the reason {@code because}. */
    private ModelQueryExecutionException cannotLock(ModelQuery<?, ?, ?> q, String read, RowLock rowLock,
            String because) {
        return new ModelQueryExecutionException(MqCode.MQ4010, q + ": " + read + " cannot lock " + rowLock + " on "
                + vendor.detectedVendor() + ", because " + because);
    }

    /**
     * Applies {@code q}'s lock to {@code statement} through {@code support}, which is empty when {@code q} takes none.
     */
    private static void applyLock(Optional<LockSupport> support, ModelQuery<?, ?, ?> q, TypedQuery<?> statement) {
        support.ifPresent(locks -> locks.apply(statement, q.lock().get()));
    }

    /** {@code MQ2211} when the locked read of {@code q} has a join in {@code built} (R-EXE-14). */
    private <M> void refuseJoin(ModelQuery<E, ?, M> q, BuiltQuery<M> built, Optional<LockSupport> support,
            String read) {
        if (hasJoin(built, support)) {
            throw new ModelQueryExecutionException(MqCode.MQ2211, q + ": " + read + " cannot lock a query whose "
                    + "statement has a join, which a locking clause would follow on or lock every table of; filter "
                    + "with Filters.exists(...) instead of a join");
        }
    }

    /**
     * Refuses a lock on a read that takes none ({@code MQ2211}, R-EXE-14): paging, which would lock the rows it skips
     * or read them out of order, {@code count}, which returns no row, and {@code stream} and export, which hold a
     * cursor across user code.
     */
    private static void refuseLock(ModelQuery<?, ?, ?> q, String call) {
        if (q.lock().isPresent()) {
            throw new ModelQueryExecutionException(MqCode.MQ2211, q + ": " + call + " cannot run a locked query; "
                    + "only list, first, one and byKeys take a lock (R-EXE-14)");
        }
    }

    /**
     * Whether {@code built}'s statement reads more than its root's table, through a join or a second root a customizer
     * added, which a locked read refuses (R-EXE-14, D-127). Fetches and, where the provider lists them apart, entity
     * joins count too.
     */
    private static boolean hasJoin(BuiltQuery<?> built, Optional<LockSupport> support) {
        Set<Root<?>> roots = built.query().getRoots();
        return roots.size() > 1
                || roots.stream().anyMatch(root -> !root.getJoins().isEmpty() || !root.getFetches().isEmpty())
                || support.map(locks -> locks.hasUnreportedJoins(built.query())).orElse(false);
    }

    /** Whether {@code rootEntity} is mapped to more than one table, where only the root table would be locked. */
    private boolean severalTables() {
        Set<String> tables = vendor.providerSupport()
                .map(support -> support.tablesOf(em.getEntityManagerFactory(), rootEntity)).orElse(Set.of());
        return tables.size() > 1;
    }

    /** Why {@code MQ4010} names for a factory whose provider offers no {@link LockSupport}. */
    private String lockSupportAbsence() {
        return vendor.providerSupport()
                .map(support -> "provider " + support.getClass().getName() + " has no LockSupport "
                        + "(ProviderSupport#locks())")
                .orElse("no ProviderSupport serves this factory; add a ProviderSupport for its provider "
                        + "(model-query-hibernate for Hibernate)");
    }

    /**
     * {@code keys} in their natural order when they are all mutually comparable, so concurrent locked callers lock
     * their chunks in one order, and as given otherwise. The result keeps the caller's order regardless (R-EXE-13).
     */
    private static List<Object> sortedForLocking(Collection<Object> keys) {
        List<Object> sorted = new ArrayList<>(keys);
        try {
            sorted.sort(null);
        } catch (ClassCastException | NullPointerException e) {
            return List.copyOf(keys); // not comparable: chunks keep the order given
        }
        return List.copyOf(sorted);
    }

    /** {@code primaryKey}'s columns ascending, the order a locked {@code byKeys} chunk locks its rows in. */
    private static <M> List<Order> primaryKeyOrders(PrimaryKey<M, ?> primaryKey, BuiltQuery<M> built,
            CriteriaBuilder cb) {
        List<Order> orders = new ArrayList<>();
        for (ColumnField<M, ?, ?> column : primaryKey.columns()) {
            orders.addAll(column.asc().toOrders(built.joins(), cb, false));
        }
        return orders;
    }

    /**
     * A to-many join of {@code built} that no selected column is read through, the one that repeats a root row once
     * per matching child when only a filter reads it (R-EXE-04, R-EXE-12); empty when there is none. A filter-only
     * join the engine makes renders as a semi-join and leaves none (R-EXE-16), so this finds a customizer's join, or
     * one a statement keeps for an order term, a group key or a shape R-EXE-16 excludes.
     */
    private static Optional<Join<?, ?>> filterOnlyToManyJoin(BuiltQuery<?> built) {
        Set<Join<?, ?>> selectedThrough = Collections.newSetFromMap(new IdentityHashMap<>());
        selectedThrough.addAll(selectedToManyJoins(built));
        return toManyJoinOutside(built.query().getRoots().iterator().next(), selectedThrough);
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

    @Override
    public <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(body, "body");
        refuseLock(q, "stream(...)");
        if (q.fetch().filter(plan -> !plan.isSelectionOnly()).isPresent()) {
            // A plan runs once per page, and a stream has none (R-FCH-09, D-96).
            throw new ModelQueryExecutionException(MqCode.MQ2605, q + ": stream(...) cannot run the fetch plan, whose "
                    + "children, join plans and enrichers run once per page, and a stream has no page; use "
                    + "export(...), which runs the plan on each page");
        }
        checkFirstRun(q);
        LOG.log(DEBUG, () -> "stream " + q + ": " + limit);
        BuiltQuery<M> built = null;
        if (preflights(q)) {
            // Before the zero-limit short cut, as list's, so a refused statement fails the same way for any limit.
            built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
            preflight(q, "stream", StatementShape.CONTENT, built);
        }
        if (zeroLimit(limit)) {
            try (Stream<M> none = Stream.empty()) {
                return body.apply(none); // no statement runs for a zero limit (R-EXE-06)
            }
        }
        if (built == null) {
            built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        }
        TypedQuery<Tuple> query = limited(q, built, limit);
        // Precondition first, so a refusal runs no statement. The profile decides the size from the configured one,
        // and the provider's support opens the stream with it (R-EXE-08, R-QRY-15, R-VND-12).
        vendor.profile().checkStreamingPreconditions(em);
        int fetchSize = vendor.profile().streamingFetchSize(streamFetchSize);
        // Rows are mapped one at a time as body pulls them; closing the mapped stream closes the result stream under
        // it, whether body returns, stops early or throws (R-EXE-07, R-EXE-09). The check site reads a recursive
        // source's checks from the first row before that row is handed on (R-SRC-08, AC-SRC-25).
        try (Stream<Tuple> tuples = resultStream(q, built, query, fetchSize);
                Stream<M> models = tuples.map(built::map)) {
            return body.apply(models);
        }
    }

    /**
     * {@code built}'s rows, streamed by the factory's provider support, or portably with a warning when none, its
     * source's checks read from the first row before that row is handed on (R-SRC-08, AC-SRC-25).
     */
    private <M> Stream<Tuple> resultStream(Object label, BuiltQuery<M> built, TypedQuery<Tuple> query,
            int fetchSize) {
        Optional<ProviderSupport> support = vendor.providerSupport();
        Stream<Tuple> tuples;
        if (support.isPresent()) {
            tuples = support.get().resultStream(query, fetchSize);
        } else {
            warnOfUnsetFetchSize();
            tuples = query.getResultStream();
        }
        if (built.recursionChecks().isEmpty()) {
            return tuples;
        }
        // The first row is read here, before the stream is handed on, so no terminal operation of the caller's,
        // a count() a sized stream answers without a row included, can pass it unchecked; it is put back in front
        // of the rest, and closing the returned stream closes the provider's (R-SRC-08, AC-SRC-25).
        Spliterator<Tuple> rest = tuples.spliterator();
        List<Tuple> first = new ArrayList<>(1);
        try {
            rest.tryAdvance(first::add);
            SourceChecks.checkRecursion(label, built, first);
        } catch (RuntimeException e) {
            tuples.close();
            throw e;
        }
        return Stream.concat(first.stream(), StreamSupport.stream(rest, false)).onClose(tuples::close);
    }

    /** Warns once per factory that, with no provider support to set the fetch size, the driver may buffer. */
    private void warnOfUnsetFetchSize() {
        EntityManagerFactory emf = em.getEntityManagerFactory();
        if (FETCH_SIZE_UNSET.add(emf)) {
            LOG.log(System.Logger.Level.WARNING, "stream sets no fetch size on {0}, because no ProviderSupport serves "
                    + "its persistence provider, so the JDBC driver may read the whole result into memory; add a "
                    + "ProviderSupport for its provider (model-query-hibernate for Hibernate), or use keyset export "
                    + "(R-VND-12, D-108)", emf);
        }
    }

    private static boolean zeroLimit(Limit limit) {
        return limit.maxRows().isPresent() && limit.maxRows().getAsInt() == 0;
    }

    /**
     * A statement of {@code query} for {@code label}, the model or entity a refusal names, within the bind limit and
     * with the configured timeout applied through the profile (R-FLT-09, R-EXE-11).
     */
    private <T> TypedQuery<T> create(Object label, CriteriaQuery<T> query) {
        return create(label, em, query, null, null, null);
    }

    /** A statement of {@code built} on {@code em}, counting the binds its repeated expression nodes add back. */
    private <M> TypedQuery<Tuple> create(Object label, BuiltQuery<M> built) {
        return create(label, em, built.query(), null, null, built.joins());
    }

    /** A statement of {@code query} on {@code on}, a chunk's own {@code EntityManager} or the caller's. */
    private <T> TypedQuery<T> create(Object label, EntityManager on, CriteriaQuery<T> query) {
        return create(label, on, query, null, null, null);
    }

    /**
     * As above, for a keyset statement, {@code cursor} being the one it was built after or {@code null} for the first
     * page or round, and {@code keyset} {@code null} for a statement without one (a run of distinct keys). It is
     * refused up front when its own binds plus the worst cursor {@code keyset} can add pass the limit, on every page,
     * so a run never fails after rows reached a sink or a round committed (D-82). {@code joins} adds back the binds a
     * repeated expression node contributes beyond the one JPA reports (R-COL-19), or {@code null} when unknown.
     */
    private <T> TypedQuery<T> create(Object label, EntityManager on, CriteriaQuery<T> query, Keyset<?> keyset,
            Object[] cursor, JoinContext joins) {
        TypedQuery<T> typed = on.createQuery(query);
        bindValues(typed, joins, query.getParameters());
        return checked(label, typed, keyset, cursor, joins);
    }

    /**
     * {@code typed}, already compiled and bound, once it is within the bind limit, with the timeout applied: the checks
     * of {@link #create(Object, EntityManager, CriteriaQuery, Keyset, Object[], JoinContext)} on a statement compiled
     * before them.
     */
    private <T> TypedQuery<T> checked(Object label, TypedQuery<T> typed, Keyset<?> keyset, Object[] cursor,
            JoinContext joins) {
        int binds = typed.getParameters().size() + (joins == null ? 0 : joins.repeatedExpressionBinds());
        int cursorBinds = keyset == null || cursor == null ? 0 : keyset.cursorBinds(cursor);
        if (keyset != null) {
            withinCursorBindLimit(label, binds - cursorBinds, keyset);
        }
        withinBindLimit(label, binds, cursorBinds);
        queryTimeout.ifPresent(timeout -> vendor.profile().applyTimeout(typed, timeout));
        LOG.log(TRACE, () -> label + ": statement binds " + binds + " of " + renderOptions.maxBindParameters()
                + (cursorBinds > 0 ? ", " + cursorBinds + " of them the keyset cursor's" : ""));
        return typed;
    }

    /**
     * Sets every {@code Expr.value} parameter {@code joins} recorded on {@code query}, so the statement binds the
     * values the build rendered as parameters (R-COL-26). A {@code null} {@code joins} renders none. A write renders
     * its clauses as recorded, so binds all of them.
     */
    private static void bindValues(Query query, JoinContext joins) {
        bindValues(query, joins, null);
    }

    /**
     * As above, only those of {@code kept}, the parameters the final {@code Criteria} query holds, when it is not
     * {@code null}: a count clears the order and replaces the select, a {@code one(...)} clears the order, after
     * the build rendered them, and a provider refuses a parameter its statement no longer holds (R-COL-26).
     */
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

    /** {@code load}'s query on {@code on}, its {@code Expr.value} parameters bound (R-COL-26). */
    private static <E> TypedQuery<E> loadStatement(EntityManager on, EntityLoad<E> load) {
        TypedQuery<E> query = on.createQuery(load.query());
        bindValues(query, load.joins(), load.query().getParameters());
        return query;
    }

    /** {@code rendered}'s update with {@code added} assigned, its {@code Expr.value} parameters bound (R-COL-26). */
    private static <E> Query updateStatement(EntityManager on, ModelUpdate.Rendered<E> rendered,
            List<WriteAssignments.Written> added) {
        Map<ParameterExpression<?>, Object> assigned = new LinkedHashMap<>();
        Query statement = on.createQuery(assigning(rendered.update(), added, rendered.joins(),
                on.getCriteriaBuilder(), assigned));
        bindValues(statement, rendered.joins());
        assigned.forEach((parameter, value) -> bind(statement, parameter, value));
        return statement;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bind(Query query, ParameterExpression<?> parameter, Object value) {
        query.setParameter((Parameter) parameter, value);
    }

    /** {@code rendered}'s delete statement, its {@code Expr.value} parameters bound (R-COL-26). */
    private static Query deleteStatement(EntityManager on, ModelDelete.Rendered<?> rendered) {
        Query statement = on.createQuery(rendered.delete());
        bindValues(statement, rendered.joins());
        return statement;
    }

    /** {@code tree}'s key-select statement, its {@code Expr.value} parameters bound (R-COL-26). */
    private static Query keySelectStatement(EntityManager on, BuiltQuery<?> tree) {
        Query statement = on.createQuery(tree.query());
        bindValues(statement, tree.joins());
        return statement;
    }

    /**
     * {@code query}'s entities, with their count and time in the trace log (D-95). It takes only an entity query, so
     * a {@code Tuple} read has no unchecked overload to call (P-2, AC-SRC-25).
     */
    private List<E> rows(Object label, TypedQuery<E> query) {
        long start = System.nanoTime();
        List<E> rows;
        try {
            rows = query.getResultList();
        } catch (PersistenceException e) {
            throw timeoutCancellation(e);
        }
        traceTimed(label, rows.size() + (rows.size() == 1 ? " row" : " rows"), start);
        return rows;
    }

    /**
     * {@code built}'s rows, with their count and time in the trace log (D-95), its source's checks read from the
     * first row before any row is returned (R-SRC-08, AC-SRC-25). Every {@code Tuple} list read goes through here.
     */
    private <M> List<Tuple> rows(Object label, BuiltQuery<M> built, TypedQuery<Tuple> query) {
        long start = System.nanoTime();
        List<Tuple> rows;
        try {
            rows = query.getResultList();
        } catch (PersistenceException e) {
            throw timeoutCancellation(e);
        }
        traceTimed(label, rows.size() + (rows.size() == 1 ? " row" : " rows"), start);
        SourceChecks.checkRecursion(label, built, rows);
        return rows;
    }

    /**
     * {@code query}'s one count, with its time in the trace log (D-95). It takes only a count query, so a
     * {@code Tuple} read has no unchecked overload to call (P-2, AC-SRC-25).
     */
    private long single(Object label, TypedQuery<Long> query) {
        long start = System.nanoTime();
        Long row;
        try {
            row = query.getSingleResult();
        } catch (PersistenceException e) {
            throw timeoutCancellation(e);
        }
        traceTimed(label, "1 row", start);
        return row;
    }

    /**
     * {@code built}'s one row, with its time in the trace log (D-95), its source's checks read before the row is
     * returned (R-SRC-08, AC-SRC-25). The count over a source is the one caller: it selects the checks beside the
     * count and reads them here (R-SRC-07).
     */
    private <M> Tuple single(Object label, BuiltQuery<M> built, TypedQuery<Tuple> query) {
        long start = System.nanoTime();
        Tuple row;
        try {
            row = query.getSingleResult();
        } catch (PersistenceException e) {
            throw timeoutCancellation(e);
        }
        traceTimed(label, "1 row", start);
        SourceChecks.checkRecursion(label, built, List.of(row));
        return row;
    }

    /** {@link #timeoutCancellation(PersistenceException, boolean)} for this executor's configured timeout. */
    private PersistenceException timeoutCancellation(PersistenceException e) {
        return timeoutCancellation(e, queryTimeout.isPresent());
    }

    /**
     * {@code e} as JPA's {@link QueryTimeoutException} when {@code timeoutConfigured} and its cause chain holds a
     * {@link SQLException} with SQLState {@code 57014} (a statement the query timeout cancelled), else {@code e}
     * unchanged. Hibernate 6.6 already reports the cancellation as a {@code QueryTimeoutException}; Hibernate 7 on
     * PostgreSQL reports it as a plain {@link PersistenceException}, so the executor translates it for callers on
     * every supported version (R-EXE-11).
     */
    static PersistenceException timeoutCancellation(PersistenceException e, boolean timeoutConfigured) {
        if (!timeoutConfigured || e instanceof QueryTimeoutException || !cancelledByTimeout(e)) {
            return e;
        }
        return new QueryTimeoutException(e.getMessage(), e);
    }

    /** Whether {@code e}'s cause chain holds a {@link SQLException} with SQLState {@code 57014}. */
    private static boolean cancelledByTimeout(Throwable e) {
        // At most 32 causes deep, so a cyclic cause chain cannot loop forever.
        Throwable cause = e;
        for (int depth = 0; cause != null && depth < 32; cause = cause.getCause(), depth++) {
            if (cause instanceof SQLException sql && CANCELLED_SQL_STATE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void traceTimed(Object label, String what, long start) {
        long millis = (System.nanoTime() - start) / 1_000_000;
        LOG.log(TRACE, () -> label + ": " + what + " in " + millis + " ms");
    }

    /** The debug line of a write, with how it runs (R-WRT-08, R-WRT-11, D-95). */
    private void logWrite(String operation, Object write, Optional<ChunkOptions> chunk, boolean keyFirst) {
        // The write's toString names its rows and where conditions, never a key or a value (D-95, R-INS-05).
        LOG.log(DEBUG, () -> operation + " " + write + ": "
                + (chunk.isPresent() ? "chunked" : keyFirst ? "key-first" : "direct"));
    }

    /**
     * {@code statement}, unless the query parameters JPA reports for it pass the profile's bind limit: a query's own
     * statement is never split across statements, so it throws {@code MQ1307} before it runs rather than failing in
     * the database (R-FLT-09, D-80). Library-built key lists are clamped below the limit (D-32, D-63), so only the
     * query's own binds can pass it.
     */
    private <Q extends Query> Q withinBindLimit(Object label, Q statement) {
        withinBindLimit(label, statement.getParameters().size(), 0);
        return statement;
    }

    /**
     * As above, for a statement of {@code binds} values, {@code cursorBinds} of them the keyset cursor's, which the
     * refusal counts (D-82).
     */
    private void withinBindLimit(Object label, int binds, int cursorBinds) {
        int max = renderOptions.maxBindParameters();
        if (binds > max) {
            String why = cursorBinds > 0
                    ? "; " + cursorBinds + " of them are the keyset cursor's values, so narrow the query's own "
                            + "filters by at least " + (binds - max) + " or use fewer keyset columns"
                    : "; narrow its filters, since a query's own statement is never split across statements";
            throw new ModelQueryDefinitionException(MqCode.MQ1307, label + ": a statement binds " + binds
                    + " values, more than the " + max + " bind parameters one statement takes" + why);
        }
    }

    /** Throws {@code MQ1307} when a statement's {@code own} binds and the worst keyset cursor pass the limit (D-82). */
    private void withinCursorBindLimit(Object label, int own, Keyset<?> keyset) {
        int worst = keyset.maxCursorBinds();
        int max = renderOptions.maxBindParameters();
        if (own + worst > max) {
            throw new ModelQueryDefinitionException(MqCode.MQ1307, label + ": a keyset statement binds " + own
                    + " values of its own, and the keyset cursor's values can add up to " + worst + " more, over the "
                    + max + " bind parameters one statement takes; narrow the query's own filters by at least "
                    + (own + worst - max) + " or use fewer keyset columns");
        }
    }

    /** {@code built}'s statement for {@code q}, capped at {@code limit}'s rows if it has any. */
    private <M> TypedQuery<Tuple> limited(ModelQuery<E, ?, M> q, BuiltQuery<M> built, Limit limit) {
        TypedQuery<Tuple> query = create(q, built);
        limit.maxRows().ifPresent(query::setMaxResults);
        return query;
    }

    @Override
    public <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(mode, "mode");
        refuseLock(q, "page(query, PageSpec, CountMode)");
        // INLINE_COUNT builds, checks and preflights COUNT's statements as COUNT does, whether or not the window is
        // used, since a page past the end can only be known after the content statement (R-EXE-15).
        boolean withCount = mode == CountMode.COUNT || mode == CountMode.INLINE_COUNT;
        Optional<String> clientSide = checkFirstRun(q, mode != CountMode.ONLY_COUNT,
                mode == CountMode.ONLY_COUNT ? StatementShape.COUNT : StatementShape.CONTENT,
                false, withCount);
        int offset = page.offset();
        int size = page.pageSize();
        // A non-unique source needs every selected column to close the order; a tree's key suffices when the query
        // declares one (R-SRC-09).
        boolean everyColumn = needsEveryColumn(q);
        LOG.log(DEBUG, () -> "page " + q + ": offset " + offset + " size " + size + ", " + mode
                + (mode != CountMode.ONLY_COUNT && primaryKeyFirst(q, offset) ? ", primary-key-first" : ""));
        int batch = 0; // the step-2 batch of a primary-key-first page
        if (mode != CountMode.ONLY_COUNT && primaryKeyFirst(q, offset)) {
            // Before the count too, so a refused page runs no query at all (R-PAG-13), and step 2 is sized and
            // checked against the bind limit here, not after step 1 found keys (R-PAG-07, D-138).
            BuiltQuery<M> byKeys = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL_BY_KEYS, renderOptions);
            refuseToManySelection(q, byKeys, "primary-key-first paging");
            batch = keyBatchSize(q, q.primaryKey().orElseThrow(), byKeys); // primaryKeyFirst(...) needs one (MQ1201)
            preflightRowsByKeys(q, byKeys);
        }
        // With a treated node, every statement the page runs is built and preflighted before the first of them runs,
        // and then run as built (R-VND-26).
        Counting<M> counting = mode != CountMode.NO_COUNT ? preflightedCounting(q, clientSide) : null;
        BuiltQuery<M> first = null;
        if (mode != CountMode.ONLY_COUNT && (preflights(q) || mode == CountMode.INLINE_COUNT && inlineCounts)) {
            first = primaryKeyFirst(q, offset) ? keyQuery(q) : offsetPage(q, everyColumn);
        }
        // Fallbacks 1-4 are decided on the statements as built, after the customizer; the window is selected before
        // the preflight, so the statement checked is the one run (R-EXE-15).
        Optional<String> inlineFallback = mode == CountMode.INLINE_COUNT
                ? inlineCountFallback(counting, first, primaryKeyFirst(q, offset)) : Optional.empty();
        if (mode == CountMode.INLINE_COUNT && inlineFallback.isEmpty()) {
            selectInlineTotal(first);
        }
        if (first != null && preflights(q)) {
            preflight(q, primaryKeyFirst(q, offset) ? "primary-key-first page's keys" : "page",
                    StatementShape.CONTENT, first);
        }
        switch (mode) {
            case ONLY_COUNT: {
                long total = countRows(q, counting);
                return new Slice<>(List.of(), page.pageNumber(), size, offset + (long) size < total,
                        OptionalLong.of(total));
            }
            case INLINE_COUNT:
                if (inlineFallback.isEmpty()) {
                    return inlineCounted(q, page, batch, first, counting);
                }
                // A fallback reads the total from COUNT's statements, exactly as COUNT does (R-EXE-15).
                logInlineCountFallback(q, inlineFallback.get());
                // fall through
            case COUNT: {
                long total = countRows(q, counting);
                List<M> content = offset >= total ? List.of()
                        : models(q, fetch(q, offset, size, batch, everyColumn, first));
                warnOfContradictedTotal(q, offset, content.size(), total);
                return new Slice<>(content, page.pageNumber(), size, offset + (long) content.size() < total,
                        OptionalLong.of(total));
            }
            default: {
                // One row beyond the page tells whether another follows, so an exact multiple of the page size
                // does not report a next page that is empty (R-EXE-02).
                List<Loaded<M>> rows = fetch(q, offset, size == Integer.MAX_VALUE ? size : size + 1, batch,
                        everyColumn, first);
                boolean hasNext = rows.size() > size;
                // The plan runs after the probe row is dropped, so that row's children are never read (R-FCH-09).
                return new Slice<>(models(q, hasNext ? rows.subList(0, size) : rows), page.pageNumber(), size,
                        hasNext, OptionalLong.empty());
            }
        }
    }

    /**
     * An {@code INLINE_COUNT} page whose {@code first} statement, the page or step 1's key query, carries the window:
     * the total is read off its first row; with no row, it is 0 on the first page, and past it {@code COUNT}'s
     * statement runs, as a page past the end can't tell an empty result from an offset beyond it (R-EXE-15 fallback 5).
     */
    private <M> Slice<M> inlineCounted(ModelQuery<E, ?, M> q, PageSpec page, int batch, BuiltQuery<M> first,
            Counting<M> counting) {
        int offset = page.offset();
        int size = page.pageSize();
        TypedQuery<Tuple> query = create(q, first);
        query.setFirstResult(offset);
        query.setMaxResults(size);
        List<Tuple> tuples = rows(q, first, query);
        long total;
        if (!tuples.isEmpty()) {
            total = inlineTotal(tuples.get(0));
        } else if (offset == 0) {
            total = 0;
        } else {
            logInlineCountFallback(q, "the page is past the end: it read no row at offset " + offset);
            total = countRows(q, counting);
        }
        List<Loaded<M>> rows;
        if (primaryKeyFirst(q, offset)) {
            PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(); // primaryKeyFirst(...) needs one (MQ1201)
            boolean uniqueParents = q.fetch().filter(DefaultModelQueryExecutor::hasFetchWork).isPresent();
            rows = readByKeys(q, key, keysOf(q, key, first, tuples), batch, uniqueParents);
        } else {
            rows = new ArrayList<>(tuples.size());
            for (Tuple tuple : tuples) {
                rows.add(loaded(first, tuple));
            }
        }
        List<M> content = models(q, rows);
        warnOfContradictedTotal(q, offset, content.size(), total);
        return new Slice<>(content, page.pageNumber(), size, offset + (long) content.size() < total,
                OptionalLong.of(total));
    }

    @Override
    public <M> KeysetSlice<M> page(ModelQuery<E, ?, M> q, KeysetSpec keyset) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(keyset, "keyset");
        refuseLock(q, "page(query, KeysetSpec)");
        if (!q.isKeyset()) {
            // R-PAG-16: a keyset page needs a keyset() query, before any query runs (MQ2207).
            throw new ModelQueryExecutionException(MqCode.MQ2207, q + ": page(query, KeysetSpec) needs a keyset() "
                    + "query; add keyset() or page by PageSpec");
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL, renderOptions);
        // Before any query, in R-PAG-16/R-PAG-23's order: the to-many selection, then the first-run phase check
        // (R-PAG-15) and the fetch plan's own check, so MQ2207 and MQ2204 precede MQ2206 as the design orders them.
        refuseToManySelection(q, built, "keyset paging");
        checkFirstRun(q, true, StatementShape.CONTENT, true, false);
        PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(() -> new ModelQueryExecutionException(MqCode.MQ2203,
                q + ": a keyset page needs a primary key to order and identify its rows"));
        Keyset<M> forward = Keyset.of(q, key, vendor.profile().defaultAscendingNullOrdering(), providerNulls,
                keysetNullKeys);
        forward.unsupportedColumn().ifPresent(column -> {
            // A key type no codec carries is refused before any query runs (R-PAG-17, MQ2210).
            throw new ModelQueryExecutionException(MqCode.MQ2210, q + ": keyset column " + column.path() + " of type "
                    + column.attributeType().getName() + " cannot be carried in a cursor");
        });
        if (keyset.hasCursor() && !Arrays.equals(forward.fingerprint(), keyset.fingerprint())) {
            // Another sort, treat, direction, precedence, entity or deployment: refused before querying (R-PAG-19).
            throw new ModelQueryExecutionException(MqCode.MQ2209, q + ": the keyset cursor belongs to another order "
                    + "(sort, treat, direction, null precedence, entity or deployment changed); start again with "
                    + "KeysetSpec.first");
        }
        Object[] cursor = keyset.hasCursor() ? forward.resolve(keyset.values(), q) : null;
        Object cursorKey = cursor == null ? null : forward.primaryKeyOf(cursor);
        boolean before = keyset.direction() == KeysetSpec.Direction.BEFORE;
        // before reads the same rows from the other side, in flipped order, and reverses them below (R-PAG-20).
        Keyset<M> readKeyset = before ? forward.reversed() : forward;
        Keyset.Beyond after = null;
        if (cursor != null) {
            // The cursor predicate joins the query's own restriction.
            after = readKeyset.after(cursor, built.joins(), cb);
            Predicate own = built.query().getRestriction();
            built.query().where(own == null ? after.predicate() : cb.and(own, after.predicate()));
        }
        readKeyset.applyOrder(built, cb);
        if (preflights(q)) {
            // This page, then the shape of each later page it can lead to, either way, before this page runs.
            preflight(q, "keyset page", StatementShape.CONTENT, built);
            preflightLaterPages(q, forward, true, "later keyset page");
            preflightLaterPages(q, forward.reversed(), true, "later keyset page read before its cursor");
        }
        int size = keyset.size();
        // One row beyond the page tells whether another follows (R-PAG-21); size + 1 fits an int (KeysetSpec).
        TypedQuery<Tuple> query = create(q, em, built.query(), readKeyset, cursor, built.joins());
        if (after != null) {
            after.bindTo(query);
        }
        query.setMaxResults(size + 1);
        List<Tuple> rows = rows(q, built, query);
        List<Row> decoded = new ArrayList<>(rows.size());
        for (Tuple tuple : rows) {
            decoded.add(built.selection().row(tuple));
        }
        // The null rule is read on every row, the look-ahead included (R-PAG-05, R-PAG-22).
        for (Row row : decoded) {
            forward.cursor(row);
        }
        boolean lookAhead = decoded.size() > size;
        List<Row> kept = lookAhead ? decoded.subList(0, size) : decoded;
        if (before) {
            // Drop the furthest look-ahead row, then reverse the rest into the query's order (R-PAG-20).
            kept = new ArrayList<>(kept);
            Collections.reverse(kept);
        }
        if (filterOnlyToManyJoin(built).isEmpty()) {
            checkAdjacentKeys(q, kept.stream().map(row -> Keys.keyOf(q, key, row)).toList());
        }
        Set<Object> seen = new HashSet<>();
        List<Loaded<M>> fresh = new ArrayList<>(kept.size());
        for (Row row : kept) {
            Object rowKey = Keys.keyOf(q, key, row);
            if (cursorKey != null && cursorKey.equals(rowKey)) {
                // The cursor did not survive being bound, or the boundary row moved (R-PAG-24, D-110).
                throw new ModelQueryExecutionException(MqCode.MQ2205, q + ": a keyset page holds the cursor's own "
                        + "primary key: a cursor value did not survive being bound, or the boundary row moved");
            }
            // A customizer's to-many join, or one R-EXE-16 leaves in the statement, repeats its root within the page,
            // and that repeat is dropped (R-PAG-02).
            if (seen.add(rowKey)) {
                fresh.add(new Loaded<>(built.map(row), row));
            }
        }
        List<M> content = models(q, fresh);
        // The flags need no second statement (R-PAG-21); an empty page has neither cursor (R-PAG-21).
        Optional<String> previous = Optional.empty();
        Optional<String> next = Optional.empty();
        if (!content.isEmpty()) {
            // A flag's cursor is the boundary row's, encoded once: the previous row of a `before` page and of an
            // `after` page, and the next row when a look-ahead row was read (R-PAG-20, R-PAG-21).
            boolean hasPrevious = before ? lookAhead : cursor != null;
            boolean hasNext = before || lookAhead;
            String firstCursor = hasPrevious ? forward.encode(q, fresh.get(0).row()) : null;
            String lastCursor = hasNext ? forward.encode(q, fresh.get(fresh.size() - 1).row()) : null;
            if (before) {
                previous = hasPrevious ? Optional.of(firstCursor) : Optional.empty();
                next = Optional.of(lastCursor);
            } else if (cursor != null) {
                previous = Optional.of(firstCursor);
                next = hasNext ? Optional.of(lastCursor) : Optional.empty();
            } else {
                next = hasNext ? Optional.of(lastCursor) : Optional.empty();
            }
        }
        return KeysetSlice.of(content, size, previous, next);
    }

    @Override
    public long count(ModelQuery<E, ?, ?> q) {
        Objects.requireNonNull(q, "q");
        refuseLock(q, "count(query)");
        Optional<String> clientSide = checkFirstRun(q, false);
        LOG.log(DEBUG, () -> "count " + q);
        warnOfIgnoredOrder(q, "count(query)");
        return countRows(q, clientSide);
    }

    @Override
    public <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(pageTransformer, "pageTransformer");
        Objects.requireNonNull(sink, "sink");
        refuseLock(q, "export(...)");
        checkFirstRun(q, true, StatementShape.CONTENT, q.isKeyset(), false);
        refuseNonUniqueSource(q, "export(...)");
        long limit = options.limit().maxRows().isPresent() ? options.limit().maxRows().getAsInt() : Long.MAX_VALUE;
        int pageSize = options.pageSize().orElse(exportPageSize);
        LOG.log(DEBUG, () -> "export " + q + ": " + (q.isGrouped() ? "grouped offset" : q.isKeyset() ? "keyset"
                : "offset") + ", pageSize " + pageSize + ", " + options.limit());
        if (q.isGrouped()) {
            // A group has no row identity, so its group keys order and dedupe the pages in place of a primary key,
            // which a grouped query never has; keyset() and primaryKeyFirst(...) were refused at build (MQ1402), so
            // the export is offset-only (R-PAG-11, R-PAG-12, R-AGG-09). A group-key tuple is unique per result row
            // even through a to-many join, so a grouped export skips the MQ2204 refusal (R-PAG-13).
            BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
            appendStableOrder(q, built);
            return exportByOffset(q, built, null, row -> groupKeyOf(q, row), pageSize, limit, pageTransformer,
                    sink);
        }
        // A keyset query always has a primary key (MQ1201 at build time), so only offset export can fail here.
        PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(() -> new ModelQueryExecutionException(MqCode.MQ2203,
                q + ": offset export needs a primary key to order and dedupe its pages, and primaryKey(...) was "
                        + "not set"));
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        refuseToManySelection(q, built, q.isKeyset() ? "keyset paging" : "offset export");
        if (q.isKeyset()) {
            return exportByKeyset(q, built, key, pageSize, limit, pageTransformer, sink);
        }
        appendStableOrder(q, built);
        return exportByOffset(q, built, key, row -> Keys.keyOf(q, key, row), pageSize, limit, pageTransformer,
                sink);
    }

    @Override
    public long update(ModelUpdate<E, ?> u) {
        return update(u, null);
    }

    @Override
    public <R> List<R> updateReturning(ModelUpdate<E, ?> u, ModelQuery<E, ?, R> returning) {
        Objects.requireNonNull(u, "u");
        Objects.requireNonNull(returning, "returning");
        if (u.entityMode()) {
            throw returningRefused(WriteStatement.UPDATE, "throughEntities()");
        }
        Returning<R> rows = returning(u.chunkOptions(), returning, WriteStatement.UPDATE);
        update(u, rows);
        return rows.models();
    }

    @Override
    public <R> List<R> insertReturning(ModelInsert<E, ?> i, ModelQuery<E, ?, R> returning) {
        Objects.requireNonNull(i, "i");
        Objects.requireNonNull(returning, "returning");
        // The insert has no throughEntities() form, so R-WRT-50's refusal of it does not arise here
        Returning<R> rows = returning(i.chunkOptions(), returning, WriteStatement.INSERT);
        insert(i, rows);
        return rows.models();
    }

    /** {@link #update(ModelUpdate)}, collecting the changed rows into {@code returning} when it is given (R-WRT-50). */
    private long update(ModelUpdate<E, ?> u, Returning<?> returning) {
        Objects.requireNonNull(u, "u");
        checkWriteOnce(u, metamodel -> {
            u.checkMetamodel(metamodel);
            // A to-one read by its id anywhere in the statement: MQ1004 unless its foreign key renders with no join
            // (D-136)
            u.checkForeignKeys(em.getCriteriaBuilder(), renderOptions, em::getReference, this::foreignKeyColumn);
        });
        // Each run: the renderer is this executor's, and the refusal comes before the flush or a key select (R-WRT-53)
        u.checkSequences(renderOptions);
        WriteAssignments assignments = writeAssignments();
        requireTransaction(u.entityMode() ? "an entity-mode update" : "a bulk update", u.chunkOptions());
        if (u.writesNothing()) {
            return 0; // never a write just for the write assignments (R-WRT-49)
        }
        Set<String> set = u.assignments().stream().map(assignment -> assignment.column().name())
                .collect(Collectors.toSet());
        List<WriteAssignments.Written> added = assignments.execution().values(WriteKind.UPDATE, set);
        if (u.entityMode()) {
            return updateEntities(u, added); // never a returning write: it refused throughEntities() already
        }
        long written = updateRows(u, added, returning);
        if (written == 0 && u.expectedVersion().isPresent()) {
            throw new OptimisticLockException(rootEntity.getSimpleName() + ": no row was written with version "
                    + u.expectedVersion().get() + "; its version moved, or it no longer exists or matches");
        }
        return written;
    }

    /** Runs a bulk update, each statement setting {@code added}, the write assignments, after its own (R-WRT-49). */
    private <M> long updateRows(ModelUpdate<E, M> u, List<WriteAssignments.Written> added, Returning<?> returning) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        BiFunction<Class<?>, Object, ?> references = em::getReference;
        int repeated = u.repeatedExpressionBinds(cb, renderOptions, references);
        Supplier<Query> whole = () -> updateStatement(em, u.buildWrite(cb, renderOptions, references), added);
        Function<List<Object>, Query> byKeys =
                chunk -> updateStatement(em, u.buildWrite(cb, renderOptions, references, chunk), added);
        boolean rootTermsOnly = keyFirst(() -> u.entitiesReadInSubquery(cb, renderOptions));
        logWrite("update", u, u.chunkOptions(), rootTermsOnly);
        if (!rootTermsOnly && u.chunkOptions().isEmpty()) {
            return write(u.persistenceContext(),
                    () -> direct(u.distinctKeys(), whole, byKeys, repeated, run(returning)));
        }
        var keyed = new KeysetWrite.Keyed<>(rootEntity.getSimpleName(), u.primaryKey(), u.distinctKeys(),
                run -> run == null ? u.buildKeySelect(cb, renderOptions) : u.buildKeySelect(cb, renderOptions, run),
                (on, keys) -> execute(updateStatement(on, u.buildWrite(cb, renderOptions, on::getReference, keys,
                        rootTermsOnly), added), repeated, returning),
                u.startAfter(), u::modelKey);
        return write(u.persistenceContext(), () -> keyset(keyed, whole, byKeys, u.chunkOptions(), cb, repeated));
    }

    /**
     * {@code update} with a plain {@code set} of each of {@code added}'s values, after its own (R-WRT-49). Under
     * inline value handling a value other than {@code null} is an explicit parameter by the value rule, typed by the
     * attribute's mapping, else by its class, which only a mapping-independent value takes, else {@code MQ1502} naming
     * the assignment, before the statement runs; each such parameter is recorded in {@code assigned} for the
     * statement to bind (R-FLT-08, D-177).
     */
    private static <E> CriteriaUpdate<E> assigning(CriteriaUpdate<E> update, List<WriteAssignments.Written> added,
            JoinContext joins, CriteriaBuilder cb, Map<ParameterExpression<?>, Object> assigned) {
        ParameterSink sink = new ParameterSink() {
            @Override
            public boolean binds(ParameterExpression<?> parameter) {
                return assigned.containsKey(parameter) || joins.statementParameters().containsKey(parameter);
            }

            @Override
            public void add(ParameterExpression<?> parameter, Object value) {
                assigned.put(parameter, value);
            }
        };
        for (WriteAssignments.Written written : added) {
            Path<?> path = path(update.getRoot(), written.path());
            if (written.value() == null || !joins.renderOptions().inlinesValues()) {
                setValue(update, path, written.value());
            } else {
                setTo(update, path, ValueOperands.valueOperand(joins.renderOptions(), cb, path, written.type(),
                        written.value(), update.getRoot().getJavaType().getSimpleName() + "." + written.path()
                                + ": the configured write assignment under inline value handling", sink));
            }
        }
        return update;
    }

    @SuppressWarnings("unchecked")
    private static <Y> void setTo(CriteriaUpdate<?> update, Path<Y> path, Expression<?> value) {
        update.set(path, (Expression<? extends Y>) value);
    }

    @SuppressWarnings("unchecked")
    private static <Y> void setValue(CriteriaUpdate<?> update, Path<Y> path, Object value) {
        update.set(path, (Y) value);
    }

    /** The path of {@code attribute}, dotted through embeddables, below {@code root}. */
    private static Path<?> path(Path<?> root, String attribute) {
        Path<?> path = root;
        for (String segment : attribute.split("\\.")) {
            path = path.get(segment);
        }
        return path;
    }

    /**
     * Runs an update {@code throughEntities()}: each round selects its keys as a chunked update does, then loads,
     * assigns and flushes the round's entities (R-WRT-41, R-WRT-42, R-WRT-17), as {@link #entityRounds} runs them.
     * Each entity gets {@code added}, the write assignments, after the definition's values, so every matched row is
     * dirty and an entity callback setting the same attribute at the flush wins (R-WRT-49). The values are set through
     * the provider's {@link EntityWriteSupport} when it has one, on the instance behind a proxy the load returned
     * (R-WRT-42, D-119).
     */
    private <M> long updateEntities(ModelUpdate<E, M> u, List<WriteAssignments.Written> added) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        List<String> attributes = u.assignedAttributes();
        List<Object> values = u.assignedValues();
        if (!added.isEmpty()) {
            attributes = new ArrayList<>(attributes);
            values = new ArrayList<>(values);
            for (WriteAssignments.Written written : added) {
                attributes.add(written.path());
                values.add(written.value());
            }
        }
        List<String> assigned = attributes;
        List<Object> assignedTo = values;
        EntityType<E> root = em.getMetamodel().entity(rootEntity);
        EntityWriteSupport writes = vendor.providerSupport().flatMap(ProviderSupport::entityWrites).orElse(null);
        return entityWrite("update", u, u.primaryKey(), u.distinctKeys(), u.chunkOptions(), u.persistenceContext(),
                u.startAfter(), u::modelKey, run -> run == null ? u.buildKeySelect(cb, renderOptions)
                        : u.buildKeySelect(cb, renderOptions, run),
                keys -> u.buildEntityLoad(cb, renderOptions, keys),
                (on, entities) -> unproxied(on, entities, writes).forEach(entity ->
                        PersistedEntity.assign(on, root, entity, assigned, assignedTo, writes)));
    }

    /**
     * Runs a delete {@code throughEntities()}: each round selects its keys as a chunked delete does, then loads the
     * round's entities, removes each through the {@code EntityManager} and flushes, so cascades ({@code REMOVE},
     * {@code orphanRemoval}) and the mapping's {@code @SQLDelete} run, and a delete may remove more rows than the
     * entities it counts (R-WRT-41, R-WRT-42, R-WRT-43), as {@link #entityRounds} runs them.
     */
    private <M> long deleteEntities(ModelDelete<E, M> d) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        return entityWrite("delete", d, d.primaryKey(), d.distinctKeys(), d.chunkOptions(), d.persistenceContext(),
                d.startAfter(), d::modelKey, run -> run == null ? d.buildKeySelect(cb, renderOptions)
                        : d.buildKeySelect(cb, renderOptions, run),
                keys -> d.buildEntityLoad(cb, renderOptions, keys),
                (on, entities) -> entities.forEach(on::remove));
    }

    /**
     * The setup an update or delete {@code throughEntities()} shares: the {@code keySelect} tree, the binds its load
     * repeats, the persistence-context mode and the log line, then {@link #entityRounds} with {@code perRound}
     * applied to each round's loaded entities on that round's {@code EntityManager}.
     */
    private <M> long entityWrite(String operation, Object write, PrimaryKey<M, ?> key,
            Optional<List<Object>> distinctKeys, Optional<ChunkOptions> chunk,
            Optional<PersistenceContextMode> context, Optional<Object> startAfter, UnaryOperator<Object> modelKey,
            Function<List<Object>, BuiltQuery<M>> keySelect, Function<List<Object>, EntityLoad<E>> load,
            BiConsumer<EntityManager, List<E>> perRound) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        BuiltQuery<M> tree = keySelect.apply(null);
        // The load renders the tree the key select renders, so it repeats the same memoised expression binds.
        int repeated = tree.joins().repeatedExpressionBinds();
        PersistenceContextMode mode = context.orElse(persistenceContextMode);
        logWrite(operation + " throughEntities()", write, Optional.of(chunk.orElse(ChunkOptions.defaultSize())),
                false);
        var keyed = new KeysetWrite.Keyed<>(rootEntity.getSimpleName(), key, distinctKeys, keySelect,
                (on, keys) -> throughEntities(on, load.apply(keys), repeated, mode,
                        entities -> perRound.accept(on, entities)),
                startAfter, modelKey);
        return entityRounds(keyed, tree, load, chunk, mode, cb, repeated);
    }

    /** The Java classes of {@code on}'s factory's entity types, cached per factory. */
    private static Set<Class<?>> mappedEntities(EntityManager on) {
        return MAPPED_ENTITIES.computeIfAbsent(on.getEntityManagerFactory(), factory -> on.getMetamodel()
                .getEntities().stream().map(EntityType::getJavaType).collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * {@code entities}, a round's load on {@code on}, with each whose class is no mapped entity class, a proxy the
     * persistence context held for its row, replaced by the instance behind it through {@code writes}, all checked
     * before any is changed (R-WRT-42, D-119).
     *
     * @throws ModelQueryExecutionException {@code MQ2503} for a proxy when {@code writes} is null
     */
    private List<Object> unproxied(EntityManager on, List<E> entities, EntityWriteSupport writes) {
        Set<Class<?>> mapped = null;
        List<Object> targets = new ArrayList<>(entities.size());
        for (E entity : entities) {
            Class<?> type = entity.getClass();
            if (type != rootEntity) {
                if (mapped == null) {
                    mapped = mappedEntities(on);
                }
                if (!mapped.contains(type)) {
                    if (writes == null) {
                        throw new ModelQueryExecutionException(MqCode.MQ2503,
                                rootEntity.getSimpleName() + ": an update throughEntities() loaded a "
                                        + type.getName() + ", a proxy the persistence context held for the row, and "
                                        + "no ProviderSupport serving this persistence provider has an "
                                        + "EntityWriteSupport to unwrap it; add one (model-query-hibernate for "
                                        + "Hibernate), or write under PersistenceContextMode.CLEAR");
                    }
                    targets.add(writes.unproxy(entity));
                    continue;
                }
            }
            targets.add(entity);
        }
        return targets;
    }

    /**
     * Runs the rounds of an entity write: always chunked, in rounds of the {@code chunked} size, else the configured
     * {@code bulkWriteChunkSize}, within the profile's clamp, each round {@code keyed}'s key select and then its
     * {@link #throughEntities} write (R-WRT-41, R-WRT-42). Pending entity changes are flushed first and, under
     * {@code CLEAR}, the context cleared, so the first load returns no proxy it held (D-119); the second-level
     * cache is the provider's to maintain, so nothing is evicted (R-WRT-15, R-WRT-43). The query timeout applies to
     * the key selects and the loads, not to the flushes, which have no portable per-statement hint (R-WRT-45). A
     * failure, an {@code OptimisticLockException} included, reaches the caller as is, or, committing each chunk,
     * wrapped in {@code ChunkedWriteException} (R-WRT-46). Committing each chunk under {@code CLEAR}, the caller's
     * {@code EntityManager} is cleared once at the end, whether or not a round failed (R-WRT-45).
     *
     * @param tree the key select over every row the write chooses, whose binds {@code repeated} counts
     * @param load the load of one round's entities, which sizes the rounds within the bind limit
     */
    private <M> long entityRounds(KeysetWrite.Keyed<M> keyed, BuiltQuery<M> tree,
            Function<List<Object>, EntityLoad<E>> load, Optional<ChunkOptions> chunk, PersistenceContextMode mode,
            CriteriaBuilder cb, int repeated) {
        int size = chunk.map(options -> options.size().orElse(bulkWriteChunkSize)).orElse(bulkWriteChunkSize);
        if (em.isJoinedToTransaction()) {
            em.flush();
        }
        if (mode == PersistenceContextMode.CLEAR) {
            em.clear();
        }
        try {
            return keyset(keyed, () -> keySelectStatement(em, tree), keys -> loadStatement(em, load.apply(keys)), chunk,
                    OptionalInt.of(size), cb, repeated);
        } finally {
            // Each chunk ran on its own EntityManager, so the caller's copies of the written rows are stale: cleared
            // once, as a bulk write clears after its last statement (R-WRT-45).
            if (mode == PersistenceContextMode.CLEAR && chunk.map(ChunkOptions::commitsEachChunk).orElse(false)) {
                em.clear();
            }
        }
    }

    /**
     * One round of an entity write on {@code on}: loads the entities of {@code load} with one statement, applies
     * {@code write} to them, flushes, and returns how many it loaded, the rows matched; the provider writes only
     * those that changed (R-WRT-42, R-WRT-44). Afterwards, whether or not it threw, clears {@code on} under
     * {@code CLEAR}, so at most one round of entities is managed; under {@code KEEP} they stay managed (R-WRT-45).
     */
    private long throughEntities(EntityManager on, EntityLoad<E> load, int repeated, PersistenceContextMode mode,
            Consumer<List<E>> write) {
        String label = rootEntity.getSimpleName();
        try {
            TypedQuery<E> query = loadStatement(on, load);
            withinBindLimit(label, query.getParameters().size() + repeated, 0);
            queryTimeout.ifPresent(timeout -> vendor.profile().applyTimeout(query, timeout));
            List<E> entities = rows(label, query);
            write.accept(entities);
            on.flush();
            return entities.size();
        } finally {
            if (mode == PersistenceContextMode.CLEAR) {
                on.clear();
            }
        }
    }

    @Override
    public long delete(ModelDelete<E, ?> d) {
        return delete(d, null);
    }

    @Override
    public <R> List<R> deleteReturning(ModelDelete<E, ?> d, ModelQuery<E, ?, R> returning) {
        Objects.requireNonNull(d, "d");
        Objects.requireNonNull(returning, "returning");
        if (d.entityMode()) {
            throw returningRefused(WriteStatement.DELETE, "throughEntities()");
        }
        Returning<R> rows = returning(d.chunkOptions(), returning, WriteStatement.DELETE);
        delete(d, rows);
        return rows.models();
    }

    /** {@link #delete(ModelDelete)}, collecting the changed rows into {@code returning} when it is given (R-WRT-50). */
    private long delete(ModelDelete<E, ?> d, Returning<?> returning) {
        Objects.requireNonNull(d, "d");
        checkWriteOnce(d, metamodel -> {
            d.checkMetamodel(metamodel);
            // A to-one read by its id in the filter: MQ1004 unless its foreign key renders with no join (D-136)
            d.checkForeignKeys(em.getCriteriaBuilder(), renderOptions, this::foreignKeyColumn);
        });
        requireTransaction(d.entityMode() ? "an entity-mode delete" : "a bulk delete", d.chunkOptions());
        if (d.writesNothing()) {
            return 0;
        }
        return d.entityMode() ? deleteEntities(d) : deleteRows(d, returning);
    }

    private <M> long deleteRows(ModelDelete<E, M> d, Returning<?> returning) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        int repeated = d.repeatedExpressionBinds(cb, renderOptions);
        Supplier<Query> whole = () -> deleteStatement(em, d.buildWrite(cb, renderOptions));
        Function<List<Object>, Query> byKeys = chunk -> deleteStatement(em, d.buildWrite(cb, renderOptions, chunk));
        boolean rootTermsOnly = keyFirst(() -> d.entitiesReadInSubquery(cb, renderOptions));
        logWrite("delete", d, d.chunkOptions(), rootTermsOnly);
        if (!rootTermsOnly && d.chunkOptions().isEmpty()) {
            return write(d.persistenceContext(),
                    () -> direct(d.distinctKeys(), whole, byKeys, repeated, run(returning)));
        }
        var keyed = new KeysetWrite.Keyed<>(rootEntity.getSimpleName(), d.primaryKey(), d.distinctKeys(),
                run -> run == null ? d.buildKeySelect(cb, renderOptions) : d.buildKeySelect(cb, renderOptions, run),
                (on, keys) -> execute(deleteStatement(on, d.buildWrite(cb, renderOptions, keys, rootTermsOnly)),
                        repeated, returning),
                d.startAfter(), d::modelKey);
        return write(d.persistenceContext(), () -> keyset(keyed, whole, byKeys, d.chunkOptions(), cb, repeated));
    }

    /**
     * The rows a returning write collects, after R-WRT-50's refusals, all before any statement: {@code
     * commitEachChunk()} (MQ1809), the returning query's clauses, which are checked as R-WRT-48 checks them, the
     * factory's ability to return rows for {@code kind} at all (MQ4011), and the returning columns against the
     * written table (MQ1809, D-132). A {@code throughEntities()} write is refused
     * by its own caller, before this.
     */
    private <R> Returning<R> returning(Optional<ChunkOptions> chunk, ModelQuery<E, ?, R> returning,
            WriteStatement kind) {
        if (chunk.map(ChunkOptions::commitsEachChunk).orElse(false)) {
            throw returningRefused(kind, "commitEachChunk()");
        }
        checkWriteOnce(returning, metamodel -> returning.checkReturning(em.getCriteriaBuilder(), renderOptions));
        ReturningShape shape = vendor.profile().returning(kind).orElse(null);
        ReturningSupport support = vendor.providerSupport().flatMap(ProviderSupport::returning).orElse(null);
        if (shape == null) {
            throw cannotReturn(kind,
                    "the vendor profile reports no returning shape for it (VendorProfile#returning)");
        }
        if (support == null) {
            throw cannotReturn(kind, returningSupportAbsence());
        }
        if (!support.rendered(em.getEntityManagerFactory(), rootEntity, shape)) {
            throw cannotReturn(kind, "provider " + support.getClass().getName() + " cannot return rows for "
                    + rootEntity.getSimpleName() + " (ReturningSupport#rendered)");
        }
        List<String> asked = returning.returningPaths();
        List<String> paths = throughIds(asked);
        // A column off the written table is MQ1809 now, not when the statement runs after the flush (D-132)
        support.check(em.getEntityManagerFactory(), rootEntity, paths);
        return new Returning<>(support, rootEntity, returning, asked, paths, shape);
    }

    /**
     * {@code asked}, each root to-one, which {@code checkReturning} accepts only read by its id, as the path to its
     * target's id ({@code referrer.id}), which the provider maps to the foreign-key column (R-WRT-48, R-COL-29). It
     * relies on {@code checkReturning} having run first, which refused every other to-one.
     */
    private List<String> throughIds(List<String> asked) {
        EntityType<E> root = em.getMetamodel().entity(rootEntity);
        return asked.stream().map(path -> path.indexOf('.') < 0
                && root.getAttribute(path) instanceof SingularAttribute<?, ?> toOne && toOne.isAssociation()
                && toOne.getType() instanceof IdentifiableType<?> target
                ? path + "." + target.getId(target.getIdType().getJavaType()).getName() : path).toList();
    }

    /**
     * The provider's answer how the to-one {@code toOne} of {@code entity} renders by its target's id, unknown when no
     * {@link ProviderSupport} serves this factory (R-COL-29, D-136).
     */
    private ForeignKeyLookup.Answer foreignKeyColumn(Class<?> entity, String toOne) {
        return vendor.providerSupport()
                .map(support -> switch (support.foreignKeyColumn(em.getEntityManagerFactory(), entity, toOne)) {
                    case LOCAL_COLUMN -> ForeignKeyLookup.Answer.LOCAL_COLUMN;
                    case JOINS -> ForeignKeyLookup.Answer.JOINS;
                    case UNKNOWN -> ForeignKeyLookup.Answer.UNKNOWN;
                }).orElse(ForeignKeyLookup.Answer.UNKNOWN);
    }

    /** The {@code MQ1809} refusal of a returning write of {@code kind} combined with {@code clause} (R-WRT-50). */
    private static ModelQueryDefinitionException returningRefused(WriteStatement kind, String clause) {
        return new ModelQueryDefinitionException(MqCode.MQ1809, kind + " returning cannot combine with " + clause
                + ": the rows of a callback's own writes, or of a committed chunk, are not this statement's");
    }

    /** The {@code MQ4011} refusal of a returning write of {@code kind}, for the reason {@code because} (R-VND-17). */
    private ModelQueryExecutionException cannotReturn(WriteStatement kind, String because) {
        return new ModelQueryExecutionException(MqCode.MQ4011, rootEntity.getSimpleName() + ": " + kind
                + " returning cannot run on " + vendor.detectedVendor() + ", because " + because);
    }

    /** Why {@code MQ4011} names for a factory whose provider offers no {@link ReturningSupport}. */
    private String returningSupportAbsence() {
        return vendor.providerSupport()
                .map(support -> "provider " + support.getClass().getName() + " has no ReturningSupport "
                        + "(ProviderSupport#returning())")
                .orElse("no ProviderSupport serves this factory; add a ProviderSupport for its provider "
                        + "(model-query-hibernate for Hibernate)");
    }

    /**
     * The rows one returning write's statements returned, concatenated in statement order, and the models they map to
     * (R-WRT-50). The provider runs each statement and decides its column order; the executor holds the paths it asked
     * for and maps every row back by path, as R-WRT-48's returning query maps a row.
     */
    private static final class Returning<R> {

        private final ReturningSupport support;
        private final Class<?> entity;
        private final ModelQuery<?, ?, R> query;
        private final List<String> paths;
        private final Map<String, Integer> indexes = new HashMap<>();
        private final ReturningShape shape;
        private final List<Object[]> rows = new ArrayList<>();

        /**
         * {@code paths} are the provider's, each in place of the query's path at its index in {@code asked}, by which
         * a row maps back (R-COL-29).
         */
        Returning(ReturningSupport support, Class<?> entity, ModelQuery<?, ?, R> query, List<String> asked,
                List<String> paths, ReturningShape shape) {
            this.support = support;
            this.entity = entity;
            this.query = query;
            this.paths = paths;
            this.shape = shape;
            for (int p = 0; p < asked.size(); p++) {
                indexes.putIfAbsent(asked.get(p), p);
            }
        }

        /** The rows {@code statement} changed, through the provider, counted for the write's own totals. */
        long run(Query statement) {
            List<Object[]> returned = support.rows(statement, entity, paths, shape);
            rows.addAll(returned);
            return returned.size();
        }

        /** The models of every row collected so far, one per row, in the statements' order. */
        List<R> models() {
            List<R> models = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                models.add(query.mapReturning(path -> value(path, row)));
            }
            return List.copyOf(models);
        }

        /**
         * {@code path}'s value in {@code row}.
         *
         * @throws IllegalStateException for a path the returning query did not ask the provider for
         */
        private Object value(String path, Object[] row) {
            Integer index = indexes.get(path);
            if (index == null) {
                throw new IllegalStateException(path + " is not among the returning paths " + paths);
            }
            return row[index];
        }
    }

    @Override
    public long insert(ModelInsert<E, ?> i) {
        Objects.requireNonNull(i, "i");
        return insert(i, null);
    }

    /** {@link #insert(ModelInsert)}, collecting the written rows into {@code returning} when it is given (R-WRT-50). */
    private long insert(ModelInsert<E, ?> i, Returning<?> returning) {
        checkTreats(i);
        InsertSupport support = insertSupport(i);
        WriteAssignments assignments = writeAssignments();
        Map<String, Function<CriteriaBuilder, Expression<?>>> computed = checkInsertOnce(i, support, assignments);
        requireTransaction("a bulk insert", i.chunkOptions());
        if (i.sourceEntity().isPresent()) {
            return insertSelect(i, support, assignments, returning);
        }
        return insertValues(i, support, null, assignments, computed, returning);
    }

    /**
     * Runs an insert-select: its source select built as the read path builds it, then written as one statement, or
     * with {@code chunked} key-first over the distinct source-root ids, so each source row is read once; the
     * persistence context is flushed before and cleared after, as for any bulk write (R-WRT-27, R-WRT-28, R-WRT-38).
     * The write assignments the definition leaves are selected as constants after its own (R-WRT-49).
     */
    private long insertSelect(ModelInsert<E, ?> i, InsertSupport support, WriteAssignments assignments,
            Returning<?> returning) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        List<String> attributes = i.selectedAttributes(em.getMetamodel());
        Map<String, Object> constants = i.selectParameters();
        List<WriteAssignments.Written> added = assignments.execution().values(WriteKind.INSERT, attributes);
        if (!added.isEmpty()) {
            attributes = new ArrayList<>(attributes);
            constants = new LinkedHashMap<>(constants);
            for (int a = 0; a < added.size(); a++) {
                attributes.add(added.get(a).path());
                constants.put(ASSIGNED + a, added.get(a).value());
            }
        }
        List<String> inserted = attributes;
        Metamodel metamodel = em.getMetamodel();
        BuiltQuery<?> select = i.buildSelect(cb, renderOptions, metamodel);
        Map<String, Object> bound = asParameterTypes(constants, select.query());
        int repeated = select.joins().repeatedExpressionBinds();
        // The select's Expr.value parameters bind on the statement too (R-COL-26).
        InsertStatement statement = (on, built, source) -> {
            Query insert = support.insertSelect(on, rootEntity, inserted, source, bound);
            bindValues(insert, built.joins(), source.getParameters());
            return insert;
        };
        CriteriaQuery<Tuple> wholeSource = selecting(select.query(), cb, added);
        Supplier<Query> whole = () -> statement.apply(em, select, wholeSource);
        logWrite("insert", i, i.chunkOptions(), false);
        if (i.chunkOptions().isEmpty()) {
            return write(i.persistenceContext(), () -> execute(whole.get(), repeated, returning));
        }
        PrimaryKey<Object, ?> key = i.sourceKey(metamodel);
        BiFunction<EntityManager, List<Object>, Query> byKeys = (on, keys) -> {
            BuiltQuery<?> chunk = i.buildSelect(cb, renderOptions, metamodel, key, keys);
            return statement.apply(on, chunk, selecting(chunk.query(), cb, added));
        };
        // The source keys are attribute values of plain id columns, which is what the exception reports (R-WRT-32).
        var keyed = new KeysetWrite.Keyed<>(rootEntity.getSimpleName(), key, Optional.empty(),
                run -> i.buildKeySelect(cb, renderOptions, key),
                (on, keys) -> execute(byKeys.apply(on, keys), repeated, returning), Optional.empty(),
                sourceKey -> sourceKey);
        return write(i.persistenceContext(), () -> keyset(keyed, whole, keys -> byKeys.apply(em, keys),
                i.chunkOptions(), cb, repeated));
    }

    /** An insert-select statement over a built select and its source query. */
    @FunctionalInterface
    private interface InsertStatement {
        Query apply(EntityManager on, BuiltQuery<?> built, CriteriaQuery<Tuple> source);
    }

    /**
     * {@code constants} with each plain {@code java.util.Date} made the {@code java.sql} type its parameter in
     * {@code select} is typed as, which a provider may require of the value it binds (Hibernate 7 does, D-122).
     */
    private static Map<String, Object> asParameterTypes(Map<String, Object> constants, CriteriaQuery<?> select) {
        var bound = new LinkedHashMap<>(constants);
        for (ParameterExpression<?> parameter : select.getParameters()) {
            if (bound.get(parameter.getName()) instanceof Date date) {
                Class<?> type = parameter.getParameterType();
                long time = date.getTime();
                bound.put(parameter.getName(), type == java.sql.Date.class && !(date instanceof java.sql.Date)
                        ? new java.sql.Date(time)
                        : type == Time.class && !(date instanceof Time) ? new Time(time)
                        : type == Timestamp.class && !(date instanceof Timestamp) ? new Timestamp(time) : date);
            }
        }
        return bound;
    }

    /**
     * {@code source}, a new source select, selecting each of {@code added} last, as a parameter named after its index,
     * not a literal, which a provider may inline (R-WRT-14, R-WRT-49).
     */
    private static CriteriaQuery<Tuple> selecting(CriteriaQuery<Tuple> source, CriteriaBuilder cb,
            List<WriteAssignments.Written> added) {
        if (added.isEmpty()) {
            return source;
        }
        Selection<?> own = source.getSelection();
        var selected = new ArrayList<Selection<?>>(own.isCompoundSelection() ? own.getCompoundSelectionItems()
                : List.of(own));
        for (int a = 0; a < added.size(); a++) {
            selected.add(cb.parameter(added.get(a).type(), ASSIGNED + a));
        }
        return source.multiselect(selected);
    }

    @Override
    public <K> List<K> insertReturningKeys(ValuesInsert<E, K, ?> i) {
        Objects.requireNonNull(i, "i");
        checkTreats(i);
        i.checkKeysReturnable();
        InsertSupport support = insertSupport(i);
        WriteAssignments assignments = writeAssignments();
        Map<String, Function<CriteriaBuilder, Expression<?>>> computed = checkInsertOnce(i, support, assignments);
        InsertChecks.checkKeysGenerated(i, insertTarget(support).id());
        requireTransaction("a bulk insert", i.chunkOptions());
        var keys = new ArrayList<Object>(i.rowCount());
        insertValues(i, support, keys, assignments, computed, null);
        return keys.stream().map(i.keyType()::cast).toList();
    }

    /**
     * Runs an insert-values: its rows in list order, in statements of the most rows that stay within the bind limit
     * and the profile's {@code VALUES} limit, capped by the chunk size; each in the caller's transaction, or with
     * {@code commitEachChunk()} in its own. A sequence, table or UUID id is drawn from the generator before each
     * statement, written as a value and added to {@code keys} when given. A conflict clause goes in every statement.
     * An empty list runs no SQL, not even the flush; otherwise the persistence context is flushed before and cleared
     * after, as for any bulk write (R-WRT-26, R-WRT-29, R-WRT-32, R-WRT-33, R-WRT-34, R-WRT-38). Each row writes the
     * {@code INSERT} write assignments the definition leaves after its own values, and a {@code doUpdate} the
     * {@code UPDATE} ones, each supplier called once for the whole insert (R-WRT-49). {@code computed} is the
     * definition's computed values, built by {@link #checkInsertOnce} for this execution (R-WRT-53).
     */
    private long insertValues(ModelInsert<E, ?> i, InsertSupport support, List<Object> keys,
            WriteAssignments assignments, Map<String, Function<CriteriaBuilder, Expression<?>>> computed,
            Returning<?> returning) {
        int rows = i.rowCount();
        if (rows == 0) {
            return 0;
        }
        EntityManagerFactory emf = em.getEntityManagerFactory();
        List<String> attributes = new ArrayList<>(i.valueAttributes(em.getMetamodel()));
        // computed: rendered in each row by the provider, binding nothing, so not among the attributes a row fills
        WriteAssignments.Execution execution = assignments.execution();
        List<Object> added = new ArrayList<>();
        var own = new ArrayList<>(attributes);
        own.addAll(computed.keySet());
        for (WriteAssignments.Written written : execution.values(WriteKind.INSERT, own)) {
            attributes.add(written.path());
            added.add(written.value());
        }
        Map<String, Object> updated = Map.of();
        if (conflictUpdates(i)) {
            // Only those the clause does not assign itself, so no other supplier is called
            List<String> paths = assignments.paths(WriteKind.UPDATE);
            if (!paths.isEmpty()) {
                Set<String> set = new HashSet<>(paths);
                i.conflictUpdateAdds(set).forEach(set::remove);
                updated = WriteAssignments.byPath(execution.values(WriteKind.UPDATE, set));
            }
        }
        boolean drawn = InsertChecks.drawsKeys(insertTarget(support).id());
        if (drawn) {
            attributes.add(idAttribute());
        }
        int providerBinds = support.providerBindsPerRow(emf, rootEntity);
        int conflictRepeatedBinds = i.conflictRepeatedBinds(updated.keySet());
        int perStatement = rowsPerStatement(i, attributes.size() + providerBinds, i.conflictBinds(updated.keySet()));
        Map<String, Object> written = updated;
        ValuesWrite.Statement statement = (on, from, to) -> {
            // A clause per statement: it keeps the parameters of the where it renders for this statement alone.
            InsertConflict<E> clause = i.hasConflict() ? new InsertConflict<>(i, renderOptions, written) : null;
            Optional<ConflictClause<E>> conflict = Optional.ofNullable(clause);
            List<List<Object>> values = i.valueRows(from, to);
            values.forEach(row -> row.addAll(added));
            if (drawn) {
                List<Object> drawnKeys = support.generateKeys(on, rootEntity, to - from);
                for (int r = 0; r < values.size(); r++) {
                    values.get(r).add(drawnKeys.get(r));
                }
                if (keys != null) {
                    keys.addAll(drawnKeys);
                }
            }
            Query insert = support.insertValues(on, rootEntity, attributes, values, computed, conflict);
            if (clause != null) {
                clause.bind(insert); // the where's values, statement parameters under inline value handling
            }
            return Math.toIntExact(execute(insert, providerBinds * (to - from) + conflictRepeatedBinds, returning));
        };
        boolean perChunk = i.chunkOptions().map(ChunkOptions::commitsEachChunk).orElse(false);
        logWrite("insert", i, i.chunkOptions(), false);
        return write(i.persistenceContext(), () -> new ValuesWrite(rootEntity.getSimpleName(), em,
                perChunk ? chunkTransactions : null).run(rows, perStatement, statement));
    }

    /**
     * The most rows one insert-values statement takes: as many as stay within the bind limit at {@code bindsPerRow}
     * each, the columns, the {@code set} constants, the write assignments, a drawn id and the provider's own binds
     * such as the version seed, once the conflict clause's {@code conflictBinds} are bound, and within the profile's
     * {@code maxValuesRows()}, capped by the chunk size, the given or else the configured one, when chunked; at least
     * one, which {@code execute} refuses with {@code MQ1307} should it alone pass the bind limit (R-WRT-29, R-WRT-49,
     * D-80).
     */
    private int rowsPerStatement(ModelInsert<E, ?> i, int bindsPerRow, int conflictBinds) {
        int free = Math.max(0, renderOptions.maxBindParameters() - conflictBinds);
        int rows = Math.min(free / Math.max(1, bindsPerRow), vendor.profile().maxValuesRows());
        if (i.chunkOptions().isPresent()) {
            rows = Math.min(rows, i.chunkOptions().get().size().orElse(bulkWriteChunkSize));
        }
        return Math.max(1, rows);
    }

    /** The root's one id attribute, which a drawn key is written to: a generated id is never composite (R-WRT-26). */
    private String idAttribute() {
        List<String> ids = em.getMetamodel().entity(rootEntity).getSingularAttributes().stream()
                .filter(SingularAttribute::isId).map(Attribute::getName).toList();
        if (ids.size() != 1) {
            throw new IllegalStateException(rootEntity.getSimpleName() + ": a drawn key needs one id attribute, "
                    + "and the metamodel reports " + ids);
        }
        return ids.get(0);
    }

    @Override
    public <R> R persist(ModelPersist<E, ?, ?> persist, ModelQuery<E, ?, R> returning) {
        Objects.requireNonNull(persist, "persist");
        Objects.requireNonNull(returning, "returning");
        // Keyed by the query itself, which the caller holds, so the weak set keeps the entry while it lives
        checkWriteOnce(returning, metamodel -> returning.checkReturning(em.getCriteriaBuilder(), renderOptions));
        // Read from the managed entity, a to-one's id without initializing it: through an INNER join field whose
        // foreign key is null the column is null, where a read would drop the row (R-WRT-48)
        PersistenceUnitUtil util = em.getEntityManagerFactory().getPersistenceUnitUtil();
        EntityType<E> root = em.getMetamodel().entity(rootEntity);
        return persisted(persist, entity -> returning.mapReturning(path -> PersistedEntity.get(util, root, entity,
                path)));
    }

    @Override
    public <K> K persist(ModelPersist<E, K, ?> p) {
        Objects.requireNonNull(p, "p");
        return persisted(p, entity -> p.keyType().cast(em.getEntityManagerFactory().getPersistenceUnitUtil()
                .getIdentifier(entity)));
    }

    /**
     * Writes {@code p}'s row (R-WRT-39) and returns {@code result} of the created entity, called after the flush and
     * before the {@code detach}.
     */
    private <T> T persisted(ModelPersist<E, ?, ?> p, Function<E, T> result) {
        WriteAssignments assignments = writeAssignments();
        checkWriteOnce(p, metamodel -> {
            p.checkMetamodel(metamodel);
            // Only where the provider reports the root's generator: persist works without insert support (R-WRT-39)
            vendor.providerSupport().flatMap(ProviderSupport::inserts).ifPresent(support ->
                    InsertChecks.checkIdNotGenerated(p, insertTarget(support), p.writesId(metamodel)));
        });
        if (!em.isJoinedToTransaction()) {
            throw new ModelQueryExecutionException(MqCode.MQ2501, p + ": persist needs an active transaction, and "
                    + "the EntityManager is not joined to one");
        }
        // The write assignments the definition leaves, set before persist(...), so a @PrePersist setting the same
        // attribute wins (R-WRT-49)
        List<String> attributes = p.attributes();
        List<Object> values = p.attributeValues();
        List<WriteAssignments.Written> added = assignments.execution().values(WriteKind.INSERT, attributes);
        if (!added.isEmpty()) {
            attributes = new ArrayList<>(attributes);
            values = new ArrayList<>(values);
            for (WriteAssignments.Written written : added) {
                attributes.add(written.path());
                values.add(written.value());
            }
        }
        E entity = PersistedEntity.create(em, em.getMetamodel().entity(rootEntity), attributes, values);
        LOG.log(DEBUG, () -> "persist " + p);
        em.persist(entity);
        try {
            em.flush();
            return result.apply(entity);
        } finally {
            em.detach(entity);
        }
    }

    /**
     * The {@link InsertSupport} serving this executor's factory, else {@code MQ4009}, before any statement and before
     * the flush (R-VND-14).
     */
    private InsertSupport insertSupport(ModelInsert<E, ?> i) {
        return vendor.providerSupport().flatMap(ProviderSupport::inserts).orElseThrow(() ->
                new ModelQueryConfigurationException(MqCode.MQ4009, i + ": a bulk insert needs the persistence "
                        + "provider's InsertSupport, and no ProviderSupport on the class path supplies one for this "
                        + "EntityManagerFactory; add model-query-hibernate for Hibernate, or use persist(...)"));
    }

    private InsertTarget insertTarget(InsertSupport support) {
        InsertTarget target = insertTarget;
        if (target == null) {
            target = insertTarget = support.target(em.getEntityManagerFactory(), rootEntity);
        }
        return target;
    }

    /**
     * Checks {@code i} the first time it runs on this executor's factory, before any statement: against the metamodel,
     * then against the generator and the mappings {@code support} reports for the root (D-61, R-WRT-26); and on each
     * run, a conflict clause's target and its update's {@code where} against this executor's configuration and
     * profile (R-WRT-34, R-WRT-36), and its arbiter predicate against {@code support} (R-WRT-52, D-132). Returns an
     * insert-values' computed values, built once for this execution, or none for an insert-select (R-WRT-53).
     */
    private Map<String, Function<CriteriaBuilder, Expression<?>>> checkInsertOnce(ModelInsert<E, ?> i,
            InsertSupport support, WriteAssignments assignments) {
        // Each run, as the profile checks below, and before the checks that render a chunked insert-select's source,
        // so a missing sequence renderer is refused as such; then an InsertSupport that would refuse a computed value
        // only once the flush has run (R-WRT-53, R-VND-14, D-132)
        i.checkSequences(renderOptions);
        Map<String, Function<CriteriaBuilder, Expression<?>>> computed = Map.of();
        if (i.sourceEntity().isEmpty()) {
            computed = i.computedValues(em.getMetamodel(), renderOptions);
            InsertChecks.checkComputedRendered(i, computed.keySet(), support, em.getEntityManagerFactory());
        }
        checkWriteOnce(i, metamodel -> {
            EntityManagerFactory emf = em.getEntityManagerFactory();
            i.checkMetamodel(metamodel);
            // A to-one the doUpdate where reads by its id: MQ1004 unless its foreign key renders with no join (D-136)
            i.checkForeignKeys(em.getCriteriaBuilder(), renderOptions, this::foreignKeyColumn);
            InsertChecks.checkTarget(i, insertTarget(support), i.sourceEntity().isPresent(),
                    i.writesId(metamodel));
            if (!i.conflictKeys().isEmpty()) {
                InsertChecks.checkConflict(i, i.conflictKeys(), support.uniqueKeys(emf, rootEntity),
                        !i.conflictSkips() || support.doNothingRendered(emf), i.conflictArbiter().isEmpty());
            }
            if (i.chunkOptions().isPresent() && i.sourceEntity().isPresent()) {
                checkNoOverlap(i, selected(i, metamodel), metamodel);
            }
        });
        // Each run, not once per factory: the option and the profile are this executor's, and another executor on
        // the same factory may run i too. A form the vendor takes at all is checked before what its key columns mean
        // (R-WRT-52), so a conflict arbiter or a constraint name the profile refuses is refused as such.
        i.conflictConstraint().ifPresent(name ->
                InsertChecks.checkConstraint(i, name, vendor.profile().conflictOnConstraint()));
        if (!i.conflictArbiter().isEmpty()) {
            InsertChecks.checkArbiter(i, vendor.profile().conflictArbiterPredicate());
            // The provider renders the predicate's literals now, not when the statement is built after the flush
            // and a chunked insert-select's key select (INV-5, D-132)
            InsertChecks.checkArbiterRendered(i, support.arbiterRendered(em.getEntityManagerFactory(), rootEntity,
                    i.conflictArbiter()), support);
        }
        if (!i.conflictKeys().isEmpty()) {
            InsertChecks.checkConflictTarget(i, i.conflictKeys(),
                    vendor.profile().conflictTargetHonoured() || i.conflictAnyUniqueKey());
        }
        List<String> updated = conflictUpdates(i) ? assignments.paths(WriteKind.UPDATE) : List.of();
        InsertChecks.checkConflictWhere(i, i.conflictWhereReadsAssigned(em.getMetamodel(), updated),
                conflictUpdateWhereOnAssignedColumns, vendor.profile().conflictWhereSeesEarlierAssignments());
        return computed;
    }

    /** Whether {@code i} has a {@code doUpdate} clause, which applies the {@code UPDATE} write assignments. */
    private static boolean conflictUpdates(ModelInsert<?, ?> i) {
        return i.hasConflict() && !i.conflictSkips();
    }

    /**
     * The entity types {@code i}'s source select reads through its FROM: the source root, then each entity a
     * {@code map} or {@code where} column joins, at any depth, in join order. A row a chunk writes into any of them
     * could change a later chunk's rows, a to-many join's repeats or its {@code where} (R-WRT-28).
     *
     * @throws ModelQueryDefinitionException {@code MQ1806} when a join goes through a collection table
     */
    private Set<Class<?>> selected(ModelInsert<E, ?> i, Metamodel metamodel) {
        Set<Class<?>> entities = mappedEntities(em);
        var selected = new LinkedHashSet<Class<?>>();
        var froms = new ArrayDeque<From<?, ?>>(i.buildSelect(em.getCriteriaBuilder(), renderOptions, metamodel)
                .query().getRoots());
        while (!froms.isEmpty()) {
            From<?, ?> from = froms.poll();
            Class<?> type = from instanceof Join<?, ?> join ? joinedType(join.getAttribute()) : from.getJavaType();
            if (from instanceof Join<?, ?> join && readsCollectionTable(join.getAttribute())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1806, i + ": a chunked insert-select joins through "
                        + "the collection table of " + join.getAttribute().getName() + ", which the chunked write "
                        + "cannot check for overlap with the target; the unchunked statement is allowed, since the "
                        + "database reads the whole select before it inserts");
            }
            if (entities.contains(type)) {
                selected.add(type);
            }
            froms.addAll(from.getJoins());
        }
        return selected;
    }

    /**
     * Whether a join over {@code attribute} reads a link or collection table, which {@code tablesOf} does not name: a
     * many-to-many, an element collection, or a one-to-many not shown to be a foreign key on the target by
     * {@code mappedBy} or a join column, which is a join table by default.
     */
    private static boolean readsCollectionTable(Attribute<?, ?> attribute) {
        return switch (attribute.getPersistentAttributeType()) {
            case MANY_TO_MANY, ELEMENT_COLLECTION -> true;
            case ONE_TO_MANY -> !isForeignKeyOnTarget(attribute);
            default -> false;
        };
    }

    /** Whether annotations show the one-to-many {@code attribute} to be a foreign key on its target's table. */
    private static boolean isForeignKeyOnTarget(Attribute<?, ?> attribute) {
        if (!(attribute.getJavaMember() instanceof AnnotatedElement member)
                || member.isAnnotationPresent(JoinTable.class)) {
            return false;
        }
        OneToMany oneToMany = member.getAnnotation(OneToMany.class);
        return oneToMany != null && !oneToMany.mappedBy().isEmpty()
                || member.isAnnotationPresent(JoinColumn.class) || member.isAnnotationPresent(JoinColumns.class);
    }

    /** The type a join over {@code attribute} reaches: a collection's element, else the attribute's own type. */
    private static Class<?> joinedType(Attribute<?, ?> attribute) {
        return attribute instanceof PluralAttribute<?, ?, ?> plural ? plural.getElementType().getJavaType()
                : ((SingularAttribute<?, ?>) attribute).getType().getJavaType();
    }

    /**
     * Checks that a chunked insert-select's target overlaps none of the {@code selected} entities its source select
     * reads, the source root and every entity it joins: rows a chunk writes could match the next chunk's key select,
     * or change the rows its joins return, and the engine only gets a count back. An entity overlaps the target when
     * they are one entity or one hierarchy, or when the tables the provider names for them intersect, ignoring case;
     * an entity or target whose tables the provider does not name fails closed, since rows written with generated ids
     * above the cursor would otherwise be re-read silently (R-WRT-28, R-VND-13, INV-5).
     *
     * @throws ModelQueryDefinitionException {@code MQ1806} when one overlaps or a table set is unknown
     */
    private void checkNoOverlap(ModelInsert<E, ?> i, Set<Class<?>> selected, Metamodel metamodel) {
        String chunked = i + ": a chunked insert-select";
        String unchunked = "; the unchunked statement is allowed, since the database reads the whole select before "
                + "it inserts";
        EntityManagerFactory emf = em.getEntityManagerFactory();
        ProviderSupport support = vendor.providerSupport().orElseThrow();
        Set<String> targetTables = lowerCase(support.tablesOf(emf, rootEntity));
        for (Class<?> source : selected) {
            if (hierarchyRoot(metamodel, source) == hierarchyRoot(metamodel, rootEntity)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1806, chunked + " reads " + source.getSimpleName()
                        + ", of the target's own entity hierarchy, so a chunk's key select could read the rows an "
                        + "earlier chunk wrote" + unchunked);
            }
            Set<String> sourceTables = lowerCase(support.tablesOf(emf, source));
            if (targetTables.isEmpty() || sourceTables.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1806, chunked + " cannot tell whether "
                        + source.getSimpleName() + " and " + rootEntity.getSimpleName() + " share a table, since the "
                        + "provider names no tables for "
                        + (targetTables.isEmpty() ? rootEntity : source).getSimpleName() + unchunked);
            }
            sourceTables.retainAll(targetTables);
            if (!sourceTables.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1806, chunked + " reads " + source.getSimpleName()
                        + ", which shares the table " + String.join(", ", new TreeSet<>(sourceTables))
                        + " with the target, so a chunk's key select could read the rows an earlier chunk wrote"
                        + unchunked);
            }
        }
    }

    /** The topmost entity type above {@code entity}, itself when it has no entity supertype. */
    private static Class<?> hierarchyRoot(Metamodel metamodel, Class<?> entity) {
        Class<?> top = entity;
        for (IdentifiableType<?> type = metamodel.entity(entity).getSupertype(); type != null;
                type = type.getSupertype()) {
            if (type instanceof EntityType<?> supertype) {
                top = supertype.getJavaType();
            }
        }
        return top;
    }

    /**
     * Whether a write runs key-first: the profile says the database cannot read a write's target table in a
     * sub-query, and the write's rendering would, because the root shares its table hierarchy with another entity, or
     * because a sub-query reads an entity of the root's hierarchy or touching one of its tables ({@code subqueryReads})
     * (R-WRT-11, R-VND-11, D-109). The metamodel shows the hierarchy but not its inheritance strategy, so every
     * hierarchy counts: key-first is always correct, only slower.
     */
    private boolean keyFirst(Supplier<Set<Class<?>>> subqueryReads) {
        if (vendor.profile().targetTableInSubquery()) {
            return false;
        }
        if (inHierarchy()) {
            return true;
        }
        Set<Class<?>> read = subqueryReads.get();
        return read.stream().anyMatch(this::sharesRootHierarchy) || readsRootTable(read);
    }

    private boolean sharesRootHierarchy(Class<?> entity) {
        return entity.isAssignableFrom(rootEntity) || rootEntity.isAssignableFrom(entity);
    }

    /**
     * Whether reading one of {@code entities} touches a table reading the root does, by the tables the provider names,
     * ignoring case; false for an entity whose set or the root's is unknown, so two entities on one table then go
     * undetected (R-VND-13, D-109).
     */
    private boolean readsRootTable(Set<Class<?>> entities) {
        Optional<ProviderSupport> support = vendor.providerSupport();
        if (entities.isEmpty() || support.isEmpty()) {
            return false;
        }
        EntityManagerFactory emf = em.getEntityManagerFactory();
        Set<String> rootTables = lowerCase(support.get().tablesOf(emf, rootEntity));
        return !rootTables.isEmpty() && entities.stream()
                .flatMap(entity -> support.get().tablesOf(emf, entity).stream())
                .anyMatch(table -> rootTables.contains(table.toLowerCase(Locale.ROOT)));
    }

    private static Set<String> lowerCase(Set<String> tables) {
        Set<String> lower = new HashSet<>();
        tables.forEach(table -> lower.add(table.toLowerCase(Locale.ROOT)));
        return lower;
    }

    /** Whether the root has an entity supertype or subtype in the metamodel. */
    private boolean inHierarchy() {
        Metamodel metamodel = em.getMetamodel();
        for (IdentifiableType<?> type = metamodel.entity(rootEntity).getSupertype(); type != null;
                type = type.getSupertype()) {
            if (type instanceof EntityType<?>) {
                return true;
            }
        }
        return mappedEntities(em).stream()
                .anyMatch(type -> type != rootEntity && rootEntity.isAssignableFrom(type));
    }

    /**
     * Throws {@code MQ2501} naming {@code operation}, the write with its article ({@code "a bulk update"}), when
     * {@code em} is not joined to a transaction, before any statement, instead of the provider's
     * {@code TransactionRequiredException} at the end (R-WRT-18). A write whose
     * {@code chunk} options commit each chunk needs no transaction but a {@link ChunkTransactions} that serves the
     * factory, else it throws {@code MQ4004} before any statement, the flush included (R-WRT-19, D-62).
     */
    private void requireTransaction(String operation, Optional<ChunkOptions> chunk) {
        if (chunk.map(ChunkOptions::commitsEachChunk).orElse(false)) {
            requireChunkTransactions(operation);
            return;
        }
        if (!em.isJoinedToTransaction()) {
            throw new ModelQueryExecutionException(MqCode.MQ2501, rootEntity.getSimpleName() + ": " + operation
                    + " needs an active transaction, and the EntityManager is not joined to one");
        }
    }

    private void requireChunkTransactions(String operation) {
        String write = rootEntity.getSimpleName() + ": " + operation + " with commitEachChunk()";
        if (chunkTransactions == null) {
            throw new ModelQueryConfigurationException(MqCode.MQ4004, write + " runs each chunk through a "
                    + "ChunkTransactions, and none is set on ModelQueryConfig.chunkTransactions(...)");
        }
        try {
            chunkTransactions.checkServes(em.getEntityManagerFactory());
        } catch (RuntimeException e) {
            if (e instanceof ModelQueryConfigurationException configuration
                    && configuration.code() == MqCode.MQ4004) {
                throw configuration;
            }
            throw new ModelQueryConfigurationException(MqCode.MQ4004, write + ": the configured "
                    + chunkTransactions.getClass().getName() + " cannot serve this EntityManagerFactory: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Runs one write: flushes the pending entity changes first, so they are written and not overwritten afterwards,
     * then {@code statements}, and returns the rows they affected (R-WRT-15). Afterwards, whether or not it threw,
     * clears the persistence context unless the write's own mode, else the configured one, is {@code KEEP}, and evicts
     * the root from the second-level cache (D-62).
     */
    private long write(Optional<PersistenceContextMode> ownMode, LongSupplier statements) {
        if (em.isJoinedToTransaction()) {
            em.flush();
        }
        try {
            return statements.getAsLong();
        } finally {
            if (ownMode.orElse(persistenceContextMode) == PersistenceContextMode.CLEAR) {
                em.clear();
            }
            em.getEntityManagerFactory().getCache().evict(rootEntity);
        }
    }

    /**
     * The {@code whole} statement, or with {@code keys} the {@code byKeys} statements over runs of the keys sized to
     * the profile's limits, counting each statement's own binds, {@code SET} values included, plus {@code repeated},
     * the values the whole statement's memoised expressions repeat (R-WRT-08, R-COL-19, D-63); returns the summed rows
     * affected.
     */
    private long direct(Optional<List<Object>> keys, Supplier<Query> whole, Function<List<Object>, Query> byKeys,
            int repeated, StatementRun run) {
        if (keys.isEmpty()) {
            return run.run(whole.get(), repeated);
        }
        List<Object> all = keys.get();
        int chunk = all.size() == 1 ? 1 : keyChunkSize(byKeys.apply(all.subList(0, 1)), all.get(0),
                OptionalInt.empty(), repeated);
        long written = 0;
        for (int from = 0; from < all.size(); from += chunk) {
            written += run.run(byKeys.apply(all.subList(from, Math.min(all.size(), from + chunk))), repeated);
        }
        return written;
    }

    /**
     * Runs {@code keyed} through the keyset loop, key-first or chunked: in rounds of the {@code chunk} size, else the
     * configured {@code bulkWriteChunkSize} for a chunked write, else the profile's clamp, always within the clamp;
     * with the keys selected under a lock when the options say {@code lockKeys()}, and each round in a new
     * transaction when they say {@code commitEachChunk()} (R-WRT-11, R-WRT-17, R-WRT-19, D-63). The clamp counts
     * the binds of the {@code whole} statement, or of {@code byKeys} over one key, less that key's, plus the whole
     * statement's memoised expression repeats (R-COL-19): the whole tree's binds are at least those of the root terms
     * the write keeps and of the tree the key select renders, so neither passes the limit.
     */
    private <M> long keyset(KeysetWrite.Keyed<M> keyed, Supplier<Query> whole, Function<List<Object>, Query> byKeys,
            Optional<ChunkOptions> chunk, CriteriaBuilder cb, int repeated) {
        OptionalInt size = chunk.isEmpty() ? OptionalInt.empty()
                : OptionalInt.of(chunk.get().size().orElse(bulkWriteChunkSize));
        return keyset(keyed, whole, byKeys, chunk, size, cb, repeated);
    }

    /** As above, in rounds of {@code size} keys, or of the profile's clamp when empty, always within the clamp. */
    private <M> long keyset(KeysetWrite.Keyed<M> keyed, Supplier<Query> whole, Function<List<Object>, Query> byKeys,
            Optional<ChunkOptions> chunk, OptionalInt size, CriteriaBuilder cb, int repeated) {
        int n = keyed.distinctKeys()
                .map(all -> all.size() == 1 ? 1
                        : keyChunkSize(byKeys.apply(all.subList(0, 1)), all.get(0), size, repeated))
                .orElseGet(() -> keyLimits.clamp(whole.get().getParameters().size() + repeated,
                        keyed.key().columns().size(), size));
        boolean perChunk = chunk.map(ChunkOptions::commitsEachChunk).orElse(false);
        return new KeysetWrite(cb, (on, built, keyset, cursor) -> create(rootEntity.getSimpleName(), on, built.query(),
                keyset, cursor, built.joins()), this::timeoutCancellation, em, perChunk ? chunkTransactions : null)
                        .run(keyed, n, chunk.map(ChunkOptions::locksKeys).orElse(false));
    }

    /**
     * The most keys one write statement takes, {@code configured} if any, from {@code oneKey}, the statement over a
     * single key: its binds less the key's own, plus the {@code repeated} the whole statement's memoised expressions
     * add, are the statement's (D-63, R-COL-19).
     */
    private int keyChunkSize(Query oneKey, Object key, OptionalInt configured, int repeated) {
        int keyColumns = key instanceof List<?> components ? components.size() : 1;
        int ownBinds = Math.max(0, oneKey.getParameters().size() + repeated - keyColumns);
        return keyLimits.clamp(ownBinds, keyColumns, configured);
    }

    /** Runs one write statement: the rows it wrote, collected into a returning write's rows when it has some. */
    @FunctionalInterface
    private interface StatementRun {
        long run(Query statement, int repeated);
    }

    /** {@code returning}'s run, else the count-only run every plain write uses (R-WRT-50). */
    private StatementRun run(Returning<?> returning) {
        return returning == null ? this::execute : (statement, repeated) -> execute(statement, repeated, returning);
    }

    /**
     * Runs a write statement within the bind limit, with the configured timeout applied through the profile
     * (R-FLT-09, R-EXE-11). {@code repeated} counts the bind values the statement's memoised expressions repeat beyond
     * what JPA reports (R-COL-19, D-80).
     */
    private int execute(Query statement, int repeated) {
        return Math.toIntExact(execute(statement, repeated, null));
    }

    /**
     * As above. With {@code returning} it hands the statement to the provider instead of calling
     * {@code executeUpdate()}, and counts the rows the provider read back (R-WRT-50); every other write, and every
     * statement of a returning write that carries none, counts the rows it wrote.
     */
    private long execute(Query statement, int repeated, Returning<?> returning) {
        withinBindLimit(rootEntity.getSimpleName(), statement.getParameters().size() + repeated, 0);
        queryTimeout.ifPresent(timeout -> vendor.profile().applyTimeout(statement, timeout));
        LOG.log(TRACE, () -> rootEntity.getSimpleName() + ": statement binds "
                + (statement.getParameters().size() + repeated) + " of " + renderOptions.maxBindParameters());
        long start = System.nanoTime();
        long written;
        try {
            written = returning == null ? statement.executeUpdate() : returning.run(statement);
        } catch (PersistenceException e) {
            throw timeoutCancellation(e);
        }
        traceTimed(rootEntity.getSimpleName(), written + (written == 1 ? " row" : " rows") + " written", start);
        return written;
    }

    /**
     * The configured write assignments that apply to the root, checked the first time per root on this executor's
     * factory, before any statement (R-WRT-49, D-61).
     */
    private WriteAssignments writeAssignments() {
        return WriteAssignments.of(em.getEntityManagerFactory(), em.getMetamodel(), rootEntity,
                config.writeAssignments());
    }

    private void checkTreats(Object definition) {
        if (renderOptions.treatRenderer().isPresent()) {
            TreatChecks.check(definition, renderOptions, em.getMetamodel(), StatementShape.WRITE, false, false);
            return;
        }
        Set<Object> checked = TREATS_CHECKED.computeIfAbsent(em.getEntityManagerFactory(),
                factory -> Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>())));
        synchronized (checked) {
            if (!checked.contains(definition)) {
                checked.addAll(TreatChecks.check(definition));
            }
        }
    }

    /** Runs {@code check} the first time {@code definition} runs on this executor's factory, before any statement. */
    private void checkWriteOnce(Object definition, Consumer<Metamodel> check) {
        checkTreats(definition);
        Set<Object> checked = WRITES_CHECKED.computeIfAbsent(em.getEntityManagerFactory(),
                factory -> Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>())));
        if (!checked.contains(definition)) {
            check.accept(em.getMetamodel());
            checked.add(definition); // only once it passed, so a check that threw runs again next time
        }
    }

    /**
     * Runs the R-QRY-09 phase check and the R-FCH-02 fetch-plan check the first time any executor runs {@code q}
     * (D-21).
     */
    private <E2> void checkFirstRun(ModelQuery<E2, ?, ?> q) {
        checkFirstRun(q, true);
    }

    /**
     * As {@link #checkFirstRun(ModelQuery)}; without {@code fetchesModels}, as for {@code count}, which loads no
     * children and runs no enricher, a failed fetch-plan check logs a {@code WARNING} instead of throwing
     * {@code MQ1702}, and is not remembered, so the next call that returns models throws it. Returns
     * {@link #checkCount}'s reason to count the groups client-side.
     */
    private <E2> Optional<String> checkFirstRun(ModelQuery<E2, ?, ?> q, boolean fetchesModels) {
        return checkFirstRun(q, fetchesModels, fetchesModels ? StatementShape.CONTENT : StatementShape.COUNT, false,
                false);
    }

    private <E2> Optional<String> checkFirstRun(ModelQuery<E2, ?, ?> q, boolean fetchesModels, StatementShape shape,
            boolean laterPages, boolean withCount) {
        Optional<String> clientSide = Optional.empty();
        if (renderOptions.treatRenderer().isPresent()) {
            if (shape == StatementShape.COUNT) {
                clientSide = checkCount(q, laterPages, fetchesModels);
            } else {
                TreatChecks.check(q, renderOptions, em.getMetamodel(), shape, laterPages, fetchesModels);
            }
            if (withCount) {
                clientSide = checkCount(q, false, false);
            }
        } else {
            checkTreats(q);
        }
        // An orderedBy copy is covered by the check of its definition, so a per-request sort does not repeat it.
        ModelQuery<E2, ?, ?> definition = q.definition();
        if (!FIRST_RUN_CHECKED.contains(definition)) {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            q.checkPhases(cb, renderOptions);
            try {
                q.checkFetch(cb, renderOptions);
            } catch (ModelQueryDefinitionException e) {
                if (fetchesModels) {
                    throw e;
                }
                LOG.log(System.Logger.Level.WARNING, "count runs, but the fetch plan of the query is invalid, and "
                        + "every call that returns models throws: {0}", e.getMessage());
                return clientSide;
            }
            FIRST_RUN_CHECKED.add(definition); // only once they passed, so a check that threw runs again next time
        }
        if (fetchesModels) {
            // A plan that fills children, join plans or an enricher addresses rows by their keys, which a
            // non-unique source has none of (R-SRC-09). A selection-only plan reads the row itself, so it runs.
            if (q.fetch().filter(plan -> !plan.isSelectionOnly()).isPresent()) {
                refuseNonUniqueSource(q, "a fetch plan with a child, a join plan or an enricher");
            }
            checkFetchBuilds(q);
        }
        return clientSide;
    }

    /**
     * Checks the sites of {@code q}'s count. A grouped query's count of groups is a {@code GROUPED_COUNT} statement;
     * when that shape is refused and the same sites pass as {@code GROUPED}, the groups are counted client-side and
     * the refusal is returned as the reason. A site refused as {@code GROUPED} too throws its {@code MQ4016}. Decided
     * per call: no verdict is kept (R-EXE-03, R-VND-26, D-159, INV-9).
     */
    private Optional<String> checkCount(ModelQuery<?, ?, ?> q, boolean laterPages, boolean fetchesModels) {
        try {
            TreatChecks.check(q, renderOptions, em.getMetamodel(), StatementShape.COUNT, laterPages, fetchesModels);
            return Optional.empty();
        } catch (ModelQueryConfigurationException e) {
            if (!q.isGrouped() || e.code() != MqCode.MQ4016) {
                throw e;
            }
            TreatChecks.check(q, renderOptions, em.getMetamodel(), StatementShape.GROUPED, laterPages, fetchesModels);
            // The renderer's refusal without its code: the count runs, so no MQ4016 is raised (D-159).
            return Optional.of(e.getMessage().substring(e.code().code().length() + 2));
        }
    }

    /**
     * Whether {@code q}'s statements go to the renderer's preflight: with a renderer, when its own statement or its
     * source's body, inner sources or branches hold a treated node. Without one, no statement is built for it and the
     * renderer is not called (R-VND-26).
     */
    private boolean preflights(ModelQuery<?, ?, ?> q) {
        return renderOptions.treatRenderer().isPresent() && TreatChecks.reachesTreat(q);
    }

    /**
     * Hands {@code query}, a select statement the call runs, to the renderer's preflight, before the call's first
     * statement and after its sites passed {@code refusal}, with the narrowings its build declared ({@code expected},
     * D-157); {@code MQ4016} names the model, the statement, its shape and the renderer's reason. Each call preflights
     * again: no verdict is kept (R-VND-26, INV-9).
     */
    private void preflight(ModelQuery<?, ?, ?> q, String statement, StatementShape shape, BuiltQuery<?> built) {
        preflight(q, statement, shape, built.query(), built.expectedNarrowings());
    }

    /** As {@link #preflight(ModelQuery, String, StatementShape, BuiltQuery)}, for {@code query} built by another. */
    private void preflight(ModelQuery<?, ?, ?> q, String statement, StatementShape shape, CriteriaQuery<?> query,
            List<ExpectedNarrowing> expected) {
        // A grouped statement is GROUPED, as its sites are (TreatChecks); the count of its groups is preflighted as
        // GROUPED too, whether the provider builds it or it runs client-side (D-159).
        StatementShape actual = q.isGrouped() ? StatementShape.GROUPED : shape;
        renderOptions.treatRenderer().orElseThrow().preflight(em, query, actual, expected).ifPresent(reason -> {
            throw new ModelQueryConfigurationException(MqCode.MQ4016, q + ": the preflight refuses the " + statement
                    + " (" + actual + " statement): " + reason);
        });
    }

    /**
     * Preflights each later page of a keyset read, built as the read builds it, with a placeholder parameter for
     * each cursor value: every value non-NULL, then each combination of NULLs at the keys that can hold one
     * (R-VND-26). {@code reorder}
     * replaces the order with the keyset's, as {@code page(query, KeysetSpec)} does; else the tie-breakers are
     * appended to the query's own, as a keyset export does.
     */
    private <M> void preflightLaterPages(ModelQuery<E, ?, M> q, Keyset<M> keyset, boolean reorder, String read) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        for (Object[] cursor : keyset.laterPageCursors(cb)) {
            BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL, renderOptions);
            Predicate after = keyset.after(cursor, built.joins(), cb).predicate();
            Predicate own = built.query().getRestriction();
            built.query().where(own == null ? after : cb.and(own, after));
            if (reorder) {
                keyset.applyOrder(built, cb);
            } else {
                keyset.appendOrder(built, cb);
            }
            long nulls = Arrays.stream(cursor).filter(Objects::isNull).count();
            preflight(q, read + ", over a cursor with " + (nulls == 0 ? "no NULL value"
                    : nulls == 1 ? "a NULL value" : nulls + " NULL values"),
                    StatementShape.CONTENT, built);
        }
    }

    /**
     * {@code MQ2212} for a read that identifies one row by the source's key, which a non-unique source has none of
     * (R-SRC-09): {@code byKeys}, {@code one(query, key)}, {@code export}, and a fetch plan that fills children
     * through a child, a join plan or an enricher. Raised at the call, before any statement.
     */
    private <E2> void refuseNonUniqueSource(ModelQuery<E2, ?, ?> q, String read) {
        SourceQuery<?> source = q.source().filter(each -> !each.unique()).orElse(null);
        if (source != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ2212, q + ": " + read + " needs one row per key, and "
                    + "the source it reads has no unique key; list, count, first, stream and offset page read it"
                    + (source.recursive() ? "; use tree(...) for a checked recursive walk" : ""));
        }
    }

    /**
     * Builds each child statement of {@code q}'s fetch plan, through its join plans and the children's own plans, and
     * checks a one-key round of it against the bind limit, before any statement of the call: a child's refusals
     * ({@code MQ1306}, {@code MQ1307}, the {@code MQ15xx} codes) depend on the vendor, and a page that finds no
     * parent never builds its children (R-FCH-02, R-FCH-04, D-138). Once passed, not again on this factory and
     * profile.
     */
    private <E2> void checkFetchBuilds(ModelQuery<E2, ?, ?> q) {
        if (q.fetch().isEmpty()) {
            return;
        }
        Object definition = q.definition();
        Set<Object> checked;
        synchronized (FETCH_BUILDS_CHECKED) {
            checked = FETCH_BUILDS_CHECKED.computeIfAbsent(em.getEntityManagerFactory(),
                    factory -> new WeakHashMap<>()).computeIfAbsent(vendor.profile(),
                    profile -> Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>())));
        }
        if (!checked.contains(definition)) {
            checkBuilds(q.fetch().get());
            checked.add(definition); // only once it passed, so a check that threw runs again next time
        }
    }

    /** {@link #checkFetchBuilds(ModelQuery)} over {@code plan}'s children and join plans. */
    private <M> void checkBuilds(FetchPlan<M> plan) {
        for (ChildLoad<M, ?> load : plan.childLoads()) {
            checkChildBuild(load);
        }
        for (JoinPlan<M, ?> join : plan.joinPlans()) {
            checkBuilds(join.plan());
        }
    }

    /** Builds {@code load}'s statement as {@link #childrenOf} does and checks it binds one key within the limit. */
    private <M, C> void checkChildBuild(ChildLoad<M, C> load) {
        checkFirstRun(load.query()); // the child's own phases and plan, its nested children included
        BuiltQuery<C> built = load.build(em.getCriteriaBuilder(), renderOptions);
        load.requireBindableKeys(built); // the keys bind after the parent statement ran (R-FLT-08)
        withinBindLimit(load, childOwnBinds(load, built) + 1, 0);
    }

    /**
     * A child statement's own binds, before any key is added (R-PAG-07, D-32), plus the values its memoised expressions
     * repeat (R-COL-19); a dialect that binds the LIMIT of {@code maxPerParent} as a parameter takes one more.
     */
    private int childOwnBinds(ChildLoad<?, ?> load, BuiltQuery<?> built) {
        return em.createQuery(built.query()).getParameters().size() + built.joins().repeatedExpressionBinds()
                + (load.maxPerParent() > 0 ? 1 : 0);
    }

    // ---- page content

    /**
     * The rows of a page, mapped, before its fetch plan runs; {@code batch} is the step-2 batch of a primary-key-first
     * page, which {@code page} sized before any statement.
     */
    private <M> List<Loaded<M>> fetch(ModelQuery<E, ?, M> q, int offset, int maxRows, int batch) {
        return fetch(q, offset, maxRows, batch, false, null);
    }

    /**
     * As {@link #fetch(ModelQuery, int, int, int)}; {@code everyColumn} closes the order with every selected column,
     * which an offset page over a non-unique source does (R-SRC-09). {@code first}, when set, is the page's first
     * statement, which the caller built and preflighted (R-VND-26).
     */
    private <M> List<Loaded<M>> fetch(ModelQuery<E, ?, M> q, int offset, int maxRows, int batch,
            boolean everyColumn, BuiltQuery<M> first) {
        if (primaryKeyFirst(q, offset)) {
            PrimaryKey<M, ?> key = q.primaryKey().orElseThrow(); // primaryKeyFirst(...) needs one (MQ1201)
            // Read the page's keys in the stable order, then their rows: the same rows as the one-step page, a key
            // read twice through a customizer's to-many join, or one R-EXE-16 leaves in the statement, included.
            List<Object> keys = readKeys(q, key, first != null ? first : keyQuery(q), offset, maxRows);
            boolean uniqueParents = q.fetch().filter(DefaultModelQueryExecutor::hasFetchWork).isPresent();
            return readByKeys(q, key, keys, batch, uniqueParents);
        }
        BuiltQuery<M> built = first != null ? first : offsetPage(q, everyColumn);
        TypedQuery<Tuple> query = create(q, built);
        query.setFirstResult(offset);
        query.setMaxResults(maxRows);
        return mapAll(q, built, query);
    }

    /** An offset page's one-step statement, in the stable order (R-PAG-01, R-SRC-09). */
    private <M> BuiltQuery<M> offsetPage(ModelQuery<E, ?, M> q, boolean everyColumn) {
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL, renderOptions);
        appendStableOrder(q, built, everyColumn);
        return built;
    }

    /**
     * Preflights step 2 of a primary-key-first read from {@code byKeys}, its {@code MODEL_BY_KEYS} statement as built
     * to size the batch, which it then orders as each step-2 statement is. Each step-2 statement adds the batch's
     * {@code IN} over the primary key, whose columns the order already reads (R-PAG-07, R-VND-26).
     */
    private <M> void preflightRowsByKeys(ModelQuery<E, ?, M> q, BuiltQuery<M> byKeys) {
        if (preflights(q)) {
            appendStableOrder(q, byKeys);
            preflight(q, "primary-key-first page's rows", StatementShape.CONTENT, byKeys);
        }
    }

    /**
     * Offset pages of an order with ties overlap or skip rows, so the primary key (the group keys of a grouped query)
     * closes the order (api/11 §5, R-PAG-01, R-PAG-11).
     */
    private <E2, M> void appendStableOrder(ModelQuery<E2, ?, M> q, BuiltQuery<M> built) {
        appendStableOrder(q, built, false);
    }

    /**
     * As {@link #appendStableOrder(ModelQuery, BuiltQuery)}, with every selected outer column appended after the key
     * when {@code everyColumn} is set: the key of a non-unique source can't break ties, so two rows equal in every
     * column would otherwise swap between the statements of two offset pages (R-SRC-09).
     *
     * @throws ModelQueryDefinitionException {@code MQ2212} for a selected column that can't be ordered
     */
    private <E2, M> void appendStableOrder(ModelQuery<E2, ?, M> q, BuiltQuery<M> built, boolean everyColumn) {
        List<SelectField<M, ?>> closing = new ArrayList<>(tieBreakers(q));
        if (everyColumn) {
            for (SelectField<M, ?> column : q.select().fields()) {
                if (!orderable(column)) {
                    if (unkeyedTree(q)) {
                        throw new ModelQueryDefinitionException(MqCode.MQ2212, q + ": the query has no primary key, "
                                + "so every selected column closes the order of an offset page, and the selected "
                                + "column '" + column.name() + "' can't be ordered; deselect it or declare "
                                + ".primaryKey(...) on the query");
                    }
                    throw new ModelQueryDefinitionException(MqCode.MQ2212, q + ": the source has no unique key, so "
                            + "every selected column closes the order of an offset page, and the selected column '"
                            + column.name() + "' can't be ordered; deselect it or make the source unique"
                            + (q.source().orElseThrow().recursive() ? ", or use tree(...)" : ""));
                }
                if (!closing.contains(column)) {
                    closing.add(column);
                }
            }
        }
        List<Order> orders = null;
        for (SelectField<M, ?> column : closing) {
            if (!isOrdered(q, column)) {
                if (orders == null) {
                    orders = new ArrayList<>(built.query().getOrderList());
                }
                orders.add(em.getCriteriaBuilder().asc(column.expression(built.joins())));
            }
        }
        if (orders != null) {
            built.query().orderBy(orders);
        }
    }

    /** The columns that close a stable order: the primary key, or the group keys of a grouped query (R-PAG-01). */
    private static <M> List<? extends SelectField<M, ?>> tieBreakers(ModelQuery<?, ?, M> q) {
        return q.isGrouped()
                ? q.groupBy()
                : q.primaryKey().<List<? extends SelectField<M, ?>>>map(key -> key.columns()).orElse(List.of());
    }

    /**
     * Whether {@code column} can order. A column of a LOB or JSON type can't: its Java type has no natural order,
     * which {@code byte[]} and the JSON node types both lack (R-SRC-09). DECIDE: the database type is not read here,
     * so a {@code String}-mapped JSON column stays orderable.
     */
    private static boolean orderable(SelectField<?, ?> column) {
        return !(column instanceof ColumnField<?, ?, ?> named)
                || Comparable.class.isAssignableFrom(named.type());
    }

    /**
     * Whether an offset page orders by every selected column: a non-unique source, or an ungrouped source query with
     * no primary key, over a {@code tree(...)}, which has no declared key to close the order (R-PAG-01, R-SRC-09).
     */
    private static boolean needsEveryColumn(ModelQuery<?, ?, ?> q) {
        return nonUniqueSource(q) || unkeyedTree(q);
    }

    /** Whether {@code q} is an ungrouped query over a {@code tree(...)} with no primary key of its own (R-SRC-09). */
    private static boolean unkeyedTree(ModelQuery<?, ?, ?> q) {
        return !q.isGrouped() && q.primaryKey().isEmpty() && q.source().filter(SourceQuery::tree).isPresent();
    }

    /** Whether {@code q} reads a source whose rows have no unique key (R-SRC-09). */
    private static boolean nonUniqueSource(ModelQuery<?, ?, ?> q) {
        return q.source().filter(source -> !source.unique()).isPresent();
    }

    /**
     * Whether {@code column} can read NULL: false only for a path to an id, a non-optional or a primitive attribute
     * that no LEFT join sits above, so the common key orders plainly and an index can satisfy it (R-EXE-12).
     */
    static boolean mayBeNull(SelectField<?, ?> column, JoinContext joins) {
        if (!(column.expression(joins) instanceof Path<?> path)
                || !(path.getModel() instanceof SingularAttribute<?, ?> attribute)) {
            return true;
        }
        for (Path<?> up = path.getParentPath(); up != null; up = up.getParentPath()) {
            if (up instanceof Join<?, ?> join && join.getJoinType() != JoinType.INNER) {
                return true;
            }
        }
        return !(attribute.isId() || !attribute.isOptional() || attribute.getJavaType().isPrimitive());
    }

    /** Whether {@code q}'s own {@code orderBy} already orders by {@code column}. */
    private static <M> boolean isOrdered(ModelQuery<?, ?, M> q, SelectField<M, ?> column) {
        return q.orderBy().stream().map(OrderField::column).anyMatch(column::equals);
    }

    // ---- primary-key-first

    /** Whether a read at {@code offset} goes primary keys first (R-PAG-07); never without {@code primaryKeyFirst}. */
    private static boolean primaryKeyFirst(ModelQuery<?, ?, ?> q, long offset) {
        return q.primaryKeyFirst().map(pkFirst -> offset > pkFirst.offsetThreshold()).orElse(false);
    }

    /** The {@code PRIMARY_KEY} statement, in the stable order the one-step read uses (R-PAG-01). */
    private <M> BuiltQuery<M> keyQuery(ModelQuery<E, ?, M> q) {
        BuiltQuery<M> built = q.buildQuery(em.getCriteriaBuilder(), Phase.PRIMARY_KEY, renderOptions);
        appendStableOrder(q, built);
        return built;
    }

    /** Step 1: the primary keys of {@code maxRows} rows from {@code offset}, in {@code keyQuery}'s order. */
    private <M> List<Object> readKeys(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key, BuiltQuery<M> keyQuery,
            int offset, int maxRows) {
        TypedQuery<Tuple> query = create(q, keyQuery);
        query.setFirstResult(offset);
        query.setMaxResults(maxRows);
        return keysOf(q, key, keyQuery, rows(q, keyQuery, query));
    }

    /** The primary keys of {@code rows}, step 1's rows of {@code keyQuery}, in order. */
    private static <M> List<Object> keysOf(ModelQuery<?, ?, M> q, PrimaryKey<M, ?> key, BuiltQuery<M> keyQuery,
            List<Tuple> rows) {
        List<Object> keys = new ArrayList<>(rows.size());
        for (Tuple tuple : rows) {
            keys.add(Keys.keyOf(q, key, keyQuery.selection().row(tuple)));
        }
        return keys;
    }

    /**
     * Step 2: the models of {@code keys}, one per position in {@code keys}, in that order. Each statement takes at
     * most {@code batch} keys, with the query's own predicate and order (R-PAG-07, R-PAG-08). {@code IN}
     * keeps neither the step-1 order nor the order across statements, so rows are placed by their key's position. A
     * key whose row no longer matches between the two steps is skipped.
     */
    private <M> List<Loaded<M>> readByKeys(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key, List<Object> keys,
            int batch, boolean unique) {
        if (keys.isEmpty()) {
            return List.of();
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        // A customizer's to-many join, or one R-EXE-16 leaves in the statement, repeats a key in step 1; its row is
        // read once and placed at every position.
        List<Object> distinct = List.copyOf(new LinkedHashSet<>(keys));
        Map<Object, Found<M>> found = new HashMap<>();
        for (int from = 0; from < distinct.size(); from += batch) {
            List<Object> batchKeys = distinct.subList(from, Math.min(distinct.size(), from + batch));
            BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL_BY_KEYS, renderOptions);
            Predicate byKey = key.in(batchKeys, built.joins(), cb, false);
            Predicate own = built.query().getRestriction();
            built.query().where(own == null ? byKey : cb.and(own, byKey));
            appendStableOrder(q, built);
            for (Tuple tuple : rows(q, built, create(q, built))) {
                Object rowKey = Keys.keyOf(q, key, built.selection().row(tuple));
                if (found.putIfAbsent(rowKey, new Found<>(built, tuple)) != null && unique
                        && filterOnlyToManyJoin(built).isEmpty()) {
                    throw duplicateKey(q, rowKey);
                }
            }
        }
        List<Loaded<M>> models = new ArrayList<>(keys.size());
        for (Object rowKey : keys) {
            Found<M> row = found.get(rowKey);
            if (row != null) {
                // Mapped per position, so afterMap runs once per row.
                models.add(loaded(row.built(), row.tuple()));
            }
        }
        return models;
    }

    /** A step-2 row, with the statement that read it. */
    private record Found<M>(BuiltQuery<M> built, Tuple tuple) {}

    /** A {@code byKeys} row, decoded, with the statement that read it. */
    private record ReadRow<M>(BuiltQuery<M> built, Row row) {}

    /**
     * The most keys one step-2 statement takes: the configured batch size, if any, within the largest power of two
     * that fits the profile's IN-list limit and its bind-parameter limit once the statement's own binds are bound, at
     * one bind per key column (R-PAG-07, D-32, D-80). At least one, so a query that alone passes the bind limit is
     * refused here with {@code MQ1307}, before any statement, as its one-step page would be (D-138). {@code byKeys}
     * is the query's {@code MODEL_BY_KEYS} statement, compiled here without running, so a caller computes it once per
     * {@code page} or {@code export} call, before step 1. Under inline value handling a key column that would not bind
     * is {@code MQ1502} here, before step 1 (R-FLT-08).
     */
    private <M> int keyBatchSize(ModelQuery<E, ?, M> q, PrimaryKey<M, ?> key, BuiltQuery<M> byKeys) {
        key.requireBindable(byKeys.joins());
        // The statement's own binds, the query's values and the customizer's, before any key is added. JPA reports a
        // literal the provider binds as a parameter of the query; one it renders inline takes no bind. A memoised
        // expression used twice reports once, so its repeated renderings are added back (R-COL-19).
        int ownBinds = em.createQuery(byKeys.query()).getParameters().size() + byKeys.joins().repeatedExpressionBinds();
        withinBindLimit(q, ownBinds + key.columns().size(), 0);
        return keyLimits.clamp(ownBinds, key.columns().size(), primaryKeyFirstBatchSize);
    }

    private <M> List<Loaded<M>> mapAll(Object label, BuiltQuery<M> built, TypedQuery<Tuple> query) {
        List<Tuple> tuples = rows(label, built, query);
        var models = new ArrayList<Loaded<M>>(tuples.size());
        for (Tuple tuple : tuples) {
            models.add(loaded(built, tuple));
        }
        return models;
    }

    // ---- export

    /**
     * Whether {@code rowKey} is new to the export: a key seen before is a row the stable order already delivered,
     * across the boundary when rows shifted between pages, or within the page when a customizer's to-many join, or
     * one R-EXE-16 leaves in the statement, repeats the root. Records it in {@code pageKeys}.
     */
    private static boolean isFresh(Set<Object> pageKeys, Set<Object> previousKeys, Object rowKey) {
        return pageKeys.add(rowKey) && !previousKeys.contains(rowKey);
    }

    /**
     * The export loop of engine/21 §4 in offset mode; {@code built} is already in its stable order, which
     * {@code keyOfRow} identifies a row of: its primary key, or its group key tuple for a grouped query (R-PAG-11),
     * for which {@code key} is {@code null}. A page past the {@code primaryKeyFirst} offset reads its keys first and
     * only the fresh keys' rows (R-PAG-07).
     */
    private <M, S> long exportByOffset(ModelQuery<E, ?, M> q, BuiltQuery<M> built, PrimaryKey<M, ?> key,
            Function<Row, Object> keyOfRow, int pageSize, long limit, Function<List<M>, List<S>> pageTransformer,
            Consumer<S> sink) {
        long passed = 0;
        int offset = 0;
        BuiltQuery<M> keyQuery = null;
        int batch = 0;
        if (key != null && q.primaryKeyFirst().map(pkFirst -> limit > pkFirst.offsetThreshold()).orElse(false)) {
            // Step 1's statement, and step 2's batch checked against the bind limit, before the first page, so a
            // refused step 2 runs no statement at all; only when the export can pass the offset threshold, so an
            // export that stays within it is not refused for binds it never runs (R-PAG-07, R-PAG-13, D-138).
            BuiltQuery<M> byKeys = q.buildQuery(em.getCriteriaBuilder(), Phase.MODEL_BY_KEYS, renderOptions);
            refuseToManySelection(q, byKeys, "primary-key-first paging");
            keyQuery = keyQuery(q);
            batch = keyBatchSize(q, key, byKeys);
            preflightRowsByKeys(q, byKeys);
        }
        if (preflights(q)) {
            // Every page runs one of these statements, at another offset (R-VND-26).
            preflight(q, "export page", StatementShape.CONTENT, built);
            if (keyQuery != null) {
                preflight(q, "primary-key-first export page's keys", StatementShape.CONTENT, keyQuery);
            }
        }
        // The only state carried from page to page, so memory stays bounded by one page (R-PAG-02, INV-4).
        Set<Object> previousKeys = Set.of();
        while (passed < limit) {
            Set<Object> keys = new HashSet<>();
            List<Loaded<M>> fresh;
            int read;
            if (keyQuery != null && primaryKeyFirst(q, offset)) {
                // Only an ungrouped query with a primary key gets here (MQ1201, MQ1402); keyQuery is unset only when
                // the limit cannot reach the threshold, where a duplicate-thinned page may still cross it.
                List<Object> pageKeys = readKeys(q, key, keyQuery, offset, pageSize);
                if (filterOnlyToManyJoin(keyQuery).isEmpty()) {
                    checkAdjacentKeys(q, pageKeys);
                }
                List<Object> freshKeys = new ArrayList<>(pageKeys.size());
                for (Object rowKey : pageKeys) {
                    if (isFresh(keys, previousKeys, rowKey)) {
                        freshKeys.add(rowKey);
                    }
                }
                fresh = readByKeys(q, key, freshKeys, batch, true); // only the fresh keys' rows are read (R-PAG-07)
                read = pageKeys.size();
            } else {
                TypedQuery<Tuple> query = create(q, built);
                query.setFirstResult(offset);
                query.setMaxResults(pageSize);
                List<Tuple> rows = rows(q, built, query);
                if (key != null && filterOnlyToManyJoin(built).isEmpty()) {
                    checkAdjacentKeys(q, rows.stream()
                            .map(tuple -> keyOfRow.apply(built.selection().row(tuple))).toList());
                }
                fresh = new ArrayList<>(rows.size());
                for (Tuple tuple : rows) {
                    Row row = built.selection().row(tuple);
                    if (isFresh(keys, previousKeys, keyOfRow.apply(row))) {
                        fresh.add(new Loaded<>(built.map(row), row));
                    }
                }
                read = rows.size();
            }
            // After the dedupe, so the plan runs on exactly the rows passed on (R-FCH-09).
            passed = pass(models(q, fresh), passed, limit, pageTransformer, sink);
            if (read < pageSize) {
                break;
            }
            previousKeys = keys;
            if (offset > Integer.MAX_VALUE - read) {
                throw new ModelQueryExecutionException(MqCode.MQ2002, q + ": offset export went past "
                        + Integer.MAX_VALUE + " rows, the largest offset JPA takes; use keyset() for a result this "
                        + "large");
            }
            offset += read;
        }
        return passed;
    }

    /**
     * The export loop of engine/21 §4 in keyset mode: each page is the rows after the last row of the page before, so
     * no offset is skipped and no page overlaps the last (R-PAG-04, R-PAG-06). {@code first} is the unrestricted first
     * page's statement; each later page is built afresh with the keyset predicate added to the query's own. A page
     * holding a key of the page before throws {@code MQ2205} (R-PAG-14).
     */
    private <M, S> long exportByKeyset(ModelQuery<E, ?, M> q, BuiltQuery<M> first, PrimaryKey<M, ?> key,
            int pageSize, long limit, Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        Keyset<M> keyset = Keyset.of(q, key, vendor.profile().defaultAscendingNullOrdering(), providerNulls,
                keysetNullKeys);
        long passed = 0;
        BuiltQuery<M> built = first;
        // The last row's keyset values and the page's keys: the only state carried from page to page, bounded by one
        // page (INV-4).
        Object[] cursor = null;
        Set<Object> previousKeys = Set.of();
        keyset.appendOrder(first, cb);
        // Each later page binds a cursor read from the page before: refuse one that can't bind before any page runs.
        keyset.requireBindableCursors(first.joins());
        if (preflights(q)) {
            // The first page, then each later page's shape, before the first page runs (R-VND-26).
            preflight(q, "keyset export page", StatementShape.CONTENT, first);
            preflightLaterPages(q, keyset, false, "later keyset export page");
        }
        while (passed < limit) {
            Keyset.Beyond after = null;
            if (cursor != null) {
                built = q.buildQuery(cb, Phase.MODEL, renderOptions);
                after = keyset.after(cursor, built.joins(), cb);
                Predicate own = built.query().getRestriction();
                built.query().where(own == null ? after.predicate() : cb.and(own, after.predicate()));
                keyset.appendOrder(built, cb);
            }
            TypedQuery<Tuple> query = create(q, em, built.query(), keyset, cursor, built.joins());
            if (after != null) {
                after.bindTo(query);
            }
            query.setMaxResults(pageSize);
            List<Tuple> rows = rows(q, built, query);
            checkPageKeys(q, key, built, rows);
            Set<Object> keys = new HashSet<>();
            List<Loaded<M>> fresh = new ArrayList<>(rows.size());
            for (Tuple tuple : rows) {
                Row row = built.selection().row(tuple);
                Object rowKey = Keys.keyOf(q, key, row);
                if (previousKeys.contains(rowKey)) {
                    // A cursor value did not compare equal to the stored one once bound, or the row's keyset value
                    // moved after the cursor between pages. Either can skip rows too, and a repeated tie group that
                    // fills a page would loop, so this throws rather than dedupes (R-PAG-14, D-31).
                    throw new ModelQueryExecutionException(MqCode.MQ2205, q + ": a keyset export page repeated a row "
                            + "of the page before: a cursor value did not survive being bound, or a row's keyset "
                            + "value changed during the export");
                }
                // Read from every row, not only the last, so a NULL key is refused wherever the page holds it.
                cursor = keyset.cursor(row);
                // Equal keys sort together and the cursor is past them all, so a key repeats only within a page,
                // when a customizer's to-many join, or one R-EXE-16 leaves in the statement, repeats the root, and
                // that repeat is dropped (R-PAG-02).
                if (keys.add(rowKey)) {
                    fresh.add(new Loaded<>(built.map(row), row));
                }
            }
            passed = pass(models(q, fresh), passed, limit, pageTransformer, sink);
            if (rows.size() < pageSize) {
                break;
            }
            previousKeys = keys;
        }
        return passed;
    }

    /**
     * Passes a page's models to {@code pageTransformer} and its items to {@code sink} until {@code limit}; neither is
     * called for an empty page (R-PAG-09). Returns the items passed so far (R-PAG-10).
     */
    private static <M, S> long pass(List<M> fresh, long passed, long limit,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        if (fresh.isEmpty()) {
            return passed;
        }
        List<S> items = Objects.requireNonNull(pageTransformer.apply(fresh), "pageTransformer result");
        long total = passed;
        for (int i = 0; i < items.size() && total < limit; i++) {
            sink.accept(items.get(i));
            total++;
        }
        return total;
    }

    /**
     * The row's group: its group-by values in {@code groupBy} order, unique per row of a grouped query (R-PAG-11). SQL
     * groups NULLs together, so a NULL is a value of the tuple like any other; a whole-table aggregate's is empty.
     */
    private static <M> Object groupKeyOf(ModelQuery<?, ?, M> q, Row row) {
        List<ScalarField<M, ?>> groupBy = q.groupBy();
        Object[] values = new Object[groupBy.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row.raw(groupBy.get(i));
        }
        return Arrays.asList(values);
    }

    /**
     * Throws {@code MQ2204} when a column of {@code built}'s selection is read through a to-many join, before any
     * query runs: one primary key then spans several rows, so the stable order is not unique and the boundary dedupe
     * would drop real rows (R-PAG-13). An expression reads every column it is built from, so one of them through a
     * to-many join is refused too (R-COL-17).
     */
    private static void refuseToManySelection(ModelQuery<?, ?, ?> q, BuiltQuery<?> built, String operation) {
        for (SelectField<?, ?> field : built.selection().fields()) {
            boolean expression = field instanceof ExpressionField<?, ?>;
            for (ColumnField<?, ?, ?> column : selectedColumns(field)) {
                Join<?, ?> join = toManyJoin(column, built.joins());
                if (join != null) {
                    throw new ModelQueryExecutionException(MqCode.MQ2204, q + ": " + operation + " "
                            + (expression ? "reads a column of an expression" : "selects a column")
                            + " through the to-many join " + joinName(join) + ", so one primary key spans several "
                            + "rows; select from the child side, or filter with Filters.exists(...)");
                }
            }
        }
    }

    /** {@code join}'s attribute as a message names it: {@code Owner.attribute}. */
    private static String joinName(Join<?, ?> join) {
        Attribute<?, ?> attribute = join.getAttribute();
        return attribute.getDeclaringType().getJavaType().getSimpleName() + "." + attribute.getName();
    }

    /** Whether {@code built}'s selection reads any column through a to-many join (R-PAG-13). */
    private static boolean readsThroughToMany(BuiltQuery<?> built) {
        return !selectedToManyJoins(built).isEmpty();
    }

    /** The to-many joins {@code built}'s selected columns are read through, one per such column (R-PAG-13). */
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

    /** The columns a selected field reads: a column itself, an expression's operands, or none for an aggregate. */
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

    /** The nearest to-many join {@code column} is read through, or {@code null} (R-PAG-13, R-COL-19). */
    private static Join<?, ?> toManyJoin(ColumnField<?, ?, ?> column, JoinContext joins) {
        for (Path<?> path = column.path(joins); path != null; path = path.getParentPath()) {
            if (path instanceof Join<?, ?> join && join.getAttribute() instanceof PluralAttribute<?, ?, ?>) {
                return join;
            }
        }
        return null;
    }

    private static <M> void checkPageKeys(Object label, PrimaryKey<M, ?> key, BuiltQuery<M> built,
            List<Tuple> rows) {
        if (filterOnlyToManyJoin(built).isEmpty()) {
            checkAdjacentKeys(label, rows.stream()
                    .map(tuple -> Keys.keyOf(label, key, built.selection().row(tuple))).toList());
        }
    }

    /** One comparison per row, within this page only (R-PAG-26). */
    private static void checkAdjacentKeys(Object label, List<Object> keys) {
        for (int i = 1; i < keys.size(); i++) {
            if (Objects.equals(keys.get(i - 1), keys.get(i))) {
                throw duplicateKey(label, keys.get(i));
            }
        }
    }

    private static ModelQueryExecutionException duplicateKey(Object label, Object key) {
        return new ModelQueryExecutionException(MqCode.MQ2213, label + ": more than one row has primary key " + key);
    }

    private static boolean hasFetchWork(FetchPlan<?> plan) {
        return !plan.childLoads().isEmpty() || !plan.joinPlans().isEmpty() || !plan.enrichers().isEmpty();
    }

    // ---- fetch plans

    /** A mapped row and the row it was read from, whose raw values a child load keys on (R-FCH-05). */
    private record Loaded<M>(M model, Row row, boolean filterOnlyToManyJoin) {
        private Loaded(M model, Row row) {
            this(model, row, false);
        }
    }

    /** {@code tuple} as a {@link Loaded}, its row built once. */
    private static <M> Loaded<M> loaded(BuiltQuery<M> built, Tuple tuple) {
        Row row = built.selection().row(tuple);
        return new Loaded<>(built.map(row), row, filterOnlyToManyJoin(built).isPresent());
    }

    /** The models of {@code rows}, in order. */
    private static <M> List<M> modelsOf(List<Loaded<M>> rows) {
        List<M> models = new ArrayList<>(rows.size());
        rows.forEach(loaded -> models.add(loaded.model()));
        return models;
    }

    /**
     * The models of {@code rows}, in order, with {@code q}'s fetch plan, if any, run once over them: on exactly the
     * rows a call returns or passes on, never on what paging only reads (R-FCH-09).
     */
    private <M> List<M> models(ModelQuery<?, ?, M> q, List<Loaded<M>> rows) {
        Optional<FetchPlan<M>> plan = q.fetch();
        if (plan.isPresent() && !rows.isEmpty()) {
            if (hasFetchWork(plan.get()) && q.primaryKey().isPresent()) {
                PrimaryKey<M, ?> key = q.primaryKey().orElseThrow();
                Set<Object> seen = new HashSet<>();
                for (Loaded<M> loaded : rows) {
                    Object rowKey = Keys.keyOf(q, key, loaded.row());
                    if (!loaded.filterOnlyToManyJoin() && !seen.add(rowKey)) {
                        throw duplicateKey(q, rowKey);
                    }
                }
            }
            return run(plan.get(), q.toString(), rows);
        }
        return modelsOf(rows);
    }

    /**
     * Runs {@code plan} once over {@code rows}, a non-empty page: its children, then its join plans, each running its
     * nested plan whole, then its enrichers in order, so a nested plan's enrichers run before this one's (R-FCH-08).
     * {@code owner} names the plan's model, or its join path, in a message.
     */
    private <M> List<M> run(FetchPlan<M> plan, String owner, List<Loaded<M>> rows) {
        List<M> models = modelsOf(rows);
        for (ChildLoad<M, ?> load : plan.childLoads()) {
            loadChild(load, rows, models);
        }
        for (JoinPlan<M, ?> join : plan.joinPlans()) {
            runJoin(join, owner, rows, models);
        }
        List<M> result = models;
        for (Enricher<M> enricher : plan.enrichers()) {
            result = enricher.enrich(owner, result);
        }
        return result;
    }

    /**
     * Runs {@code join}'s nested plan once over the nested models present in {@code models}, one entry per parent,
     * duplicates included, each read from its parent's row under the join, and sets each result back on its parent.
     * A parent whose join found nothing is skipped (R-FCH-07).
     */
    private <M, N> void runJoin(JoinPlan<M, N> join, String owner, List<Loaded<M>> rows, List<M> models) {
        JoinField<M, N> field = join.field();
        List<Integer> parents = new ArrayList<>();
        List<Loaded<N>> nested = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            Optional<N> present = field.get(models.get(i));
            if (present.isPresent()) {
                parents.add(i);
                nested.add(new Loaded<>(present.get(), rows.get(i).row().scoped(field.table())));
            }
        }
        if (nested.isEmpty()) {
            return;
        }
        List<N> filled = run(join.plan(), owner + "." + field.name(), nested);
        for (int k = 0; k < parents.size(); k++) {
            int i = parents.get(k);
            models.set(i, field.with(models.get(i), filled.get(k)));
        }
    }

    /**
     * Loads {@code load}'s children for the distinct non-null keys of {@code rows}, and sets them on the parent at the
     * same position of {@code models}. A parent whose key is null, or that has no child, keeps the empty value it was
     * mapped with (R-FCH-03).
     */
    private <M, C> void loadChild(ChildLoad<M, C> load, List<Loaded<M>> rows, List<M> models) {
        ColumnField<M, ?, ?> key = load.field().key();
        if (!rows.isEmpty() && !rows.get(0).row().isSelected(key)) {
            // raw(...) is null for an unselected column, which would read as "no key" and leave every child empty.
            throw new IllegalStateException(load + ": the parent key " + key + " is not selected, so no child can be "
                    + "matched to a parent");
        }
        Object[] keys = new Object[rows.size()];
        var distinct = new LinkedHashSet<Object>();
        for (int i = 0; i < keys.length; i++) {
            // The attribute value, before any converter, as the child's foreign key is read (R-FCH-05, R-COL-11).
            keys[i] = rows.get(i).row().raw(key);
            if (keys[i] != null) {
                distinct.add(keys[i]);
            }
        }
        if (distinct.isEmpty()) {
            LOG.log(DEBUG, () -> "child " + load + ": no key on the page, so no child query");
            return;
        }
        Map<Object, List<C>> children = childrenOf(load, load.query(), List.copyOf(distinct));
        for (int i = 0; i < keys.length; i++) {
            List<C> found = keys[i] == null ? null : children.get(keys[i]);
            if (found != null) {
                models.set(i, load.field().with(models.get(i), found));
            }
        }
    }

    /**
     * The children of {@code keys}, grouped by key in child order. The keys go in rounds of at most the largest power
     * of two within the profile's limits, each one statement {@code where foreignKey IN keys} with the child filters
     * and the child order closed by the child's primary key; a child read twice for one key is kept once. The child
     * plan then runs once over all the rows read. A {@code through} child's statement is rooted at the parent's
     * entity and matches the parent's key instead, which {@link ChildLoad} builds (R-FCH-04, R-FCH-05, R-FCH-14,
     * D-99, D-100).
     */
    private <E2, C> Map<Object, List<C>> childrenOf(ChildLoad<?, C> load, ModelQuery<E2, ?, C> q,
            List<Object> keys) {
        checkFirstRun(q);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        // The first round takes the most keys any statement could and sizes the rest from its own binds, so no
        // statement is compiled only to be counted (R-PAG-07, D-32).
        int round = Math.min(keys.size(), keyLimits.clamp(0, 1, OptionalInt.empty()));
        boolean sized = false;
        List<Loaded<C>> loaded = new ArrayList<>();
        List<Object> parents = new ArrayList<>();
        for (int from = 0; from < keys.size(); from += round) {
            List<Object> roundKeys = keys.subList(from, Math.min(keys.size(), from + round));
            BuiltQuery<C> built = childRound(load, q, cb, roundKeys);
            TypedQuery<Tuple> query;
            if (sized) {
                query = create(load, built);
            } else {
                sized = true;
                TypedQuery<Tuple> probe = em.createQuery(built.query());
                bindValues(probe, built.joins(), built.query().getParameters());
                int ownBinds = Math.max(0, probe.getParameters().size() + built.joins().repeatedExpressionBinds()
                        + (load.maxPerParent() > 0 ? 1 : 0) - roundKeys.size());
                int sizedRound = keyLimits.clamp(ownBinds, 1, OptionalInt.empty());
                // Logged before the statement is checked, so a refusal still follows its line.
                LOG.log(DEBUG, () -> "child " + load + ": " + keys.size() + (keys.size() == 1 ? " key" : " keys")
                        + " in " + ((keys.size() - 1) / sizedRound + 1) + " round(s)");
                if (roundKeys.size() <= sizedRound) {
                    query = checked(load, probe, null, null, built.joins());
                } else {
                    // More keys than the statement's own binds leave room for: the first round is built again, smaller.
                    roundKeys = keys.subList(from, Math.min(keys.size(), from + sizedRound));
                    built = childRound(load, q, cb, roundKeys);
                    query = create(load, built);
                }
                round = sizedRound;
            }
            long cap = (long) roundKeys.size() * load.maxPerParent() + 1;
            if (load.maxPerParent() > 0) {
                query.setMaxResults((int) Math.min(cap, Integer.MAX_VALUE));
            }
            List<Tuple> tuples = rows(load, built, query);
            Set<Object> wanted = new HashSet<>(roundKeys);
            // Per key: the children kept, by primary key, and the rows read, which MQ2603 names the most of.
            Map<Object, Set<Object>> kept = new HashMap<>();
            Map<Object, Integer> read = new HashMap<>();
            PrimaryKey<C, ?> childKey = q.primaryKey().orElse(null);
            for (Tuple tuple : tuples) {
                Row row = built.selection().row(tuple);
                Object parent = load.key(built, tuple, row);
                if (!wanted.contains(parent)) {
                    throw new ModelQueryExecutionException(MqCode.MQ2604, load + ": a child row's "
                            + load.field().foreignKey().<Object>map(column -> column).orElseGet(load.field()::key)
                            + " is " + parent + ", which equals none of the " + roundKeys.size() + " keys of the "
                            + "statement that matched it; the column's collation equates values Java tells apart, "
                            + "by case or trailing spaces, so key the child on a column with a binary collation");
                }
                read.merge(parent, 1, Integer::sum);
                Set<Object> children = kept.computeIfAbsent(parent, k -> new HashSet<>());
                // A child filter through a to-many join repeats a child, and a many-to-many child belongs to several
                // keys, so a child is kept once per key (R-FCH-04, D-99). Without a primary key, every row is one.
                Object rowKey = childKey == null ? new Object() : Keys.keyOf(load, childKey, row);
                boolean fresh = children.add(rowKey);
                if (fresh) {
                    if (children.size() > 1 && !load.field().isToMany()) {
                        throw new ModelQueryExecutionException(MqCode.MQ2601, load + ": the to-one child found two "
                                + "distinct rows for key " + parent + "; key it on a column unique per child, or "
                                + "declare the field a List");
                    }
                    loaded.add(new Loaded<>(built.map(row), row));
                    parents.add(parent);
                }
            }
            if (load.maxPerParent() > 0) {
                checkBound(load, roundKeys.size(), tuples.size() >= cap, kept, read);
            }
        }
        // Children are deduplicated per parent; a many-to-many child can still belong to several (R-FCH-04).
        List<C> children = loaded.isEmpty() ? List.of()
                : q.fetch().map(plan -> run(plan, q.toString(), loaded)).orElseGet(() -> modelsOf(loaded));
        Map<Object, List<C>> grouped = new HashMap<>();
        for (int i = 0; i < children.size(); i++) {
            grouped.computeIfAbsent(parents.get(i), k -> new ArrayList<>()).add(children.get(i));
        }
        return grouped;
    }

    /**
     * One round of {@link #childrenOf}: {@code load}'s statement built again, filtered to {@code roundKeys} (R-FCH-04).
     */
    private <C> BuiltQuery<C> childRound(ChildLoad<?, C> load, ModelQuery<?, ?, C> q, CriteriaBuilder cb,
            List<Object> roundKeys) {
        BuiltQuery<C> built = load.build(cb, renderOptions);
        Predicate byKey = load.keyIn(built, roundKeys);
        Predicate own = built.query().getRestriction();
        built.query().where(own == null ? byKey : cb.and(own, byKey));
        appendStableOrder(q, built);
        return built;
    }

    /**
     * Throws {@code MQ2603} when a key of a round has more distinct children than {@code maxPerParent}, or the round
     * read its cap of keys × {@code maxPerParent} + 1 rows, and names the key with the most rows read (R-FCH-11).
     */
    private static void checkBound(ChildLoad<?, ?> load, int keys, boolean capped, Map<Object, Set<Object>> kept,
            Map<Object, Integer> read) {
        int bound = load.maxPerParent();
        Object over = null;
        Object most = null;
        for (Map.Entry<Object, Integer> entry : read.entrySet()) {
            Object key = entry.getKey();
            if (kept.get(key).size() > bound && (over == null || entry.getValue() > read.get(over))) {
                over = key;
            }
            if (most == null || entry.getValue() > read.get(most)) {
                most = key;
            }
        }
        if (over != null) {
            throw new ModelQueryExecutionException(MqCode.MQ2603, load + ": key " + over + " has "
                    + kept.get(over).size() + " children, more than maxPerParent(" + bound + "); raise the bound or "
                    + "narrow the child filters");
        }
        if (capped) {
            throw new ModelQueryExecutionException(MqCode.MQ2603, load + ": a round of " + keys + " keys read its "
                    + "cap of " + keys + " x maxPerParent(" + bound + ") + 1 rows, key " + most + " the most, "
                    + read.get(most) + "; raise the bound or narrow the child filters");
        }
    }

    // ---- count

    /**
     * Counts {@code q}'s rows, its count statement preflighted first when it holds a treated node (R-VND-26), and a
     * grouped one's groups client-side when {@code clientSide} holds a reason (D-159).
     */
    private <M> long countRows(ModelQuery<E, ?, M> q, Optional<String> clientSide) {
        return countRows(q, preflightedCounting(q, clientSide));
    }

    /**
     * {@code q}'s count statement, preflighted when it holds a treated node (R-VND-26); a grouped one counts its rows
     * client-side when {@code clientSide} holds the reason its sites were refused as {@code GROUPED_COUNT} (D-159).
     */
    private <M> Counting<M> preflightedCounting(ModelQuery<E, ?, M> q, Optional<String> clientSide) {
        Counting<M> counting = counting(q, clientSide);
        if (preflights(q)) {
            preflight(q, "count", StatementShape.COUNT, counting.statement(),
                    counting.built().expectedNarrowings());
        }
        return counting;
    }

    /** Runs {@code counting}, the count statement {@link #counting} built for {@code q}. */
    private <M> long countRows(ModelQuery<E, ?, M> q, Counting<M> counting) {
        BuiltQuery<M> built = counting.built();
        if (counting.groups() != null) {
            return single(q, create(q, em, counting.groups(), null, null, built.joins()));
        }
        if (counting.inMemory()) {
            if (counting.refusal().isPresent()) {
                LOG.log(System.Logger.Level.WARNING, "count over the grouped query on {0} runs it and counts its "
                        + "rows in memory, because the treat renderer refuses the count of its groups: {1} (R-EXE-03)",
                        rootEntity.getSimpleName(), counting.refusal().get());
            } else if (built.recursionChecks().isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "count over the grouped query on {0} runs it and counts its "
                        + "rows in memory, because no ProviderSupport counts groups for this persistence provider; "
                        + "add model-query-hibernate for a count in the database (R-EXE-03)",
                        rootEntity.getSimpleName());
            }
            // Read through the check site: the group rows carry the checks, and no group carries none (R-SRC-08).
            return rows(q, built, create(q, built)).size();
        }
        return ((Number) single(q, built, create(q, built)).get(0)).longValue();
    }

    /**
     * A count's statement, built and not run: {@code built}'s own, or {@code groups}, the provider's count over a
     * grouped statement; {@code inMemory} when a grouped statement's rows are counted client-side, with the treat
     * renderer's {@code refusal} when it refused the count of the groups (D-159). {@code notWindowed} says why a
     * window on the content statement would not count what this counts, and is empty where it would: the content
     * statement with its selection replaced by {@code count(root)} or {@code count(1)}, or the database's count of its
     * groups (R-EXE-15).
     */
    private record Counting<M>(BuiltQuery<M> built, CriteriaQuery<Long> groups, boolean inMemory,
            Optional<String> refusal, Optional<String> notWindowed) {

        /** The statement the count runs. */
        CriteriaQuery<?> statement() {
            return groups != null ? groups : built.query();
        }
    }

    /**
     * The statement that counts {@code q}'s rows, or its groups for a grouped query (R-EXE-05), client-side when
     * {@code clientSide} holds the renderer's refusal of the count of the groups (D-159).
     */
    private <M> Counting<M> counting(ModelQuery<E, ?, M> q, Optional<String> clientSide) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        if (!q.isGrouped() && q.source().isEmpty() && TreatChecks.treated(q)) {
            // Built fresh and counting the root id: the content statement with its selection replaced would lose the
            // rows a select-only or order-only treat narrows away, and count(root) those a null-accepting predicate
            // keeps (R-COL-35, R-EXE-05). A grouped query keeps its count of groups below.
            return new Counting<>(q.buildRootIdCount(cb, renderOptions), null, false, Optional.empty(),
                    Optional.of("COUNT counts a treated, ungrouped root on a freshly built statement"));
        }
        BuiltQuery<M> built = q.buildQuery(cb, Phase.MODEL, renderOptions);
        CriteriaQuery<Tuple> query = built.query();
        query.orderBy(List.<Order>of()); // the count needs no order (R-EXE-05)
        if (q.isGrouped()) {
            return groupCounting(q, built, clientSide);
        }
        // Every predicate and join stays, since one can change the number of rows; only the selection is replaced.
        // This drops select-only correlated sub-queries but keeps those in WHERE (R-EXE-05).
        From<?, ?> root = query.getRoots().iterator().next();
        // A source's root is the CTE's, whose derived model part has no identifying parts to count or expand, so the
        // rows are counted by a constant (R-SRC-12); a source root has no to-many join, which needs no dedup.
        boolean source = q.source().isPresent();
        boolean readThroughToMany = !source && readsThroughToMany(built);
        boolean distinctRoots = !source && hasToManyJoin(root) && !readThroughToMany;
        Expression<Long> count = source ? cb.count(cb.literal(1))
                : distinctRoots ? cb.countDistinct(root) : cb.count(root);
        // The count replaces the selection, so a recursive source's checks are selected beside it, and the count's own
        // statement is the one that reads them (R-SRC-07).
        List<Selection<?>> selections = new ArrayList<>();
        selections.add(count);
        selections.addAll(built.recursionChecks());
        query.multiselect(selections);
        // A recursive source's checks beside the count fall back too: COUNT's one row reads them even over no row,
        // where an empty page would leave them unread (R-SRC-08).
        Optional<String> notWindowed = distinctRoots
                ? Optional.of("COUNT counts distinct roots over a to-many join no selected column reads")
                : !built.recursionChecks().isEmpty()
                        ? Optional.of("COUNT selects the recursive source's checks beside its count")
                        : Optional.empty();
        return new Counting<>(built, null, false, Optional.empty(), notWindowed);
    }

    /**
     * {@code count(*)} over the groups: in the database when the provider support can, else client-side, as when
     * {@code clientSide} holds the renderer's refusal of the count of the groups (D-159).
     */
    private <M> Counting<M> groupCounting(ModelQuery<E, ?, M> q, BuiltQuery<M> built, Optional<String> clientSide) {
        CriteriaQuery<Tuple> query = built.query();
        // A source's own checks are part of every statement's selection and a count keeps them (R-EXE-05, R-SRC-08).
        List<Selection<?>> checks = built.recursionChecks();
        if (!q.groupBy().isEmpty()) {
            // The group keys identify a group; the checks are selected beside them, which is what the count needs
            // (R-EXE-05).
            List<Selection<?>> keys = new ArrayList<>();
            for (ScalarField<M, ?> key : q.groupBy()) {
                // Two keys over one attribute can resolve to one aliased path, which is selected once (R-COL-10).
                Selection<?> path = key.expression(built.joins());
                if (keys.stream().noneMatch(added -> added == path)) {
                    keys.add(path);
                }
            }
            keys.addAll(checks);
            query.multiselect(keys);
        }
        // A count over groups over a source that carries checks counts client-side: whatever the provider's
        // createCountQuery() projects, the grouped query's CTE ends up inside its derived table, out of the checks'
        // reach, and with zero groups no row carries them (R-EXE-03, RFC 0014 §3). The provider support only builds
        // the count; it runs here, so the configured timeout applies (R-EXE-11).
        // A renderer that refuses the count of the groups (GROUPED_COUNT) has them counted client-side too (D-159).
        Optional<CriteriaQuery<Long>> count = checks.isEmpty() && clientSide.isEmpty()
                ? vendor.providerSupport().flatMap(p -> p.countQuery(query))
                : Optional.empty();
        Optional<String> notWindowed = count.isPresent() ? Optional.empty()
                : Optional.of("COUNT counts the groups client-side: " + (clientSide.isPresent()
                        ? "the treat renderer refuses their count" : !checks.isEmpty()
                        ? "the source carries checks" : "no ProviderSupport counts groups"));
        return new Counting<>(built, count.orElse(null), count.isEmpty(), clientSide, notWindowed);
    }

    private static boolean hasToManyJoin(From<?, ?> from) {
        for (Join<?, ?> join : from.getJoins()) {
            if (join.getAttribute() instanceof PluralAttribute<?, ?, ?> || hasToManyJoin(join)) {
                return true;
            }
        }
        return false;
    }

}
