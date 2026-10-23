package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Objects;
import java.util.function.Function;

/**
 * How a query rooted at a source reaches its provider's CTE and window support, so {@code core} names no provider
 * (INV-7): it registers {@code body}, the standard {@code CriteriaQuery<Tuple>} the source's fields alias by name, on
 * the {@code outer} statement that reads it, returns the root of the CTE's columns, and renders a window field's
 * {@code OVER (...)} clause (R-SRC-10, R-SRC-12, R-SRC-04, D-143).
 *
 * <p>The executor supplies one from the factory's {@code ProviderSupport#sources()}; the engine calls it once per
 * statement it builds over a source, so a statement never reaches the database without its CTE registered (R-SRC-12).
 * A query over a source with no renderer is {@code MQ4014}, raised at build time, before any statement.
 *
 * <p>A provider implements {@link #register} and {@link #from}, and {@link #registerRecursive} for recursion;
 * {@link #cte} and {@link #recursive} default over them (D-151). Those three, {@link #join}, {@link #window} and
 * {@link #combine} default to {@code MQ4014}, so a provider can ship plain CTE support only.
 *
 * @implSpec R-SRC-10, R-SRC-12, R-SRC-18, R-SRC-21, R-SRC-22, R-SRC-04, INV-7
 */
@EngineFacing
@Incubating
public interface SourceRenderer {

    /**
     * An opaque handle to a CTE registered on one top statement, for the next CTE body in the chain or the top
     * statement to read through {@link #from} (R-SRC-18, D-150). A handle is valid only on the {@code top} statement it
     * was registered on and that statement's sub-queries.
     */
    @EngineFacing
    @Incubating
    interface CteHandle {}

    /**
     * A recursive source's CTE once registered on the top statement (R-SRC-18, D-150): its {@link CteHandle}, for the
     * next CTE body to read through {@link #from}, and the check expressions the top statement selects (R-SRC-08,
     * R-SRC-14). Valid only on the {@code top} statement it was registered on and that statement's sub-queries.
     *
     * @param handle the registered recursive CTE
     * @param depthKey the depth check, always present: the smallest key of the rows past {@code maxDepth}, {@code null}
     *         in the statement's row where the CTE holds none
     * @param cycleKey the cycle check, always present: the smallest key of the marked rows, {@code null} in the
     *         statement's row where the CTE holds none
     * @param duplicateKey the duplicate-key check, or {@code null} when the renderer supplies none
     */
    @EngineFacing
    @Incubating
    record RecursiveHandle(CteHandle handle, Expression<?> depthKey, Expression<?> cycleKey,
            Expression<?> duplicateKey) {

        public RecursiveHandle {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(depthKey, "depthKey");
            Objects.requireNonNull(cycleKey, "cycleKey");
        }
    }

    /**
     * Registers {@code body} as the CTE of {@code outer} and returns the root whose columns the outer statement reads
     * by the source field names, each a {@link Tuple} element aliased to that name (R-SRC-12). Defaults to
     * {@link #register} then {@link #from}.
     */
    default Root<Tuple> cte(CriteriaQuery<?> outer, CriteriaQuery<Tuple> body) {
        try {
            return from(outer, register(outer, body));
        } catch (ModelQueryConfigurationException e) {
            throw refused(e, null);
        }
    }

    /**
     * Registers {@code body} as a CTE on {@code top} without adding it to {@code top}'s {@code FROM} clause, returning
     * a handle that the next CTE body or {@code top} reads through {@link #from} (R-SRC-18).
     */
    @Incubating
    default CteHandle register(CriteriaQuery<?> top, CriteriaQuery<Tuple> body) {
        throw unsupported("chained CTEs");
    }

