package com.rey.modelquery.portability.internal;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.FetchPlan;
import com.rey.modelquery.core.JoinPlan;
import com.rey.modelquery.core.KeyGroups;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.VendorProfile;
import com.rey.modelquery.jpa.vendor.ChildStatements;
import com.rey.modelquery.jpa.vendor.VendorResolver;
import com.rey.modelquery.portability.Target;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.BaseStream;

/**
 * Runs each call on each target's offline factory, one {@code EntityManager} and one JDBC recording per call and
 * target, and classifies it: by the recording first, then the cause chain, never the top-level exception type, since
 * the library wraps a chunk's failure in {@code ChunkedWriteException} and a provider wraps the stub's stop in its
 * own exception.
 *
 * @implSpec R-INS-11, R-INS-17, D-133, D-138, D-224
 */
@Incubating
public final class DryRun {

    private DryRun() {
    }

    /**
     * The results of {@code calls} on {@code targets}, call by call and, within a call, target by target, under
     * {@linkplain #harnessConfig(ModelQueryConfig) the harness's copy} of {@code config}.
     *
     * @throws com.rey.modelquery.core.ModelQueryConfigurationException {@code MQ4013} when a target's factory cannot
     *     boot offline
     */
    public static List<CallResult> run(OfflineUnit unit, List<Target> targets, ModelQueryConfig config,
            List<DryCall> calls) {
        return runEach(unit, targets, config, calls).stream().flatMap(List::stream).toList();
    }

    /**
     * As {@link #run(OfflineUnit, List, ModelQueryConfig, List)}, one list per call in {@code calls}' order, each
     * call's results target by target.
     *
     * @throws com.rey.modelquery.core.ModelQueryConfigurationException {@code MQ4013} when a target's factory cannot
     *     boot offline
     */
    public static List<List<CallResult>> runEach(OfflineUnit unit, List<Target> targets, ModelQueryConfig config,
            List<DryCall> calls) {
        Objects.requireNonNull(unit, "unit");
        ModelQueryConfig harness = harnessConfig(config);
        // Every target boots before any call runs, so a unit that cannot boot fails before a line is reported.
        Map<Target, EntityManagerFactory> factories = new LinkedHashMap<>();
        for (Target target : targets) {
            factories.put(target, OfflineFactory.boot(unit, target));
        }
        List<List<CallResult>> results = new ArrayList<>();
        for (DryCall call : calls) {
            List<CallResult> each = new ArrayList<>();
            factories.forEach((target, emf) -> each.addAll(run(emf, target, harness, call)));
            results.add(each);
        }
        return results;
    }

    /**
     * {@code config} as the dry run uses it: without an explicit {@code vendor(...)}, which would skip each target's
     * detection, and with the harness's own {@code ChunkTransactions} in place of the application's, which cannot
     * serve an offline factory (a false {@code MQ4004}); every other setting is kept.
     */
    // D-138: the explicit vendor is stripped, not refused; ChunkTransactions is replaced only when the application
    // configures one, so a commitEachChunk() call without one stays a true MQ4004.
    public static ModelQueryConfig harnessConfig(ModelQueryConfig config) {
        Objects.requireNonNull(config, "config");
        ModelQueryConfig kept = ModelQueryConfig.defaults();
        kept = copy(kept, config, ModelQueryConfig::vendorProfiles, ModelQueryConfig::vendorProfiles);
        kept = copy(kept, config, ModelQueryConfig::primaryKeyFirstBatchSize,
                (to, size) -> to.primaryKeyFirstBatchSize(size.getAsInt()));
        kept = copy(kept, config, ModelQueryConfig::queryTimeout, (to, timeout) -> to.queryTimeout(timeout.get()));
        kept = copy(kept, config, ModelQueryConfig::mysqlStreamingMode, ModelQueryConfig::mysqlStreamingMode);
        kept = copy(kept, config, ModelQueryConfig::keysetNullKeys, ModelQueryConfig::keysetNullKeys);
        kept = copy(kept, config, ModelQueryConfig::exportPageSize, ModelQueryConfig::exportPageSize);
        kept = copy(kept, config, ModelQueryConfig::streamFetchSize, ModelQueryConfig::streamFetchSize);
        kept = copy(kept, config, ModelQueryConfig::persistenceContextMode, ModelQueryConfig::persistenceContextMode);
        kept = copy(kept, config, ModelQueryConfig::bulkWriteChunkSize, ModelQueryConfig::bulkWriteChunkSize);
        kept = copy(kept, config, ModelQueryConfig::conflictUpdateWhereOnAssignedColumns,
                ModelQueryConfig::conflictUpdateWhereOnAssignedColumns);
        kept = copy(kept, config, ModelQueryConfig::writeAssignments, ModelQueryConfig::writeAssignments);
        kept = copy(kept, config, ModelQueryConfig::targetVendors, (to, vendors) -> to.targetVendors(vendors.get()));
        return config.chunkTransactions().isPresent() ? kept.chunkTransactions(new HarnessChunks()) : kept;
    }

