package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The identifying columns of a model: one column, or several. Immutable.
 *
 * @param <M> the model the key belongs to
 * @param <K> the key's Java type; {@code List<Object>} (component values, in declaration order) for a composite key
 * @implSpec R-QRY-03, R-QRY-04, R-EXE-12, R-EXE-13
 */
@Incubating
public final class PrimaryKey<M, K> {

    private final List<ColumnField<M, ?, ?>> columns;

    private PrimaryKey(List<ColumnField<M, ?, ?>> columns) {
        this.columns = List.copyOf(columns);
    }

    /** A single-column key. */
    public static <M, K> PrimaryKey<M, K> of(ColumnField<M, ?, K> column) {
        return new PrimaryKey<>(List.of(Objects.requireNonNull(column, "column")));
    }

    /** A composite key of two or more columns, in the given order. */
    @SafeVarargs
    public static <M> PrimaryKey<M, List<Object>> composite(
            ColumnField<M, ?, ?> first, ColumnField<M, ?, ?> second, ColumnField<M, ?, ?>... more) {
        var all = new ArrayList<ColumnField<M, ?, ?>>();
        all.add(Objects.requireNonNull(first, "first"));
        all.add(Objects.requireNonNull(second, "second"));
        for (ColumnField<M, ?, ?> column : Objects.requireNonNull(more, "more")) {
            all.add(Objects.requireNonNull(column, "more element"));
        }
        return new PrimaryKey<>(all);
    }

    /** The key columns in order, as a list that throws on mutation. */
    public List<ColumnField<M, ?, ?>> columns() {
        return columns;
    }

    /**
     * {@code key IN (keys)} over attribute-value keys, as {@link #columns()} paths in {@code ctx}; a composite key is
     * an OR of per-key conjunctions, since JPA has no row-value IN (P-4). With {@code collapseSingle}, one key renders
     * as an equality, with no {@code OR} around one conjunction, as a write does; without it, as a one-element
     * {@code IN}, as a read by keys does. Under inline value handling each key value is its own explicit parameter,
     * typed by its column's mapping, else {@code MQ1502} before any statement (R-FLT-08, D-177).
     */
    @EngineFacing
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Predicate in(List<Object> keys, JoinContext ctx, CriteriaBuilder cb, boolean collapseSingle) {
        if (columns.size() == 1) {
            ColumnField<M, ?, ?> column = columns.get(0);
            Path path = column.path(ctx);
            if (collapseSingle && keys.size() == 1) {
                return equal(column, path, keys.get(0), ctx, cb);
            }
            if (!ctx.renderOptions().inlinesValues()) {
                return path.in(keys);
            }
            // Each element its own parameter, as a filter's IN binds its later elements (R-FLT-08).
            return path.in(keys.stream().map(key -> operand(column, path, key, ctx)).toArray(Expression[]::new));
        }
        Predicate[] each = new Predicate[keys.size()];
        for (int i = 0; i < each.length; i++) {
            List<?> values = (List<?>) keys.get(i);
            Predicate[] equal = new Predicate[columns.size()];
            for (int c = 0; c < equal.length; c++) {
                ColumnField<M, ?, ?> column = columns.get(c);
                equal[c] = equal(column, column.path(ctx), values.get(c), ctx, cb);
            }
            each[i] = cb.and(equal);
        }
        return collapseSingle && each.length == 1 ? each[0] : cb.or(each);
    }

    /** {@code path = value}, the value an explicit parameter under inline value handling (R-FLT-08). */
    private static Predicate equal(ColumnField<?, ?, ?> column, Path<?> path, Object value, JoinContext ctx,
            CriteriaBuilder cb) {
        return ctx.renderOptions().inlinesValues() ? cb.equal(path, operand(column, path, value, ctx))
                : cb.equal(path, value);
    }

    /**
     * Under inline value handling, {@code MQ1502} as {@link #in} raises it for a key of these columns in {@code ctx},
     * decided from the columns' types alone: for a primary-key-first page, whose step-2 keys bind only after step 1
     * ran, so it refuses before step 1 (R-FLT-08, R-FLT-21, D-177). A no-op under the default setting.
     */
    @EngineFacing
    public void requireBindable(JoinContext ctx) {
        if (!ctx.renderOptions().inlinesValues()) {
            return;
        }
        for (ColumnField<M, ?, ?> column : columns) {
            ValueOperands.requireBindable(ctx.renderOptions(), column.path(ctx),
                    ColumnField.boxed(column.attributeType()), label(column));
        }
    }

    /** {@code value} as {@code column}'s explicit key parameter, or {@code MQ1502} naming the key and the column. */
    private static Expression<?> operand(ColumnField<?, ?, ?> column, Path<?> path, Object value, JoinContext ctx) {
        return ValueOperands.attributeOperand(ctx, column, path, value, label(column));
    }

    /** {@code column} as {@code MQ1502} names a key column under inline value handling. */
    private static String label(ColumnField<?, ?, ?> column) {
        return column + ": the key under inline value handling";
    }

    /**
     * {@code key = modelKey}, as {@link #columns()} paths in {@code ctx}: the model key converted through each
     * column's converter as {@code whereKey} converts it (R-WRT-08, D-63), for {@code one(q, key)} (R-EXE-12).
     *
     * @throws IllegalArgumentException for a {@code null} key or component, or a composite key that is not a list of
     *     as many components as the key has columns
     */
    @EngineFacing
    public Predicate equal(Object modelKey, JoinContext ctx, CriteriaBuilder cb) {
        return in(List.of(attributeKey(modelKey)), ctx, cb, true);
    }

    /**
     * {@code modelKey} as the attribute value a statement binds and a row's key is compared with: converted through
     * each column's converter as {@code whereKey} converts it, a composite key as an unmodifiable list of component
     * values, for {@code byKeys} (R-EXE-13).
     *
     * @throws IllegalArgumentException for a {@code null} key or component, a component a converter turns into
     *     {@code null}, or a composite key that is not a list of as many components as the key has columns
     */
    @EngineFacing
    public Object attributeKey(Object modelKey) {
        if (modelKey == null) {
            throw new IllegalArgumentException("the key is null");
        }
        if (columns.size() > 1) {
            if (!(modelKey instanceof List<?> values) || values.size() != columns.size()) {
                throw new IllegalArgumentException("a key of " + columns.size() + " components is a list of "
                        + columns.size() + " values, not " + modelKey);
            }
            // List.of(...).contains(null) throws, so each component is tested.
            if (values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("a key component is null: " + modelKey);
            }
        }
        Object key = WriteRendering.distinctKeys(this, List.of(modelKey)).get(0);
        // A converter can turn a value into NULL, and key = NULL matches no row.
        if (key == null || key instanceof List<?> converted && converted.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("a key component converts to null: " + modelKey);
        }
        return key;
    }
}
