package com.rey.modelquery.core;

import jakarta.persistence.criteria.JoinType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * RFC 0024 spike emulation of the core changes the RFC asks for, kept out of production sources. Everything here is
 * a stand-in for one sentence of the RFC; the rest of each query runs the real engine path.
 */
public final class SpikeCore {

    private SpikeCore() {}

    /** Whether the Part C shadow of {@link EntityJoin} is the class loaded (its {@code CHAINS} switch exists). */
    public static boolean shadowLoaded() {
        try {
            EntityJoin.class.getDeclaredField("CHAINS");
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    /** Part C: lets a column anchor read an earlier entity join (the shadow's only change). */
    public static void chains(boolean on) {
        EntityJoin.CHAINS = on;
    }

    /**
     * Part A: {@code presentBy(key)} on a joined-entity root, keeping its join name. Production throws MQ1104 there
     * and its copy drops the name; this builds the node the RFC describes through TableField's private constructor.
     */
    @SuppressWarnings("unchecked")
    public static <P, T> TableField<P, T> presentBy(TableField<P, T> joinedRoot, PrimaryKey<?, ?> key) {
        Objects.requireNonNull(key, "key");
        if (!joinedRoot.isJoinedEntity()) {
            throw new IllegalArgumentException(joinedRoot.describe() + " is not a joined-entity root");
        }
        try {
            Constructor<?> ctor = TableField.class.getDeclaredConstructor(Class.class, TableField.class, String.class,
                    JoinType.class, String.class, BiFunction.class, PrimaryKey.class, String.class, boolean.class,
                    Class.class, TableField.class, String.class);
            ctor.setAccessible(true);
            return (TableField<P, T>) ctor.newInstance(joinedRoot.rootEntity(), null, null, null, "", null, key,
                    field(joinedRoot, "property"), false, null, null, field(joinedRoot, "joinName"));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The join name a TableField carries, or null. */
    public static String joinName(TableField<?, ?> table) {
        return (String) field(table, "joinName");
    }

    public static boolean isJoinedEntity(TableField<?, ?> table) {
        return table.isJoinedEntity();
    }

    public static PrimaryKey<?, ?> presenceKey(TableField<?, ?> table) {
        return table.presenceKey();
    }

    public static String propertyPath(TableField<?, ?> table) {
        return table.propertyPath();
    }

    /** The path a sort property or {@code FilterSpec} path is matched against first (R-QRY-14). */
    public static String propertyPath(ColumnField<?, ?, ?> column) {
        return column.propertyPath();
    }

    private static Object field(TableField<?, ?> table, String name) {
        try {
            Field f = TableField.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(table);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Part B: the map from a nested model's joined roots to the outer joins the outer model registered for one
     * {@code @Join} field. Built once per field, as a generated constant would be; each pair is checked here, so a
     * generator bug (a swapped prefix, a wrong type) is loud at class initialization rather than a wrong value.
     */
    public static final class NameMap {
        private final TableField<?, ?> scope;
        private final Map<JoinKey, TableField<?, ?>> roots;

        private NameMap(TableField<?, ?> scope, Map<JoinKey, TableField<?, ?>> roots) {
            this.scope = scope;
            this.roots = roots;
        }

        public TableField<?, ?> scope() {
            return scope;
        }
    }

    /**
     * {@code pairs} alternate a nested join ({@link EntityJoin} or {@link SourceJoin} of the nested model) and the
     * outer join that stands for it under {@code scope}.
     *
     * @throws IllegalStateException for a pair of different kinds or targets, or an outer join whose anchors do not
     *     read at or below {@code scope} (the swapped-prefix case)
     */
    public static NameMap nameMap(TableField<?, ?> scope, Object... pairs) {
        var roots = new LinkedHashMap<JoinKey, TableField<?, ?>>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object nested = pairs[i];
            Object outer = pairs[i + 1];
            if (nested instanceof EntityJoin<?, ?> n && outer instanceof EntityJoin<?, ?> o) {
                if (n.entity() != o.entity() || n.kind() != o.kind()) {
                    throw new IllegalStateException("RFC 0024 name map: " + o + " does not stand for " + n);
                }
                for (EntityJoin.On<?> on : o.on()) {
                    requireUnder(scope, on.anchor().table(), o);
                }
                roots.put(n.root().key(), o.root());
            } else if (nested instanceof SourceJoin<?, ?> n && outer instanceof SourceJoin<?, ?> o) {
                if (n.source() != o.source() || n.kind() != o.kind()) {
                    throw new IllegalStateException("RFC 0024 name map: " + o + " does not stand for " + n);
                }
                for (SourceJoin.On<?, ?> on : o.on()) {
                    requireUnder(scope, on.column().table(), o);
                }
                roots.put(n.root().key(), o.root());
            } else {
                throw new IllegalStateException("RFC 0024 name map: mismatched pair " + nested + " / " + outer);
            }
        }
        return new NameMap(scope, Map.copyOf(roots));
    }

    private static void requireUnder(TableField<?, ?> scope, TableField<?, ?> anchor, Object outer) {
        if (!anchor.isAtOrBelow(scope)) {
            throw new IllegalStateException("RFC 0024 name map: " + outer + " is anchored on " + anchor.describe()
                    + ", not at or below the scope " + scope.describe());
        }
    }

    /**
     * Part B: {@code row.scoped(scope)} that stops at joined roots. A column of the nested model on its own root (or
     * an association below it) reads through the production scope; a column on a nested joined root (entity or
     * source join) reads through the outer join the map names, and an unmapped joined root throws: never the
     * production fallback, which re-roots a joined root onto the scope itself and reads the scope's own column.
     */
    public static Row scoped(Row row, NameMap names) {
        Row plain = row.scoped(names.scope);
        return new Row() {
            @Override
            public <C> C get(SelectField<?, C> column) {
                return target(column).get(column);
            }

            @Override
            public Object raw(SelectField<?, ?> column) {
                return target(column).raw(column);
            }

            @Override
            public boolean isSelected(SelectField<?, ?> column) {
                return target(column).isSelected(column);
            }

            @Override
            public Row scoped(TableField<?, ?> join) {
                throw new UnsupportedOperationException("RFC 0024 spike: nesting two levels deep is not emulated");
            }

            private Row target(SelectField<?, ?> column) {
                if (!(column instanceof ColumnField<?, ?, ?> field)) {
                    return plain;
                }
                TableField<?, ?> top = field.table();
                while (top.parent() != null) {
                    top = top.parent();
                }
                if (joinName(top) == null) {
                    return plain;
                }
                TableField<?, ?> outer = names.roots.get(top.key());
                if (outer == null) {
                    throw new IllegalStateException("RFC 0024 scoped row: " + field.name() + " reads the nested "
                            + "join " + top.describe() + ", which the name map of " + names.scope.describe()
                            + " does not map to an outer join");
                }
                return row.scoped(outer);
            }
        };
    }
}
