package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@code @Join} field's scope for {@link Row#scoped(JoinScope)}: its table, and the name map from each join the
 * nested model declares ({@link EntityJoin}, {@link SourceJoin}) to the outer join registered for it under the
 * field's prefix. Built once per field and checked pair by pair, so a mapping that would read another join's row is
 * refused when the constant is initialized, not read as a wrong value (INV-5). Immutable, so a constant (INV-9).
 *
 * @implSpec R-COL-41, D-214, D-216
 */
@Incubating
public final class JoinScope {

    private final TableField<?, ?> table;
    /** Each nested joined root's key to the outer joined root standing for it, in mapping order. */
    private final Map<JoinKey, TableField<?, ?>> roots;

    private JoinScope(TableField<?, ?> table, Map<JoinKey, TableField<?, ?>> roots) {
        this.table = table;
        this.roots = roots;
    }

    /** The scope of {@code join} with no nested join mapped: what {@link Row#scoped(TableField)} reads through. */
    @Incubating
    public static JoinScope of(TableField<?, ?> join) {
        return new JoinScope(Objects.requireNonNull(join, "join"), Map.of());
    }

    /**
     * This scope with the nested model's entity join {@code nested} read through {@code outer}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1107} when {@code nested} or {@code outer} is mapped already,
     *     the two join different entities or kinds, {@code outer} is not registered {@code within} this scope's
     *     table (or the outer join standing for {@code nested}'s) with its {@code presentBy} key, or its pairs and
     *     {@code when} term are not {@code nested}'s re-rooted under the scope
     */
    @Incubating
    public <E> JoinScope entity(EntityJoin<?, E> nested, EntityJoin<?, E> outer) {
        Objects.requireNonNull(nested, "nested");
        Objects.requireNonNull(outer, "outer");
        String where = this + ": " + outer + " for " + nested;
        if (nested.entity() != outer.entity() || nested.kind() != outer.kind()) {
            throw refused(where, "they join a different entity or kind");
        }
        requireRegistered(where, nested.root(), outer.root());
        requireSameSize(where, nested.on(), outer.on());
        for (int i = 0; i < nested.on().size(); i++) {
            EntityJoin.On<?> in = nested.on().get(i);
            EntityJoin.On<?> out = outer.on().get(i);
            if (!in.idAttribute().equals(out.idAttribute())) {
                throw refused(where, "pair " + i + " pairs id '" + out.idAttribute() + "', not '"
                        + in.idAttribute() + "'");
            }
            requireReRooted(where, in.anchor(), out.anchor());
        }
        Optional<? extends EntityJoin.When<?>> in = nested.when();
        Optional<? extends EntityJoin.When<?>> out = outer.when();
        if (in.isPresent() != out.isPresent()
                || in.isPresent() && !Objects.equals(in.get().value(), out.get().value())) {
            throw refused(where, "their when(...) terms differ");
        }
        if (in.isPresent()) {
            requireReRooted(where, in.get().column(), out.get().column());
        }
        return with(nested.root(), outer.root());
    }

    /**
     * This scope with the nested model's source join {@code nested} read through {@code outer}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1107} when {@code nested} or {@code outer} is mapped already,
     *     the two join different sources or kinds, {@code outer} is not registered {@code within} this scope's
     *     table (or the outer join standing for {@code nested}'s) with its {@code presentBy} key, or its pairs are
     *     not {@code nested}'s re-rooted under the scope
     */
    @Incubating
    public <S> JoinScope source(SourceJoin<?, S> nested, SourceJoin<?, S> outer) {
        Objects.requireNonNull(nested, "nested");
        Objects.requireNonNull(outer, "outer");
        String where = this + ": " + outer + " for " + nested;
        if (nested.source() != outer.source() || nested.kind() != outer.kind()) {
            throw refused(where, "they join a different source or kind");
        }
        requireRegistered(where, nested.root(), outer.root());
        requireSameSize(where, nested.on(), outer.on());
        for (int i = 0; i < nested.on().size(); i++) {
            SourceJoin.On<?, ?> in = nested.on().get(i);
            SourceJoin.On<?, ?> out = outer.on().get(i);
            if (!in.key().name().equals(out.key().name())) {
                throw refused(where, "pair " + i + " reads key '" + out.key().name() + "', not '"
                        + in.key().name() + "'");
            }
            requireReRooted(where, in.column(), out.column());
        }
        return with(nested.root(), outer.root());
    }

