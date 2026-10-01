package com.rey.modelquery.core;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Metamodel;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * An immutable bulk-delete definition: the rows to delete, and how the delete runs. Like a {@link ModelQuery} it holds
 * no Criteria object, so one instance can be shared by any number of threads (INV-9). A soft delete is a
 * {@link ModelUpdate}.
 *
 * <p>A delete loads no entity and runs no lifecycle callback, cascade, Bean Validation or Envers audit: those stay
 * with JPA entity writes (R-WRT-01).
 *
 * @param <E> the root entity
 * @param <M> the model whose key and filter columns the delete uses
 * @implSpec R-WRT-08, R-WRT-09, R-WRT-10, R-WRT-11, R-WRT-12, R-WRT-15, D-60, D-61, D-62, D-63
 */
@Incubating
public final class ModelDelete<E, M> {

    private final Draft<E, ?, M> definition;

    private ModelDelete(Draft<E, ?, M> definition) {
        this.definition = definition;
    }

    /**
     * Starts a delete of {@code root}'s rows. Each stage returns a new immutable builder; the order is the key, one
     * row choice, then options (D-60).
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

    /** The entity the delete removes rows of. */
    public Class<E> rootEntity() {
        return definition.root().rootEntity();
    }

    /** Whether the delete runs no statement: its {@code whereKeys} received no key (R-WRT-12). */
    public boolean writesNothing() {
        List<Object> keys = definition.rows().keys();
        return keys != null && keys.isEmpty();
    }

    /**
     * Checks the definition against {@code metamodel}, which {@code build()} cannot see (INV-7). An executor calls it
     * on the definition's first execution per {@code EntityManagerFactory}, before any statement (D-61).
     *
     * @throws ModelQueryDefinitionException {@code MQ1608} when the primary key does not name exactly the root
     *     entity's id attributes
     */
    public void checkMetamodel(Metamodel metamodel) {
        WriteRendering.checkKey(metamodel.entity(rootEntity()), definition.root(), definition.primaryKey(),
                modelName());
    }

    /**
     * Renders the delete against {@code cb}: one {@code CriteriaDelete} choosing the keys, then the {@code where}
     * tree, on the root when it needs no join and else whole in one {@code EXISTS} over a second root correlated by
     * the key columns (R-WRT-10).
     */
    public CriteriaDelete<E> buildWrite(CriteriaBuilder cb, RenderOptions options) {
        return render(cb, options, WriteRendering.keysToRender(distinctKeys().orElse(null), null), false);
    }

    /**
     * Renders the delete as {@link #buildWrite(CriteriaBuilder, RenderOptions)} does, choosing only the keys of
     * {@code chunk}, a run of {@link #distinctKeys()}: an executor splits the keys across statements to the vendor's
     * limits (R-WRT-08, D-63).
     *
     * @param chunk attribute-value keys, as {@link #distinctKeys()} returns them
     * @throws IllegalArgumentException for an empty {@code chunk}, or on a delete without {@code whereKey} or
     *     {@code whereKeys}
     */
    public CriteriaDelete<E> buildWrite(CriteriaBuilder cb, RenderOptions options, List<?> chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return render(cb, options, WriteRendering.keysToRender(distinctKeys().orElse(null), chunk), false);
    }

    /**
     * Renders one round of a key-first or chunked delete: {@code key IN (keys)}, the keys a
     * {@link #buildKeySelect key select} chose, and the {@code where} tree as {@link #buildWrite(CriteriaBuilder,
     * RenderOptions)} renders it, or with {@code rootTermsOnly} only its root terms, the top-level {@code AND} terms
     * that need no join and no sub-query. Re-applying them means a row that stopped matching on its own columns since
     * the key select is not deleted; a change to a joined row in between is not re-checked (R-WRT-11, R-WRT-17,
     * D-63).
     *
     * @param keys attribute-value keys, as a key select's rows hold them
     * @throws IllegalArgumentException for empty {@code keys}
     */
    public CriteriaDelete<E> buildWrite(CriteriaBuilder cb, RenderOptions options, List<?> keys,
            boolean rootTermsOnly) {
        return render(cb, options, WriteRendering.selectedKeys(Objects.requireNonNull(keys, "keys")), rootTermsOnly);
    }

