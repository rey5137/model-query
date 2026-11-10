package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Splits a child load's keys into groups and binds {@link QueryParam}s from each group's value, so every round reads
 * the keys of one group with that group's values bound into the same filter (R-FCH-18, D-204). Immutable, so it may
 * be a {@code static final} constant (INV-9); the group and value functions must be pure. Equal by identity, as its
 * functions and its {@link GroupingRule} are.
 *
 * <p>Built by {@link #by}, then {@link Builder#bind} and {@link Builder#groupWhen}, and finished by one of the two
 * endings that say how a row outside its key's group is treated (D-206): {@link Builder#verifyMissing} or
 * {@link Builder#assumeInRange}.
 *
 * @param <K> the key's Java type: the attribute type of the child's foreign key, or of the parent's key
 * @param <G> the group value a key maps to
 * @implSpec api/15 R-FCH-18, D-204, D-206, D-208
 */
@Incubating
public final class KeyGroups<K, G> {

    /**
     * The binding a child load's query holds for each param its key groups bind: each round replaces it with the
     * round's value, and a statement built without a round renders the param as a parameter nothing binds.
     */
    static final Object PER_ROUND = new Object() {
        @Override
        public String toString() {
            return "a key-group round's value";
        }
    };

    private final Class<K> keyType;
    private final Function<? super K, ? extends G> group;
    /** Each bound param, by identity, with the function that computes its value from a group, in bind order. */
    private final Map<QueryParam<?>, Function<? super G, ?>> binds;
    /** When to run grouped, or {@code null} to always run grouped (D-208). */
    private final GroupingRule rule;
    /** Each bound param's value for the catch-all group, or {@code null} when the definition has none. */
    private final Map<QueryParam<?>, Object> catchAll;
    private final boolean verifyMissing;

    private KeyGroups(Builder<K, G> builder, Map<QueryParam<?>, Object> catchAll, boolean verifyMissing) {
        this.keyType = builder.keyType;
        this.group = builder.group;
        this.binds = builder.binds;
        this.rule = builder.rule;
        this.catchAll = catchAll;
        this.verifyMissing = verifyMissing;
    }

    /**
     * Starts a definition whose keys are of {@code keyType} (a primitive type is its wrapper) and fall in the group
     * {@code group} maps them to.
     */
    @SuppressWarnings("unchecked")
    public static <K, G> Builder<K, G> by(Class<K> keyType, Function<? super K, ? extends G> group) {
        return new Builder<>((Class<K>) ColumnField.boxed(Objects.requireNonNull(keyType, "keyType")),
                Objects.requireNonNull(group, "group"), Map.of(), null, null);
    }

    /**
     * Starts a definition whose keys are a composite key's {@code List<Object>}, one element per key column in the
     * order of the {@link PrimaryKey}, and fall in the group {@code group} maps them to; as {@link #by} with
     * {@code List} as the key type, without the raw cast.
     */
    @SuppressWarnings("unchecked")
    public static <G> Builder<List<Object>, G> byComposite(Function<? super List<Object>, ? extends G> group) {
        return new Builder<>((Class<List<Object>>) (Class<?>) List.class, Objects.requireNonNull(group, "group"),
                Map.of(), null, null);
    }

    /**
     * The key type, a wrapper for a primitive; for a child load it must equal the matched key column's attribute
     * type, before any converter, and for {@code byKeys} and {@code one(q, key)} the primary key's type, as the caller
     * passes it.
     */
    @EngineFacing
    public Class<K> keyType() {
        return keyType;
    }

    /** The params the groups bind, in bind order. */
    @EngineFacing
    public Set<QueryParam<?>> params() {
        return binds.keySet();
    }

    /**
     * The group {@code key} falls in; {@code key} is, for a child load, the matched column's attribute value, before any
     * converter, and for {@code byKeys} and {@code one(q, key)} the key as the caller passes it.
     *
     * @throws ModelQueryExecutionException {@code MQ2607} when the group function returns {@code null}; the function's
     *     own exception propagates unwrapped
     */
    @EngineFacing
    public Object groupOf(Object key, Object owner) {
        G value = group.apply(keyType.cast(key));
        if (value == null) {
            throw new ModelQueryExecutionException(MqCode.MQ2607, owner + ": the key-group function returned null "
                    + "for the key " + key + "; map a key it can't decode to the catch-all group instead");
        }
        return value;
    }

    /**
     * Each bound param's value for {@code group}, a value {@link #groupOf} returned, in bind order; a message names
     * {@code owner} and the param, never a value (D-95).
     *
     * @throws ModelQueryDefinitionException {@code MQ1502} when a value is {@code null}, not an instance of its param's
     *     type, or of a type no expression binds; a value function's own exception propagates unwrapped
     */
    @EngineFacing
    @SuppressWarnings("unchecked")
    public Map<QueryParam<?>, Object> values(Object group, Object owner) {
        return values((G) group, String.valueOf(owner)); // a group value is only ever one the group function returned
    }

    /**
     * Each bound param's value for the catch-all group, the {@code all} of {@link Builder#verifyMissing} or
     * {@link Builder#groupWhen}, evaluated at the terminal call; empty when the definition has no catch-all.
     */
    @EngineFacing
    public Optional<Map<QueryParam<?>, Object>> catchAll() {
        return Optional.ofNullable(catchAll);
    }

    /** Whether the keys that found no row are read again with the catch-all values (D-206). */
    @EngineFacing
    public boolean verifiesMissing() {
        return verifyMissing;
    }

    /** The rule that decides per call whether to run grouped, or empty to always run grouped (D-208). */
    @EngineFacing
    public Optional<GroupingRule> rule() {
        return Optional.ofNullable(rule);
    }

    private Map<QueryParam<?>, Object> values(G group, String site) {
        var values = new LinkedHashMap<QueryParam<?>, Object>();
        binds.forEach((param, value) -> {
            Object bound = value.apply(group);
            ParamReads.requireBindable(site + ", bind(" + param + ", ...)", param, bound);
            values.put(param, bound);
        });
        return Collections.unmodifiableMap(values);
    }

    /** The key type, the params and the ending, never a value (D-95). */
    @Override
    public String toString() {
        return "KeyGroups.by(" + keyType.getSimpleName() + ")" + (binds.isEmpty() ? "" : ".bind" + binds.keySet())
                + (rule == null ? "" : ".groupWhen") + (verifyMissing ? ".verifyMissing" : ".assumeInRange");
    }

    /**
     * An unfinished {@link KeyGroups}: immutable, each call returns a copy. Only an ending builds the definition, so
     * the compiler makes the caller choose how a row outside its key's group is treated (D-206).
     *
     * @param <K> the key's Java type
     * @param <G> the group value a key maps to
     * @implSpec api/15 R-FCH-18, D-206, D-208
     */
    @CheckReturnValue
    @Incubating
    public static final class Builder<K, G> {

        private final Class<K> keyType;
        private final Function<? super K, ? extends G> group;
        private final Map<QueryParam<?>, Function<? super G, ?>> binds;
        private final GroupingRule rule;
        /** {@code groupWhen}'s catch-all, or {@code null}. */
        private final G all;

        private Builder(Class<K> keyType, Function<? super K, ? extends G> group,
                Map<QueryParam<?>, Function<? super G, ?>> binds, GroupingRule rule, G all) {
            this.keyType = keyType;
            this.group = group;
            this.binds = binds;
            this.rule = rule;
            this.all = all;
        }

        /**
         * Binds {@code param} in each round to {@code value} of the round's group, replacing an earlier binding of
         * {@code param}. The value is the param's type, which is the compared attribute's.
         */
        public <T> Builder<K, G> bind(QueryParam<T> param, Function<? super G, ? extends T> value) {
            Objects.requireNonNull(param, "param");
            Objects.requireNonNull(value, "value");
            var bound = new LinkedHashMap<>(binds);
            bound.put(param, value);
            return new Builder<>(keyType, group, Collections.unmodifiableMap(bound), rule, all);
        }

        /**
         * Asks {@code rule} once per call whether to run grouped; when it says no, the call runs as if every key were
         * in the group {@code all}, which must exclude no row (D-208). Replaces an earlier {@code groupWhen}.
         */
        public Builder<K, G> groupWhen(GroupingRule rule, G all) {
            return new Builder<>(keyType, group, binds, Objects.requireNonNull(rule, "rule"),
                    Objects.requireNonNull(all, "all"));
        }

        /**
         * Ends the definition: the keys that found no row are read again with {@code all}'s values bound, which must
         * exclude no row, and a row found so logs {@code MQ2608} once per definition (D-206).
         *
         * @throws ModelQueryDefinitionException {@code MQ1708} when {@code all} is unequal to {@code groupWhen}'s, and
         *     {@code MQ1502} when a value of {@code all} is {@code null} or mistyped; a value function's exception
         *     propagates unwrapped
         */
        public KeyGroups<K, G> verifyMissing(G all) {
            Objects.requireNonNull(all, "all");
            if (this.all != null && !this.all.equals(all)) {
                throw new ModelQueryDefinitionException(MqCode.MQ1708, this + ": verifyMissing(all) is given a "
                        + "catch-all unequal to groupWhen's; one definition has one catch-all, so the "
                        + "ungrouped run and the re-read can't disagree");
            }
            return finish(all, true);
        }

        /**
         * Ends the definition with no re-read: a row outside its key's group may not be read, and under
         * {@code groupWhen} whether it is depends on the branch a call takes (D-206).
         *
         * @throws ModelQueryDefinitionException {@code MQ1502} when a value of {@code groupWhen}'s {@code all} is
         *     {@code null} or mistyped; a value function's exception propagates unwrapped
         */
        public KeyGroups<K, G> assumeInRange() {
            return finish(all, false);
        }

        private KeyGroups<K, G> finish(G catchAll, boolean verifyMissing) {
            var groups = new KeyGroups<>(this, null, verifyMissing);
            if (catchAll == null) {
                return groups;
            }
            return new KeyGroups<>(this, groups.values(catchAll, this + ", the catch-all group"),
                    verifyMissing);
        }

        /** The key type and the params, never a value (D-95). */
        @Override
        public String toString() {
            return "KeyGroups.by(" + keyType.getSimpleName() + ")" + (binds.isEmpty() ? "" : ".bind" + binds.keySet());
        }
    }

    /**
     * Decides once per call, from the call's distinct keys and groups after dedupe, whether grouping pays for its
     * extra statements (D-208). Must be pure; compared by identity, so a {@code static final} rule is checked once.
     *
     * @implSpec api/15 R-FCH-18, D-208
     */
    @Incubating
    @FunctionalInterface
    public interface GroupingRule {

        /** Whether a call with {@code keys} distinct keys in {@code groups} groups runs one round set per group. */
        boolean group(int keys, int groups);

        /**
         * Groups when there are at least {@code n} keys per group on average: {@code keys >= n * groups}.
         *
         * @throws IllegalArgumentException when {@code n} is below 1
         */
        static GroupingRule minKeysPerGroup(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("minKeysPerGroup(" + n + "): n must be at least 1");
            }
            return (keys, groups) -> keys >= (long) n * groups; // long, so a large n can't overflow
        }

        /**
         * Groups when there are at most {@code n} groups.
         *
         * @throws IllegalArgumentException when {@code n} is below 1
         */
        static GroupingRule maxGroups(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("maxGroups(" + n + "): n must be at least 1");
            }
            return (keys, groups) -> groups <= n;
        }
    }
}
