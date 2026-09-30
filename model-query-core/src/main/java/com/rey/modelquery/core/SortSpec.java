package com.rey.modelquery.core;

import java.util.List;
import java.util.Objects;

/**
 * A sort chosen per call: ordering keys that name their column by property, resolved against a query's selected
 * columns by {@link ModelQuery#orderedBy(SortSpec)}. Immutable.
 *
 * @param keys the ordering keys, in order; empty for no sort of its own
 * @implSpec R-QRY-14, D-52
 */
@Incubating
public record SortSpec(List<Key> keys) {

    private static final SortSpec UNSORTED = new SortSpec(List.of());

    /** Copies {@code keys}, so the spec cannot change after it is made. */
    public SortSpec {
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
    }

    /** No key: {@code orderedBy} then returns the definition unchanged. */
    public static SortSpec unsorted() {
        return UNSORTED;
    }

    /** The given keys, in order. */
    public static SortSpec of(Key... keys) {
        return new SortSpec(List.of(Objects.requireNonNull(keys, "keys")));
    }

    /**
     * One ordering key: a property, a direction and a null precedence. Immutable.
     *
     * @param property a selected column's attribute path from the root ({@code customer.name}), or its name; exact and
     *     case-sensitive
     * @param ascending {@code true} for ascending
     * @param nulls where NULLs sort
     * @implSpec R-QRY-14
     */
    @Incubating
    public record Key(String property, boolean ascending, NullPrecedence nulls) {

        /** Validates the components. */
        public Key {
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(nulls, "nulls");
        }

        /** Ascending order on {@code property}, with the database's own null order. */
        public static Key asc(String property) {
            return new Key(property, true, NullPrecedence.DEFAULT);
        }

        /** Descending order on {@code property}, with the database's own null order. */
        public static Key desc(String property) {
            return new Key(property, false, NullPrecedence.DEFAULT);
        }

        /** A copy with {@code nulls}. */
        public Key nulls(NullPrecedence nulls) {
            return new Key(property, ascending, nulls);
        }
    }
}