    /**
     * Renders the key select of a key-first or chunked delete: the {@link #primaryKey()} columns of the rows the
     * delete chooses, {@code whereKey} or {@code whereKeys} and the {@code where} tree rendered as a read renders
     * them, joins included, with no order. An executor adds the keyset order, the cursor, the row limit and any lock;
     * the returned query maps no model (R-WRT-11, R-WRT-17, D-63).
     */
    public BuiltQuery<M> buildKeySelect(CriteriaBuilder cb, RenderOptions options) {
        return keySelect(cb, options, WriteRendering.keysToRender(distinctKeys().orElse(null), null));
    }

    /**
     * Renders the key select as {@link #buildKeySelect(CriteriaBuilder, RenderOptions)} does, choosing among the
     * keys of {@code chunk} only, a run of {@link #distinctKeys()} (R-WRT-08, D-63).
     *
     * @throws IllegalArgumentException for an empty {@code chunk}, or on a delete without {@code whereKey} or
     *     {@code whereKeys}
     */
    public BuiltQuery<M> buildKeySelect(CriteriaBuilder cb, RenderOptions options, List<?> chunk) {
        Objects.requireNonNull(chunk, "chunk");
        return keySelect(cb, options, WriteRendering.keysToRender(distinctKeys().orElse(null), chunk));
    }