    /** The {@code @Join} field's table: the scope a nested model's own root is re-rooted under. */
    @Incubating
    public TableField<?, ?> table() {
        return table;
    }

    /**
     * {@code path}, a join key of the nested model, as a path of the outer one: a path on the nested root re-rooted
     * under {@link #table()}, a path on a nested joined root re-rooted under the outer joined root standing for it,
     * or {@code null} for a joined root this scope does not map (D-216).
     */
    JoinKey translate(JoinKey path) {
        JoinKey root = path.root();
        if (!root.isJoinedRoot()) {
            return path.reroot(table.key());
        }
        TableField<?, ?> outer = roots.get(root);
        return outer == null ? null : path.replaceRoot(outer.key());
    }

    /**
     * The scope's view of {@code column}: a column on a nested joined root this scope does not map is
     * {@code MQ1107}, never the scope's own column of that attribute (INV-5, D-214).
     */
    JoinKey translate(ColumnField<?, ?, ?> column) {
        JoinKey path = translate(column.table().key());
        if (path == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1107, column + ": read through " + this
                    + ", which maps no outer join for its joined root " + column.table().describe());
        }
        return path;
    }

    /** {@code outer} is unmapped and registered within the outer path of {@code nested}'s {@code @Join}. */
    private void requireRegistered(String where, TableField<?, ?> nested, TableField<?, ?> outer) {
        if (roots.containsKey(nested.key()) || roots.containsValue(outer)) {
            throw refused(where, "one of them is mapped already");
        }
        JoinKey expected = nested.within() == null ? table.key() : translate(nested.within().key());
        if (outer.within() == null || !outer.within().key().equals(expected)) {
            throw refused(where, "the outer join is registered within "
                    + (outer.within() == null ? "no @Join" : outer.within().describe()) + ", not this scope's");
        }
        // Equal join keys are not enough: the presence walk reads the presentBy of the very table passed to within.
        PrimaryKey<?, ?> key = (nested.within() == null ? table : nested.within()).presenceKey();
        if (!Objects.equals(outer.within().presenceKey(), key)) {
            throw refused(where, "the outer join is registered within " + outer.within().describe() + " "
                    + describe(outer.within().presenceKey()) + ", not " + describe(key)
                    + "; register it within the @Join field's own constant");
        }
    }

    private static String describe(PrimaryKey<?, ?> key) {
        return key == null ? "with no presentBy" : "presentBy " + key.columns();
    }

    private JoinScope with(TableField<?, ?> nested, TableField<?, ?> outer) {
        var mapped = new LinkedHashMap<>(roots);
        mapped.put(nested.key(), outer);
        return new JoinScope(table, Collections.unmodifiableMap(mapped));
    }

    private void requireReRooted(String where, ColumnField<?, ?, ?> nested, ColumnField<?, ?, ?> outer) {
        JoinKey expected = translate(nested.table().key());
        if (expected == null || !expected.equals(outer.table().key()) || !nested.name().equals(outer.name())) {
            throw refused(where, "the outer join reads " + outer.name() + " on " + outer.table().describe()
                    + ", not " + nested.name() + " on " + nested.table().describe() + " re-rooted under the scope");
        }
    }

    private static void requireSameSize(String where, List<?> nested, List<?> outer) {
        if (nested.size() != outer.size()) {
            throw refused(where, "they have " + nested.size() + " and " + outer.size() + " on(...) pairs");
        }
    }

    private static ModelQueryDefinitionException refused(String where, String reason) {
        return new ModelQueryDefinitionException(MqCode.MQ1107, where + ": refused, " + reason);
    }

    /** Equal when the table has the same join key and the same nested joins map to the same outer ones. */
    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof JoinScope other && table.equals(other.table) && roots.equals(other.roots);
    }

    @Override
    public int hashCode() {
        return 31 * table.hashCode() + roots.hashCode();
    }

    @Override
    public String toString() {
        var names = new StringBuilder();
        roots.forEach((nested, outer) -> names.append(names.isEmpty() ? "" : ", ").append(nested.alias())
                .append(" -> ").append(outer.key().alias()));
        return "JoinScope[" + table.describe() + (names.isEmpty() ? "" : ": " + names) + "]";
    }
}
