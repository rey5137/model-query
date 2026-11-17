package com.rey.modelquery.jpa.vendor;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.Row;
import java.util.List;
import java.util.Optional;

/**
 * Told of the models the executor hands on, with the row each was mapped from: after the fetch plan and the enrichers
 * ran, so the models are the ones the caller receives (R-INS-18, D-228); not API (D-139).
 *
 * <p>At most one is found, through {@code ServiceLoader} on this type's class loader, the first time a call reads
 * rows, never while an executor is built (D-133); {@link #NONE} when there is none, and the executor then does
 * no hand-off work at all.
 *
 * <p>The executor passes no call site and no executor method: the implementation captures both itself, once per
 * outermost {@code ModelQueryExecutor} call, and attributes each hand-off, a child load's included, to that call.
 */
@Incubating
public interface HandOffObserver {

    /** The default: observes nothing. */
    @Incubating
    HandOffObserver NONE = (query, child, models, rows) -> {};

    /** Opens one executor invocation; nested invocations close before their outer invocation. */
    @Incubating
    default Scope openCall() {
        return Scope.NONE;
    }

    /** A call's lifetime, closed even when mapping, a stream body or an export sink throws. */
    @Incubating
    interface Scope extends AutoCloseable {
        /** The scope for an observer with no call state. */
        @Incubating
        Scope NONE = () -> {};

        /** Enters this call on a mapping thread until closed, restoring that thread's previous context. */
        @Incubating
        default Scope enter() {
            return NONE;
        }

        /** Releases this invocation's state. */
        @Override
        @Incubating
        void close();
    }

    /**
     * {@code models} are handed on, {@code rows.get(i)} the row {@code models.get(i)} was mapped from. Both lists are
     * unmodifiable and read only during the call. A model a call returns, passes to an export's transformer or to a
     * stream's body is handed on once; a child load's children are handed on once per load, from the child query's
     * own rows, after the child's own plan ran, before they are set on their parents. The models a join plan fills
     * are not handed on by themselves: each is its parent's, read from the parent's row scoped to the join.
     *
     * @param query the query the rows were read by: the call's, or the child load's
     * @param child the child load that read the rows, or empty for the models the call itself returns
     * @param models the models, in order
     * @param rows the row of each model, by position
     */
    @Incubating
    void handedOff(ModelQuery<?, ?, ?> query, Optional<ChildLoad<?, ?>> child, List<?> models, List<Row> rows);

    /** The observer this type's class loader found, or {@link #NONE}; looked up on the first call. */
    @Incubating
    static HandOffObserver installed() {
        return HandOffLookup.Holder.INSTALLED;
    }
}