    /**
     * Whether the statement {@link #buildWrite(CriteriaBuilder, RenderOptions)} renders reads the root's table in a
     * sub-query: the {@code where} tree needs a join (R-WRT-10), or an {@code exists(...)} path leads back to the
     * root entity type. Where the database refuses that, an executor runs the delete key-first (R-WRT-11, R-VND-11).
     */
    public boolean readsTargetInSubquery(CriteriaBuilder cb, RenderOptions options) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        return WriteRendering.readsTargetInSubquery(definition.rows().where(), rootEntity(), cb, options);
    }

    /** The primary key the delete chooses rows by, whose columns a key select reads (D-63). */
    public PrimaryKey<M, ?> primaryKey() {
        return definition.primaryKey();
    }

    /** The delete's {@code chunked(...)} options, or empty without them (R-WRT-11, R-WRT-17). */
    public Optional<ChunkOptions> chunkOptions() {
        return Optional.ofNullable(definition.chunkOptions());
    }

    /**
     * The distinct keys of {@code whereKey} or {@code whereKeys}, each converted to its attribute value (a list of
     * component values for a composite key), in first-seen order; empty when the rows were chosen without keys. Each
     * distinct key is deleted once (R-WRT-08, D-63).
     *
     * @throws IllegalArgumentException for a composite key with the wrong number of components
     */
    public Optional<List<Object>> distinctKeys() {
        List<Object> keys = definition.rows().keys();
        return keys == null ? Optional.empty()
                : Optional.of(WriteRendering.distinctKeys(definition.primaryKey(), keys));
    }

    /** The write's own {@code persistenceContext(...)}, which wins over the executor's configured mode (D-62). */
    public Optional<PersistenceContextMode> persistenceContext() {
        return Optional.ofNullable(definition.persistenceContext());
    }

    private BuiltQuery<M> keySelect(CriteriaBuilder cb, RenderOptions options, List<Object> keys) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        return WriteRendering.keySelect(keys, definition.rows().where(), definition.primaryKey(), rootEntity(), cb,
                options, modelName());
    }

    private String modelName() {
        return definition.primaryKey().columns().get(0).model().getSimpleName();
    }

    private CriteriaDelete<E> render(CriteriaBuilder cb, RenderOptions options, List<Object> keys,
            boolean rootTermsOnly) {
        Objects.requireNonNull(cb, "cb");
        Objects.requireNonNull(options, "options");
        CriteriaDelete<E> delete = cb.createCriteriaDelete(rootEntity());
        Root<E> from = delete.from(rootEntity());
        List<Predicate> where = WriteRendering.rows(keys, definition.rows().where(), definition.primaryKey(), delete,
                from, cb, options, rootTermsOnly);
        if (!where.isEmpty()) {
            delete.where(where.toArray(Predicate[]::new));
        }
        return delete;
    }

    /** Everything a stage holds; each stage call returns a copy with one part changed. */
    private record Draft<E, K, M>(
            TableField<E, E> root,
            PrimaryKey<M, K> primaryKey,
            WriteRows rows,
            ChunkOptions chunkOptions,
            PersistenceContextMode persistenceContext) {

        Draft<E, K, M> rows(WriteRows rows) {
            return new Draft<>(root, primaryKey, rows, chunkOptions, persistenceContext);
        }

        Draft<E, K, M> chunk(ChunkOptions options) {
            return new Draft<>(root, primaryKey, rows, options, persistenceContext);
        }

        Draft<E, K, M> mode(PersistenceContextMode mode) {
            return new Draft<>(root, primaryKey, rows, chunkOptions, mode);
        }
    }

    /**
     * The first stage: the delete's root, before its key.
     *
     * @param <E> the root entity
     */
    @Incubating
    public static final class Start<E> {

        private final TableField<E, E> root;

        private Start(TableField<E, E> root) {
            this.root = root;
        }

        /** The model's key, which {@code whereKey} and {@code whereKeys} take values of (R-WRT-08). */
        public <K, M> Builder<E, K, M> primaryKey(PrimaryKey<M, K> primaryKey) {
            return new Builder<>(new Draft<>(root, Objects.requireNonNull(primaryKey, "primaryKey"), null, null,
                    null));
        }
    }

    /**
     * The row-choice stage: exactly one of {@code whereKey}, {@code whereKeys}, {@code where} or {@code all()}
     * (R-WRT-12).
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the model
     */
    @Incubating
    public static final class Builder<E, K, M> {

        private final Draft<E, K, M> draft;

        private Builder(Draft<E, K, M> draft) {
            this.draft = draft;
        }

        /** Deletes the row whose key is {@code key}; a {@code where} may narrow it. */
        public Narrowable<E, K, M> whereKey(K key) {
            return new Narrowable<>(draft.rows(WriteRows.key(key)));
        }

        /**
         * Deletes the rows whose keys are in {@code keys}; a {@code where} may narrow them. An empty collection
         * affects nothing and runs no SQL (R-WRT-08, R-WRT-12).
         */
        public Narrowable<E, K, M> whereKeys(Collection<? extends K> keys) {
            return new Narrowable<>(draft.rows(WriteRows.keys(keys)));
        }

        /**
         * Deletes the rows {@code filters} matches: it receives an empty {@link Filters}, and every filter it adds is
         * ANDed. It runs once, here, as {@link ModelQuery.Builder#where} does. If every filter is skipped,
         * {@link Options#build()} throws {@code MQ1601}; {@link #all()} is the only way to delete every row
         * (R-WRT-12).
         */
        public Options<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Options<>(draft.rows(WriteRows.where(FilterGroup.collect(
                    Objects.requireNonNull(filters, "filters")))));
        }

        /** Deletes every row; no {@code where} follows (R-WRT-12). */
        public Options<E, K, M> all() {
            return new Options<>(draft.rows(WriteRows.everyRow()));
        }
    }

    /**
     * The options stage, after the row choice: no further {@code where} or {@code all()} compiles (R-WRT-12).
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the model
     */
    @Incubating
    public static sealed class Options<E, K, M> permits Narrowable {

        final Draft<E, K, M> draft;

        private Options(Draft<E, K, M> draft) {
            this.draft = draft;
        }

        /** Deletes in key-first chunks, as {@code options} states (R-WRT-17). */
        public Options<E, K, M> chunked(ChunkOptions options) {
            return new Options<>(draft.chunk(Objects.requireNonNull(options, "options")));
        }

        /** What the delete does to the persistence context afterwards, over the executor's configured mode (D-62). */
        public Options<E, K, M> persistenceContext(PersistenceContextMode mode) {
            return new Options<>(draft.mode(Objects.requireNonNull(mode, "mode")));
        }

        /**
         * Checks the definition and returns it.
         *
         * @throws ModelQueryDefinitionException {@code MQ1601} when the rows were chosen by a {@code where} whose
         *     every filter was skipped
         */
        public ModelDelete<E, M> build() {
            draft.rows().check(draft.primaryKey().columns().get(0).model().getSimpleName());
            return new ModelDelete<>(draft);
        }
    }

    /**
     * The stage after {@code whereKey} or {@code whereKeys}: one {@code where} may narrow the rows.
     *
     * @param <E> the root entity
     * @param <K> the primary-key type
     * @param <M> the model
     */
    @Incubating
    public static final class Narrowable<E, K, M> extends Options<E, K, M> {

        private Narrowable(Draft<E, K, M> draft) {
            super(draft);
        }

        /** Narrows the rows by {@code filters}, ANDed with the keys; skipping every filter leaves the keys alone. */
        public Options<E, K, M> where(UnaryOperator<Filters<M>> filters) {
            return new Options<>(draft.rows(draft.rows().and(FilterGroup.collect(
                    Objects.requireNonNull(filters, "filters")))));
        }
    }
}
