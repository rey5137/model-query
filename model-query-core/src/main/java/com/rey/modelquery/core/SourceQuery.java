package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * An immutable source definition: the entity a {@code @SourceModel} reads, its fields, its key and its grouping, and
 * the predicates its builder recorded. It holds no Criteria object and no per-query state, so one instance can be a
 * {@code static final} constant shared by any number of threads (INV-9). It is what an outer model's
 * {@code query(SourceQuery<S>)} takes (R-SRC-10).
 *
 * <p>Its structure is fixed where it is built: {@link #builder} takes the fields, the key and the group keys, and the
 * builder adds only {@code where}, {@code having}, the shape, the discriminator and the recursion. There is no
 * {@code select}: a source always carries every field of its model, which keeps the outer model's columns checkable
 * at compile time (R-GEN-37). {@link #union}, {@link #unionAll}, {@link #intersect} and {@link #except} combine two
 * sources of one shape into another immutable one, whose own {@code where} and {@code having} are empty because every
 * predicate belongs to a branch (R-SRC-05). A source whose model declares a {@code @Depth} field adds the step its
 * CTE recurses by, and is read as {@code with recursive} (R-PROC-37, R-SRC-07).
 *
 * @param <S> the source's shape: the model itself, or the model named by {@code @SourceModel(shape)} (R-PROC-38)
 * @implSpec R-SRC-01, R-SRC-05, R-SRC-10, R-GEN-37
 */
@Incubating
public final class SourceQuery<S> {

    /**
     * The CTE column the cycle mark is set on, which the provider passes to the cycle clause and the outer query
     * filters on (R-SRC-07). It is not a field alias, so no source field can read it.
     */
    static final String MARK_ALIAS = "mqCycleMark";

    private final Class<?> model;
    private final Class<?> shape;
    private final SourceQuery<?> inner;
    private final TableField<?, ?> root;
    private final SelectSet<?> fields;
    private final PrimaryKey<?, ?> key;
    /** The group keys, or {@code null} for an ungrouped source. */
    private final SelectSet<?> groupKeys;
    private final List<Filter> where;
    /** The {@code HAVING} predicate, or {@code null} without one. */
    private final HavingGroup.Clause having;
    /** The field fixed to a constant, or {@code null} for a branch with no {@code @Discriminator} (R-PROC-38). */
    private final Discriminator discriminator;
    /** The operation tree of a combined source, or {@code null} for a plain one (R-SRC-05). */
    private final Combined operation;
    /** The recursion of a recursive source, or {@code null} for a source that does not recurse (R-SRC-07). */
    private final Recursion recursion;

    private SourceQuery(Class<?> model, Class<?> shape, TableField<?, ?> root, SelectSet<?> fields,
            PrimaryKey<?, ?> key, SelectSet<?> groupKeys, List<Filter> where, HavingGroup.Clause having,
            Discriminator discriminator, Recursion recursion, SourceQuery<?> inner) {
        this.model = model;
        this.inner = inner;
        this.shape = shape;
        this.root = root;
        this.fields = fields;
        this.key = key;
        this.groupKeys = groupKeys;
        this.where = List.copyOf(where);
        this.having = having;
        this.discriminator = discriminator;
        this.operation = null;
        this.recursion = recursion;
    }

    /**
     * A combined source: one set operation over two branches (R-SRC-05). It carries the shape's structure and no
     * {@code where} or {@code having} of its own, since every predicate belongs to a branch.
     */
    private SourceQuery(SetOperation operation, SourceQuery<?> left, SourceQuery<?> right) {
        this.model = left.shape;
        this.inner = null;
        this.shape = left.shape;
        this.root = null;
        this.fields = left.fields;
        this.key = left.key;
        this.groupKeys = null;
        this.where = List.of();
        this.having = null;
        this.discriminator = null;
        this.operation = new Combined(operation, left, right);
        // A branch cannot recurse (MQ3034), so no combined source carries a recursion.
        this.recursion = null;
    }

    /**
     * Starts an ungrouped source of {@code model}: the entity the model is read from, the fields it carries and the
     * key that identifies a row. Only {@code where} is added afterwards; a source with no predicate reads every row.
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join; {@code MQ1907} for a
     *     source root without an inner source; {@code MQ1908} for a correlated field
     */
    public static <E, M> Builder<E, M, M> builder(
            Class<M> model, TableField<E, E> root, SelectSet<M> fields, PrimaryKey<M, ?> key) {
        return new Builder<>(model, root, fields, key, null, null);
    }

    /**
     * Starts a grouped source of {@code model}, as {@link #builder} does: {@code groupKeys} are the source's
     * {@code GROUP_KEYS}, which are also its key (R-SRC-02).
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join; {@code MQ1907} for a
     *     source root without an inner source; {@code MQ1908} for a correlated field
     */
    public static <E, M> Builder<E, M, M> builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields,
            PrimaryKey<M, ?> key, SelectSet<M> groupKeys) {
        return new Builder<>(model, root, fields, key, Objects.requireNonNull(groupKeys, "groupKeys"), null);
    }

    /**
     * Starts an ungrouped source over {@code inner}, whose shape must match {@code root} (R-SRC-16).
     *
     * @throws ModelQueryDefinitionException {@code MQ1907} for a missing or mismatched inner source, or an entity
     *     root; {@code MQ1908} for a correlated field
     */
    @Incubating
    public static <E, M> Builder<E, M, M> builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields,
            PrimaryKey<M, ?> key, SourceQuery<E> inner) {
        return new Builder<>(model, root, fields, key, null, inner);
    }

    /**
     * Starts a grouped source over {@code inner}, unique by {@code groupKeys} even when the inner is not.
     *
     * @throws ModelQueryDefinitionException {@code MQ1907} for a missing or mismatched inner source, or an entity
     *     root; {@code MQ1908} for a correlated field
     */
    @Incubating
    public static <E, M> Builder<E, M, M> builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields,
            PrimaryKey<M, ?> key, SelectSet<M> groupKeys, SourceQuery<E> inner) {
        return new Builder<>(model, root, fields, key, Objects.requireNonNull(groupKeys, "groupKeys"), inner);
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
        private final SourceQuery<?> inner;
        private final TableField<E, E> root;
        private final SelectSet<M> fields;
        private final PrimaryKey<M, ?> key;
        /** The group keys, or {@code null} for an ungrouped source. */
        private final SelectSet<M> groupKeys;
        private final List<Filter> where;
        /** The {@code HAVING} predicate, or {@code null} without one. */
        private final HavingGroup.Clause having;
        /** The shape, the discriminator and the recursion, each as set so far (R-PROC-38, R-SRC-07). */
        private final Settings settings;

        private Builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields, PrimaryKey<M, ?> key,
                SelectSet<M> groupKeys, SourceQuery<?> inner) {
            this(model, root, fields, key, groupKeys, List.of(), null, Settings.NONE, inner);
        }

        private Builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields, PrimaryKey<M, ?> key,
                SelectSet<M> groupKeys, List<Filter> where, HavingGroup.Clause having, Settings settings,
                SourceQuery<?> inner) {
            this.model = Objects.requireNonNull(model, "model");
            this.root = Objects.requireNonNull(root, "root");
            this.fields = Objects.requireNonNull(fields, "fields");
            this.key = Objects.requireNonNull(key, "key");
            this.inner = inner;
            if (root.rootEntity() == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1203,
                        "builder(...) takes a TableField.root(...) or a TableField.source(...), not the "
                                + root.describe());
            }
            checkSource();
            this.groupKeys = groupKeys;
            this.where = where;
            this.having = having;
            this.settings = settings;
        }

        private void checkSource() {
            if (root.isSource() ? inner == null || inner.shape != root.rootEntity() : inner != null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1907, model.getSimpleName()
                        + ": the root " + root.rootEntity().getSimpleName()
                        + (root.isSource() ? " needs an inner source of that shape"
                                : " is an entity and takes no inner")
                        + "; got " + (inner == null ? "none" : inner.shape.getSimpleName()));
            }
            if (fields.fields().stream().anyMatch(ExpressionField::containsCorrelated)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1908, model.getSimpleName()
                        + ": a source cannot carry a correlated field; select it in an outer query instead");
            }
        }

        /**
         * Reads this branch as {@code shape}, another source model whose fields it maps onto its own root
         * (R-PROC-38). The built source is then a {@code SourceQuery<Shape>}, so it only combines with a branch of the
         * same shape. Absent, the source is read as its own model.
         *
         * @param <S> the shape model
         */
        public <S> Builder<E, M, S> shape(Class<S> shape) {
            return new Builder<>(model, root, fields, key, groupKeys, where, having,
                    settings.withShape(Objects.requireNonNull(shape, "shape")), inner);
        }

        /**
         * Fixes {@code field} to {@code value} in this branch's CTE body, which is the {@code @Discriminator} column
         * of a shape's branch (R-PROC-38, R-SRC-06). The field must be one of the key's columns, which the processor
         * checks ({@code MQ3034}); a source whose key holds it is unique by key where the branches' values differ.
         */
        public Builder<E, M, S> discriminator(ColumnField<M, ?, String> field, String value) {
            return new Builder<>(model, root, fields, key, groupKeys, where, having, settings.withDiscriminator(
                    new Discriminator(Objects.requireNonNull(field, "field"), Objects.requireNonNull(value, "value"))),
                    inner);
        }

        /**
         * Names the level column of a recursive source (R-SRC-07, R-PROC-37): the {@code @Depth} field's constant,
         * which is the alias the CTE assigns the level of a row by, the seed rows being level 0. It is what the
         * generated {@code source()} of a recursive model emits, and it is what makes {@code recursive(...)} legal on
         * this builder; a source built with it and no step is {@code MQ1902} at {@link #build()}, so a recursive model
         * is never silently read as a plain one.
         */
        public Builder<E, M, S> depth(ColumnField<M, ?, Integer> field) {
            return new Builder<>(model, root, fields, key, groupKeys, where, having,
                    settings.withRecursion(new Recursion(Objects.requireNonNull(field, "field"), null, 0, false)),
                    inner);
        }

        /**
         * Makes this a recursive source (R-SRC-07, R-PROC-37): the CTE starts at the rows {@link #where} keeps,
         * which are level 0, and steps from a parent row to its children while the level is at most
         * {@code maxDepth}. The step names the child and the parent key, which must be of one entity
         * ({@link RecursiveStep}); a model with no {@code @Depth} cannot recurse, and a model with one must call
         * this before {@link #build()}.
         *
         * @param step names the pair of keys a row is a child of another by
         * @param maxDepth the deepest level {@code build()}'s source returns, the seeds being level 0
         * @throws ModelQueryDefinitionException {@code MQ1902} when {@code maxDepth} is below 1 or above
         *     {@code Integer.MAX_VALUE - 2}, or the model declares no {@code @Depth}
         */
        public Builder<E, M, S> recursive(Function<RecursiveStep<M>, RecursiveKeys<M, ?>> step, int maxDepth) {
            return recurse(step, maxDepth, false);
        }

        /**
         * Makes this a recursive source whose walk must reach every node once (R-SRC-13, R-SRC-14).
         * Uses the same step and depth limits as {@link #recursive}; the last call to either replaces the other.
         *
         * @param step names the pair of keys a row is a child of another by
         * @param maxDepth the deepest level the source returns, the seeds being level 0
         * @throws ModelQueryDefinitionException {@code MQ1902} for an invalid depth or a model without {@code @Depth}
         */
        @Incubating
        public Builder<E, M, S> tree(Function<RecursiveStep<M>, RecursiveKeys<M, ?>> step, int maxDepth) {
            return recurse(step, maxDepth, true);
        }

        private Builder<E, M, S> recurse(Function<RecursiveStep<M>, RecursiveKeys<M, ?>> step, int maxDepth,
                boolean tree) {
            if (maxDepth < 1 || maxDepth > Integer.MAX_VALUE - 2) {
                throw new ModelQueryDefinitionException(MqCode.MQ1902, model.getSimpleName() + ": maxDepth " + maxDepth
                        + " is out of range; it is at least 1 and at most " + (Integer.MAX_VALUE - 2) + ", so that "
                        + "the level past the deepest one cannot overflow");
            }
            ColumnField<?, ?, ?> depth = settings.depth();
            if (depth == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1902, model.getSimpleName() + ": only a source whose "
                        + "model declares a @Depth field recurses, and this one has none");
            }
            RecursiveKeys<M, ?> keys = Objects.requireNonNull(step, "step").apply(new RecursiveStep<>());
            return new Builder<>(model, root, fields, key, groupKeys, where, having,
                    settings.withRecursion(new Recursion(depth, keys, maxDepth, tree)), inner);
        }

        /**
         * The {@code WHERE} predicate: {@code filters} receives an empty {@link Filters}, and every filter it adds is
         * ANDed. It runs once, here, like {@link ModelQuery.Builder#where}. Replaces any predicate set before. A
         * path crossing a to-many join is refused where the source is built (R-SRC-03).
         */
        public Builder<E, M, S> where(UnaryOperator<Filters<M>> filters) {
            return new Builder<>(model, root, fields, key, groupKeys,
                    FilterGroup.collect(root.rootEntity(), Objects.requireNonNull(filters, "filters")), having,
                    settings, inner);
        }

        /**
         * The {@code HAVING} predicate, as {@link ModelQuery.Builder#having}: it does not make the source grouped, so
         * {@link #build()} throws {@code MQ1407} on a source with no group keys.
         */
        public Builder<E, M, S> having(UnaryOperator<Having<M>> filters) {
            return new Builder<>(model, root, fields, key, groupKeys, where,
                    HavingGroup.collect(Objects.requireNonNull(filters, "filters")), settings, inner);
        }

        /**
         * Builds the immutable source.
         *
         * @throws ModelQueryDefinitionException {@code MQ1407} for {@code having(...)} on an ungrouped source, and
         *     {@code MQ1902} for a recursive source built without its step; {@code MQ1907} for an invalid inner source;
         *     {@code MQ1908} for a correlated field
         */
        @SuppressWarnings("unchecked")
        public SourceQuery<S> build() {
            checkSource();
            if (having != null && (groupKeys == null || groupKeys.fields().isEmpty())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1407, model.getSimpleName()
                        + ": having(...) needs a grouped source; give it GROUP_KEYS, or filter rows with where(...)");
            }
            // A recursive model's source() names its @Depth field, so the level column is known; the step is still
            // required. Without it the build is refused rather than read as a plain source, which would quietly
            // return the seed rows alone (R-SRC-07).
            Recursion recursion = settings.recursion();
            if (recursion != null && recursion.keys() == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1902, model.getSimpleName()
                        + ": a recursive source needs recursive(step -> step.childKey(childKey).parentKey(parentKey), "
                        + "maxDepth) or tree(step -> step.childKey(childKey).parentKey(parentKey), "
                        + "maxDepth) before build()");
            }
            if (recursion != null && !fields.fields().contains(recursion.depth())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1902, model.getSimpleName() + ": the depth field "
                        + recursion.depth().name() + " is not one of the source's fields");
            }
            Discriminator discriminator = settings.discriminator();
            if (discriminator != null && !key.columns().contains(discriminator.field())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1904, model.getSimpleName() + ": the discriminator "
                        + discriminator.field().name() + " is not one of the source key's columns");
            }
            // The shape is the model itself unless shape(...) named another one, which makes this a branch (R-PROC-38).
            Class<?> shape = settings.shape();
            return new SourceQuery<>(model, shape == null ? model : shape, root, (SelectSet<S>) fields,
                    (PrimaryKey<S, ?>) key, (SelectSet<S>) groupKeys, where, having, settings.discriminator(),
                    recursion, inner);
        }
    }

    /** The field a branch fixes to a constant, and the constant (R-PROC-38). */
    private record Discriminator(ColumnField<?, ?, ?> field, String value) {}

    /** The recursion of a source: the {@code @Depth} column the level is aliased by, its step, and its depth limit. */
    private record Recursion(ColumnField<?, ?, ?> depth, RecursiveKeys<?, ?> keys, int maxDepth, boolean tree) {}

    /** What a builder has set besides the source's structure: its shape, its discriminator and its recursion. */
    private record Settings(Class<?> shape, Discriminator discriminator, Recursion recursion) {

        /** A builder over a model that is its own shape, with no discriminator and no recursion. */
        static final Settings NONE = new Settings(null, null, null);

        Settings withShape(Class<?> shape) {
            return new Settings(shape, discriminator, recursion);
        }

        Settings withDiscriminator(Discriminator discriminator) {
            return new Settings(shape, discriminator, recursion);
        }

        Settings withRecursion(Recursion recursion) {
            return new Settings(shape, discriminator, recursion);
        }

        /** The {@code @Depth} column, or {@code null} for a source that does not recurse (R-SRC-07). */
        ColumnField<?, ?, ?> depth() {
            return recursion == null ? null : recursion.depth();
        }
    }

    /** One set operation over two branches; the tree's node (R-SRC-05). */
    private record Combined(SetOperation operation, SourceQuery<?> left, SourceQuery<?> right) {}

    /**
     * This source combined with {@code other} into one {@code union} of distinct rows (R-SRC-05). Operations nest in
     * the order they are called, so {@code a.union(b).except(c)} is {@code (a union b) except c}. The result is
     * immutable; {@code this} and {@code other} are unchanged.
     *
     * @throws ModelQueryDefinitionException {@code MQ1904} when both sides are a {@code union} or {@code unionAll} and
     *     two of their branches can carry the same discriminator value (R-SRC-06), unless every branch reads one
     *     model and the operation is a {@code union}, or either side is recursive
     *     (R-SRC-07)
     */
    public SourceQuery<S> union(SourceQuery<S> other) {
        return combine(SetOperation.UNION, other);
    }

    /** This source combined with {@code other} into one {@code union all}, which keeps duplicates (R-SRC-05). */
    public SourceQuery<S> unionAll(SourceQuery<S> other) {
        return combine(SetOperation.UNION_ALL, other);
    }

    /** This source combined with {@code other} into one {@code intersect} (R-SRC-05). */
    public SourceQuery<S> intersect(SourceQuery<S> other) {
        return combine(SetOperation.INTERSECT, other);
    }

    /** This source combined with {@code other} into one {@code except}, the left rows the right one has not. */
    public SourceQuery<S> except(SourceQuery<S> other) {
        return combine(SetOperation.EXCEPT, other);
    }

    /**
     * {@code this} and {@code other} as one operation of {@code operation} (R-SRC-05, R-SRC-06). A {@code union} or
     * {@code unionAll} whose branches fix their discriminator to the same value would lose key uniqueness silently,
     * so it is {@code MQ1904} where the operation is built; {@code intersect} and {@code except} don't check the
     * values, since their uniqueness follows the rules of R-SRC-05 alone. A recursive source on either side is
     * {@code MQ1904} for every operation, since a recursive model can't be a branch (R-SRC-07).
     */
    private SourceQuery<S> combine(SetOperation operation, SourceQuery<S> other) {
        SourceQuery<S> right = Objects.requireNonNull(other, "other");
        if (recursion != null || right.recursion != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1904, "a recursive source can't be a branch of "
                    + operation + ": " + (recursion != null ? model : right.model).getSimpleName() + " recurses, "
                    + "and a set operation would read it as its seed rows alone (R-SRC-07)");
        }
        if (shape != other.shape) {
            throw new ModelQueryDefinitionException(MqCode.MQ1904, "only branches of the same shape combine: "
                    + shape.getSimpleName() + " and " + other.shape.getSimpleName());
        }
        if (!sameFields(fields.fields(), right.fields.fields())) {
            throw new ModelQueryDefinitionException(MqCode.MQ1904, "the branches of " + shape.getSimpleName()
                    + " must carry the same fields in the same order: " + model.getSimpleName() + " and "
                    + right.model.getSimpleName() + " differ");
        }
        if ((operation == SetOperation.UNION || operation == SetOperation.UNION_ALL)
                && !(operation == SetOperation.UNION && sameModel(allBranches(this, right)))) {
            Set<String> left = discriminatorValues(this, false);
            Set<String> theirs = discriminatorValues(right, false);
            var shared = new TreeSet<String>();
            if (left != null && theirs != null) {
                shared.addAll(left);
                shared.retainAll(theirs);
            }
            if (!shared.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1904, "two branches of one " + operation
                        + " carry the discriminator '" + shared.iterator().next() + "'; give every branch of "
                        + shape.getSimpleName() + " its own @Discriminator value");
            }
        }
        return new SourceQuery<>(operation, this, right);
    }

    /** Whether the two field lists agree position by position in property, type and converter class. */
    private static boolean sameFields(List<? extends SelectField<?, ?>> left, List<? extends SelectField<?, ?>> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            SelectField<?, ?> a = left.get(i);
            SelectField<?, ?> b = right.get(i);
            if (!propertyOf(a).equals(propertyOf(b)) || a.type() != b.type()
                    || converterClass(a) != converterClass(b)) {
                return false;
            }
        }
        return true;
    }

    static Optional<String> propertyOf(SelectField<?, ?> field) {
        return field instanceof ColumnField<?, ?, ?> column ? column.property()
                : field instanceof ExpressionField<?, ?> expression ? expression.property()
                        : field instanceof AggregateField<?, ?> aggregate ? aggregate.property()
                                : field instanceof WindowField<?, ?> window ? window.property()
                                        : Optional.empty();
    }

    private static Class<?> converterClass(SelectField<?, ?> field) {
        return field instanceof ColumnField<?, ?, ?> column ? column.converterClass() : null;
    }

    /**
     * The discriminator values the rows of {@code source} can carry (R-SRC-06): a plain branch its own one value, a
     * {@code union} or {@code unionAll} the values of both sides, an {@code intersect} the values both sides share and
     * an {@code except} those of its left side. {@code null} when a branch fixes none, or, with {@code inKey}, none
     * the key contains, so the values say nothing about the rows' keys.
     */
    private static Set<String> discriminatorValues(SourceQuery<?> source, boolean inKey) {
        if (source.operation == null) {
            Discriminator d = source.discriminator;
            return d == null || inKey && !source.key.columns().contains(d.field()) ? null : Set.of(d.value());
        }
        Set<String> left = discriminatorValues(source.operation.left, inKey);
        if (source.operation.operation == SetOperation.EXCEPT) {
            return left;
        }
        Set<String> right = discriminatorValues(source.operation.right, inKey);
        if (left == null || right == null) {
            return null;
        }
        var values = new TreeSet<>(left);
        if (source.operation.operation == SetOperation.INTERSECT) {
            values.retainAll(right);
        } else {
            values.addAll(right);
        }
        return values;
    }

    private static List<SourceQuery<?>> allBranches(SourceQuery<?> left, SourceQuery<?> right) {
        var leaves = new ArrayList<SourceQuery<?>>(branches(left));
        leaves.addAll(branches(right));
        return leaves;
    }

    /**
     * Whether every row this source produces has a key no other row shares (R-SRC-05, R-SRC-06, R-SRC-08). A plain or
     * windowed source over an entity is unique by construction (R-SRC-02); an ungrouped source over a source
     * inherits its uniqueness, and grouping makes it unique by its group set (R-SRC-16). A combined one is unique
     * where the rules of R-SRC-05
     * hold; a tree is checked for uniqueness, while a plain recursive source can emit a node twice (R-SRC-08).
     * Everything non-unique is refused for the features that identify a row by its key (R-SRC-09).
     *
     * <p>Engine-facing: {@code core} raises {@code MQ1901} from it at the outer {@code build()}, and {@code jpa}
     * {@code MQ2212} at the executor call.
     */
    @EngineFacing
    public boolean unique() {
        if (recursion != null) {
            return recursion.tree();
        }
        if (operation == null) {
            return inner == null || groupKeys != null && !groupKeys.fields().isEmpty() || inner.unique();
        }
        return switch (operation.operation) {
            // The result is a subset of the left rows (R-SRC-05).
            case EXCEPT -> operation.left.unique();
            // A row appears at most as many times as in the branch where it is rarest.
            case INTERSECT -> operation.left.unique() || operation.right.unique();
            case UNION, UNION_ALL -> operation.left.unique() && operation.right.unique()
                    && (disjoint(operation.left, operation.right)
                            || operation.operation == SetOperation.UNION && sameModel(branches(this)));
        };
    }

    /**
     * Whether the two sides of one {@code union} or {@code unionAll} can carry no discriminator value in common, which
     * keeps their rows disjoint by key (R-SRC-06).
     */
    private static boolean disjoint(SourceQuery<?> left, SourceQuery<?> right) {
        Set<String> l = discriminatorValues(left, true);
        Set<String> r = discriminatorValues(right, true);
        return l != null && r != null && Collections.disjoint(l, r);
    }

    /**
     * Every leaf branch of {@code source}, in order: a plain source is one branch, a combined one the branches of
     * its two sides.
     */
    static List<SourceQuery<?>> branches(SourceQuery<?> source) {
        if (source.operation == null) {
            return List.of(source);
        }
        return allBranches(source.operation.left, source.operation.right);
    }

    /**
     * Whether every branch is built from the same branch model, whose rows collapse under a distinct union. A grouped
     * or windowed model doesn't qualify: its aggregates and windows are computed over the branch's own filtered
     * rows, so one key can carry different columns in two branches and the rows would not collapse.
     */
    private String discriminatorValue() {
        return discriminator == null ? null : discriminator.value();
    }

    private static boolean sameModel(List<SourceQuery<?>> branches) {
        return branches.stream().allMatch(leaf -> leaf.model == branches.get(0).model
                && Objects.equals(leaf.discriminatorValue(), branches.get(0).discriminatorValue())
                && (leaf.groupKeys == null || leaf.groupKeys.fields().isEmpty())
                && leaf.fields.fields().stream().noneMatch(field -> field instanceof WindowField<?, ?>));
    }

    /**
     * The source model the CTE is read as: this model itself for a plain source and for a branch, and the shape for a
     * combined one, which no single branch model describes (R-PROC-38).
     */
    Class<?> model() {
        return model;
    }

    /** The source this body reads, or {@code null} for an entity root or a combined source. */
    SourceQuery<?> inner() {
        return inner;
    }

    /** The entity the source reads. */
    TableField<?, ?> root() {
        return root;
    }

    /** Every field the source carries, in declaration order. */
    SelectSet<?> fields() {
        return fields;
    }

    /** The key that identifies a source row: unique by construction, or made so by the branches (R-SRC-02). */
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

    /**
     * Whether this source recurses (R-SRC-07): its model declares a {@code @Depth} field and its builder took the
     * step, so its CTE is registered as {@code with recursive} rather than as a plain one.
     */
    @EngineFacing
    public boolean recursive() {
        return recursion != null;
    }

    /** Whether this source is a checked {@code tree(...)} walk rather than a plain recursive one (R-SRC-14). */
    @Incubating
    @EngineFacing
    public boolean tree() {
        return recursion != null && recursion.tree();
    }

    /** The CTE column alias of the level, or {@code null} for a source that does not recurse (R-SRC-07). */
    String levelAlias() {
        return recursion == null ? null : alias(recursion.depth());
    }

    /** The deepest level the outer query returns, the seed rows being level 0 (R-SRC-07). */
    int maxDepth() {
        return recursion == null ? 0 : recursion.maxDepth();
    }

    /**
     * The source's CTE body: a standard {@code CriteriaQuery<Tuple>} selecting every field of {@link #fields()},
     * aliased to the field name the outer model reads it by, filtered by {@link #where()} and {@link #having()} and
     * grouped by {@link #groupKeys()} (R-SRC-12, D-143). A {@link WindowField} renders through {@code renderer}, its
     * key appended first when it is order-sensitive (R-SRC-04). Built per statement, since it is registered on each
     * statement that reads the source.
     *
     * <p>A recursive source's CTE is not this body but the pair {@link #recursiveSpec} builds, which the provider
     * registers through {@code SourceRenderer#recursive} (R-SRC-07).
     *
     * @throws ModelQueryDefinitionException {@code MQ1906} when a {@code where} predicate's path crosses a to-many
     *     join, which no join inside the CTE can express (R-SRC-03)
     */
    CriteriaQuery<Tuple> body(CriteriaBuilder cb, RenderOptions options, SourceRenderer renderer) {
        if (operation != null) {
            // The combined body: each branch's own body, combined in the order the operations were called, and
            // itself a statement the provider registers as the source (R-SRC-05, R-SRC-12).
            return renderer.combine(cb, operation.operation, operation.left.body(cb, options, renderer),
                    operation.right.body(cb, options, renderer));
        }
        return select(cb, options, renderer, null);
    }

    /**
     * The CTE of a recursive source, for the provider to register as {@code with recursive} (R-SRC-07, R-SRC-12):
     * the seed is the source's own rows at level 0, and the term steps one level deeper from each row, joined through
     * the step's keys, while the level is below {@code maxDepth + 1}. Levels above {@code maxDepth} are produced so
     * that the depth check of {@code RecursiveCte} can see a tree deeper than the limit.
     */
    RecursiveSpec recursiveSpec(CriteriaBuilder cb, RenderOptions options, SourceRenderer renderer) {
        Recursion recursion = Objects.requireNonNull(this.recursion, "recursion");
        return new RecursiveSpec(cb, select(cb, options, renderer, cb.literal(0)), term(cb, options, renderer),
                key.columns().get(0).type(), keyAliases(), alias(recursion.depth()), MARK_ALIAS,
                recursion.maxDepth(), recursion.tree());
    }

    /** The one level of the recursion a recursive source's CTE steps by (R-SRC-07). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private RecursiveTerm term(CriteriaBuilder cb, RenderOptions options, SourceRenderer renderer) {
        return (query, parent) -> {
            RecursiveKeys<?, ?> keys = Objects.requireNonNull(recursion, "recursion").keys();
            JoinContext joins = JoinContext.readOf(query.from(root.rootEntity()), cb, query, options);
            Expression<Integer> level = (Expression<Integer>) (Expression<?>) parent.get(alias(recursion.depth()));
            List<Selection<?>> selections = new ArrayList<>(fields.fields().size());
            for (SelectField<?, ?> field : fields.fields()) {
                selections.add(expression(cb, joins, renderer, field, cb.sum(level, 1)).alias(alias(field)));
            }
            query.multiselect(selections);
            // One row per child of a row of the level before: the step's keys, then the row past maxDepth is asked
            // for but never stepped from, which is the level the depth check reads (R-SRC-07).
            query.where(cb.equal(keys.childKey().expression(joins), parent.get(alias(keys.parentKey()))),
                    cb.lt(level, recursion.maxDepth() + 1));
            return query;
        };
    }

    /**
     * The CTE columns of the source's key, in key order: the aliases the provider cycles on and selects the first
     * offending key by (R-SRC-07, R-SRC-08).
     */
    private List<String> keyAliases() {
        return key.columns().stream().map(SourceQuery::alias).toList();
    }

    /**
     * The CTE body of {@code level}, a plain source or the seed of a recursive one (R-SRC-12): every field of
     * {@link #fields()} aliased to the name the outer model reads it by, filtered by {@link #where()} and
     * {@link #having()} and grouped by {@link #groupKeys()}.
     *
     * @param level the expression the {@code @Depth} column takes, or {@code null} for a source that does not recurse
     * @throws ModelQueryDefinitionException {@code MQ1906} when a {@code where} predicate's path crosses a to-many
     *     join, which no join inside the CTE can express (R-SRC-03)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private CriteriaQuery<Tuple> select(CriteriaBuilder cb, RenderOptions options, SourceRenderer renderer,
            Expression<?> level) {
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root from = query.from(root.rootEntity());
        JoinContext joins = JoinContext.readOf(from, cb, query, options);
        List<Selection<?>> selections = new ArrayList<>(fields.fields().size());
        for (SelectField<?, ?> field : fields.fields()) {
            selections.add(expression(cb, joins, renderer, field, level).alias(alias(field)));
        }
        query.multiselect(selections);
        if (groupKeys != null && !groupKeys.fields().isEmpty()) {
            query.groupBy(groupKeys.fields().stream().<Expression<?>>map(key -> key.expression(joins)).toList());
        }
        int joined = joins.toManyJoins().size();
        List<List<Predicate>> predicates = ConditionGroup.clausePredicates(
                List.of(where, having == null ? List.of() : having.filters()), joins);
        if (joins.toManyJoins().size() > joined) {
            throw new ModelQueryDefinitionException(MqCode.MQ1906, model.getSimpleName()
                    + ": a where predicate crosses the to-many join " + joins.toManyJoins().get(joined).getAttribute()
                            .getName()
                    + "; two conditions on one joined line would match different lines, so write it as "
                    + "Filters.exists(path, inner)");
        }
        if (!predicates.get(0).isEmpty()) {
            query.where(predicates.get(0).toArray(Predicate[]::new));
        }
        if (!predicates.get(1).isEmpty()) {
            query.having(predicates.get(1).toArray(Predicate[]::new));
        }
        return query;
    }

    /**
     * The CTE column of {@code field}: its own expression, the level {@code level} for a recursive source's
     * {@code @Depth} field, or, for a {@link WindowField}, the {@code OVER (...)} clause rendered by {@code renderer}
     * with the source key appended first when the window is order-sensitive (R-SRC-04, R-GEN-38, R-SRC-07).
     */
    private Expression<?> expression(CriteriaBuilder cb, JoinContext joins, SourceRenderer renderer,
            SelectField<?, ?> field, Expression<?> level) {
        if (discriminator != null && discriminator.field().equals(field)) {
            // A branch's discriminator is a constant, never read from the root: Hibernate inlines a literal and
            // casts it itself for the select list (RFC 0013 §8, R-SRC-06).
            return cb.literal(discriminator.value());
        }
        if (recursion != null && recursion.depth().equals(field)) {
            // A recursive source's @Depth column is the row's level, never a column of the entity (R-SRC-07).
            return Objects.requireNonNull(level, "level");
        }
        if (!(field instanceof WindowField<?, ?> window)) {
            return field.expression(joins);
        }
        WindowSpec spec = window.spec(joins);
        return renderer.window(cb, spec.orderSensitive() ? spec.withOrder(keyOrders(cb, joins, window)) : spec);
    }

    /**
     * The source key's columns, ascending, as the last order terms of an order-sensitive window (R-SRC-04). The key
     * is the root id for an ungrouped source and the group keys for a grouped one (R-SRC-02); a key column the window
     * already orders by is skipped, so no column is repeated.
     */
    private List<Order> keyOrders(CriteriaBuilder cb, JoinContext joins, WindowField<?, ?> window) {
        List<? extends SelectField<?, ?>> ordered = window.orderedColumns();
        var orders = new ArrayList<Order>(key.columns().size());
        for (ColumnField<?, ?, ?> keyColumn : key.columns()) {
            // A discriminator key column is a constant in every branch, so ordering by it adds nothing (R-SRC-06).
            if (!ordered.contains(keyColumn) && (discriminator == null || !discriminator.field().equals(keyColumn))) {
                orders.add(cb.asc(keyColumn.expression(joins)));
            }
        }
        return orders;
    }

    /**
     * The name {@code field} is aliased by in the CTE, which the outer column reads it by: its property prefixed by
     * the engine (D-146), as the source's QModel sets it with {@code named} (R-SRC-10, R-GEN-37, R-GEN-38).
     */
    private static String alias(SelectField<?, ?> field) {
        return columnName(propertyOf(field).orElseThrow(() -> new ModelQueryDefinitionException(MqCode.MQ1905,
                "a source's field " + field.name() + " has no property to alias its CTE column by; a generated "
                        + "source constant is aliased by named(...)")));
    }

    /**
     * The CTE column name of a source property: engine-owned, so that a property named a reserved word ({@code rank},
     * {@code year}, {@code key}) is never a bare column name (D-146). The property itself, sort matching and the
     * generated code are unchanged.
     */
    static String columnName(String property) {
        return "mq_" + property;
    }
}