    /**
     * Registers {@code spec} as the {@code WITH RECURSIVE} CTE of {@code outer} and returns its root together with
     * the check expressions the statement's first row is read for (R-SRC-07, R-SRC-12, R-SRC-14). A tree source
     * requires a duplicate-key check; returning none is {@code MQ4014} before any statement. The CTE recurses to
     * {@code maxDepth + 1} levels, marks the row that closes a cycle, and joins each level to the previous one
     * through the step's keys (RFC 0013 §5). Defaults to {@link #registerRecursive} then {@link #from}.
     */
    default RecursiveCte recursive(CriteriaQuery<?> outer, RecursiveSpec spec) {
        try {
            RecursiveHandle registered = registerRecursive(outer, spec);
            return new RecursiveCte(from(outer, registered.handle()), registered.depthKey(), registered.cycleKey(),
                    registered.duplicateKey());
        } catch (ModelQueryConfigurationException e) {
            throw refused(e, "recursive CTEs");
        }
    }

    /**
     * Registers {@code spec} as a {@code WITH RECURSIVE} CTE on {@code top} without adding it to {@code top}'s
     * {@code FROM} clause, returning its handle together with the check scalar sub-selects over that CTE for
     * {@code top} to select (R-SRC-18, R-SRC-08, R-SRC-14).
     */
    @Incubating
    default RecursiveHandle registerRecursive(CriteriaQuery<?> top, RecursiveSpec spec) {
        throw unsupported("chained CTEs");
    }

    /**
     * Adds {@code inner}, a CTE registered on the top statement, to {@code query}'s {@code FROM} clause and returns
     * the root whose columns {@code query} reads by the inner source's field aliases (R-SRC-18).
     */
    @Incubating
    default Root<Tuple> from(AbstractQuery<?> query, CteHandle inner) {
        throw unsupported("chained CTEs");
    }

    /**
     * Joins {@code source}, a CTE registered on {@code outer}'s statement, to {@code outer} with {@code type}
     * ({@code INNER} or {@code LEFT}), applies {@code on} over the new join as its {@code ON} condition, and returns
     * the join, whose columns the statement reads by the source's field aliases (R-SRC-21, R-SRC-22). The engine
     * builds the predicate over standard Criteria and never calls {@code Join#on} itself, since the type a provider
     * joins a CTE with need not be a {@code Join}. Called once per statement, for each source the query joins.
     */
    @Incubating
    default From<?, Tuple> join(From<?, ?> outer, CteHandle source, JoinType type,
            Function<From<?, Tuple>, Predicate> on) {
        throw unsupported("joined sources");
    }

    /**
     * Renders {@code spec} as a window function with an {@code OVER (...)} clause, over {@code cb}'s criteria. The
     * provider's typed window methods are used where it has them, and {@code windowFunction(name, ...)} otherwise
     * (R-SRC-04).
     */
    default Expression<?> window(CriteriaBuilder cb, WindowSpec spec) {
        throw unsupported("window functions");
    }

    /**
     * Combines {@code left} and {@code right} into one set operation, itself a statement the provider can register as
     * the source's body (R-SRC-05, R-PROC-38). A combined source builds its CTE body from this, in the order its
     * operations were called, so {@code a.union(b).except(c)} is {@code (a union b) except c}.
     *
     * @param cb the provider's builder the branches were built with
     * @param operation the operation to apply
     * @param left the left branch, itself a combined query when the operation nests
     * @param right the right branch
     * @return the combined statement, whose columns are the branches' aliases in order
     */
    default CriteriaQuery<Tuple> combine(CriteriaBuilder cb, SetOperation operation, CriteriaQuery<Tuple> left,
            CriteriaQuery<Tuple> right) {
        throw unsupported("set operations");
    }

    /**
     * The single-statement entry points report their own feature, not the chain's, keeping the cause; a {@code null}
     * feature is {@link #cte}'s, where the provider renders no CTE source at all.
     */
    private static ModelQueryConfigurationException refused(ModelQueryConfigurationException e, String feature) {
        if (e.code() != MqCode.MQ4014) {
            return e;
        }
        var refused = feature == null
                ? new ModelQueryConfigurationException(MqCode.MQ4014,
                        "the factory's persistence provider renders no CTE sources")
                : unsupported(feature);
        refused.initCause(e);
        return refused;
    }

    private static ModelQueryConfigurationException unsupported(String feature) {
        return new ModelQueryConfigurationException(MqCode.MQ4014,
                "the factory's persistence provider renders CTE sources only, not " + feature);
    }
}
