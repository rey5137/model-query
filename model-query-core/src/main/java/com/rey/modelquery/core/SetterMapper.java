package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A {@link RowMapper} for a mutable class: a factory plus one setter per column. Immutable; {@link #bind} returns a
 * copy, so a shared constant is safe for concurrent queries (INV-9). A setter is called for every bound column, with
 * {@code null} when the column is NULL or was not selected; bind a primitive setter only to a non-null column.
 *
 * @param <M> the model type
 * @implSpec R-COL-11
 */
@Incubating
public final class SetterMapper<M> implements RowMapper<M> {

    private record Binding<M, C>(SelectField<?, C> column, BiConsumer<M, ? super C> setter) {
        void apply(M model, Row row) {
            setter.accept(model, row.get(column));
        }
    }

    private final Supplier<M> factory;
    private final List<Binding<M, ?>> bindings;

    private SetterMapper(Supplier<M> factory, List<Binding<M, ?>> bindings) {
        this.factory = factory;
        this.bindings = bindings;
    }

    static <M> SetterMapper<M> of(Supplier<M> factory) {
        return new SetterMapper<>(factory, List.of());
    }

    /**
     * A copy that also sets {@code column}'s value through {@code setter}. The column belongs to this mapper's model,
     * so another model's column, which no query of this model selects, does not compile (D-88).
     */
    public <C> SetterMapper<M> bind(SelectField<M, C> column, BiConsumer<M, ? super C> setter) {
        var next = new ArrayList<Binding<M, ?>>(bindings);
        next.add(new Binding<>(Objects.requireNonNull(column, "column"), Objects.requireNonNull(setter, "setter")));
        return new SetterMapper<>(factory, List.copyOf(next));
    }

    @Override
    public M map(Row row) {
        M model = factory.get();
        for (Binding<M, ?> binding : bindings) {
            binding.apply(model, row);
        }
        return model;
    }
}
