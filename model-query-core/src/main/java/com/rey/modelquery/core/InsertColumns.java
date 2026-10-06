package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The columns an insert model writes, in declaration order, each with the function that reads its value from a row:
 * the type of a generated {@code INSERT_COLUMNS}, and the processor-free entry point of every insert (INV-8). Each
 * {@code add} returns a new list, so one instance can be {@code static final} and shared (INV-9).
 *
 * @param <M> the insert model
 * @param <E> the root entity the rows are written to
 * @implSpec R-WRT-25, D-117
 */
@Incubating
public final class InsertColumns<M, E> {

    private final TableField<E, E> root;
    private final List<Column<M, E, ?>> columns;

    private InsertColumns(TableField<E, E> root, List<Column<M, E, ?>> columns) {
        this.root = root;
        this.columns = columns;
    }

    /**
     * An empty column list for rows written to {@code root}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join, not a root
     */
    public static <M, E> InsertColumns<M, E> of(TableField<E, E> root) {
        return new InsertColumns<>(InsertRules.requireRoot(root, "of"), List.of());
    }

    /**
     * This list with {@code column} appended, read from each row by {@code value}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1801} for a column already in the list, or one not on the root,
     *     as through a self-referencing join
     */
    public <C> InsertColumns<M, E> add(ColumnField<M, E, C> column, Function<? super M, ? extends C> value) {
        return append(new Column<>(column, value, false));
    }

    /**
     * This list with {@code column}, a column of the model's {@code @PrimaryKey}, appended: a row whose value for it
     * is {@code null} throws {@code MQ1802} when it is read (R-WRT-26).
     *
     * @throws ModelQueryDefinitionException {@code MQ1801} as {@link #add} does
     */
    public <C> InsertColumns<M, E> addKey(ColumnField<M, E, C> column, Function<? super M, ? extends C> value) {
        return append(new Column<>(column, value, true));
    }

    /** The entity the rows are written to. */
    public Class<E> rootEntity() {
        return root.rootEntity();
    }

    /** The columns, in the order added, as a list that throws on mutation. */
    public List<ColumnField<M, E, ?>> columns() {
        return columns.stream().<ColumnField<M, E, ?>>map(Column::field).toList();
    }

    /** The columns added with {@link #addKey}, in the order added. */
    public List<ColumnField<M, E, ?>> keyColumns() {
        return columns.stream().filter(Column::key).<ColumnField<M, E, ?>>map(Column::field).toList();
    }

    /** The entity name and the attributes written, for a log or a message. The format is not API. */
    @Override
    public String toString() {
        return rootEntity().getSimpleName() + columns.stream().map(column -> column.field().name()).toList();
    }

    TableField<E, E> root() {
        return root;
    }

    /**
     * The position of the column writing the same root attribute as {@code column}, or -1; a column of a join, even
     * one naming the same attribute, is not one of them.
     */
    int indexOf(ColumnField<?, ?, ?> column) {
        if (!column.table().key().equals(root.key())) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).field().name().equals(column.name())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reads {@code row} once into its column values, in column order, as a list that throws on mutation. The copy is
     * shallow: a mutable value is still the caller's (INV-9).
     *
     * @param index the row's position in the call, for the message
     * @throws ModelQueryDefinitionException {@code MQ1803} for a {@code null} row, {@code MQ1802} for a {@code null}
     *     value of a key column
     */
    List<Object> read(M row, int index) {
        if (row == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1803, this + ": row " + index + " is null");
        }
        var values = new Object[columns.size()];
        for (int i = 0; i < values.length; i++) {
            Column<M, E, ?> column = columns.get(i);
            values[i] = column.value().apply(row);
            if (values[i] == null && column.key()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1802, column.field() + ": row " + index
                        + " has a null id; an id with no generator is assigned by the row");
            }
        }
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    private InsertColumns<M, E> append(Column<M, E, ?> column) {
        ColumnField<M, E, ?> field = column.field();
        InsertRules.requireOnRoot(root, field, "an insert model");
        if (indexOf(field) >= 0) {
            throw new ModelQueryDefinitionException(MqCode.MQ1801, field + ": added twice; each attribute is one "
                    + "column of the row");
        }
        var all = new ArrayList<>(columns);
        all.add(column);
        return new InsertColumns<>(root, List.copyOf(all));
    }

    /** One column, the function reading its value from a row, and whether it is part of the model's key. */
    private record Column<M, E, C>(ColumnField<M, E, C> field, Function<? super M, ? extends C> value, boolean key) {

        Column {
            Objects.requireNonNull(field, "column");
            Objects.requireNonNull(value, "value");
        }
    }
}
