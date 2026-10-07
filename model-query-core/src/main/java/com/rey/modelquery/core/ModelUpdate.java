package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * An immutable bulk-update definition: the columns to write, the rows to write them to, and how the write runs. Like
 * a {@link ModelQuery} it holds no Criteria object, so one instance can be shared by any number of threads (INV-9).
 *
 * <p>An update loads no entity and runs no lifecycle callback, cascade, Bean Validation or Envers audit: those stay
 * with JPA entity writes (R-WRT-01).
 *
 * @param <E> the root entity
 * @param <M> the update model
 * @implSpec R-WRT-05, R-WRT-07, R-WRT-08, R-WRT-10, R-WRT-11, R-WRT-13, R-WRT-14, R-WRT-15, R-WRT-16, R-WRT-41,
 *     R-WRT-46, D-60, D-61, D-62, D-63
 */
@Incubating
public final class ModelUpdate<E, M> {

    /** The {@code @Version} types a bulk update increments (R-WRT-16); {@link #nextVersion} handles each. */
    static final Set<Class<?>> VERSION_TYPES = Set.of(Integer.class, Long.class, Short.class,
            BigInteger.class, Instant.class, LocalDateTime.class, OffsetDateTime.class, Timestamp.class, Date.class);

    private final Draft<E, ?, M> definition;

    private ModelUpdate(Draft<E, ?, M> definition) {
        this.definition = definition;
    }

    /**
     * Starts an update of {@code root}'s rows. Each stage returns a new immutable builder; the order is the key,
     * the assignments, one row choice, then options (D-60).
     *
     * @throws ModelQueryDefinitionException {@code MQ1203} when {@code root} is a join, not a root
     */
    public static <E> Start<E> builder(TableField<E, E> root) {
        Objects.requireNonNull(root, "root");
        if (root.rootEntity() == null) {
            throw new ModelQueryDefinitionException(MqCode.MQ1203,
                    "builder(...) takes a TableField.root(...), not the " + root.describe());
        }
        return new Start<>(root);
    }

    /** The entity the update writes. */
    public Class<E> rootEntity() {
        return definition.root().rootEntity();
    }

    /**
     * The entity and how the rows are chosen, for the executor's log: never a key or a filter's values (D-95,
     * R-INS-05). The format is not API.
     */
    @Override
    public String toString() {
        return rootEntity().getSimpleName() + " (" + definition.rows() + ")";
    }

    /** The columns written, in the order assigned, as a list that throws on mutation. */
    public List<Assignment<M, ?>> assignments() {
        return definition.assignments();
    }

    /** The version {@code expectVersion} expects the row to have, or empty without it (R-WRT-16). */
    public Optional<Object> expectedVersion() {
        return Optional.ofNullable(definition.expectedVersion());
    }

    /**
     * Whether the update runs no statement: it assigns nothing and expects no version (R-WRT-07), or its
     * {@code whereKeys} received no key (R-WRT-12).
     */
    @EngineFacing
    public boolean writesNothing() {
        List<Object> keys = definition.rows().keys();
        return keys != null && keys.isEmpty()
                || definition.assignments().isEmpty() && definition.expectedVersion() == null;
    }

