package com.rey.modelquery.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The rows a bulk write chooses: by key, with {@code where} filters ANDed, or every row. Immutable; the staged
 * builders allow one row choice and at most one {@code where} (D-60).
 *
 * @param keys the model keys of {@code whereKey} or {@code whereKeys}, or {@code null} when the write chose none;
 *     the attribute keys, distinct, once the definition is built
 * @param where the filters a {@code where} recorded, ANDed; empty without one
 * @param all whether {@code all()} chose every row
 */
record WriteRows(List<Object> keys, List<Filter> where, boolean all) {

    private static final WriteRows ALL = new WriteRows(null, List.of(), true);

    /** One key, as {@code whereKey} takes it. */
    static WriteRows key(Object key) {
        return new WriteRows(List.of(Objects.requireNonNull(key, "key")), List.of(), false);
    }

    /** The given keys, as {@code whereKeys} takes them; an empty collection affects nothing (R-WRT-12). */
    static WriteRows keys(Collection<?> keys) {
        Objects.requireNonNull(keys, "keys").forEach(key -> Objects.requireNonNull(key, "keys element"));
        return new WriteRows(List.copyOf(keys), List.of(), false);
    }

    /** These rows with their keys replaced, as {@code build()} converts and deduplicates them (D-66). */
    WriteRows withKeys(List<Object> converted) {
        return new WriteRows(converted, where, all);
    }

    /** The rows {@code where} matches. */
    static WriteRows where(List<Filter> where) {
        return new WriteRows(null, where, false);
    }

    /** Every row. */
    static WriteRows everyRow() {
        return ALL;
    }

    /** These keys, narrowed by the filters a {@code where} recorded. */
    WriteRows and(List<Filter> filters) {
        return new WriteRows(keys, filters, all);
    }

    /**
     * Checks that a predicate is left.
     *
     * @throws ModelQueryDefinitionException {@code MQ1601} when the rows were chosen by a {@code where} whose every
     *     filter was skipped
     */
    void check(String model) {
        if (keys == null && !all && where.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1601, model
                    + ": where(...) left no predicate, since every filter was skipped; all() writes every row");
        }
    }
}
