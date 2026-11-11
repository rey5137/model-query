package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.annotations.JoinKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A source joined to a model rooted at an entity, by name (R-SRC-21): the source model, the join's kind and the pairs
 * of outer column and source key field it joins on. What a {@code @JoinSource} generates; the query takes the
 * {@link SourceQuery} it reads through {@code ModelQuery.Builder#joinSource}. Immutable, so a constant (INV-9).
 *
 * <p>The pairs cover the source's full key, each key field once, and the source is unique, so the join adds at most
 * one row per outer row; both are checked when the query is built ({@code MQ1910}). {@link #root()} is the table the
 * joined columns sit on: {@code ColumnField.over(model, JOIN.root(), sourceField)} reads a source field through it.
 *
 * @param <M> the outer model
 * @param <S> the source model
 * @implSpec R-SRC-21, R-SRC-22, D-185
 */
@Incubating
public final class SourceJoin<M, S> {

    private final String name;
    private final Class<S> source;
    private final JoinKind kind;
    private final List<On<M, S>> on;
    private final TableField<S, S> root;

    private SourceJoin(String name, Class<S> source, JoinKind kind, List<On<M, S>> on, TableField<?, ?> within) {
        this.name = name;
        this.source = source;
        this.kind = kind;
        this.on = on;
        TableField<S, S> joined = TableField.joinedSource(name, source);
        this.root = within == null ? joined : joined.within(within);
    }

    /**
     * The join {@code name} of {@code source}, of {@code kind}, on the {@code on} pairs in order.
     *
     * @throws IllegalArgumentException when {@code name} is blank or {@code on} is empty
     */
    public static <M, S> SourceJoin<M, S> of(String name, Class<S> source, JoinKind kind, List<On<M, S>> on) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a joined source needs a name");
        }
        List<On<M, S>> pairs = List.copyOf(Objects.requireNonNull(on, "on"));
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("join '" + name + "' needs at least one on(...) pair");
        }
        return new SourceJoin<>(name, Objects.requireNonNull(source, "source"), Objects.requireNonNull(kind, "kind"),
                pairs, null);
    }

    /**
     * This join registered under {@code join}, the {@code @Join} field whose nested model declared it: a column of
     * {@link #root()} then also selects the presence key of {@code join} and of every join above it, and a
     * {@link JoinScope} maps the nested model's join to this one (D-216). The link is not part of the root's key.
     * Pass the {@code @Join} field's own constant, whose {@code presentBy} the walk reads (a copy with an equal key
     * but no {@code presentBy} selects no key, and a scope refuses it), and build this join's columns from the
     * {@link #root()} returned here (a column built from the root before {@code within} still selects the link's keys,
     * as the presence walk reads {@code within} from the join the query binds, D-220).
     *
     * @throws ModelQueryDefinitionException {@code MQ1107} for a join already registered under one, or an outer
     *     column of a pair that is not read at or below {@code join}
     */
    @Incubating
    public SourceJoin<M, S> within(TableField<?, ?> join) {
        Objects.requireNonNull(join, "join");
        if (root.within() != null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1107, this + ": within(" + join.describe()
                    + "): already registered within " + root.within().describe());
        }
        for (On<M, S> pair : on) {
            EntityJoin.requireReadUnder(this, pair.column(), join);
        }
        return new SourceJoin<>(name, source, kind, on, join);
    }

    /** The pair joining the outer {@code column} to the source key field {@code key}, compared by {@code =}. */
    public static <M, S, C> On<M, S> on(ColumnField<M, ?, C> column, ColumnField<S, ?, C> key) {
        return new On<>(column, key);
    }

    /** The join's name, unique in the query that binds it. */
    public String name() {
        return name;
    }

    /** The source model. */
    public Class<S> source() {
        return source;
    }

    /**
     * {@code LEFT} keeps an outer row with no source row, reading its joined columns as NULL; {@code INNER} drops it.
     */
    public JoinKind kind() {
        return kind;
    }

    /** The pairs, in declaration order. */
    public List<On<M, S>> on() {
        return on;
    }

    /**
     * The table of this join's columns, equal only to the root of a join of the same name and source model: two joins
     * of one source model stay apart (R-SRC-21).
     */
    public TableField<S, S> root() {
        return root;
    }

    @Override
    public String toString() {
        return "SourceJoin[" + name + ": " + kind + " " + source.getSimpleName() + "]";
    }

    /**
     * {@code source} bound to {@code join} by the builder: the key-coverage and uniqueness checks run on it when the
     * query is built (R-SRC-21).
     *
     * @throws ModelQueryDefinitionException {@code MQ1910} for a source of another model, a non-unique source (a
     *     plain {@code recursive(...)} walk included), or pairs that miss or exceed its key
     */
    static void check(String model, SourceJoin<?, ?> join, SourceQuery<?> source) {
        String where = model + ": join '" + join.name + "' of " + join.source.getSimpleName();
        if (source.model() != join.source) {
            throw new ModelQueryDefinitionException(MqCode.MQ1910, where + " is bound to a SourceQuery of "
                    + source.model().getSimpleName());
        }
        if (!source.unique()) {
            // A plain recursive(...) walk can reach a node twice, unchecked, which would repeat its outer row (D-187).
            throw new ModelQueryDefinitionException(MqCode.MQ1910, where + ": the source's rows have no unique key, so "
                    + "the join could repeat an outer row; " + (source.recursive()
                            ? "use tree(...) for a checked recursive walk"
                            : "make the branches disjoint with @Discriminator"));
        }
        Set<String> key = new HashSet<>();
        source.key().columns().forEach(column -> key.add(SourceQuery.propertyOf(column).orElse(column.name())));
        Set<String> paired = new HashSet<>();
        for (On<?, ?> pair : join.on) {
            String field = SourceQuery.propertyOf(pair.key).orElse(pair.key.name());
            if (!key.contains(field)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1910, where + ": on(...) names " + field
                        + ", which is not a key field of the source; join on its key " + sorted(key));
            }
            if (pair.key.isConverted()
                    || ColumnField.boxed(pair.column.attributeType()) != ColumnField.boxed(pair.key.attributeType())) {
                throw new ModelQueryDefinitionException(MqCode.MQ1910, where + ": on(" + pair.column.name() + ", "
                        + field + ") joins a converted or differently typed key; the join compares database values, "
                        + "so a converted or differently typed key can't be joined");
            }
            if (!paired.add(field)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1910, where + ": on(...) pairs the key field "
                        + field + " twice");
            }
        }
        if (!paired.equals(key)) {
            var missing = new HashSet<>(key);
            missing.removeAll(paired);
            throw new ModelQueryDefinitionException(MqCode.MQ1910, where + ": on(...) misses the key field(s) "
                    + sorted(missing) + "; a partial key could repeat an outer row");
        }
    }

    /**
     * {@code MQ1910} unless each outer column of {@code join} reads the root or a to-one path from it, or an entity
     * join of {@code entityJoins} anchored on the root's columns and its to-one paths, which, under an {@code INNER}
     * {@code join}, is {@code INNER} too (R-SRC-21, D-218). Such a source is created after every column-anchored join,
     * so binding order doesn't matter; a source-anchored join is created after the sources it reads, and another
     * joined source's columns are no anchor; an {@code INNER} source on a {@code LEFT} join would drop the outer row
     * the {@code LEFT} join keeps.
     */
    static void checkAnchors(String model, SourceJoin<?, ?> join, List<? extends EntityJoin<?, ?>> entityJoins) {
        String where = model + ": join '" + join.name + "' of " + join.source.getSimpleName();
        for (On<?, ?> pair : join.on) {
            TableField<?, ?> read = pair.column.table();
            while (read != null && !read.isJoinedEntity() && !read.isJoinedSource()) {
                read = read.parent();
            }
            if (read == null) {
                continue;
            }
            String reads = where + ": on(" + pair.column.name() + ", ...) reads the "
                    + (read.isJoinedSource() ? "joined source '" : "entity join '") + read.key().alias() + "'";
            if (read.isJoinedSource()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1910, reads + "; a joined source is anchored on the "
                        + "root, its to-one paths, or an entity join anchored on the root's columns, never on another "
                        + "joined source");
            }
            EntityJoin<?, ?> anchor = null;
            for (EntityJoin<?, ?> entityJoin : entityJoins) {
                if (entityJoin.root().key().equals(read.key())) {
                    anchor = entityJoin;
                }
            }
            if (anchor == null) {
                throw new ModelQueryDefinitionException(MqCode.MQ1910, reads + ", which this query does not bind; "
                        + "bind it with joinEntity(...)");
            }
            if (anchor.anchoredOnSource()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1910, reads + ", which is anchored on a joined "
                        + "source; only an entity join anchored on the root's columns may anchor a source");
            }
            if (join.kind == JoinKind.INNER && anchor.kind() == JoinKind.LEFT) {
                throw new ModelQueryDefinitionException(MqCode.MQ1910, reads + ", which is LEFT: this INNER join "
                        + "would drop every row '" + anchor.name() + "' misses; declare '" + join.name + "' LEFT, or '"
                        + anchor.name() + "' INNER");
            }
        }
    }

    private static List<String> sorted(Set<String> names) {
        var list = new ArrayList<>(names);
        list.sort(null);
        return list;
    }

    /**
     * One pair of a join's {@code on}: the outer {@code column}, an entity path the outer model reads, and the source's
     * {@code key} field it equals.
     *
     * @param <M> the outer model
     * @param <S> the source model
     */
    @Incubating
    public static final class On<M, S> {

        private final ColumnField<M, ?, ?> column;
        private final ColumnField<S, ?, ?> key;

        private On(ColumnField<M, ?, ?> column, ColumnField<S, ?, ?> key) {
            this.column = Objects.requireNonNull(column, "column");
            this.key = Objects.requireNonNull(key, "key");
        }

        /** The outer column. */
        public ColumnField<M, ?, ?> column() {
            return column;
        }

        /** The source key field. */
        public ColumnField<S, ?, ?> key() {
            return key;
        }

        /** The CTE column the key field is aliased by (R-SRC-12). */
        String alias() {
            return SourceQuery.columnName(SourceQuery.propertyOf(key).orElse(key.name()));
        }
    }
}
