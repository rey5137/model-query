package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * What a conflict clause's {@code doUpdate} writes to the stored row a new row conflicts with. Each call returns an
 * {@link Assigned}, so an update that assigns nothing does not compile; one {@code where} may then narrow the stored
 * rows updated. Immutable.
 *
 * <p>The assignments follow R-WRT-13: a key column (a conflict column or the model's {@code @PrimaryKey}), or a
 * column assigned twice, throws {@code MQ1804} at {@code build()}, and the {@code @Version} column on first execution
 * (R-WRT-34).
 *
 * @param <E> the root entity
 * @param <M> the insert model
 * @implSpec R-WRT-34, D-117
 */
@Incubating
public final class ConflictUpdate<E, M> {

    ConflictUpdate() {
    }

    /**
     * Writes the incoming row's values of {@code first} and {@code rest} over the stored row's. Each must be a column
     * of the model's {@code InsertColumns}, else {@code build()} throws {@code MQ1804}.
     */
    @SafeVarargs
    public final Assigned<E, M> setFromRow(ColumnField<M, E, ?> first, ColumnField<M, E, ?>... rest) {
        return new Assigned<E, M>(List.of(), List.of()).setFromRow(first, rest);
    }

    /**
     * Writes {@code value} to {@code column}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1603} for a {@code null} value, which {@link #setNull} writes
     */
    public <C> Assigned<E, M> set(ColumnField<?, E, C> column, C value) {
        return new Assigned<E, M>(List.of(), List.of()).set(column, value);
    }

    /** Writes NULL to {@code column}. */
    public <C> Assigned<E, M> setNull(ColumnField<?, E, C> column) {
        return new Assigned<E, M>(List.of(), List.of()).setNull(column);
    }

    /**
     * A complete conflict update: what {@code doUpdate}'s function returns.
     *
     * @param <E> the root entity
     * @param <M> the insert model
     */
    @Incubating
    public static sealed class Action<E, M> permits Assigned {

        private final List<ColumnField<M, E, ?>> fromRow;
        private final List<Assignment<?, ?>> assignments;
        private final List<Filter> where;

        private Action(List<ColumnField<M, E, ?>> fromRow, List<Assignment<?, ?>> assignments, List<Filter> where) {
            this.fromRow = fromRow;
            this.assignments = assignments;
            this.where = where;
        }

        /** The columns written from the incoming row, in the order named. */
        List<ColumnField<M, E, ?>> fromRow() {
            return fromRow;
        }

        /** The columns written a value or NULL, in the order assigned. */
        List<Assignment<?, ?>> assignments() {
            return assignments;
        }

        /** The filters the {@code where} recorded, ANDed; empty without one or when every filter was skipped. */
        List<Filter> where() {
            return where;
        }
    }

    /**
     * An update with at least one assignment, which may assign more, then narrow the stored rows once.
     *
     * @param <E> the root entity
     * @param <M> the insert model
     */
    @Incubating
    public static final class Assigned<E, M> extends Action<E, M> {

        private Assigned(List<ColumnField<M, E, ?>> fromRow, List<Assignment<?, ?>> assignments) {
            super(fromRow, assignments, List.of());
        }

        /** Also writes the incoming row's values of {@code first} and {@code rest}, as {@link ConflictUpdate} does. */
        @SafeVarargs
        public final Assigned<E, M> setFromRow(ColumnField<M, E, ?> first, ColumnField<M, E, ?>... rest) {
            var all = new ArrayList<>(fromRow());
            all.add(Objects.requireNonNull(first, "first"));
            for (ColumnField<M, E, ?> column : Objects.requireNonNull(rest, "rest")) {
                all.add(Objects.requireNonNull(column, "rest element"));
            }
            return new Assigned<>(List.copyOf(all), assignments());
        }

        /**
         * Also writes {@code value} to {@code column}.
         *
         * @throws ModelQueryDefinitionException {@code MQ1603} for a {@code null} value
         */
        public <C> Assigned<E, M> set(ColumnField<?, E, C> column, C value) {
            return assign(Assignment.of(Objects.requireNonNull(column, "column"), value));
        }

        /** Also writes NULL to {@code column}. */
        public <C> Assigned<E, M> setNull(ColumnField<?, E, C> column) {
            return assign(Assignment.ofNull(Objects.requireNonNull(column, "column")));
        }

        /**
         * Updates only the stored rows {@code filters} matches: it receives an empty {@link Filters}, and every filter
         * it adds is ANDed. It reads the stored row's root columns only; a joined column, {@code exists} or a
         * sub-select throws {@code MQ1804} at {@code build()}. If every filter is skipped, the update applies to every
         * conflicting row. Reading two or more of the columns the update assigns, the {@code @Version} increment
         * included unless {@code keepVersion()}, throws {@code MQ1804} on execution unless the executor is configured
         * with {@code conflictUpdateWhereOnAssignedColumns(true)}, and even then on MySQL, which would filter on the
         * values the earlier assignments wrote (R-WRT-34).
         */
        public Action<E, M> where(UnaryOperator<Filters<M>> filters) {
            return new Action<>(fromRow(), assignments(), FilterGroup.collect(Objects.requireNonNull(filters,
                    "filters")));
        }

        private Assigned<E, M> assign(Assignment<?, ?> assignment) {
            var all = new ArrayList<>(assignments());
            all.add(assignment);
            return new Assigned<>(fromRow(), List.copyOf(all));
        }
    }
}
