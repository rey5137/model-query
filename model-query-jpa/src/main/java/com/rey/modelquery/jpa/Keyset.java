package com.rey.modelquery.jpa;

import com.rey.modelquery.core.BuiltQuery;
import com.rey.modelquery.core.ColumnField;
import com.rey.modelquery.core.JoinContext;
import com.rey.modelquery.core.KeysetCursorCodec;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelQueryExecutionException;
import com.rey.modelquery.core.MqCode;
import com.rey.modelquery.core.NullOrdering;
import com.rey.modelquery.core.NullPrecedence;
import com.rey.modelquery.core.OrderField;
import com.rey.modelquery.core.PrimaryKey;
import com.rey.modelquery.core.Row;
import jakarta.persistence.Parameter;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The keyset of an ungrouped query, or of a primary key alone: its order closed by the primary key, the cursor read
 * from a row, the predicate selecting the rows after a cursor, its order fingerprint and, for {@code before}, a
 * reversed copy. Immutable.
 *
 * @param <M> the model
 * @implSpec R-PAG-04, R-PAG-05, R-PAG-06, R-PRF-09, R-COL-13, R-PAG-17, R-PAG-18, R-PAG-19, R-PAG-20, R-PAG-22, D-63
 */
final class Keyset<M> {

    /** Where a key's resolved null precedence came from (R-PAG-22): the caller, the provider or the database. */
    private enum Precedence {
        /** An explicit {@code nullsFirst()} or {@code nullsLast()} on the order column. */
        EXPLICIT,
        /** {@code ProviderSupport.defaultNullPrecedence}, applied the same way in both directions (D-36). */
        PROVIDER_DEFAULT,
        /** The profile's default ascending null ordering, which descending order reverses (D-35). */
        DATABASE_DEFAULT,
        /** A primary-key column, which is never NULL (R-PAG-03). */
        PRIMARY_KEY
    }

    /**
     * One keyset column. {@code nulls} is where its NULLs sort: the explicit precedence, else for a non-key column
     * the provider's configured default or the database's default in its direction, and {@code DEFAULT} where
     * neither is known, or for a primary-key column, which R-PAG-03 already keeps non-NULL. {@code refuseNull} when
     * a NULL in it throws {@code MQ2202}. {@code source} says how that precedence was resolved, for {@code before}'s
     * rendering (R-PAG-20, R-PAG-22).
     */
    private record Key<M>(OrderField<M, ?> order, NullPrecedence nulls, boolean refuseNull, boolean primaryKey,
            Precedence source) {}

    /** How many of {@code keys} are the query's own order, ahead of the primary-key tie-breakers. */
    private final int ordered;
    /** What the keyset belongs to, for messages. */
    private final Object label;
    /** The root entity, for the fingerprint; {@code null} for a write's key-only keyset. */
    private final Class<?> rootEntity;
    /** The primary key, used to recognize a cursor's own key; {@code null} for a write's key-only keyset. */
    private final PrimaryKey<M, ?> pk;
    private final List<Key<M>> keys;
    /** The first 8 bytes of the canonical form's SHA-256, or {@code null} without a root entity (R-PAG-19). */
    private final byte[] fingerprint;

    private Keyset(int ordered, Object label, Class<?> rootEntity, PrimaryKey<M, ?> pk, List<Key<M>> keys) {
        this.ordered = ordered;
        this.label = label;
        this.rootEntity = rootEntity;
        this.pk = pk;
        this.keys = List.copyOf(keys);
        this.fingerprint = rootEntity == null ? null : canonicalFingerprint();
    }