    /** {@code to} with {@code from}'s setting, when it is not the default. */
    private static <T> ModelQueryConfig copy(ModelQueryConfig to, ModelQueryConfig from,
            Function<ModelQueryConfig, T> get, BiFunction<ModelQueryConfig, T, ModelQueryConfig> set) {
        T value = get.apply(from);
        return Objects.equals(value, get.apply(ModelQueryConfig.defaults())) ? to : set.apply(to, value);
    }

    /**
     * {@code call} on {@code emf}, the factory of {@code target}, classified; when every statement of the call is
     * reached, then each child statement of the fetch plan its query runs (D-224).
     */
    static List<CallResult> run(EntityManagerFactory emf, Target target, ModelQueryConfig config, DryCall call) {
        Pass first = pass(emf, target, config, call, 0);
        CallResult result = first.result();
        if (result.outcome() != Outcome.REACHED) {
            return List.of(result);
        }
        List<CallResult> results = new ArrayList<>();
        results.add(result.withStatement(first.page().statement()));
        if (first.page().countedPage() != 0) {
            Pass second = pass(emf, target, config, call, first.page().countedPage());
            results.add(second.result().withStatement(first.page().content()));
            if (second.result().outcome() != Outcome.REACHED) {
                return results;
            }
        }
        // A parent refused on the target walks no children there; one reached renders them on placeholder keys.
        first.parent().query().flatMap(ModelQuery::fetch)
                .ifPresent(plan -> children(emf, target, config, call, plan, "", results));
        return results;
    }

    private record Pass(CallResult result, PagePass page, ParentQuery parent) {}

    /**
     * Each child load of {@code plan} at {@code path}, depth first, then each of its join plans' (R-INS-17): a child
     * reached walks its own children on placeholders of their keys; a child whose key type has no placeholder gives a
     * failing line naming the type.
     */
    private static void children(EntityManagerFactory emf, Target target, ModelQueryConfig config, DryCall call,
            FetchPlan<?> plan, String path, List<CallResult> results) {
        for (ChildLoad<?, ?> load : plan.childLoads()) {
            String at = path.isEmpty() ? load.field().name() : path + "." + load.field().name();
            Class<?> type = Placeholders.boxed(load.field().key().attributeType());
            Optional<List<Object>> keys = Placeholders.of(type);
            if (keys.isEmpty()) {
                results.add(new CallResult(call.label(), target, Outcome.HARNESS_ERROR, false, "the checker can't "
                        + "build a placeholder key of " + type.getName() + " for " + load + ", so its statement is "
                        + "not checked; with real keys it runs").withChild(at));
                continue;
            }
            CallResult child = child(emf, target, config, call, load, keys.get()).withChild(at);
            results.add(child);
            if (child.outcome() == Outcome.REACHED) {
                load.query().fetch().ifPresent(own -> children(emf, target, config, call, own, at, results));
            }
        }
        for (JoinPlan<?, ?> join : plan.joinPlans()) {
            String at = path.isEmpty() ? join.field().name() : path + "." + join.field().name();
            children(emf, target, config, call, join.plan(), at, results);
        }
    }

