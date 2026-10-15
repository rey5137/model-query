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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * An immutable source definition: the entity a {@code @SourceModel} reads, its fields, its key and its grouping, and
 * the predicates its builder recorded. It holds no Criteria object and no per-query state, so one instance can be a
 * {@code static final} constant shared by any number of threads (INV-9). It is what an outer model's
 * {@code query(SourceQuery<S>)} takes (R-SRC-10).
 *
 * <p>Its structure is fixed where it is built: {@link #builder} takes the fields, the key and the group keys, and the
 * builder adds only {@code where}, {@code having}, the shape and the discriminator. There is no {@code select}: a
 * source always carries every field of its model, which keeps the outer model's columns checkable at compile time
 * (R-GEN-37). {@link #union}, {@link #unionAll}, {@link #intersect} and {@link #except} combine two sources of one
 * shape into another immutable one, whose own {@code where} and {@code having} are empty because every predicate
 * belongs to a branch (R-SRC-05). A later release adds the recursion to it (R-PROC-37).
 *
 * @param <S> the source's shape: the model itself, or the model named by {@code @SourceModel(shape)} (R-PROC-38)
 * @implSpec R-SRC-01, R-SRC-05, R-SRC-10, R-GEN-37
 */
@Incubating
public final class SourceQuery<S> {

    private final Class<?> model;
    private final Class<?> shape;
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

    private SourceQuery(Class<?> model, Class<?> shape, TableField<?, ?> root, SelectSet<?> fields,
            PrimaryKey<?, ?> key, SelectSet<?> groupKeys, List<Filter> where, HavingGroup.Clause having,
            Discriminator discriminator) {
        this.model = model;
        this.shape = shape;
        this.root = root;
        this.fields = fields;
        this.key = key;
        this.groupKeys = groupKeys;
        this.where = List.copyOf(where);
        this.having = having;
        this.discriminator = discriminator;
        this.operation = null;
    }

    /**
     * A combined source: one set operation over two branches (R-SRC-05). It carries the shape's structure and no
     * {@code where} or {@code having} of its own, since every predicate belongs to a branch.
     */
    private SourceQuery(SetOperation operation, SourceQuery<?> left, SourceQuery<?> right) {
        this.model = left.shape;
        this.shape = left.shape;
        this.root = null;
        this.fields = left.fields;
        this.key = left.key;
        this.groupKeys = null;
        this.where = List.of();
        this.having = null;
        this.discriminator = null;
        this.operation = new Combined(operation, left, right);
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
        /** The shape, or {@code null} while it is the model itself (R-PROC-38). */
        private final Class<?> shape;
        private final TableField<E, E> root;
        private final SelectSet<M> fields;
        private final PrimaryKey<M, ?> key;
        /** The group keys, or {@code null} for an ungrouped source. */
        private final SelectSet<M> groupKeys;
        private final List<Filter> where;
        /** The {@code HAVING} predicate, or {@code null} without one. */
        private final HavingGroup.Clause having;
        /** The field fixed to a constant, or {@code null} for a branch with no {@code @Discriminator}. */
        private final Discriminator discriminator;

        private Builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields, PrimaryKey<M, ?> key,
                SelectSet<M> groupKeys) {
            this(model, root, fields, key, groupKeys, List.of(), null, null, null);
        }

        private Builder(Class<M> model, TableField<E, E> root, SelectSet<M> fields, PrimaryKey<M, ?> key,
                SelectSet<M> groupKeys, List<Filter> where, HavingGroup.Clause having, Class<?> shape,
                Discriminator discriminator) {
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
            this.shape = shape;
            this.discriminator = discriminator;
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
                    Objects.requireNonNull(shape, "shape"), discriminator);
        }

        /**
         * Fixes {@code field} to {@code value} in this branch's CTE body, which is the {@code @Discriminator} column
         * of a shape's branch (R-PROC-38, R-SRC-06). The field must be one of the key's columns, which the processor
         * checks ({@code MQ3034}); a source whose key holds it is unique by key where the branches' values differ.
         */
        public Builder<E, M, S> discriminator(ColumnField<?, ?, ?> field, String value) {
            return new Builder<>(model, root, fields, key, groupKeys, where, having, shape,
                    new Discriminator(Objects.requireNonNull(field, "field"), Objects.requireNonNull(value, "value")));
        }

        /**
         * The {@code WHERE} predicate: {@code filters} receives an empty {@link Filters}, and every filter it adds is
         * ANDed. It runs once, here, like {@link ModelQuery.Builder#where}. Replaces any predicate set before. A
         * path crossing a to-many join is refused where the source is built (R-SRC-03).
         */
        public Builder<E, M, S> where(UnaryOperator<Filters<M>> filters) {
            return new Builder<>(model, root, fields, key, groupKeys,
                    FilterGroup.collect(root.rootEntity(), Objects.requireNonNull(filters, "filters")), having, shape,
                    discriminator);
        }

        /**
         * The {@code HAVING} predicate, as {@link ModelQuery.Builder#having}: it does not make the source grouped, so
         * {@link #build()} throws {@code MQ1407} on a source with no group keys.
         */
        public Builder<E, M, S> having(UnaryOperator<Having<M>> filters) {
            return new Builder<>(model, root, fields, key, groupKeys, where,
                    HavingGroup.collect(Objects.requireNonNull(filters, "filters")), shape, discriminator);
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
            // The shape is the model itself unless shape(...) named another one, which makes this a branch (R-PROC-38).
            return new SourceQuery<>(model, shape == null ? model : shape, root, (SelectSet<S>) fields,
                    (PrimaryKey<S, ?>) key, (SelectSet<S>) groupKeys, where, having, discriminator);
        }
    }

    /** The field a branch fixes to a constant, and the constant (R-PROC-38). */
    private record Discriminator(ColumnField<?, ?, ?> field, String value) {}

    /** One set operation over two branches; the tree's node (R-SRC-05). */
    private record Combined(SetOperation operation, SourceQuery<?> left, SourceQuery<?> right) {}

    /**
     * This source combined with {@code other} into one {@code union} of distinct rows (R-SRC-05). Operations nest in
     * the order they are called, so {@code a.union(b).except(c)} is {@code (a union b) except c}. The result is
     * immutable; {@code this} and {@code other} are unchanged.
     *
     * @throws ModelQueryDefinitionException {@code MQ1904} when both sides are a {@code union} or {@code unionAll} and
     *     two of their branches fix their discriminator to the same value (R-SRC-06)
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
     * values, since their uniqueness follows the rules of R-SRC-05 alone.
     */
    private SourceQuery<S> combine(SetOperation operation, SourceQuery<S> other) {
        SourceQuery<S> right = Objects.requireNonNull(other, "other");
        if (shape != other.shape) {
            throw new ModelQueryDefinitionException(MqCode.MQ1904, "only branches of the same shape combine: "
                    + shape.getSimpleName() + " and " + other.shape.getSimpleName());
        }
        if (operation == SetOperation.UNION || operation == SetOperation.UNION_ALL) {
            var shared = new TreeSet<>(discriminatorValues());
            shared.retainAll(right.discriminatorValues());
            if (!shared.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1904, "two branches of one " + operation
                        + " carry the discriminator '" + shared.iterator().next() + "'; give every branch of "
                        + shape.getSimpleName() + " its own @Discriminator value");
            }
        }
        return new SourceQuery<>(operation, this, right);
    }

    /**
     * The discriminator value of every branch of this source: a plain branch contributes its own, a combined one the
     * values of its two sides (R-SRC-06). Empty for a source whose branches carry no {@code @Discriminator}.
     */
    private Set<String> discriminatorValues() {
        var values = new TreeSet<String>();
        for (SourceQuery<?> leaf : branches(this)) {
            if (leaf.discriminator != null) {
                values.add(leaf.discriminator.value());
            }
        }
        return values;
    }

    /**
     * Whether every row this source produces has a key no other row shares (R-SRC-05, R-SRC-06). A plain or windowed
     * source is unique by construction (R-SRC-02); a combined one is unique where the rules of R-SRC-05 hold, and
     * everything else is non-unique and refused for the features that identify a row by its key (R-SRC-09).
     *
     * <p>Engine-facing: {@code core} raises {@code MQ1901} from it at the outer {@code build()}, and {@code jpa}
     * {@code MQ2212} at the executor call.
     */
    @EngineFacing
    public boolean unique() {
        if (operation == null) {
            return true;
        }
        return switch (operation.operation) {
            // The result is a subset of the left rows (R-SRC-05).
            case EXCEPT -> operation.left.unique();
            // A row appears at most as many times as in the branch where it is rarest.
            case INTERSECT -> operation.left.unique() || operation.right.unique();
            case UNION, UNION_ALL -> operation.left.unique() && operation.right.unique()
                    && (byDiscriminator(branches(this))
                            || operation.operation == SetOperation.UNION && sameModel(branches(this)));
        };
    }

    /**
     * Whether every branch of one {@code union} or {@code unionAll} fixes the shape's key discriminator to a value of
     * its own, which keeps the branches disjoint by key (R-SRC-06).
     */
    private static boolean byDiscriminator(List<SourceQuery<?>> branches) {
        var values = new HashSet<String>();
        for (SourceQuery<?> leaf : branches) {
            if (leaf.discriminator == null || !leaf.key.columns().contains(leaf.discriminator.field())
                    || !values.add(leaf.discriminator.value())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every leaf branch of {@code source}, in order: a plain source is one branch, a combined one the branches of
     * its two sides.
     */
    private static List<SourceQuery<?>> branches(SourceQuery<?> source) {
        if (source.operation == null) {
            return List.of(source);
        }
        var leaves = new ArrayList<SourceQuery<?>>(branches(source.operation.left));
        leaves.addAll(branches(source.operation.right));
        return leaves;
    }

    /**
     * Whether every branch is built from the same branch model, whose rows collapse under a distinct union. A grouped
     * or windowed model doesn't qualify: its aggregates and windows are computed over the branch's own filtered
     * rows, so one key can carry different columns in two branches and the rows would not collapse.
     */
    private static boolean sameModel(List<SourceQuery<?>> branches) {
        return branches.stream().allMatch(leaf -> leaf.model == branches.get(0).model
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
     * The source's CTE body: a standard {@code CriteriaQuery<Tuple>} selecting every field of {@link #fields()},
     * aliased to the field name the outer model reads it by, filtered by {@link #where()} and {@link #having()} and
     * grouped by {@link #groupKeys()} (R-SRC-12, D-143). A {@link WindowField} renders through {@code renderer}, its
     * key appended first when it is order-sensitive (R-SRC-04). Built per statement, since it is registered on each
     * statement that reads the source.
     *
     * @throws ModelQueryDefinitionException {@code MQ1906} when a {@code where} predicate's path crosses a to-many
     *     join, which no join inside the CTE can express (R-SRC-03)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @EngineFacing
    CriteriaQuery<Tuple> body(CriteriaBuilder cb, RenderOptions options, SourceRenderer renderer) {
        if (operation != null) {
            // The combined body: each branch's own body, combined in the order the operations were called, and
            // itself a statement the provider registers as the source (R-SRC-05, R-SRC-12).
            return renderer.combine(cb, operation.operation, operation.left.body(cb, options, renderer),
                    operation.right.body(cb, options, renderer));
        }
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root from = query.from(root.rootEntity());
        JoinContext joins = JoinContext.readOf(from, cb, query, options);
        List<Selection<?>> selections = new ArrayList<>(fields.fields().size());
        for (SelectField<?, ?> field : fields.fields()) {
            selections.add(expression(cb, joins, renderer, field).alias(alias(field)));
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
     * The CTE column of {@code field}: its own expression, or, for a {@link WindowField}, the {@code OVER (...)}
     * clause rendered by {@code renderer} with the source key appended first when the window is order-sensitive
     * (R-SRC-04, R-GEN-38).
     */
    private Expression<?> expression(CriteriaBuilder cb, JoinContext joins, SourceRenderer renderer,
            SelectField<?, ?> field) {
        if (discriminator != null && discriminator.field().equals(field)) {
            // A branch's discriminator is a constant, never read from the root: Hibernate inlines a literal and
            // casts it itself for the select list (RFC 0013 §8, R-SRC-06).
            return cb.literal(discriminator.value());
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
     * The name {@code field} is aliased by in the CTE, which the outer column reads it by: its property, as the
     * source's QModel sets it with {@code named} (R-SRC-10, R-GEN-37, R-GEN-38).
     */
    private static String alias(SelectField<?, ?> field) {
        Optional<String> property = field instanceof ColumnField<?, ?, ?> column ? column.property()
                : field instanceof ExpressionField<?, ?> expression ? expression.property()
                        : field instanceof AggregateField<?, ?> aggregate ? aggregate.property()
                                : field instanceof WindowField<?, ?> window ? window.property()
                                        : Optional.empty();
        return property.orElseThrow(() -> new ModelQueryDefinitionException(MqCode.MQ1905,
                "a source's field " + field.name() + " has no property to alias its CTE column by; a generated "
                        + "source constant is aliased by named(...)"));
    }
}