    /**
     * The query's order, then every primary-key column it does not already order by, in the direction of its last
     * order column, so no two rows share a cursor (R-PAG-04). A {@code DEFAULT}-precedence column's NULLs sort where
     * {@code providerNulls} puts them in both directions when the provider is configured with a default null ordering,
     * else where the profile's {@code defaultOrdering} puts them in its direction; {@code nullKeys} says whether it
     * pages its NULLs there (R-PAG-05, R-COL-13, D-35, D-36).
     */
    static <M> Keyset<M> of(ModelQuery<?, ?, M> q, PrimaryKey<M, ?> key, NullOrdering defaultOrdering,
            Optional<NullPrecedence> providerNulls, KeysetNullKeys nullKeys) {
        List<Key<M>> keys = new ArrayList<>();
        boolean ascending = true;
        for (OrderField<M, ?> order : q.orderBy()) {
            keys.add(key(order, key.columns().contains(order.column()), defaultOrdering, providerNulls, nullKeys));
            ascending = order.ascending();
        }
        for (ColumnField<M, ?, ?> column : key.columns()) {
            if (q.orderBy().stream().noneMatch(order -> order.column().equals(column))) {
                keys.add(new Key<>(new OrderField<>(column, ascending, NullPrecedence.DEFAULT),
                        NullPrecedence.DEFAULT, false, true, Precedence.PRIMARY_KEY));
            }
        }
        return new Keyset<>(q.orderBy().size(), q, q.rootEntity(), key, keys);
    }

    /**
     * The keyset of {@code key} alone, ascending in its column order, as a write's key loop pages by (D-63). A
     * primary-key column is never NULL (R-PAG-03), so no column refuses one.
     */
    static <M> Keyset<M> ofKey(PrimaryKey<M, ?> key) {
        List<Key<M>> keys = new ArrayList<>();
        for (ColumnField<M, ?, ?> column : key.columns()) {
            keys.add(new Key<>(new OrderField<>(column, true, NullPrecedence.DEFAULT), NullPrecedence.DEFAULT, false,
                    true, Precedence.PRIMARY_KEY));
        }
        return new Keyset<>(0, "primary key " + key.columns(), null, null, keys);
    }

    private static <M> Key<M> key(OrderField<M, ?> order, boolean keyColumn, NullOrdering defaultOrdering,
            Optional<NullPrecedence> providerNulls, KeysetNullKeys nullKeys) {
        if (keyColumn) {
            return new Key<>(order, order.nulls(), false, true, Precedence.PRIMARY_KEY);
        }
        if (order.nulls() != NullPrecedence.DEFAULT) {
            return new Key<>(order, order.nulls(), false, false, Precedence.EXPLICIT);
        }
        // The provider's configured default sorts a bare order the same way in both directions; the database's
        // default is ascending, and descending order reverses it.
        if (providerNulls.isPresent()) {
            return new Key<>(order, providerNulls.get(), nullKeys == KeysetNullKeys.FAIL, false,
                    Precedence.PROVIDER_DEFAULT);
        }
        NullPrecedence nulls = defaultOrdering == NullOrdering.UNKNOWN ? NullPrecedence.DEFAULT
                : (defaultOrdering == NullOrdering.NULLS_FIRST) == order.ascending()
                        ? NullPrecedence.FIRST : NullPrecedence.LAST;
        return new Key<>(order, nulls, nullKeys == KeysetNullKeys.FAIL || nulls == NullPrecedence.DEFAULT, false,
                Precedence.DATABASE_DEFAULT);
    }

    /** The same keys with every direction and null precedence flipped, for {@code before} (R-PAG-20, R-PAG-22). */
    Keyset<M> reversed() {
        List<Key<M>> flipped = new ArrayList<>(keys.size());
        for (Key<M> key : keys) {
            NullPrecedence nulls = flip(key.nulls());
            // Explicit and provider-default precedence renders flipped and explicit; the database's default and a
            // primary-key column may render bare reversed, because the database flips it (R-PAG-22).
            NullPrecedence rendered = key.source == Precedence.EXPLICIT || key.source == Precedence.PROVIDER_DEFAULT
                    ? nulls : NullPrecedence.DEFAULT;
            OrderField<M, ?> order = new OrderField<>(key.order().column(), !key.order().ascending(), rendered);
            flipped.add(new Key<>(order, nulls, key.refuseNull, key.primaryKey, key.source));
        }
        return new Keyset<>(ordered, label, rootEntity, pk, flipped);
    }

    private static NullPrecedence flip(NullPrecedence nulls) {
        return switch (nulls) {
            case FIRST -> NullPrecedence.LAST;
            case LAST -> NullPrecedence.FIRST;
            case DEFAULT -> NullPrecedence.DEFAULT;
        };
    }