    /** {@code load} run on {@code keys}, on its own {@code EntityManager} and recording, classified. */
    private static CallResult child(EntityManagerFactory emf, Target target, ModelQueryConfig config, DryCall call,
            ChildLoad<?, ?> load, List<Object> keys) {
        Throwable thrown = null;
        try (JdbcRecording recording = JdbcRecording.call()) {
            EntityManager em = emf.createEntityManager();
            try {
                if (!call.outsideTransaction()) {
                    em.getTransaction().begin();
                }
                @SuppressWarnings("unchecked")
                ModelQueryExecutor<Object> executor = ModelQueryExecutor.create(em, (Class<Object>) call.root(),
                        config);
                ChildStatements.run(executor, load, keys);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable t) { // any other throwable is classified, never propagated
                thrown = t;
            } finally {
                thrown = end(em, null, thrown);
            }
            CallResult result = classify(call.label(), target, false, recording, thrown, providerPackage(emf));
            boolean beforeStatement = thrown != null && recording.statements().isEmpty()
                    && recording.deniedBeforeStatement().isEmpty();
            return beforeStatement && placeholderGroupFails(load, keys.get(0))
                    ? new CallResult(call.label(), target, Outcome.HARNESS_ERROR, false, "the checker's limit, not "
                            + "the application's error: the key groups of " + load + " have no catch-all, and their "
                            + "group of the placeholder key " + keys.get(0) + " fails: " + result.detail())
                    : result;
        }
    }

