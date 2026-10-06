package com.rey.modelquery.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything an insert's stages hold; each stage call returns a copy with one part changed, and {@link #build} checks
 * it and reads the rows (D-60, D-117).
 *
 * @param columns the columns the model writes
 * @param constants the {@code set} constants, in the order set
 * @param source an insert-select's source root, or {@code null} for insert-values
 * @param mappings an insert-select's {@code map} calls, in order; empty for insert-values
 * @param where an insert-select's row choice, or {@code null} before it and for insert-values
 * @param rows an insert-values call's rows as given, until {@code build()} reads them; otherwise {@code null}
 * @param values the rows {@code build()} read, each a list of column values in column order; otherwise {@code null}
 * @param conflict the conflict clause, or {@code null} without one
 * @param chunkOptions the {@code chunked(...)} options, or {@code null}
 * @param persistenceContext the {@code persistenceContext(...)} mode, or {@code null}
 */
record InsertDraft<E, M>(
        InsertColumns<M, E> columns,
        List<Assignment<?, ?>> constants,
        TableField<?, ?> source,
        List<Mapped> mappings,
        WriteRows where,
        List<? extends M> rows,
        List<List<Object>> values,
        Conflict<E, M> conflict,
        ChunkOptions chunkOptions,
        PersistenceContextMode persistenceContext) {

    /** An insert-select's start, before its first {@code map}. */
    static <E, M> InsertDraft<E, M> select(InsertColumns<M, E> columns, TableField<?, ?> source) {
        return new InsertDraft<>(columns, List.of(), source, List.of(), null, null, null, null, null, null);
    }

    /** An insert-values call's start; {@code rows} is copied, its rows read only at {@code build()}. */
    static <E, M> InsertDraft<E, M> values(InsertColumns<M, E> columns, List<? extends M> rows) {
        // An ArrayList, not List.copyOf: a null row is MQ1803 at build(), not a NullPointerException here.
        return new InsertDraft<>(columns, List.of(), null, List.of(), null, new ArrayList<M>(rows), null, null, null,
                null);
    }

    InsertDraft<E, M> map(ColumnField<?, ?, ?> target, ColumnField<?, ?, ?> sourceColumn) {
        var all = new ArrayList<>(mappings);
        all.add(new Mapped(target, sourceColumn));
        return new InsertDraft<>(columns, constants, source, List.copyOf(all), where, rows, values, conflict,
                chunkOptions, persistenceContext);
    }

    InsertDraft<E, M> set(Assignment<?, ?> constant) {
        var all = new ArrayList<>(constants);
        all.add(constant);
        return new InsertDraft<>(columns, List.copyOf(all), source, mappings, where, rows, values, conflict,
                chunkOptions, persistenceContext);
    }

    InsertDraft<E, M> where(WriteRows chosen) {
        return new InsertDraft<>(columns, constants, source, mappings, chosen, rows, values, conflict, chunkOptions,
                persistenceContext);
    }

    InsertDraft<E, M> conflict(Conflict<E, M> clause) {
        return new InsertDraft<>(columns, constants, source, mappings, where, rows, values, clause, chunkOptions,
                persistenceContext);
    }

    InsertDraft<E, M> chunk(ChunkOptions options) {
        return new InsertDraft<>(columns, constants, source, mappings, where, rows, values, conflict, options,
                persistenceContext);
    }

    InsertDraft<E, M> mode(PersistenceContextMode mode) {
        return new InsertDraft<>(columns, constants, source, mappings, where, rows, values, conflict, chunkOptions,
                mode);
    }

    /**
     * Checks this draft and reads its rows, returning the draft a definition keeps: the rows read once into
     * immutable value lists, and the caller's list dropped (INV-9, R-WRT-30).
     *
     * @throws ModelQueryDefinitionException {@code MQ1601}, {@code MQ1801}, {@code MQ1802}, {@code MQ1803},
     *     {@code MQ1804} or {@code MQ1808}, as the builders' {@code build()} state
     */
    InsertDraft<E, M> build() {
        InsertRules.checkConstants(columns, constants);
        if (source != null) {
            where.check(columns.rootEntity().getSimpleName() + " insert-select");
            checkMappings();
            return this;
        }
        if (chunkOptions != null && chunkOptions.locksKeys()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1801, this + ": lockKeys() on insert-values, which "
                    + "selects no key to lock");
        }
        if (conflict != null) {
            conflict.check(columns);
        }
        var read = new ArrayList<List<Object>>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            read.add(columns.read(rows.get(i), i));
        }
        if (conflict != null) {
            conflict.checkDistinct(columns, read);
        }
        return new InsertDraft<>(columns, constants, null, mappings, null, null, List.copyOf(read), conflict,
                chunkOptions, persistenceContext);
    }

    /**
     * Checks that every column is mapped exactly once, from a source column with the same converter class or none.
     *
     * @throws ModelQueryDefinitionException {@code MQ1801} otherwise
     */
    private void checkMappings() {
        Set<Integer> mapped = new HashSet<>();
        for (Mapped mapping : mappings) {
            int index = columns.indexOf(mapping.target());
            if (index < 0) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, mapping.target() + ": mapped, but not a "
                        + "column of " + columns);
            }
            if (!mapped.add(index)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, mapping.target() + ": mapped twice");
            }
            Class<?> targetConverter = mapping.target().converterClass();
            Class<?> sourceConverter = mapping.source().converterClass();
            if (targetConverter != sourceConverter) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, mapping.target() + ": mapped from "
                        + mapping.source() + " with " + converterName(sourceConverter) + ", but written with "
                        + converterName(targetConverter) + "; the database copies attribute values, so both "
                        + "columns need the same converter or none");
            }
        }
        List<ColumnField<M, E, ?>> all = columns.columns();
        for (int i = 0; i < all.size(); i++) {
            if (!mapped.contains(i)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1801, all.get(i) + ": not mapped; an insert-select "
                        + "maps every column of the insert model once");
            }
        }
    }

    private static String converterName(Class<?> converter) {
        return converter == null ? "no converter" : converter.getSimpleName();
    }

    /** The written entity and how many rows, or where they come from, for a log; never a value (D-95). */
    @Override
    public String toString() {
        String target = columns.rootEntity().getSimpleName();
        if (source != null) {
            String chosen = where == null ? "" : where.toString();
            return target + " (from " + source.rootEntity().getSimpleName() + (chosen.isEmpty() ? "" : ", " + chosen)
                    + ")";
        }
        int count = values != null ? values.size() : rows.size();
        return target + " (" + count + (count == 1 ? " row" : " rows") + (conflict == null ? "" : ", on conflict")
                + ")";
    }

    /** One {@code map} call: a column of the insert model and the source column it is copied from. */
    record Mapped(ColumnField<?, ?, ?> target, ColumnField<?, ?, ?> source) {}

    /**
     * A conflict clause: the key columns, and the update, or {@code null} for {@code doNothing()}.
     *
     * @param keys the conflict columns, in the order named
     * @param update the {@code doUpdate} action, or {@code null} for {@code doNothing()}
     * @param anyUniqueKey whether {@code anyUniqueKey()} accepts the vendor's any-key detection (R-WRT-36)
     * @param keepVersion whether {@code keepVersion()} leaves the stored row's {@code @Version} as it is
     */
    record Conflict<E, M>(List<ColumnField<M, E, ?>> keys, ConflictUpdate.Action<E, M> update, boolean anyUniqueKey,
            boolean keepVersion) {

        Conflict<E, M> anyKey() {
            return new Conflict<>(keys, update, true, keepVersion);
        }

        Conflict<E, M> keep() {
            return new Conflict<>(keys, update, anyUniqueKey, true);
        }

        /**
         * Checks the key columns and the update's assignments and {@code where} (R-WRT-34).
         *
         * @throws ModelQueryDefinitionException {@code MQ1804} for a key column named twice or not in
         *     {@code columns}, an update assigning a key column, a column twice or a column not on the root, a
         *     {@code setFromRow} column not in {@code columns}, or a {@code where} reading a joined column or holding
         *     {@code exists} or a sub-select
         */
        void check(InsertColumns<M, E> columns) {
            Set<String> keyNames = new HashSet<>();
            for (ColumnField<M, E, ?> key : keys) {
                if (columns.indexOf(key) < 0) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1804, key + ": onConflict(...) names a column "
                            + "that is not one of " + columns + ", so no row carries its value");
                }
                if (!keyNames.add(key.name())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1804, key + ": onConflict(...) names it twice");
                }
            }
            if (update == null) {
                return;
            }
            columns.keyColumns().forEach(key -> keyNames.add(key.name()));
            Set<String> assigned = new HashSet<>();
            var written = new ArrayList<ColumnField<?, ?, ?>>(update.fromRow());
            for (ColumnField<M, E, ?> column : update.fromRow()) {
                if (columns.indexOf(column) < 0) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1804, column + ": setFromRow(...) on a column "
                            + "that is not one of " + columns + ", so the incoming row has no value for it");
                }
            }
            update.assignments().forEach(assignment -> written.add(assignment.column()));
            for (ColumnField<?, ?, ?> column : written) {
                if (!column.table().key().equals(columns.root().key())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1804, column + ": doUpdate(...) assigns the "
                            + column.table().describe() + "; it writes only the stored row's own columns");
                }
                if (keyNames.contains(column.name())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1804, column + ": doUpdate(...) assigns a key "
                            + "column; the conflict key and the id stay as stored");
                }
                if (!assigned.add(column.name())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1804, column + ": doUpdate(...) assigns it "
                            + "twice");
                }
            }
            InsertRules.checkConflictWhere(columns.root(), ConditionGroup.conditions(update.where()));
        }

        /**
         * Checks that no two rows share a conflict-key tuple, compared by {@code equals} (R-WRT-37). A tuple holding a
         * {@code null} is left out: SQL {@code NULL}s never conflict, and a unique index that treats them as equal
         * reports its own constraint error.
         *
         * @throws ModelQueryDefinitionException {@code MQ1808} naming the two rows' positions, never their values
         */
        void checkDistinct(InsertColumns<M, E> columns, List<List<Object>> rows) {
            int[] at = keys.stream().mapToInt(columns::indexOf).toArray();
            Map<List<Object>, Integer> seen = new HashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                var tuple = new ArrayList<>(at.length);
                for (int index : at) {
                    tuple.add(rows.get(i).get(index));
                }
                if (tuple.contains(null)) {
                    continue;
                }
                Integer first = seen.putIfAbsent(tuple, i);
                if (first != null) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1808, columns.rootEntity().getSimpleName()
                            + ": rows " + first + " and " + i + " share the conflict key " + keys + "; vendors "
                            + "disagree on such rows, so one call may not hold both");
                }
            }
        }
    }
}