    /** Appends the primary-key tie-breakers to {@code built}'s order, which already holds the query's own. */
    void appendOrder(BuiltQuery<M> built, CriteriaBuilder cb) {
        if (keys.size() == ordered) {
            return;
        }
        List<Order> orders = new ArrayList<>(built.query().getOrderList());
        for (Key<M> key : keys.subList(ordered, keys.size())) {
            Expression<?> column = key.order().column().expression(built.joins());
            orders.add(key.order().ascending() ? cb.asc(column) : cb.desc(column));
        }
        built.query().orderBy(orders);
    }

    /**
     * Sets {@code built}'s order to this keyset's keys, replacing the query's own: the forward keyset reproduces the
     * query's order, and the reversed one renders it flipped for {@code before} (R-PAG-20).
     */
    void applyOrder(BuiltQuery<M> built, CriteriaBuilder cb) {
        List<Order> orders = new ArrayList<>();
        for (Key<M> key : keys) {
            orders.addAll(key.order().toOrders(built.joins(), cb));
        }
        built.query().orderBy(orders);
    }

    /** The first 8 bytes of SHA-256 over this keyset's canonical form (R-PAG-19). */
    byte[] fingerprint() {
        return fingerprint.clone();
    }

    /** The first key column whose attribute type no cursor codec carries, or empty (R-PAG-17, MQ2210). */
    Optional<ColumnField<M, ?, ?>> unsupportedColumn() {
        for (Key<M> key : keys) {
            ColumnField<M, ?, ?> column = column(key);
            if (!KeysetCursorCodec.supports(column.attributeType())) {
                return Optional.of(column);
            }
        }
        return Optional.empty();
    }

    /**
     * The cursor's decoded values resolved against the keys: an enum name becomes its constant, a value of another
     * type its column's, and a NULL in a refusing or primary-key column is refused (R-PAG-17, R-PAG-18, MQ2208).
     */
    Object[] resolve(Object[] decoded, Object resolver) {
        if (decoded.length != keys.size()) {
            throw malformed(resolver, "it names " + decoded.length + " keys, not the " + keys.size()
                    + " this query orders by");
        }
        Object[] values = new Object[decoded.length];
        for (int i = 0; i < decoded.length; i++) {
            Key<M> key = keys.get(i);
            ColumnField<M, ?, ?> column = column(key);
            Object value = decoded[i];
            if (value == null) {
                if (key.refuseNull() || key.primaryKey()) {
                    throw malformed(resolver, "column " + column.path() + " is null, and a cursor cannot name it");
                }
                values[i] = null;
                continue;
            }
            Class<?> expected = box(column.attributeType());
            if (expected.isEnum()) {
                if (!(value instanceof String name)) {
                    throw malformed(resolver, "a value for column " + column.path() + " is not an enum name");
                }
                values[i] = enumConstant(resolver, column, expected, name);
            } else if (!expected.isAssignableFrom(value.getClass())) {
                throw malformed(resolver, "a value for column " + column.path() + " is a "
                        + value.getClass().getSimpleName() + ", not a " + expected.getSimpleName());
            } else {
                values[i] = value;
            }
        }
        return values;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Object resolver, ColumnField<?, ?, ?> column, Class<?> expected, String name) {
        try {
            return Enum.valueOf((Class<? extends Enum>) expected, name);
        } catch (IllegalArgumentException e) {
            throw malformed(resolver, "value " + name + " is not a " + expected.getSimpleName() + " for column "
                    + column.path());
        }
    }

