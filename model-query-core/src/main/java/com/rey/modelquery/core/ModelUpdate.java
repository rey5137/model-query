package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * An immutable bulk-update definition: the columns to write, the rows to write them to, and how the write runs. Like
 * a {@link ModelQuery} it holds no Criteria object, so one instance can be shared by any number of threads (INV-9).
 *
 * <p>An update loads no entity and runs no lifecycle callback, cascade, Bean Validation or Envers audit: those stay
 * with JPA entity writes (R-WRT-01).
 *
 * @param <E> the root entity
 * @param <M> the update model
 * @implSpec R-WRT-05, D-60
 */
@Incubating
public final class ModelUpdate<E, M> {

    private final Draft<E, ?, M> definition;

    private ModelUpdate(Draft<E, ?, M> definition) {
        this.definition = definition;
    }

    /**
     * Starts an update of {@code root}'s rows. Each stage returns a new immutable builder; the order is the key,
     * the assignments, one row choice, then options (D-60).
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join, not a root
     */
    public static <E> Start<E> builder(TableField<E, E> root) {
        Objects.requireNonNull(root, "root");
        if (root.rootEntity() == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1203,
                    "builder(...) takes a TableField.root(...), not the " + root.describe());
        }
        return new Start<>(root);
    }

    /** The entity the update writes. */
    public Class<E> rootEntity() {
        return definition.root().rootEntity();
    }

    /** The columns written, in the order assigned, as a list that throws on mutation. */
    public List<Assignment<M, ?>> assignments() {
        return definition.assignments();
    }

    /** Everything a stage holds; each stage call returns a copy with one part changed. */
    private record Draft<E, K, M>(
            TableField<E, E> root,
            PrimaryKey<M, K> primaryKey,
            List<Assignment<M, ?>> assignments,
            WriteRows rows,
            Object expectedVersion,
            boolean keepVersion,
            ChunkOptions chunkOptions,
            PersistenceContextMode persistenceContext) {

        Draft<E, K, M> assign(List<Assignment<M, ?>> more) {
            var all = new ArrayList<>(assignments);
            all.addAll(more);
            return new Draft<>(root, primaryKey, List.copyOf(all), rows, expectedVersion, keepVersion, chunkOptions,
                    persistenceContext);
        }

        Draft<E, K, M> rows(WriteRows rows) {
            return new Draft<>(root, primaryKey, assignments, rows, expectedVersion, keepVersion, chunkOptions,
                    persistenceContext);
        }

        Draft<E, K, M> expect(Object version) {
            return new Draft<>(root, primaryKey, assignments, rows, version, keepVersion, chunkOptions,
                    persistenceContext);
        }

        Draft<E, K, M> keep() {
            return new Draft<>(root, primaryKey, assignments, rows, expectedVersion, true, chunkOptions,
                    persistenceContext);
        }

        Draft<E, K, M> chunk(ChunkOptions options) {
            return new Draft<>(root, primaryKey, assignments, rows, expectedVersion, keepVersion, options,
                    persistenceContext);
        }

        Draft<E, K, M> mode(PersistenceContextMode mode) {
            return new Draft<>(root, primaryKey, assignments, rows, expectedVersion, keepVersion, chunkOptions, mode);
        }

        /**
         * Checks and builds.
         *
         * @throws ModelQueryDefinitionException {@code MQ1601}, {@code MQ1602}, {@code MQ1604}, {@code MQ1605},
         *     {@code MQ1606} or {@code MQ1609}, as {@link Options#build()} states
         */
        ModelUpdate<E, M> build() {
            String model = primaryKey.columns().get(0).model().getSimpleName();
            rows.check(model);
            var keyAttributes = new HashSet<String>();
            for (ColumnField<M, ?, ?> key : primaryKey.columns()) {
                keyAttributes.add(key.name());
            }
            Set<String> assigned = new HashSet<>();
            for (Assignment<M, ?> assignment : assignments) {
                ColumnField<M, ?, ?> column = assignment.column();
                // The type check lets a join back to the root's own entity type through (R-WRT-06).
                if (!column.table().key().equals(root.key())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1604, column + ": sits on the "
                            + column.table().describe() + ", not on the root " + root.rootEntity().getSimpleName()
                            + "; an update writes only the root's own columns");
                }
                if (keyAttributes.contains(column.name())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1605, column
                            + ": a primary-key column is never assigned; whereKey(...) chooses the row");
                }
                if (assignment instanceof Assignment.Expression<M, ?> && column.converterClass() != null) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1609, column + ": setExpression(...) on a "
                            + "column converted by " + column.converterClass().getSimpleName() + "; the expression's "
                            + "path has the entity attribute's type, not the model's");
                }
                if (!assigned.add(column.name())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1602, column + ": assigned twice; a column "
                            + "the server sets belongs in a hand-written ColumnField, not in the update model");
                }
            }
            if (keepVersion && expectedVersion != null && assignments.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1606, model + ": expectVersion(...) with "
                        + "keepVersion() and nothing to write; check Changes#isEmpty() before updating");
            }
            return new ModelUpdate<>(this);
        }
    }

    /**
     * The first stage: the update's root, before its key.
     *
     * @param <E> the root entity
     */
    @Incubating
    public static final class Start<E> {

        private final TableField<E, E> root;

        private Start(TableField<E, E> root) {
            this.root = root;
        }

        /** The update model's key, which {@code whereKey} and {@code whereKeys} take values of (R-WRT-08). */
        public <K, M> Builder<E, K, M> primaryKey(PrimaryKey<M, K> primaryKey) {
            return new Builder<>(new Draft<>(root, Objects.requireNonNull(primaryKey, "primaryKey"), List.of(), null,
                    null, false, null, null));
        }
    }

    /**
     * The assignment stage. {@code set}, {@code setNull} and {@code setExpression} take only columns on the root
     * entity type, so most joined columns do not compile; then exactly one row choice follows (R-WRT-06, R-WRT-12).
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static final class Builder<E, K, M> {

        private final Draft<E, K, M> draft;

        private Builder(Draft<E, K, M> draft) {
            this.draft = draft;
        }

        /**
         * Writes every column {@code changes} marked, NULLs included. The assignments are copied now, so changing
         * {@code changes} afterwards changes neither this builder nor an update already built (R-WRT-05, R-WRT-07).
         */
        public Builder<E, K, M> set(Changes<M> changes) {
            var copy = new ArrayList<Assignment<M, ?>>();
            for (Assignment<M, ?> assignment : Objects.requireNonNull(changes, "changes").assignments()) {
                copy.add(Objects.requireNonNull(assignment, "changes.assignments() element"));
            }
            return new Builder<>(draft.assign(copy));
        }

        /**
         * Writes {@code value} to {@code column}.
         *
         * @throws ModelQueryDefinitionException {@code MQ1603} for a {@code null} value, which {@link #setNull}
         *     writes
         */
        public <C> Builder<E, K, M> set(ColumnField<M, E, C> column, C value) {
            return new Builder<>(draft.assign(List.of(Assignment.of(Objects.requireNonNull(column, "column"),
                    value))));
        }

        /** Writes NULL to {@code column}. */
        public <C> Builder<E, K, M> setNull(ColumnField<M, E, C> column) {
            return new Builder<>(draft.assign(List.of(Assignment.ofNull(column))));
        }

        /**
         * Writes what {@code expression} returns for {@code column}'s path, an escape hatch like {@code Agg.of}.
         * Expression assignments render before the others, so one reading a column a plain assignment writes sees
         * the old value on every vendor; one reading a column another {@code setExpression} writes is
         * vendor-dependent: MySQL reads the new value, PostgreSQL and H2 the old one. A column with a converter
         * throws {@code MQ1609} at {@link Options#build()}, since the path has the entity attribute's type
         * (R-WRT-13, R-WRT-14).
         */
        public <C> Builder<E, K, M> setExpression(ColumnField<M, E, C> column,
                BiFunction<Path<C>, CriteriaBuilder, Expression<? extends C>> expression) {
            return new Builder<>(draft.assign(List.of(new Assignment.Expression<>(column, expression))));
        }

        /** Writes the row whose key is {@code key}; a {@code where} may narrow it, then {@code expectVersion}. */
        public Keyed<E, K, M> whereKey(K key) {
            return new Keyed<>(draft.rows(WriteRows.key(key)));
        }

        /**
         * Writes the rows whose keys are in {@code keys}; a {@code where} may narrow them. An empty collection
         * affects nothing and runs no SQL (R-WRT-08, R-WRT-12).
         */
        public Narrowable<E, K, M> whereKeys(Collection<? extends K> keys) {
            return new Narrowable<>(draft.rows(WriteRows.keys(keys)));
        }

        /**
         * Writes the rows {@code filters} matches: it receives an empty {@link Filters}, and every filter it adds is
         * ANDed. It runs once, here, as {@link ModelQuery.Builder#where} does. If every filter is skipped,
         * {@link Options#build()} throws {@code MQ1601}; {@link #all()} is the only way to write every row (R-WRT-12).
         */
        public Options<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Options<>(draft.rows(WriteRows.where(FilterGroup.collect(
                    Objects.requireNonNull(filters, "filters")))));
        }

        /** Writes every row; no {@code where} follows (R-WRT-12). */
        public Options<E, K, M> all() {
            return new Options<>(draft.rows(WriteRows.everyRow()));
        }
    }

    /**
     * The options stage, after the row choice: no further {@code where} or {@code all()} compiles (R-WRT-12).
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static sealed class Options<E, K, M> permits Versioned, Narrowable {

        final Draft<E, K, M> draft;

        private Options(Draft<E, K, M> draft) {
            this.draft = draft;
        }

        /** Leaves the root's {@code @Version} attribute as it is, instead of incrementing it (R-WRT-16). */
        public Options<E, K, M> keepVersion() {
            return new Options<>(draft.keep());
        }

        /** Writes in key-first chunks, as {@code options} states (R-WRT-17). */
        public Options<E, K, M> chunked(ChunkOptions options) {
            return new Options<>(draft.chunk(Objects.requireNonNull(options, "options")));
        }

        /** What the write does to the persistence context afterwards, over the executor's configured mode (D-62). */
        public Options<E, K, M> persistenceContext(PersistenceContextMode mode) {
            return new Options<>(draft.mode(Objects.requireNonNull(mode, "mode")));
        }

        /**
         * Checks the definition and returns it.
         *
         * @throws ModelQueryDefinitionException {@code MQ1601} when the rows were chosen by a {@code where} whose
         *     every filter was skipped, {@code MQ1602} for a column assigned twice, {@code MQ1604} for a column not
         *     on the root, as through a self-referencing join, {@code MQ1605} for a column of the primary key,
         *     {@code MQ1606} for {@code expectVersion} with {@code keepVersion} and nothing to write, {@code MQ1609}
         *     for {@code setExpression} on a column with a converter
         */
        public ModelUpdate<E, M> build() {
            return draft.build();
        }
    }

    /**
     * A single-key update, which may expect the row's version.
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static sealed class Versioned<E, K, M> extends Options<E, K, M> permits Keyed {

        private Versioned(Draft<E, K, M> draft) {
            super(draft);
        }

        /**
         * Writes the row only while its {@code @Version} attribute is {@code version}; zero rows written then throws
         * JPA's {@code OptimisticLockException}. A root with no {@code @Version}, or a value of the wrong type,
         * throws {@code MQ1606} on the definition's first execution (R-WRT-16, D-61). Call it before
         * {@code keepVersion()}, which returns the plain options stage (D-64).
         */
        public Options<E, K, M> expectVersion(Object version) {
            return new Options<>(draft.expect(Objects.requireNonNull(version, "version")));
        }
    }

    /**
     * The stage after {@code whereKey}: one {@code where} may narrow the row, then {@code expectVersion}.
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static final class Keyed<E, K, M> extends Versioned<E, K, M> {

        private Keyed(Draft<E, K, M> draft) {
            super(draft);
        }

        /** Narrows the row by {@code filters}, ANDed with the key; skipping every filter leaves the key alone. */
        public Versioned<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Versioned<>(draft.rows(draft.rows().and(FilterGroup.collect(
                    Objects.requireNonNull(filters, "filters")))));
        }
    }

    /**
     * The stage after {@code whereKeys}: one {@code where} may narrow the rows.
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static final class Narrowable<E, K, M> extends Options<E, K, M> {

        private Narrowable(Draft<E, K, M> draft) {
            super(draft);
        }

        /** Narrows the rows by {@code filters}, ANDed with the keys; skipping every filter leaves the keys alone. */
        public Options<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Options<>(draft.rows(draft.rows().and(FilterGroup.collect(
                    Objects.requireNonNull(filters, "filters")))));
        }
    }
}
