package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One treated read and its enclosing statement (R-VND-26). Providers should read accessors rather than use record
 * deconstruction patterns, so later components remain compatible.
 */
@EngineFacing
@Incubating
public record TreatSite(@Incubating Class<?> entity, @Incubating Class<?> subtype,
        @Incubating Optional<String> attribute, @Incubating StatementShape shape,
        @Incubating boolean anchored, @Incubating Set<TreatUse> uses) {

    /** An immutable site, copying its uses in enum order (R-VND-26, INV-9). */
    @Incubating
    public TreatSite {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(subtype, "subtype");
        Objects.requireNonNull(attribute, "attribute");
        Objects.requireNonNull(shape, "shape");
        var copy = EnumSet.noneOf(TreatUse.class);
        copy.addAll(Objects.requireNonNull(uses, "uses"));
        uses = Collections.unmodifiableSet(copy);
    }
}