    /**
     * Checks the definition against {@code metamodel}, which {@code build()} cannot see (INV-7). An executor calls it
     * on the definition's first execution per {@code EntityManagerFactory}, before any statement (D-61).
     *
     * @throws ModelQueryDefinitionException {@code MQ1608} when the primary key does not name exactly the root
     *     entity's id attributes, {@code MQ1605} when a column writes an id or the {@code @Version} attribute,
     *     {@code MQ1606} for {@code expectVersion} on a root with no {@code @Version} attribute or with a value of
     *     another type, and for a {@code @Version} of a type a bulk update cannot increment, unless
     *     {@code keepVersion()}, {@code MQ1001} for a to-one column whose type is not the target's id type
     */
    @EngineFacing
    public void checkMetamodel(Metamodel metamodel) {
        EntityType<E> entity = metamodel.entity(rootEntity());
        WriteRendering.checkKey(entity, definition.root(), definition.primaryKey(), modelName());
        WriteRendering.checkAssignable(entity, definition.assignments());
        for (Assignment<M, ?> assignment : definition.assignments()) {
            if (!(assignment instanceof Assignment.Expression)) {
                EntityType<?> target = toOneTarget(entity, assignment.column().name());
                if (target != null) {
                    checkToOneId(entity, assignment.column(), target);
                }
            }
        }
        if (!definition.keepVersion()) {
            WriteRendering.version(entity).ifPresent(version -> {
                Class<?> type = ColumnField.boxed(version.getJavaType());
                if (!VERSION_TYPES.contains(type)) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1606, modelName() + ": "
                            + entity.getJavaType().getSimpleName() + "." + version.getName() + " is a "
                            + type.getSimpleName() + " @Version, which a bulk update cannot increment; "
                            + "keepVersion() leaves it alone");
                }
            });
        }
        expectedVersionAttribute(entity, WriteRendering.version(entity));
    }

    /**
     * Renders the update against {@code cb}: one {@code CriteriaUpdate} whose {@code SET} holds the
     * {@code setExpression} assignments first, then the others in the order assigned, then the version increment
     * unless {@code keepVersion()} (R-WRT-13, R-WRT-16). A value passes through its column's converter and is bound;
     * a to-one attribute written by id binds what {@code references} returns for the target entity and the id,
     * which an executor makes {@code EntityManager#getReference} so no row is loaded (R-WRT-14). The rows are chosen
     * as {@link ModelDelete#buildWrite} chooses them (R-WRT-10), with {@code AND version = ?} for
     * {@code expectVersion}.
     *
     * @param references the reference to bind for a target entity type and an id
     * @throws ModelQueryDefinitionException the codes of {@link #checkMetamodel}, which an executor has run already
     */
    @EngineFacing
    public CriteriaUpdate<E> buildWrite(CriteriaBuilder cb, RenderOptions options,
            BiFunction<Class<?>, Object, ?> references) {
        return render(cb, options, references, WriteRendering.keysToRender(distinctKeys().orElse(null), null),
                false).update();
    }

    /**
     * Renders the update as {@link #buildWrite(CriteriaBuilder, RenderOptions, BiFunction)} does, choosing only the
     * keys of {@code chunk}, a run of {@link #distinctKeys()}: an executor splits the keys across statements to the
     * vendor's limits (R-WRT-08, D-63).
     *
     * @param chunk attribute-value keys, as {@link #distinctKeys()} returns them
     * @throws IllegalArgumentException for an empty {@code chunk}, or on an update without {@code whereKey} or
     *     {@code whereKeys}
     */
    @EngineFacing
    public CriteriaUpdate<E> buildWrite(CriteriaBuilder cb, RenderOptions options,
            BiFunction<Class<?>, Object, ?> references, List<?> chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return render(cb, options, references, WriteRendering.keysToRender(distinctKeys().orElse(null), chunk),
                false).update();
    }

    /**
     * Renders one round of a key-first or chunked update: {@code key IN (keys)}, the keys a
     * {@link #buildKeySelect key select} chose, and the {@code where} tree as
     * {@link #buildWrite(CriteriaBuilder, RenderOptions, BiFunction)} renders it, or with {@code rootTermsOnly} only
     * its root terms, the top-level {@code AND} terms that need no join and no sub-query, with
     * {@code AND version = ?} for {@code expectVersion}. Re-applying them means a row that stopped matching on its
     * own columns since the key select is not written; a change to a joined row in between is not re-checked
     * (R-WRT-11, R-WRT-17, D-63).
     *
     * @param keys attribute-value keys, as a key select's rows hold them
     * @throws IllegalArgumentException for empty {@code keys}
     */
    @EngineFacing
    public CriteriaUpdate<E> buildWrite(CriteriaBuilder cb, RenderOptions options,
            BiFunction<Class<?>, Object, ?> references, List<?> keys, boolean rootTermsOnly) {
        return render(cb, options, references, WriteRendering.selectedKeys(Objects.requireNonNull(keys, "keys")),
                rootTermsOnly).update();
    }

    /**
     * The bind values the repeated renderings of memoised expressions add beyond what JPA reports, for the whole
     * statement {@link #buildWrite(CriteriaBuilder, RenderOptions, BiFunction)} renders (R-COL-19, D-80). An executor
     * adds them to the parameters JPA reports before checking the bind limit: a chunked or key-first write renders the
     * same filter in every round, so the whole statement's repeats bound every chunk's and key-first form's binds too
     * (D-63).
     *
     * @param references the reference to bind for a target entity type and an id, as the executor's write build takes:
     *     a to-one assignment by id needs one, since the provider rejects a null value as it renders the statement
     */
    @EngineFacing
    public int repeatedExpressionBinds(CriteriaBuilder cb, RenderOptions options,
            BiFunction<Class<?>, Object, ?> references) {
        Objects.requireNonNull(references, "references");
        return render(cb, options, references, WriteRendering.keysToRender(distinctKeys().orElse(null), null), false)
                .joins().repeatedExpressionBinds();
    }

    /**
     * Renders the key select of a key-first or chunked update: the {@link #primaryKey()} columns of the rows the
     * update chooses, {@code whereKey} or {@code whereKeys} and the {@code where} tree rendered as a read renders
     * them, joins included, with no order. An executor adds the keyset order, the cursor, the row limit and any lock;
     * the returned query maps no model (R-WRT-11, R-WRT-17, D-63).
     */
    @EngineFacing
    public BuiltQuery<M> buildKeySelect(CriteriaBuilder cb, RenderOptions options) {
        return keySelect(cb, options, WriteRendering.keysToRender(distinctKeys().orElse(null), null));
    }

    /**
     * Renders the key select as {@link #buildKeySelect(CriteriaBuilder, RenderOptions)} does, choosing among the
     * keys of {@code chunk} only, a run of {@link #distinctKeys()} (R-WRT-08, D-63).
     *
     * @throws IllegalArgumentException for an empty {@code chunk}, or on an update without {@code whereKey} or
     *     {@code whereKeys}
     */
    @EngineFacing
    public BuiltQuery<M> buildKeySelect(CriteriaBuilder cb, RenderOptions options, List<?> chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return keySelect(cb, options, WriteRendering.keysToRender(distinctKeys().orElse(null), chunk));
    }

    /**
     * The entity types the sub-queries of the statement {@link #buildWrite(CriteriaBuilder, RenderOptions, BiFunction)}
     * renders read: the root's own when the {@code where} tree needs a join (R-WRT-10), and each type an
     * {@code exists(...)} path joins; empty when it renders none. An executor runs the update key-first where the
     * database refuses a sub-query reading the root's table and one of these shares the root's hierarchy or table
     * (R-WRT-11, R-VND-11, D-109).
     *
     * @return an unmodifiable set
     */
    @EngineFacing
    public Set<Class<?>> entitiesReadInSubquery(CriteriaBuilder cb, RenderOptions options) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        return WriteRendering.entitiesReadInSubquery(definition.rows().where(), rootEntity(), cb, options);
    }

    /** The primary key the update chooses rows by, whose columns a key select reads (D-63). */
    public PrimaryKey<M, ?> primaryKey() {
        return definition.primaryKey();
    }

    /** The update's {@code chunked(...)} options, or empty without them (R-WRT-11, R-WRT-17). */
    public Optional<ChunkOptions> chunkOptions() {
        return Optional.ofNullable(definition.chunkOptions());
    }

    /**
     * The key of {@code chunked(options, startAfter)} converted to its attribute value (a list of component values
     * for a composite key), or empty without one: a chunked write's first key select starts after it (R-WRT-20).
     * Converted when the definition was built.
     */
    @EngineFacing
    public Optional<Object> startAfter() {
        return Optional.ofNullable(definition.startAfter());
    }

    /**
     * The model key, the type {@code whereKey} takes, of {@code attributeKey}, a key as a key select returns it:
     * {@link ChunkedWriteException} reports its keys so (R-WRT-20, D-63).
     */
    @EngineFacing
    public Object modelKey(Object attributeKey) {
        return WriteRendering.modelKey(definition.primaryKey(), Objects.requireNonNull(attributeKey, "attributeKey"));
    }

    private BuiltQuery<M> keySelect(CriteriaBuilder cb, RenderOptions options, List<Object> keys) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        return WriteRendering.keySelect(keys, definition.rows().where(), definition.primaryKey(), rootEntity(), cb,
                options, modelName());
    }

    /**
     * The distinct keys of {@code whereKey} or {@code whereKeys}, each converted to its attribute value (a list of
     * component values for a composite key), in first-seen order; empty when the rows were chosen without keys. Each
     * distinct key is written once. Converted, deduplicated and copied when the definition was built (R-WRT-08, D-63,
     * D-66).
     */
    @EngineFacing
    public Optional<List<Object>> distinctKeys() {
        return Optional.ofNullable(definition.rows().keys());
    }

    /** The write's own {@code persistenceContext(...)}, which wins over the executor's configured mode (D-62). */
    public Optional<PersistenceContextMode> persistenceContext() {
        return Optional.ofNullable(definition.persistenceContext());
    }

    /**
     * Whether the update was built {@code throughEntities()}: an executor then loads the matched entities chunk by
     * chunk and writes them through the {@code EntityManager} instead of rendering a bulk statement (R-WRT-41,
     * R-WRT-42).
     */
    @EngineFacing
    @Incubating
    public boolean entityMode() {
        return definition.entityMode();
    }

    /**
     * Renders the load of one chunk of an update {@code throughEntities()}: the root entities whose key is among
     * {@code keys}, the keys a {@link #buildKeySelect key select} chose, that the {@code where} tree still chooses,
     * rendered as {@link #buildWrite(CriteriaBuilder, RenderOptions, BiFunction)} renders it, so each entity comes
     * once; a row that stopped matching since the key select is not loaded (R-WRT-17, R-WRT-42).
     *
     * @param keys attribute-value keys, as a key select's rows hold them
     * @throws IllegalArgumentException for empty {@code keys}
     */
    @EngineFacing
    @Incubating
    public CriteriaQuery<E> buildEntityLoad(CriteriaBuilder cb, RenderOptions options, List<?> keys) {
        return WriteRendering.entityLoad(cb, options, keys, definition.rows().where(), definition.primaryKey(),
                rootEntity());
    }

    /**
     * The root attribute each value or NULL assignment writes, in the order assigned, which an update
     * {@code throughEntities()} sets on each loaded entity; a list that throws on mutation (R-WRT-42). An entity-mode
     * update has no {@code setExpression} assignment ({@code MQ1610}).
     */
    @EngineFacing
    @Incubating
    public List<String> assignedAttributes() {
        return valueAssignments().stream().map(assignment -> assignment.column().name()).toList();
    }

    /**
     * The values of {@link #assignedAttributes()}, at the same index: each passed through its column's converter, a
     * NULL assignment's {@code null}, and a to-one's the target's id, as a bulk update binds them (R-WRT-14,
     * R-WRT-42). A list that throws on mutation; each call converts again.
     */
    @EngineFacing
    @Incubating
    public List<Object> assignedValues() {
        var values = new ArrayList<Object>();
        for (Assignment<M, ?> assignment : valueAssignments()) {
            values.add(assignment instanceof Assignment.Value<M, ?> value ? attributeValue(value) : null);
        }
        return Collections.unmodifiableList(values);
    }

    private List<Assignment<M, ?>> valueAssignments() {
        return definition.assignments().stream().filter(assignment -> !(assignment instanceof Assignment.Expression))
                .toList();
    }

    private static <C> Object attributeValue(Assignment.Value<?, C> value) {
        return value.column().toAttribute(value.value());
    }

    /** A rendered update: the statement and the context that rendered it, whose repeated expression binds an executor
     * counts (R-COL-19, D-80). */
    private record Rendered<E>(CriteriaUpdate<E> update, JoinContext joins) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rendered<E> render(CriteriaBuilder cb, RenderOptions options,
            BiFunction<Class<?>, Object, ?> references, List<Object> keys, boolean rootTermsOnly) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(references, "references");
        Class<E> type = rootEntity();
        CriteriaUpdate<E> update = cb.createCriteriaUpdate(type);
        Root<E> from = update.from(type);
        JoinContext ctx = JoinContext.of(from, cb, update, options);
        EntityType<E> entity = from.getModel();
        // Expressions first, so one reading a column a plain assignment writes sees the old value on MySQL too.
        for (Assignment<M, ?> assignment : definition.assignments()) {
            if (assignment instanceof Assignment.Expression expression) {
                Path path = expression.column().path(ctx);
                setTo(update, path, (Expression) expression.expression().apply(path, cb));
            }
        }
        for (Assignment<M, ?> assignment : definition.assignments()) {
            if (!(assignment instanceof Assignment.Expression)) {
                assign(update, from, ctx, entity, assignment, cb, references);
            }
        }
        Optional<SingularAttribute<?, ?>> versionAttribute = WriteRendering.version(entity);
        if (!definition.keepVersion()) {
            versionAttribute.ifPresent(version -> {
                Path path = from.get(version.getName());
                Object next = nextVersion(path, cb);
                if (next instanceof Expression expression) {
                    setTo(update, path, expression);
                } else {
                    setValue(update, path, next);
                }
            });
        }
        var where = WriteRendering.rows(keys, definition.rows().where(), definition.primaryKey(),
                update, from, ctx, cb, options, rootTermsOnly);
        SingularAttribute<?, ?> version = expectedVersionAttribute(entity, versionAttribute);
        if (version != null) {
            where.add(cb.equal(from.get(version.getName()), definition.expectedVersion()));
        }
        if (!where.isEmpty()) {
            update.where(where.toArray(Predicate[]::new));
        }
        return new Rendered<>(update, ctx);
    }

    /** @throws ModelQueryDefinitionException {@code MQ1001} when the column's type is not the target's id type */
    private static void checkToOneId(EntityType<?> entity, ColumnField<?, ?, ?> column, EntityType<?> target) {
        Class<?> idType = ColumnField.boxed(target.getIdType().getJavaType());
        if (idType != column.attributeType()) {
            throw new ModelQueryDefinitionException(MqCode.MQ1001, column + ": writes the to-one "
                    + entity.getJavaType().getSimpleName() + "." + column.name() + " by id, whose type is "
                    + idType.getSimpleName() + ", not " + column.attributeType().getSimpleName());
        }
    }

    /** One value or NULL assignment; a to-one attribute written by id binds a reference to the target (R-WRT-14). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E> void assign(CriteriaUpdate<E> update, Root<E> from, JoinContext ctx, EntityType<E> entity,
            Assignment<?, ?> assignment, CriteriaBuilder cb, BiFunction<Class<?>, Object, ?> references) {
        ColumnField column = assignment.column();
        EntityType<?> target = toOneTarget(entity, column.name());
        Path path;
        if (target == null) {
            path = column.path(ctx);
        } else {
            checkToOneId(entity, column, target); // an executor ran it in checkMetamodel already
            path = from.get(column.name());
        }
        if (assignment instanceof Assignment.Value value) {
            Object attribute = column.toAttribute(value.value());
            setValue(update, path, target == null ? attribute : references.apply(target.getJavaType(), attribute));
        } else {
            if (target == null) {
                setValue(update, path, null); // the provider's own null, so a JPA converter sees it
            } else {
                setTo(update, path, cb.nullLiteral(path.getJavaType()));
            }
        }
    }

    private static <Y> void setTo(CriteriaUpdate<?> update, Path<Y> path, Expression<? extends Y> value) {
        update.set(path, value);
    }

    /** Binds {@code value}, which the provider checks against the path's type. */
    @SuppressWarnings("unchecked")
    private static <Y> void setValue(CriteriaUpdate<?> update, Path<Y> path, Object value) {
        update.set(path, (Y) value);
    }

    /** The entity a root attribute named {@code name} leads to when it is a to-one association, else {@code null}. */
    private static EntityType<?> toOneTarget(EntityType<?> entity, String name) {
        if (name.indexOf('.') >= 0) {
            return null;
        }
        Attribute<?, ?> attribute;
        try {
            attribute = entity.getAttribute(name);
        } catch (IllegalArgumentException e) {
            return null; // the column's own path reports MQ1002
        }
        return attribute instanceof SingularAttribute<?, ?> singular && singular.isAssociation()
                && singular.getType() instanceof EntityType<?> target ? target : null;
    }

    /** {@code version + 1}, or the current time for a timestamp version (R-WRT-16). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object nextVersion(Path path, CriteriaBuilder cb) {
        Class<?> type = ColumnField.boxed(path.getJavaType());
        if (type == Integer.class) {
            return cb.sum(path, 1);
        }
        if (type == Long.class) {
            return cb.sum(path, 1L);
        }
        if (type == Short.class) {
            return cb.sum(path, (short) 1);
        }
        if (type == BigInteger.class) {
            return cb.sum(path, BigInteger.ONE);
        }
        if (type == Instant.class) {
            return Instant.now();
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.now();
        }
        if (type == OffsetDateTime.class) {
            return OffsetDateTime.now();
        }
        if (type == Timestamp.class) {
            return new Timestamp(System.currentTimeMillis());
        }
        if (type == Date.class) {
            return new Date();
        }
        // Unreachable once checkMetamodel ran (MQ1606), which an executor does before any statement.
        throw new IllegalStateException("@Version of type " + type.getName() + " is not supported by bulk updates");
    }

    /**
     * The {@code @Version} attribute {@code expectVersion} compares, or {@code null} without {@code expectVersion}.
     *
     * @throws ModelQueryDefinitionException {@code MQ1606} for a root with no {@code @Version} attribute, or a
     *     value of another type
     */
    private SingularAttribute<?, ?> expectedVersionAttribute(EntityType<E> entity,
            Optional<SingularAttribute<?, ?>> versionAttribute) {
        Object expected = definition.expectedVersion();
        if (expected == null) {
            return null;
        }
        SingularAttribute<?, ?> version = versionAttribute.orElseThrow(() ->
                new ModelQueryDefinitionException(MqCode.MQ1606, modelName() + ": expectVersion(...) on "
                        + entity.getJavaType().getSimpleName() + ", which has no @Version attribute"));
        Class<?> type = ColumnField.boxed(version.getJavaType());
        if (!type.isInstance(expected)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1606, modelName() + ": expectVersion(...) received a "
                    + expected.getClass().getSimpleName() + ", but " + entity.getJavaType().getSimpleName() + "."
                    + version.getName() + " is " + type.getSimpleName());
        }
        return version;
    }

    private String modelName() {
        return definition.primaryKey().columns().get(0).model().getSimpleName();
    }

    /** Everything a stage holds; each stage call returns a copy with one part changed. */
    private record Draft<E, K, M>(
            TableField<E, E> root,
            PrimaryKey<M, K> primaryKey,
            List<Assignment<M, ?>> assignments,
            WriteRows rows,
            Object expectedVersion,
            boolean keepVersion,
            ChunkOptions chunkOptions,
            PersistenceContextMode persistenceContext,
            Object startAfter,
            boolean entityMode) {

        Draft<E, K, M> assign(List<Assignment<M, ?>> more) {
            var all = new ArrayList<>(assignments);
            all.addAll(more);
            return new Draft<>(root, primaryKey, List.copyOf(all), rows, expectedVersion, keepVersion, chunkOptions,
                    persistenceContext, startAfter, entityMode);
        }

        Draft<E, K, M> rows(WriteRows rows) {
            return new Draft<>(root, primaryKey, assignments, rows, expectedVersion, keepVersion, chunkOptions,
                    persistenceContext, startAfter, entityMode);
        }

        Draft<E, K, M> expect(Object version) {
            return new Draft<>(root, primaryKey, assignments, rows, version, keepVersion, chunkOptions,
                    persistenceContext, startAfter, entityMode);
        }

        Draft<E, K, M> keep() {
            return new Draft<>(root, primaryKey, assignments, rows, expectedVersion, true, chunkOptions,
                    persistenceContext, startAfter, entityMode);
        }

        Draft<E, K, M> chunk(ChunkOptions options, Object after) {
            return new Draft<>(root, primaryKey, assignments, rows, expectedVersion, keepVersion, options,
                    persistenceContext, after, entityMode);
        }

        Draft<E, K, M> throughEntities() {
            return new Draft<>(root, primaryKey, assignments, rows, expectedVersion, keepVersion, chunkOptions,
                    persistenceContext, startAfter, true);
        }

        Draft<E, K, M> mode(PersistenceContextMode mode) {
            return new Draft<>(root, primaryKey, assignments, rows, expectedVersion, keepVersion, chunkOptions, mode,
                    startAfter, entityMode);
        }

        /**
         * This draft with its keys converted to attribute values, deduplicated and copied, so the definition keeps
         * what {@code build()} saw whatever the caller does with its lists afterwards (INV-9, D-66).
         *
         * @throws IllegalArgumentException for a composite key with the wrong number of components
         */
        Draft<E, K, M> canonical() {
            WriteRows chosen = rows.keys() == null ? rows
                    : rows.withKeys(WriteRendering.distinctKeys(primaryKey, rows.keys()));
            Object after = startAfter == null ? null
                    : WriteRendering.distinctKeys(primaryKey, List.of(startAfter)).get(0);
            return new Draft<>(root, primaryKey, assignments, chosen, expectedVersion, keepVersion, chunkOptions,
                    persistenceContext, after, entityMode);
        }

        /**
         * Checks and builds.
         *
         * @throws ModelQueryDefinitionException {@code MQ1601}, {@code MQ1602}, {@code MQ1604}, {@code MQ1605},
         *     {@code MQ1606}, {@code MQ1609} or {@code MQ1610}, as {@link Options#build()} states
         */
        ModelUpdate<E, M> build() {
            String model = primaryKey.columns().get(0).model().getSimpleName();
            rows.check(model);
            var keyAttributes = new HashSet<String>();
            for (ColumnField<M, ?, ?> key : primaryKey.columns()) {
                keyAttributes.add(key.name());
            }
            Set<String> assigned = new HashSet<>();
            for (Assignment<M, ?> assignment : assignments) {
                ColumnField<M, ?, ?> column = assignment.column();
                // The type check lets a join back to the root's own entity type through (R-WRT-06).
                if (!column.table().key().equals(root.key())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1604, column + ": sits on the "
                            + column.table().describe() + ", not on the root " + root.rootEntity().getSimpleName()
                            + "; an update writes only the root's own columns");
                }
                if (keyAttributes.contains(column.name())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1605, column
                            + ": a primary-key column is never assigned; whereKey(...) chooses the row");
                }
                if (assignment instanceof Assignment.Expression<M, ?> && column.converterClass() != null) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1609, column + ": setExpression(...) on a "
                            + "column converted by " + column.converterClass().getSimpleName() + "; the expression's "
                            + "path has the entity attribute's type, not the model's");
                }
                if (!assigned.add(column.name())) {
                    throw new ModelQueryDefinitionException(MqCode.MQ1602, column + ": assigned twice; a column "
                            + "the server sets belongs in a hand-written ColumnField, not in the update model");
                }
            }
            if (entityMode) {
                checkEntityMode(model);
            }
            if (keepVersion && expectedVersion != null && assignments.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1606, model + ": expectVersion(...) with "
                        + "keepVersion() and nothing to write; check Changes#isEmpty() before updating");
            }
            return new ModelUpdate<>(canonical());
        }

        /** Refuses what an entity-mode update cannot honour, whatever the order it was called in (R-WRT-46). */
        private void checkEntityMode(String model) {
            var refused = new ArrayList<String>();
            for (Assignment<M, ?> assignment : assignments) {
                if (assignment instanceof Assignment.Expression<M, ?>) {
                    refused.add("setExpression(" + assignment.column().name() + ", ...)");
                }
            }
            if (keepVersion) {
                refused.add("keepVersion()");
            }
            if (expectedVersion != null) {
                refused.add("expectVersion(...)");
            }
            if (!refused.isEmpty()) {
                throw new ModelQueryDefinitionException(MqCode.MQ1610, model + ": throughEntities() with "
                        + String.join(", ", refused) + "; an entity-mode update sets each attribute in Java and leaves "
                        + "the @Version to the provider's flush");
            }
        }
    }

    /**
     * The first stage: the update's root, before its key.
     *
     * @param <E> the root entity
     */
    @Incubating
    public static final class Start<E> {

        private final TableField<E, E> root;

        private Start(TableField<E, E> root) {
            this.root = root;
        }

        /** The update model's key, which {@code whereKey} and {@code whereKeys} take values of (R-WRT-08). */
        public <K, M> Builder<E, K, M> primaryKey(PrimaryKey<M, K> primaryKey) {
            return new Builder<>(new Draft<>(root, Objects.requireNonNull(primaryKey, "primaryKey"), List.of(), null,
                    null, false, null, null, null, false));
        }
    }

    /**
     * The assignment stage. {@code set}, {@code setNull} and {@code setExpression} take only columns on the root
     * entity type, so most joined columns do not compile; then exactly one row choice follows (R-WRT-06, R-WRT-12).
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static final class Builder<E, K, M> {

        private final Draft<E, K, M> draft;

        private Builder(Draft<E, K, M> draft) {
            this.draft = draft;
        }

        /**
         * Writes every column {@code changes} marked, NULLs included. The assignments are copied now, so changing
         * {@code changes} afterwards changes neither this builder nor an update already built (R-WRT-05, R-WRT-07).
         */
        public Builder<E, K, M> set(Changes<M> changes) {
            var copy = new ArrayList<Assignment<M, ?>>();
            for (Assignment<M, ?> assignment : Objects.requireNonNull(changes, "changes").assignments()) {
                copy.add(Objects.requireNonNull(assignment, "changes.assignments() element"));
            }
            return new Builder<>(draft.assign(copy));
        }

        /**
         * Writes {@code value} to {@code column}.
         *
         * @throws ModelQueryDefinitionException {@code MQ1603} for a {@code null} value, which {@link #setNull}
         *     writes
         */
        public <C> Builder<E, K, M> set(ColumnField<M, E, C> column, C value) {
            return new Builder<>(draft.assign(List.of(Assignment.of(Objects.requireNonNull(column, "column"),
                    value))));
        }

        /** Writes NULL to {@code column}. */
        public <C> Builder<E, K, M> setNull(ColumnField<M, E, C> column) {
            return new Builder<>(draft.assign(List.of(Assignment.ofNull(column))));
        }

        /**
         * Writes what {@code expression} returns for {@code column}'s path, an escape hatch like {@code Agg.of}.
         * Expression assignments render before the others, so one reading a column a plain assignment writes sees
         * the old value on every vendor; one reading a column another {@code setExpression} writes is
         * vendor-dependent: MySQL reads the new value, PostgreSQL and H2 the old one. A column with a converter
         * throws {@code MQ1609} at {@link Options#build()}, since the path has the entity attribute's type
         * (R-WRT-13, R-WRT-14).
         */
        public <C> Builder<E, K, M> setExpression(ColumnField<M, E, C> column,
                BiFunction<Path<C>, CriteriaBuilder, Expression<? extends C>> expression) {
            return new Builder<>(draft.assign(List.of(new Assignment.Expression<>(column, expression))));
        }

        /** Writes the row whose key is {@code key}; a {@code where} may narrow it, then {@code expectVersion}. */
        public Keyed<E, K, M> whereKey(K key) {
            return new Keyed<>(draft.rows(WriteRows.key(key)));
        }

        /**
         * Writes the rows whose keys are in {@code keys}; a {@code where} may narrow them. An empty collection
         * affects nothing and runs no SQL (R-WRT-08, R-WRT-12).
         */
        public Narrowable<E, K, M> whereKeys(Collection<? extends K> keys) {
            return new Narrowable<>(draft.rows(WriteRows.keys(keys)));
        }

        /**
         * Writes the rows {@code filters} matches: it receives an empty {@link Filters}, and every filter it adds is
         * ANDed. It runs once, here, as {@link ModelQuery.Builder#where} does. If every filter is skipped,
         * {@link Options#build()} throws {@code MQ1601}; {@link #all()} is the only way to write every row (R-WRT-12).
         */
        public Resumable<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Resumable<>(draft.rows(WriteRows.where(FilterGroup.collect(
                    draft.root().rootEntity(), Objects.requireNonNull(filters, "filters")))));
        }

        /** Writes every row; no {@code where} follows (R-WRT-12). */
        public Resumable<E, K, M> all() {
            return new Resumable<>(draft.rows(WriteRows.everyRow()));
        }
    }

    /**
     * The options stage, after the row choice: no further {@code where} or {@code all()} compiles (R-WRT-12).
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static sealed class Options<E, K, M> permits Versioned, Narrowable, Resumable {

        final Draft<E, K, M> draft;

        private Options(Draft<E, K, M> draft) {
            this.draft = draft;
        }

        /** Leaves the root's {@code @Version} attribute as it is, instead of incrementing it (R-WRT-16). */
        public Options<E, K, M> keepVersion() {
            return new Options<>(draft.keep());
        }

        /**
         * Writes in chunks, as {@code options} states: each selects the next keys in key order, then writes them, so
         * no row is written twice and an update that leaves its rows matching terminates (R-WRT-17). With
         * {@code commitEachChunk()} each chunk commits on its own (R-WRT-19, R-WRT-20).
         */
        public Options<E, K, M> chunked(ChunkOptions options) {
            return new Options<>(draft.chunk(Objects.requireNonNull(options, "options"), null));
        }

        /** What the write does to the persistence context afterwards, over the executor's configured mode (D-62). */
        public Options<E, K, M> persistenceContext(PersistenceContextMode mode) {
            return new Options<>(draft.mode(Objects.requireNonNull(mode, "mode")));
        }

        /**
         * Writes through the entities instead of one bulk statement: each chunk selects its keys as
         * {@link #chunked(ChunkOptions)} does, loads those entities with one query, sets the assignments on them and
         * flushes, so entity callbacks, the provider's listeners, audits, Bean Validation and cascades run. Always
         * chunked: without {@code chunked(...)} the configured bulk-write chunk size applies. The count is the rows
         * matched, not the rows the provider wrote. With {@link PersistenceContextMode#CLEAR} the persistence context
         * is cleared after each chunk, and with {@code commitEachChunk()} the caller's is also cleared after the last
         * chunk; with {@code KEEP} it grows with every matched row. The flush checks and increments a
         * {@code @Version}: its {@code OptimisticLockException} reaches the caller as is, or with
         * {@code commitEachChunk()} as a {@link ChunkedWriteException} to resume after. The configured query timeout
         * applies to the key select and the load, not to the flush's statements (R-WRT-41 to R-WRT-47).
         *
         * <p>The provider's own write rules hold, as for any entity change: an attribute mapped
         * {@code @Column(updatable = false)}, or any attribute of a Hibernate {@code @Immutable} entity, a write
         * assignment's included, is written by the bulk statement but not here, while the count still says the row
         * matched (R-WRT-43, D-119).
         *
         * <p>{@code keepVersion()}, {@code expectVersion(...)} and {@code setExpression(...)} cannot be honoured on
         * entities: combined with this, in any order, {@link #build()} throws {@code MQ1610}. That happens when the
         * definition is built (for a {@code static final} constant, at class initialization), not at compile time.
         */
        @Incubating
        public Options<E, K, M> throughEntities() {
            return new Options<>(draft.throughEntities());
        }

        /**
         * Checks the definition and returns it, its keys converted, deduplicated and copied (D-66).
         *
         * @throws IllegalArgumentException for a composite key with the wrong number of components
         * @throws ModelQueryDefinitionException {@code MQ1601} when the rows were chosen by a {@code where} whose
         *     every filter was skipped, {@code MQ1602} for a column assigned twice, {@code MQ1604} for a column not
         *     on the root, as through a self-referencing join, {@code MQ1605} for a column of the primary key,
         *     {@code MQ1606} for {@code expectVersion} with {@code keepVersion} and nothing to write, {@code MQ1609}
         *     for {@code setExpression} on a column with a converter, {@code MQ1610} for {@code throughEntities()}
         *     with {@code keepVersion()}, {@code expectVersion(...)} or a {@code setExpression(...)}
         */
        public ModelUpdate<E, M> build() {
            return draft.build();
        }
    }

    /**
     * A single-key update, which may expect the row's version.
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static sealed class Versioned<E, K, M> extends Options<E, K, M> permits Keyed {

        private Versioned(Draft<E, K, M> draft) {
            super(draft);
        }

        /**
         * Writes the row only while its {@code @Version} attribute is {@code version}; zero rows written then throws
         * JPA's {@code OptimisticLockException}. A root with no {@code @Version}, or a value of the wrong type,
         * throws {@code MQ1606} on the definition's first execution (R-WRT-16, D-61). Call it before
         * {@code keepVersion()}, which returns the plain options stage (D-64).
         */
        public Options<E, K, M> expectVersion(Object version) {
            return new Options<>(draft.expect(Objects.requireNonNull(version, "version")));
        }
    }

    /**
     * The stage after {@code whereKey}: one {@code where} may narrow the row, then {@code expectVersion}.
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static final class Keyed<E, K, M> extends Versioned<E, K, M> {

        private Keyed(Draft<E, K, M> draft) {
            super(draft);
        }

        /** Narrows the row by {@code filters}, ANDed with the key; skipping every filter leaves the key alone. */
        public Versioned<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Versioned<>(draft.rows(draft.rows().and(FilterGroup.collect(
                    draft.root().rootEntity(), Objects.requireNonNull(filters, "filters")))));
        }
    }

    /**
     * The stage after {@code whereKeys}: one {@code where} may narrow the rows.
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static final class Narrowable<E, K, M> extends Options<E, K, M> {

        private Narrowable(Draft<E, K, M> draft) {
            super(draft);
        }

        /** Narrows the rows by {@code filters}, ANDed with the keys; skipping every filter leaves the keys alone. */
        public Options<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Options<>(draft.rows(draft.rows().and(FilterGroup.collect(
                    draft.root().rootEntity(), Objects.requireNonNull(filters, "filters")))));
        }
    }

    /**
     * The options stage of an update that chose its rows by {@code where} or {@code all()}, whose chunks run in key
     * order, so a chunked write can resume after a key.
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the update model
     */
    @Incubating
    public static final class Resumable<E, K, M> extends Options<E, K, M> {

        private Resumable(Draft<E, K, M> draft) {
            super(draft);
        }

        @Override
        public Resumable<E, K, M> keepVersion() {
            return new Resumable<>(draft.keep());
        }

        @Override
        public Resumable<E, K, M> chunked(ChunkOptions options) {
            return new Resumable<>(draft.chunk(Objects.requireNonNull(options, "options"), null));
        }

        /**
         * Writes in chunks as {@link #chunked(ChunkOptions)} does, starting after {@code startAfter}: only rows whose
         * key comes after it in key order are written. Given a {@link ChunkedWriteException#lastCommittedKey()}, it
         * resumes the write that threw; that is safe after an in-doubt chunk only once its keys were checked
         * (R-WRT-20, D-63).
         */
        public Resumable<E, K, M> chunked(ChunkOptions options, K startAfter) {
            return new Resumable<>(draft.chunk(Objects.requireNonNull(options, "options"),
                    Objects.requireNonNull(startAfter, "startAfter")));
        }

        @Override
        public Resumable<E, K, M> persistenceContext(PersistenceContextMode mode) {
            return new Resumable<>(draft.mode(Objects.requireNonNull(mode, "mode")));
        }

        @Incubating
        @Override
        public Resumable<E, K, M> throughEntities() {
            return new Resumable<>(draft.throughEntities());
        }
    }
}