    /**
     * Whether {@code load}'s key groups have no catch-all and their group or value function fails on the placeholder
     * {@code key}, asked again after the child run failed before its statement: the child's own checks passed with
     * the parent's first run (R-FCH-04, D-138), so a group function's {@code MQ2607}, a value's {@code MQ1502}, or
     * either function's own exception is about a key no real parent sends.
     */
    private static boolean placeholderGroupFails(ChildLoad<?, ?> load, Object key) {
        KeyGroups<?, ?> groups = load.keyGroups().filter(g -> g.catchAll().isEmpty()).orElse(null);
        if (groups == null) {
            return false;
        }
        try {
            groups.values(groups.groupOf(key, load), load);
            return false;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) { // the functions' own exceptions propagate unwrapped (D-204)
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static Pass pass(EntityManagerFactory emf, Target target, ModelQueryConfig config, DryCall call,
            int rewrite) {
        Throwable thrown = null;
        boolean firstStatementOnly = false;
        try (JdbcRecording recording = JdbcRecording.call()) {
            EntityManager em = emf.createEntityManager();
            Object result = null;
            PagePass page = null;
            ParentQuery parent = null;
            try {
                if (!call.outsideTransaction()) {
                    em.getTransaction().begin();
                }
                ModelQueryExecutor<Object> executor = ModelQueryExecutor.create(em, (Class<Object>) call.root(),
                        config);
                firstStatementOnly = call.firstStatementOnly(emf, profile(emf, config));
                page = new PagePass(executor, recording, rewrite);
                parent = new ParentQuery(page.executor(), recording);
                result = call.body().apply(parent.executor());
                drainOne(result);
                Object done = result;
                result = null;
                close(done);
                if (em.getTransaction().isActive()) {
                    em.flush(); // what the call left pending is part of it, a persist's insert among them
                }
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable t) { // any other throwable is classified, never propagated
                thrown = t;
            } finally {
                thrown = end(em, result, thrown);
            }
            return new Pass(classify(call.label(), target, firstStatementOnly, recording, thrown,
                    providerPackage(emf)), page, parent);
        }
    }

    /** Closes {@code result} if the call did not, then rolls back and closes {@code em}; returns what was thrown. */
    private static Throwable end(EntityManager em, Object result, Throwable thrown) {
        Throwable first = thrown;
        try {
            close(result);
        } catch (Exception e) {
            first = first == null ? e : first;
        }
        try {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
        } catch (RuntimeException e) {
            first = first == null ? e : first;
        } finally {
            em.close();
        }
        return first;
    }

    /** Draws one element of a lazy result, so a statement it defers is reached. */
    private static void drainOne(Object result) {
        Iterator<?> iterator = result instanceof BaseStream<?, ?> stream ? stream.iterator()
                : result instanceof Iterator<?> it ? it : null;
        if (iterator != null && iterator.hasNext()) {
            iterator.next();
        }
    }

    private static void close(Object result) throws Exception {
        if (result instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    /** The profile an executor over {@code emf} uses, for a sweep's marks. */
    private static VendorProfile profile(EntityManagerFactory emf, ModelQueryConfig config) {
        return VendorResolver.withSupplied(emf, VendorResolver.resolve(emf, Optional.empty(),
                config.mysqlStreamingMode()), config.vendorProfiles()).profile();
    }

    /** The provider's root package, {@code org.hibernate} for Hibernate, read from the factory's class. */
    private static String providerPackage(EntityManagerFactory emf) {
        String[] parts = emf.getClass().getName().split("\\.");
        return parts.length > 2 ? parts[0] + "." + parts[1] + "." : emf.getClass().getPackageName() + ".";
    }

    /** The outcome of a call that recorded {@code recording} and threw {@code thrown} (null when it returned). */
    static CallResult classify(String label, Target target, boolean firstStatementOnly, JdbcRecording recording,
            Throwable thrown, String providerPackage) {
        List<String> denied = recording.deniedBeforeStatement();
        if (!denied.isEmpty()) {
            return new CallResult(label, target, Outcome.HARNESS_ERROR, false, "reached JDBC other than through a "
                    + "statement: " + denied + (thrown == null ? "" : ", then threw " + oneLine(thrown.toString())));
        }
        List<String> statements = recording.statements();
        Optional<ModelQueryException> refusal = innermost(thrown, ModelQueryException.class::isInstance)
                .map(ModelQueryException.class::cast);
        if (statements.isEmpty()) {
            if (thrown == null) {
                return new CallResult(label, target, Outcome.NO_STATEMENT, false,
                        "the call returned having reached no statement");
            }
            if (refusal.isPresent()) {
                return new CallResult(label, target, Outcome.REFUSED, false, oneLine(refusal.get().getMessage()));
            }
            // A stub stop (a denial or a reached statement) with no recording means JDBC was reached on a thread the
            // recording does not follow; the provider's wrapping of it is not a refusal of the call (R-INS-11).
            if (innermost(thrown, StubDataSource.Stop.class::isInstance).isPresent()) {
                return new CallResult(label, target, Outcome.HARNESS_ERROR, false, "JDBC was reached off the calling "
                        + "thread: " + oneLine(thrown.toString()));
            }
            // D-138: a provider's translation error before any statement is told by its class's package, the
            // factory's provider root (org.hibernate), since the module may not name a provider type (INV-7).
            Optional<Throwable> translation = innermost(thrown, t -> t.getClass().getName().startsWith(
                    providerPackage));
            if (translation.isPresent()) {
                Throwable error = translation.get();
                return new CallResult(label, target, Outcome.REFUSED, false, error.getClass().getSimpleName() + ": "
                        + oneLine(String.valueOf(error.getMessage())));
            }
            return new CallResult(label, target, Outcome.HARNESS_ERROR, false, oneLine(thrown.toString()));
        }
        String first = oneLine(statements.get(0));
        if (thrown == null || innermost(thrown, StubDataSource.StatementReached.class::isInstance).isPresent()) {
            return new CallResult(label, target, Outcome.REACHED, firstStatementOnly, first);
        }
        if (refusal.isPresent()) {
            return new CallResult(label, target, Outcome.REFUSED_AFTER_STATEMENT, false,
                    oneLine(refusal.get().getMessage()) + " after " + first);
        }
        return new CallResult(label, target, Outcome.HARNESS_ERROR, false, oneLine(thrown.toString()) + " after "
                + first);
    }

    /** The innermost throwable in {@code thrown}'s cause chain that {@code matches}, cycles cut. */
    private static Optional<Throwable> innermost(Throwable thrown, Predicate<Throwable> matches) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable found = null;
        for (Throwable t = thrown; t != null && seen.add(t); t = t.getCause()) {
            if (matches.test(t)) {
                found = t;
            }
        }
        return Optional.ofNullable(found);
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s*\\R\\s*", " ").strip();
    }

    /**
     * Runs each chunk of a {@code commitEachChunk()} write on its own {@code EntityManager} in a stub transaction,
     * flushed and rolled back: the stub keeps nothing, and the chunk's statements are recorded as the call's.
     */
    private static final class HarnessChunks implements ChunkTransactions {
        @Override
        public <T> T inNewTransaction(EntityManagerFactory emf, Function<EntityManager, T> chunk) {
            EntityManager em = emf.createEntityManager();
            try {
                em.getTransaction().begin();
                T result = chunk.apply(em);
                em.flush();
                return result;
            } finally {
                try {
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().rollback();
                    }
                } finally {
                    em.close();
                }
            }
        }
    }
}
