package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A narrowing the engine rendered on purpose in one statement, which the provider's preflight expects rather than
 * refuses (R-VND-26, D-157): a sub-select's root of {@code entity}, read through a treat as each of {@code types}, or a
 * node an {@code ofType} restricts to {@code types} (D-158). A
 * statement's narrowings are listed in build order and compared as a multiset, so two equal {@code exists} are two
 * entries. Providers should read accessors rather than use record deconstruction patterns, so later components remain
 * compatible.
 */
@EngineFacing
@Incubating
public record ExpectedNarrowing(@Incubating Kind kind, @Incubating Class<?> entity, @Incubating Set<Class<?>> types) {

    /** An immutable narrowing, copying its types in their order (R-VND-26, INV-9). */
    @Incubating
    public ExpectedNarrowing {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entity, "entity");
        var copy = new LinkedHashSet<Class<?>>(Objects.requireNonNull(types, "types"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("an expected narrowing of " + entity.getSimpleName()
                    + " names no subtype");
        }
        for (Class<?> type : copy) {
            if (!entity.isAssignableFrom(Objects.requireNonNull(type, "type"))) {
                throw new IllegalArgumentException(type.getSimpleName() + " is not a subtype of "
                        + entity.getSimpleName());
            }
        }
        types = Collections.unmodifiableSet(copy);
    }

    /** Which narrowing the engine rendered on purpose; unknown future kinds must be refused (R-VND-26). */
    @EngineFacing
    @Incubating
    public enum Kind {
        /** The correlated root of a path {@code exists}, read through a treat (R-FLT-11). */
        @Incubating EXISTS_ROOT,
        /**
         * A node of the statement's own {@code where}, restricted by a top-level {@code ofType} to {@code types}, its
         * listed types and their mapped concrete subtypes (R-FLT-28): a narrowing to them loses no row the filter
         * keeps. Not declared under an {@code or}, a {@code not}, a {@code CASE}, an {@code add(...)} or in a
         * sub-select, where a narrowing would.
         */
        @Incubating TYPE_RESTRICTION
    }
}
