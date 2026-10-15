package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * An immutable source definition: the entity a {@code @SourceModel} reads, its fields, its key and its grouping, and
 * the predicates its builder recorded. It holds no Criteria object and no per-query state, so one instance can be a
 * {@code static final} constant shared by any number of threads (INV-9). It is what an outer model's
 * {@code query(SourceQuery<S>)} takes (R-SRC-10).
 *
 * <p>Its structure is fixed where it is built: {@link #builder} takes the fields, the key and the group keys, and the
 * builder adds only {@code where} and {@code having}. There is no {@code select}: a source always carries every field
 * of its model, which keeps the outer model's columns checkable at compile time (R-GEN-37). A later release adds the
 * set operations, the recursion and the shape to it (R-PROC-38, R-PROC-37).
 *
 * @param <S> the source's shape: the model itself until a later release names another (R-PROC-38)
 * @implSpec R-SRC-01, R-SRC-10, R-GEN-37
 */
@Incubating
public final class SourceQuery<S> {

    private final Class<?> model;
    private final TableField<?, ?> root;
    private final SelectSet<?> fields;
    private final PrimaryKey<?, ?> key;
    /** The group keys, or {@code null} for an ungrouped source. */
    private final SelectSet<?> groupKeys;
    private final List<Filter> where;
    /** The {@code HAVING} predicate, or {@code null} without one. */
    private final HavingGroup.Clause having;

    private SourceQuery(Class<?> model, TableField<?, ?> root, SelectSet<?> fields, PrimaryKey<?, ?> key,
            SelectSet<?> groupKeys, List<Filter> where, HavingGroup.Clause having) {
        this.model = model;
        this.root = root;
        this.fields = fields;
        this.key = key;
        this.groupKeys = groupKeys;
        this.where = List.copyOf(where);
        this.having = having;
    }

    /**
     * Starts an ungrouped source of {@code model}: the entity the model is read from, the fields it carries and the
     * key that identifies a row. Only {@code where} is added afterwards; a source with no predicate reads every row.
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join, not a root
     */
    public static <E, M> Builder<E, M, M> builder(
            Class<M> model, TableField<E, E> root, SelectSet<M> fields, PrimaryKey<M, ?> key) {
        return new Builder<>(model, root, fields, key, null);
    }

    /**
     * Starts a grouped source of {@code model}, as {@link #builder} does: {@code groupKeys} are the source's
     * {@code GROUP_KEYS}, which are also its key (R-SRC-02).
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join, not a root
     */
    public static <E, M> Builder<E, M, M> builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields,
            PrimaryKey<M, ?> key, SelectSet<M> groupKeys) {
        return new Builder<>(model, root, fields, key, Objects.requireNonNull(groupKeys, "groupKeys"));
    }

    /**
     * Collects the predicates of a {@link SourceQuery}. Immutable: every method returns a new builder, so one builder
     * can be the base of several sources without them affecting each other (R-SRC-01).
     *
     * @param <E> the entity the source reads
     * @param <M> the source model
     * @param <S> the shape the source is read as
     */
    @Incubating
    public static final class Builder<E, M, S> {

        private final Class<M> model;
        private final TableField<E, E> root;
        private final SelectSet<M> fields;
        private final PrimaryKey<M, ?> key;
        /** The group keys, or {@code null} for an ungrouped source. */
        private final SelectSet<M> groupKeys;
        private final List<Filter> where;
        /** The {@code HAVING} predicate, or {@code null} without one. */
        private final HavingGroup.Clause having;

        private Builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields, PrimaryKey<M, ?> key,
                SelectSet<M> groupKeys) {
            this(model, root, fields, key, groupKeys, List.of(), null);
        }

        private Builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields, PrimaryKey<M, ?> key,
                SelectSet<M> groupKeys, List<Filter> where, HavingGroup.Clause having) {
            this.model = Objects.requireNonNull(model, "model");
            this.root = Objects.requireNonNull(root, "root");
            this.fields = Objects.requireNonNull(fields, "fields");
            this.key = Objects.requireNonNull(key, "key");
            if (root.rootEntity() == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1203,
                        "builder(...) takes a TableField.root(...) or a TableField.source(...), not the "
                                + root.describe());
            }
            this.groupKeys = groupKeys;
            this.where = where;
            this.having = having;
        }

        /**
         * The {@code WHERE} predicate: {@code filters} receives an empty {@link Filters}, and every filter it adds is
         * ANDed. It runs once, here, like {@link ModelQuery.Builder#where}. Replaces any predicate set before. A
         * path crossing a to-many join is refused where the source is built (R-SRC-03).
         */
        public Builder<E, M, S> where(UnaryOperator<Filters<M>> filters) {
            return new Builder<>(model, root, fields, key, groupKeys,
                    FilterGroup.collect(root.rootEntity(), Objects.requireNonNull(filters, "filters")), having);
        }

        /**
         * The {@code HAVING} predicate, as {@link ModelQuery.Builder#having}: it does not make the source grouped, so
         * {@link #build()} throws {@code MQ1407} on a source with no group keys.
         */
        public Builder<E, M, S> having(UnaryOperator<Having<M>> filters) {
            return new Builder<>(model, root, fields, key, groupKeys, where,
                    HavingGroup.collect(Objects.requireNonNull(filters, "filters")));
        }

        /**
         * Builds the immutable source.
         *
         * @throws ModelQueryDefinitionException {@code MQ1407} for {@code having(...)} on an ungrouped source
         */
        @SuppressWarnings("unchecked")
        public SourceQuery<S> build() {
            if (having != null && (groupKeys == null || groupKeys.fields().isEmpty())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1407, model.getSimpleName()
                        + ": having(...) needs a grouped source; give it GROUP_KEYS, or filter rows with where(...)");
            }
            // Sound while the shape is the model itself; a later release lets shape name another model (R-PROC-38).
            return new SourceQuery<>((Class<S>) model, root, (SelectSet<S>) fields, (PrimaryKey<S, ?>) key,
                    (SelectSet<S>) groupKeys, where, having);
        }
    }

    /** The source model the CTE is read as, once a later release lets the shape differ (R-PROC-38). */
    Class<?> model() {
        return model;
    }

    /** The entity the source reads. */
    TableField<?, ?> root() {
        return root;
    }

    /** Every field the source carries, in declaration order. */
    SelectSet<?> fields() {
        return fields;
    }

    /** The key that identifies a source row, unique by construction (R-SRC-02). */
    PrimaryKey<?, ?> key() {
        return key;
    }

    /** The group keys, or {@code null} for an ungrouped source. */
    SelectSet<?> groupKeys() {
        return groupKeys;
    }

    /** The {@code WHERE} predicates the source's builder recorded, in order. */
    List<Filter> where() {
        return where;
    }

    /** The {@code HAVING} predicate, or {@code null} without one. */
    HavingGroup.Clause having() {
        return having;
    }
}
