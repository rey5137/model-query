package com.rey.modelquery.jpa.vendor;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.ChildLoad;
import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The portability dry run's entry to one child load of a fetch plan, on keys it chooses rather than a page's
 * (RFC 0025 Part C, D-224); not API (D-139).
 */
@Incubating
public final class ChildStatements {

    private ChildStatements() {}

    /**
     * Reads {@code load}'s children of {@code keys} on {@code executor} in one run, as its child stage reads a page
     * whose parents hold those keys: the child's own checks first, then its rounds of the profile's size. With key
     * groups, the run binds the catch-all's values where one exists, else those of the first key's group, asked with
     * {@code load} as the owner, so the keys are never split by group. The child's own children are not loaded.
     *
     * @param executor an executor {@link ModelQueryExecutor#create} returned
     * @param load a child load of any fetch plan, at any depth
     * @param keys distinct, non-null keys of the type of {@code load}'s key attribute, before any converter
     * @return the children read, by key, in child order
     * @throws IllegalArgumentException when {@code executor} is not the library's, or {@code keys} is empty or holds
     *     a key twice
     */
    @Incubating
    public static <C> Map<Object, List<C>> run(ModelQueryExecutor<?> executor, ChildLoad<?, C> load, List<?> keys) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(load, "load");
        List<Object> copy = List.copyOf(Objects.requireNonNull(keys, "keys"));
        if (copy.isEmpty() || new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException(load + ": a child run takes one or more distinct keys, not " + copy);
        }
        if (!(executor instanceof Runner runner)) {
            throw new IllegalArgumentException(load + ": a child run needs an executor ModelQueryExecutor.create "
                    + "returned, not a " + executor.getClass().getName());
        }
        return runner.runChildLoad(load, copy);
    }

    /** The executor's side of {@link #run}, implemented by the executor {@link ModelQueryExecutor#create} returns. */
    @Incubating
    public interface Runner {

        /** As {@link ChildStatements#run}, on keys already checked. */
        @Incubating
        <C> Map<Object, List<C>> runChildLoad(ChildLoad<?, C> load, List<Object> keys);
    }
}