    /**
     * The primary key the cursor's values name, in the shape {@link Keys#keyOf} returns: the single value, or the
     * list of a composite key. {@code null} without a primary key.
     */
    Object primaryKeyOf(Object[] values) {
        if (pk == null) {
            return null;
        }
        List<ColumnField<M, ?, ?>> columns = pk.columns();
        Object[] keyValues = new Object[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            ColumnField<M, ?, ?> column = columns.get(c);
            keyValues[c] = null;
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).primaryKey() && keys.get(i).order().column().equals(column)) {
                    keyValues[c] = values[i];
                    break;
                }
            }
        }
        return keyValues.length == 1 ? keyValues[0] : List.of(keyValues);
    }

    /** The cursor naming {@code row}'s position, or {@code MQ2210} when it exceeds the cap (R-PAG-18). */
    String encode(Object resolver, Row row) {
        Object[] values = cursor(row);
        String cursor = KeysetCursorCodec.encode(fingerprint(), values);
        if (cursor.length() > KeysetCursorCodec.MAX_CURSOR_LENGTH) {
            // Named after the key whose value takes the most bytes, the one that pushed the cursor over the cap.
            int longest = 0;
            for (int i = 1; i < values.length; i++) {
                if (KeysetCursorCodec.encodedLength(values[i]) > KeysetCursorCodec.encodedLength(values[longest])) {
                    longest = i;
                }
            }
            ColumnField<M, ?, ?> column = column(keys.get(longest));
            throw new ModelQueryExecutionException(MqCode.MQ2210, resolver + ": keyset column " + column.path()
                    + " makes the cursor longer than " + KeysetCursorCodec.MAX_CURSOR_LENGTH + " characters");
        }
        return cursor;
    }

    private static ModelQueryExecutionException malformed(Object resolver, String reason) {
        return new ModelQueryExecutionException(MqCode.MQ2208, resolver + ": the keyset cursor is not one this "
                + "library issued: " + reason + "; start again with KeysetSpec.first");
    }

    private static <M> ColumnField<M, ?, ?> column(Key<M> key) {
        return (ColumnField<M, ?, ?>) key.order().column();
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        return type;
    }

    /** The canonical UTF-8 form of R-PAG-19, hashed. */
    private byte[] canonicalFingerprint() {
        StringBuilder canonical = new StringBuilder();
        canonical.append(rootEntity.getName()).append('\n');
        for (Key<M> key : keys) {
            ColumnField<M, ?, ?> column = column(key);
            canonical.append(column.path()).append('\u0000')
                    .append(column.attributeType().getName()).append('\u0000')
                    .append(key.order().ascending() ? "ASC" : "DESC").append('\u0000')
                    .append(key.refuseNull() || key.primaryKey() ? "REFUSING" : key.nulls().name()).append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(hash, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /**
     * The row's keyset values, read from the {@code Row} as attribute values, before any converter (R-COL-11).
     *
     * @throws ModelQueryExecutionException {@code MQ2202} when a column without explicit null precedence is NULL,
     *     unless it pages its NULLs by the database's known default (R-PAG-05)
     */
    Object[] cursor(Row row) {
        Object[] values = new Object[keys.size()];
        for (int i = 0; i < values.length; i++) {
            Key<M> key = keys.get(i);
            OrderField<M, ?> order = key.order();
            values[i] = row.raw(order.column());
            if (values[i] == null && key.refuseNull()) {
                throw new ModelQueryExecutionException(MqCode.MQ2202, label + ": keyset column "
                        + order.column().name() + " is null in an exported row"
                        + (key.nulls() == NullPrecedence.DEFAULT ? " and the database's null ordering is unknown" : "")
                        + "; order it with nullsFirst() or nullsLast() so the next page can be found after it");
            }
        }
        return values;
    }

    /**
     * The rows-after-a-cursor predicate, and the values its comparisons bind through a parameter. A comparison value
     * the {@code CriteriaBuilder} value overloads do not take, such as the {@code byte[]} key of R-PAG-17, is compared
     * as an expression bound through a parameter the executor sets, so it never reaches SQL as an inlined literal
     * (R-FLT-08, AC-PAG-28). Empty for a cursor of value types alone.
     */
    record Beyond(Predicate predicate, Map<ParameterExpression<?>, Object> parameters) {

        /** Sets this predicate's own parameter values, if any, on {@code query}. */
        void bindTo(Query query) {
            parameters.forEach((parameter, value) -> bind(query, parameter, value));
        }
    }

    /**
     * The rows after {@code cursor}: an OR over the keys, each branch holding the earlier keys equal to the cursor's
     * and its own key beyond the cursor's value (R-PAG-06, vendor/41 §5). NULL branches follow R-PRF-09.
     */
    Beyond after(Object[] cursor, JoinContext joins, CriteriaBuilder cb) {
        List<Predicate> branches = new ArrayList<>();
        List<Expression<?>> columns = new ArrayList<>();
        Map<ParameterExpression<?>, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            Key<M> key = keys.get(i);
            Expression<?> column = key.order().column().expression(joins);
            Predicate beyond = beyond(key, column, cursor[i], cb, parameters);
            if (beyond != null) {
                // Each branch builds its own equal predicates: one shared across branches renders a bind per branch
                // but JPA reports it once, so the bind limit check would undercount (D-82).
                List<Predicate> branch = new ArrayList<>();
                for (int j = 0; j < i; j++) {
                    branch.add(cursor[j] == null ? cb.isNull(columns.get(j)) : cb.equal(columns.get(j), cursor[j]));
                }
                branch.add(beyond);
                branches.add(branch.size() == 1 ? beyond : cb.and(branch.toArray(Predicate[]::new)));
            }
            columns.add(column);
        }
        return new Beyond(cb.or(branches.toArray(Predicate[]::new)), parameters);
    }

    /**
     * The most binds {@link #after} can add, for any cursor: {@code k(k+1)/2} over {@code k} keys. Key {@code i}
     * (from 0) binds its own value beyond the cursor's and the {@code i} equal values before it, and only a non-NULL
     * cursor value binds, so a cursor of all non-NULL values is the worst case; a NULL under FIRST or LAST adds
     * fewer. A statement is refused up front when its own binds plus this pass the limit (D-82).
     */
    int maxCursorBinds() {
        return keys.size() * (keys.size() + 1) / 2;
    }

    /**
     * How many binds {@link #after} adds for {@code cursor}: a value beyond it and the equal values before it. It
     * mirrors the branches {@code after} and {@code beyond} build, so a change there changes this too (D-82).
     */
    int cursorBinds(Object[] cursor) {
        int binds = 0;
        int bound = 0;
        for (int i = 0; i < keys.size(); i++) {
            boolean value = cursor[i] != null;
            if (value || keys.get(i).nulls() == NullPrecedence.FIRST) {
                // A branch binds the earlier equal values, and its own value unless it is IS NOT NULL.
                binds += bound + (value ? 1 : 0);
            }
            if (value) {
                bound++;
            }
        }
        return binds;
    }

    /** The values of {@code key}'s column that sort after {@code value}, or {@code null} when none does. */
    @SuppressWarnings("rawtypes")
    private static Predicate beyond(Key<?> key, Expression<?> column, Object value, CriteriaBuilder cb,
            Map<ParameterExpression<?>, Object> parameters) {
        NullPrecedence nulls = key.nulls();
        if (value == null) {
            // Only a known precedence reaches here (cursor() refuses the rest): under FIRST every value follows a
            // NULL, under LAST nothing does.
            return nulls == NullPrecedence.FIRST ? cb.isNotNull(column) : null;
        }
        Predicate past = comparison(key.order().ascending(), column, value, cb, parameters);
        // Under LAST every NULL follows every value. A column that refuses its NULLs keeps the branch whatever the
        // ordering, known or not: it makes sure a NULL is read, and so refused, wherever the database really sorts
        // it, instead of being skipped silently when the reported ordering is wrong (INV-5, D-30, D-35).
        boolean nullsFollow = nulls == NullPrecedence.LAST || key.refuseNull();
        return nullsFollow ? cb.or(past, cb.isNull(column)) : past;
    }

    /**
     * Binds {@code parameter} to {@code value}. JPA's {@code setParameter(Parameter<T>, T)} cannot be called with a
     * wildcard parameter and an {@code Object} value, so the parameter is raw: the value's runtime type is the
     * parameter's declared type, which {@link #comparison} set from it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bind(Query query, ParameterExpression<?> parameter, Object value) {
        query.setParameter((Parameter) parameter, value);
    }

    /**
     * {@code column} against {@code value} with {@code <} or {@code >}. The value binds as a parameter, never as an
     * inlined literal, so R-FLT-08 stays absolute (AC-PAG-28). A {@code byte[]} key (R-PAG-17) is not
     * {@code Comparable}, so it cannot reach the value overloads: it is compared as an expression and registered for
     * the executor to bind. The column types every other bind, so a UUID column stored as text is not compared as
     * {@code uuid}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate comparison(boolean ascending, Expression<?> column, Object value, CriteriaBuilder cb,
            Map<ParameterExpression<?>, Object> parameters) {
        if (value instanceof Comparable) {
            return ascending ? cb.greaterThan((Expression) column, (Comparable) value)
                    : cb.lessThan((Expression) column, (Comparable) value);
        }
        ParameterExpression<?> bound = cb.parameter(value.getClass());
        parameters.put(bound, value);
        return ascending ? cb.greaterThan((Expression) column, (Expression) bound)
                : cb.lessThan((Expression) column, (Expression) bound);
    }
}
