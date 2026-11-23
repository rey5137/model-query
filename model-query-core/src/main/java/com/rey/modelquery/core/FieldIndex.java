package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A model's fields by name: its select fields, filter-only columns, column sets and children under exact,
 * case-sensitive keys, so an application turns the names a client sends into a selection without keeping its own map.
 * The processor generates one per {@code @QueryModel}; a hand-written vocabulary builds one with {@link #builder}.
 *
 * <p>A select field's key is tier 1 of the rule {@link ModelQuery#orderedBy} uses (R-QRY-14): a column's property
 * path, else its attribute path; an expression's name; an aggregate's {@link AggregateField#named named} property,
 * else its name. A filter-only column is keyed by its attribute path, a join set by its join's property path and a
 * child, value child or map child by its name. A select key and a set key can be one string, because each is looked
 * up by kind. Immutable, so an index serves any number of concurrent callers (INV-9).
 *
 * @param <M> the model the fields belong to
 * @implSpec R-COL-23, R-COL-24, D-121, D-262
 */
@Incubating
public final class FieldIndex<M> {

    private final Class<M> model;
    private final Map<String, SelectField<M, ?>> selects;
    /** Only the filter-only columns whose key no select field holds. */
    private final Map<String, ColumnField<M, ?, ?>> filterOnly;
    private final Map<String, SelectSet<M>> sets;
    private final Map<String, ChildField<M, ?>> children;
    private final Map<String, ValueChildField<M, ?>> values;
    private final Map<String, MapChildField<M, ?, ?>> maps;
    /** What {@link #filter} accepts: the select columns and expressions, then the filter-only columns. */
    private final Map<String, ScalarField<M, ?>> filters;
    private final Set<String> names;

    private FieldIndex(Class<M> model, Map<String, SelectField<M, ?>> selects,
            Map<String, ColumnField<M, ?, ?>> filterOnly, Map<String, SelectSet<M>> sets,
            Map<String, ChildField<M, ?>> children, Map<String, ValueChildField<M, ?>> values,
            Map<String, MapChildField<M, ?, ?>> maps) {
        this.model = model;
        this.selects = selects;
        this.filterOnly = filterOnly;
        this.sets = sets;
        this.children = children;
        this.values = values;
        this.maps = maps;
        var filters = new LinkedHashMap<String, ScalarField<M, ?>>();
        selects.forEach((key, field) -> {
            if (field instanceof ScalarField<M, ?> scalar) {
                filters.put(key, scalar);
            }
        });
        filters.putAll(filterOnly);
        this.filters = Collections.unmodifiableMap(filters);
        var all = new LinkedHashSet<>(selects.keySet());
        all.addAll(sets.keySet());
        all.addAll(children.keySet());
        all.addAll(values.keySet());
        all.addAll(maps.keySet());
        this.names = Collections.unmodifiableSet(all);
    }

    /** A builder of an index for {@code model}. */
    public static <M> Builder<M> builder(Class<M> model) {
        return new Builder<>(Objects.requireNonNull(model, "model"));
    }

    /** The select field (a column, expression or aggregate) named {@code name}; a filter-only column is not one. */
    public Optional<SelectField<M, ?>> select(String name) {
        return Optional.ofNullable(selects.get(Objects.requireNonNull(name, "name")));
    }

    /**
     * The column (a filter-only one included) or expression named {@code name}: what {@code Filters} takes (D-115).
     */
    public Optional<ScalarField<M, ?>> filter(String name) {
        return Optional.ofNullable(filters.get(Objects.requireNonNull(name, "name")));
    }

    /** The column set named {@code name}. */
    public Optional<SelectSet<M>> set(String name) {
        return Optional.ofNullable(sets.get(Objects.requireNonNull(name, "name")));
    }

    /** The child named {@code name}. */
    public Optional<ChildField<M, ?>> child(String name) {
        return Optional.ofNullable(children.get(Objects.requireNonNull(name, "name")));
    }

    /** The value child (an element collection's basic or embeddable values) named {@code name}. */
    public Optional<ValueChildField<M, ?>> valueChild(String name) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(name, "name")));
    }

    /** The map child (an element collection's {@code Map}) named {@code name}. */
    public Optional<MapChildField<M, ?, ?>> mapChild(String name) {
        return Optional.ofNullable(maps.get(Objects.requireNonNull(name, "name")));
    }

    /**
     * Turns each name into a select field, a set, a child, a value child or a map child, looked up in that order of
     * kinds. The select fields keep input order with a set expanded in place and a repeated field kept once; each
     * child, value child and map child appears once; a name that is none of them, a filter-only column and
     * {@code ""} included, is listed in {@code unknown}, distinct.
     * Never throws for a name the index does not hold, because the names usually come from a client (R-COL-23).
     *
     * @throws NullPointerException when {@code names} or an element is {@code null}
     */
    public Resolution<M> resolve(Collection<String> names) {
        Objects.requireNonNull(names, "names");
        var select = new LinkedHashSet<SelectField<M, ?>>();
        var kids = new LinkedHashSet<ChildField<M, ?>>();
        var valueKids = new LinkedHashSet<ValueChildField<M, ?>>();
        var mapKids = new LinkedHashSet<MapChildField<M, ?, ?>>();
        var unknown = new LinkedHashSet<String>();
        for (String name : names) {
            Objects.requireNonNull(name, "names element");
            SelectField<M, ?> field = selects.get(name);
            SelectSet<M> set = sets.get(name);
            ChildField<M, ?> child = children.get(name);
            ValueChildField<M, ?> value = values.get(name);
            MapChildField<M, ?, ?> map = maps.get(name);
            if (field != null) {
                select.add(field);
            } else if (set != null) {
                select.addAll(set.fields());
            } else if (child != null) {
                kids.add(child);
            } else if (value != null) {
                valueKids.add(value);
            } else if (map != null) {
                mapKids.add(map);
            } else {
                unknown.add(name);
            }
        }
        return new Resolution<>(SelectSet.copyOf(select), List.copyOf(kids), List.copyOf(valueKids),
                List.copyOf(mapKids), List.copyOf(unknown));
    }

    /**
     * An index holding just {@code names}, for a public API that exposes part of a model. A kept set holds only the
     * select fields the narrowed index keeps (R-COL-24).
     *
     * @throws ModelQueryDefinitionException {@code MQ1105} when a name is neither in {@link #names()} nor in
     *     {@link #filterNames()}, or a kept set is left empty
     * @throws NullPointerException when {@code names} or an element is {@code null}
     */
    public FieldIndex<M> only(Collection<String> names) {
        Objects.requireNonNull(names, "names");
        var keep = new LinkedHashSet<String>();
        var missing = new ArrayList<String>();
        for (String name : names) {
            Objects.requireNonNull(name, "names element");
            keep.add(name);
            if (!this.names.contains(name) && !filters.containsKey(name)) {
                missing.add(name);
            }
        }
        var keptSelects = kept(selects, keep);
        var keptFields = new LinkedHashSet<>(keptSelects.values());
        var keptSets = new LinkedHashMap<String, SelectSet<M>>();
        var emptied = new ArrayList<String>();
        sets.forEach((key, set) -> {
            if (keep.contains(key)) {
                var narrowed = set.fields().stream().filter(keptFields::contains).toList();
                if (narrowed.isEmpty()) {
                    emptied.add(key);
                }
                keptSets.put(key, narrowed.size() == set.fields().size() ? set : SelectSet.copyOf(narrowed));
            }
        });
        if (!missing.isEmpty() || !emptied.isEmpty()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1105, model.getSimpleName() + ": only(...) "
                    + (missing.isEmpty() ? "" : "names no key: " + missing + (emptied.isEmpty() ? "" : "; "))
                    + (emptied.isEmpty() ? "" : "leaves the set empty: " + emptied)
                    + "; names() are " + this.names + ", filterNames() are " + filters.keySet());
        }
        return new FieldIndex<>(model, keptSelects, kept(filterOnly, keep), keptSets, kept(children, keep),
                kept(values, keep), kept(maps, keep));
    }

    /** What {@link #resolve} accepts: the select, set, child, value child and map child keys, in that order. */
    public Set<String> names() {
        return names;
    }

    /** What {@link #filter} accepts: the select columns' and expressions' keys, then the filter-only columns'. */
    public Set<String> filterNames() {
        return filters.keySet();
    }

    @Override
    public String toString() {
        return "FieldIndex[" + model.getSimpleName() + " " + names + " filter " + filters.keySet() + "]";
    }

    /** The entries of {@code map} whose key is in {@code keep}, in map order. */
    private static <V> Map<String, V> kept(Map<String, V> map, Set<String> keep) {
        var result = new LinkedHashMap<String, V>();
        map.forEach((key, value) -> {
            if (keep.contains(key)) {
                result.put(key, value);
            }
        });
        return result;
    }

    /**
     * The name tier 1 of a sort gives {@code field}, or {@code null} when it has none: a column's property path, an
     * aggregate's {@code named} property. An expression is matched by its name, which is its {@code named} property
     * when it has one. The one helper the sort (R-QRY-14) and the index keys share (R-COL-23).
     */
    static String propertyKey(SelectField<?, ?> field) {
        if (field instanceof ColumnField<?, ?, ?> column) {
            return column.propertyPath();
        }
        return field instanceof AggregateField<?, ?> aggregate ? aggregate.property().orElse(null) : null;
    }

    /** The key {@code field} is held under: its tier 1 name, else a column's attribute path, else its name. */
    private static String key(SelectField<?, ?> field) {
        String key = propertyKey(field);
        if (key != null) {
            return key;
        }
        return field instanceof ColumnField<?, ?, ?> column ? column.identityPath() : field.name();
    }

    /**
     * What {@link #resolve} made of some names (R-COL-23).
     *
     * @param select the select fields, in input order, as a set
     * @param children the children, each once, in input order
     * @param values the value children, each once, in input order
     * @param maps the map children, each once, in input order
     * @param unknown the names that are none of them, distinct, in input order
     * @param <M> the model
     */
    @Incubating
    public record Resolution<M>(SelectSet<M> select, List<ChildField<M, ?>> children,
            List<ValueChildField<M, ?>> values, List<MapChildField<M, ?, ?>> maps, List<String> unknown) {

        /** Copies the lists, so a resolution cannot be changed through them (CC-IMM). */
        public Resolution {
            Objects.requireNonNull(select, "select");
            children = List.copyOf(children);
            values = List.copyOf(values);
            maps = List.copyOf(maps);
            unknown = List.copyOf(unknown);
        }
    }

    /**
     * Collects the fields of one index: public and {@code @Incubating}, because generated code in a user's jar calls
     * it (RFC 0006). Not thread-safe; one thread builds, and the {@link FieldIndex} it makes is.
     *
     * @param <M> the model
     */
    @Incubating
    public static final class Builder<M> {

        private final Class<M> model;
        private final Map<String, SelectField<M, ?>> selects = new LinkedHashMap<>();
        private final Map<String, ColumnField<M, ?, ?>> filterOnly = new LinkedHashMap<>();
        private final Map<String, SelectSet<M>> sets = new LinkedHashMap<>();
        private final Map<String, ChildField<M, ?>> children = new LinkedHashMap<>();
        private final Map<String, ValueChildField<M, ?>> values = new LinkedHashMap<>();
        private final Map<String, MapChildField<M, ?, ?>> maps = new LinkedHashMap<>();
        /** The first key given two different fields, thrown by {@link #build()}. */
        private IllegalStateException clash;

        private Builder(Class<M> model) {
            this.model = model;
        }

        /** Adds select fields, each under its key (see {@link FieldIndex}). */
        @SafeVarargs
        public final Builder<M> select(SelectField<M, ?>... fields) {
            for (SelectField<M, ?> field : checked(fields, "fields")) {
                add(selects, key(field), field, "select");
            }
            return this;
        }

        /** Adds columns that can be filtered on but not selected, each under its attribute path. */
        @SafeVarargs
        public final Builder<M> filterOnly(ColumnField<M, ?, ?>... columns) {
            for (ColumnField<M, ?, ?> column : checked(columns, "columns")) {
                add(filterOnly, column.identityPath(), column, "filter-only");
            }
            return this;
        }

        /** Adds {@code set} under {@code name}, such as {@code "ALL"}. */
        public Builder<M> set(String name, SelectSet<M> set) {
            add(sets, Objects.requireNonNull(name, "name"), Objects.requireNonNull(set, "set"), "set");
            return this;
        }

        /**
         * Adds {@code set} under {@code join}'s property path.
         *
         * @throws IllegalArgumentException when {@code join} has no property of its own, even below a named joined
         *     root, or is a root other than a joined root given one with {@code named} (D-213)
         */
        public Builder<M> joinSet(TableField<?, ?> join, SelectSet<M> set) {
            String key = Objects.requireNonNull(join, "join").propertyPath();
            if (key == null || key.isEmpty() || join.parent() != null && join.property() == null) {
                throw new IllegalArgumentException(
                        model.getSimpleName() + ": a join set needs a join with a property, got " + join);
            }
            return set(key, set);
        }

        /** Adds children, each under its {@link ChildField#name() name}. */
        @SafeVarargs
        public final Builder<M> child(ChildField<M, ?>... children) {
            for (ChildField<M, ?> child : checked(children, "children")) {
                add(this.children, child.name(), child, "child");
            }
            return this;
        }

        /** Adds value children, each under its {@link ValueChildField#name() name}. */
        @SafeVarargs
        public final Builder<M> valueChild(ValueChildField<M, ?>... children) {
            for (ValueChildField<M, ?> child : checked(children, "children")) {
                add(values, child.name(), child, "value child");
            }
            return this;
        }

        /** Adds map children, each under its {@link MapChildField#name() name}. */
        @SafeVarargs
        public final Builder<M> mapChild(MapChildField<M, ?, ?>... children) {
            for (MapChildField<M, ?, ?> child : checked(children, "children")) {
                add(maps, child.name(), child, "map child");
            }
            return this;
        }

        /**
         * The index. A filter-only column whose key a select column or expression already holds is dropped when
         * equal to it.
         *
         * @throws IllegalStateException when one key was given two different fields of a kind, a filter-only column
         *     included
         */
        public FieldIndex<M> build() {
            if (clash != null) {
                throw clash;
            }
            var onlyFilters = new LinkedHashMap<>(filterOnly);
            filterOnly.forEach((key, column) -> {
                if (selects.get(key) instanceof ScalarField<M, ?> held) {
                    if (!held.equals(column)) {
                        throw clash(key, "filter", held, column);
                    }
                    onlyFilters.remove(key);
                }
            });
            return new FieldIndex<>(model, Collections.unmodifiableMap(new LinkedHashMap<>(selects)),
                    Collections.unmodifiableMap(onlyFilters), Collections.unmodifiableMap(new LinkedHashMap<>(sets)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(children)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(values)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(maps)));
        }

        /**
         * Puts {@code value} under {@code key}; an equal value already there stays, and a different one is recorded
         * for {@link #build()} to throw.
         */
        private <V> void add(Map<String, V> into, String key, V value, String kind) {
            V held = into.putIfAbsent(key, value);
            if (held == null || held == value) {
                return;
            }
            if (!held.equals(value)) {
                if (clash == null) {
                    clash = clash(key, kind, held, value);
                }
                return;
            }
            if (value instanceof SelectField<?, ?> field && AggregateField.conflict((SelectField<?, ?>) held, field)) {
                throw AggregateField.redefined("FieldIndex", field);
            }
        }

        private IllegalStateException clash(String key, String kind, Object held, Object other) {
            return new IllegalStateException(model.getSimpleName() + ": two different " + kind
                    + " fields share the key '" + key + "': " + held + " and " + other + "; give one of them a "
                    + "name of its own");
        }

        private static <V> List<V> checked(V[] values, String name) {
            Objects.requireNonNull(values, name);
            var result = new ArrayList<V>(values.length);
            for (V value : values) {
                result.add(Objects.requireNonNull(value, name + " element"));
            }
            return result;
        }
    }
}
