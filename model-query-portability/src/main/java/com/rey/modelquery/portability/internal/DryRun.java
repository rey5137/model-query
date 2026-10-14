package com.rey.modelquery.portability.internal;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ModelQueryException;
import com.rey.modelquery.jpa.ChunkTransactions;
import com.rey.modelquery.jpa.ModelQueryConfig;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import com.rey.modelquery.jpa.spi.VendorProfile;
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
 * @implSpec R-INS-11, D-133, D-138
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
        Objects.requireNonNull(unit, "unit");
        ModelQueryConfig harness = harnessConfig(config);
        // Every target boots before any call runs, so a unit that cannot boot fails before a line is reported.
        Map<Target, EntityManagerFactory> factories = new LinkedHashMap<>();
        for (Target target : targets) {
            factories.put(target, OfflineFactory.boot(unit, target));
        }
        List<CallResult> results = new ArrayList<>();
        for (DryCall call : calls) {
            factories.forEach((target, emf) -> results.add(run(emf, target, harness, call)));
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

    /** {@code call} on {@code emf}, the factory of {@code target}, classified. */
    @SuppressWarnings("unchecked")
    static CallResult run(EntityManagerFactory emf, Target target, ModelQueryConfig config, DryCall call) {
        Throwable thrown = null;
        boolean firstStatementOnly = false;
        try (JdbcRecording recording = JdbcRecording.call()) {
            EntityManager em = emf.createEntityManager();
            Object result = null;
            try {
                if (!call.outsideTransaction()) {
                    em.getTransaction().begin();
                }
                ModelQueryExecutor<Object> executor = ModelQueryExecutor.create(em, (Class<Object>) call.root(),
                        config);
                firstStatementOnly = call.firstStatementOnly(emf, profile(emf, config));
                result = call.body().apply(executor);
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
            return classify(call.label(), target, firstStatementOnly, recording, thrown, providerPackage(emf));
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
