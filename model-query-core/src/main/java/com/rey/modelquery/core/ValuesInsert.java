package com.rey.modelquery.core;

import com.rey.modelquery.annotations.Incubating;
import jakarta.persistence.metamodel.Metamodel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An immutable insert-values definition without a conflict clause: rows given as insert-model instances, read once at
 * {@code build()} into the definition (INV-9), whose generated keys the executor's {@code insertReturningKeys} can
 * return in row order. With a conflict clause the definition is a plain {@link ModelInsert}, whose keys cannot be
 * asked for, since a skipped row would leave a key with no row (R-WRT-33).
 *
 * @param <E> the root entity the rows are written to
 * @param <K> the root's id type, fixed by the generated {@code insert(rows)} (D-117)
 * @param <M> the insert model
 * @implSpec R-WRT-29, R-WRT-30, R-WRT-32, R-WRT-33, D-60, D-61, D-117
 */
@Incubating
public final class ValuesInsert<E, K, M> extends ModelInsert<E, M> {

    private final Class<K> keyType;

    private ValuesInsert(InsertDraft<E, M> definition, Class<K> keyType) {
        super(definition);
        this.keyType = keyType;
    }

    /**
     * Starts an insert of {@code rows}, in list order. The list is copied now; each row is read once, at
     * {@code build()}. Each stage returns a new immutable builder: constants and a conflict clause, then options
     * (D-60).
     *
     * @param keyType the root's id type, which the definition's first execution checks against the provider's
     *     ({@code MQ1807})
     */
    public static <M, E, K> Rows<E, K, M> builder(InsertColumns<M, E> columns, Class<K> keyType,
            List<? extends M> rows) {
        return new Rows<>(InsertDraft.values(Objects.requireNonNull(columns, "columns"),
                Objects.requireNonNull(rows, "rows")), Objects.requireNonNull(keyType, "keyType"));
    }

    /** The root's id type, which keys are returned as. */
    public Class<K> keyType() {
        return keyType;
    }

    /**
     * {@inheritDoc}
     *
     * @throws ModelQueryDefinitionException also {@code MQ1807} when the key type is not the root's boxed id type, as
     *     an {@code orm.xml} mapping can make it (R-WRT-33, D-117)
     */
    @EngineFacing
    @Override
    public void checkMetamodel(Metamodel metamodel) {
        super.checkMetamodel(metamodel);
        InsertMetamodel.checkKeyType(metamodel.entity(rootEntity()), keyType, toString());
    }

    /**
     * Checks that this definition's keys can be returned: a write that commits each chunk would lose the committed
     * rows' keys when a later chunk fails. The executor calls it from {@code insertReturningKeys}, before the flush;
     * {@code build()} cannot, since it does not know which method the definition is given to (R-WRT-33, D-117).
     *
     * @throws ModelQueryDefinitionException {@code MQ1801} when the definition is chunked with
     *     {@code commitEachChunk()}
     */
    @EngineFacing
    public void checkKeysReturnable() {
        if (chunkOptions().map(ChunkOptions::commitsEachChunk).orElse(false)) {
            throw new ModelQueryDefinitionException(MqCode.MQ1801, this + ": insertReturningKeys(...) with "
                    + "commitEachChunk(), whose failure would lose the committed rows' keys; use insert(...), or "
                    + "chunk in the caller's transaction");
        }
    }

    /**
     * The first stage: {@code set} constants, a conflict clause, options, or {@code build()}.
     *
     * @param <E> the root entity
     * @param <K> the root's id type
     * @param <M> the insert model
     */
    @Incubating
    public static final class Rows<E, K, M> {

        private final InsertDraft<E, M> draft;
        private final Class<K> keyType;

        private Rows(InsertDraft<E, M> draft, Class<K> keyType) {
            this.draft = draft;
            this.keyType = keyType;
        }

        /**
         * Writes {@code value} to {@code column} in every row, one bind per row: a column the server fills, which the
         * insert model leaves out. There is no {@code setNull}: a model column writes NULL. A column the model has,
         * one set twice, or one not on the root throws {@code MQ1801} at {@code build()} (R-WRT-25, R-WRT-29).
         *
         * @throws ModelQueryDefinitionException {@code MQ1603} for a {@code null} value
         */
        public <C> Rows<E, K, M> set(ColumnField<?, E, C> column, C value) {
            return new Rows<>(draft.set(Assignment.of(Objects.requireNonNull(column, "column"), value)), keyType);
        }

        /**
         * Names the unique key a conflict is detected on: exactly the root's id, its natural id, or a declared unique
         * constraint ({@code @Column} or {@code @JoinColumn(unique = true)}, {@code @Table(uniqueConstraints)}),
         * checked on first execution ({@code MQ1804}). A unique index that exists only in a migration or in
         * {@code orm.xml} is not seen and is rejected: declare it in the mapping's annotations. Each column must be in
         * the model's {@code InsertColumns}, named once, else {@code build()} throws {@code MQ1804}; two rows sharing a
         * conflict-key tuple throw {@code MQ1808} at {@code build()} (R-WRT-34, R-WRT-37).
         */
        @SafeVarargs
        public final ModelInsert.Conflict<E, M> onConflict(ColumnField<M, E, ?> first, ColumnField<M, E, ?>... rest) {
            var keys = new ArrayList<ColumnField<M, E, ?>>();
            keys.add(Objects.requireNonNull(first, "first"));
            for (ColumnField<M, E, ?> column : Objects.requireNonNull(rest, "rest")) {
                keys.add(Objects.requireNonNull(column, "rest element"));
            }
            return new ModelInsert.Conflict<>(draft, List.copyOf(keys));
        }

        /**
         * Writes in chunks of at most {@code options}' size rows, each one statement; the vendor's bind and
         * {@code VALUES} limits may make a statement smaller. With {@code commitEachChunk()} each chunk commits on its
         * own; {@code lockKeys()} throws {@code MQ1801} at {@code build()}, since insert-values selects no key
         * (R-WRT-29, R-WRT-32).
         */
        public Options<E, K, M> chunked(ChunkOptions options) {
            return new Options<>(draft.chunk(Objects.requireNonNull(options, "options")), keyType);
        }

        /** What the insert does to the persistence context afterwards, over the executor's configured mode (D-62). */
        public Options<E, K, M> persistenceContext(PersistenceContextMode mode) {
            return new Options<>(draft.mode(Objects.requireNonNull(mode, "mode")), keyType);
        }

        /**
         * Checks the definition, reads every row once into it, and returns it (INV-9, R-WRT-30).
         *
         * @throws ModelQueryDefinitionException {@code MQ1803} for a {@code null} row, {@code MQ1802} for a row with
         *     a {@code null} assigned id, {@code MQ1801} for a {@code set} on a column the model has, a column set
         *     twice or a {@code set} column not on the root
         */
        public ValuesInsert<E, K, M> build() {
            return new ValuesInsert<>(draft.build(), keyType);
        }
    }

    /**
     * The options stage, after {@code chunked} or {@code persistenceContext}: no conflict clause follows.
     *
     * @param <E> the root entity
     * @param <K> the root's id type
     * @param <M> the insert model
     */
    @Incubating
    public static final class Options<E, K, M> {

        private final InsertDraft<E, M> draft;
        private final Class<K> keyType;

        private Options(InsertDraft<E, M> draft, Class<K> keyType) {
            this.draft = draft;
            this.keyType = keyType;
        }

        /** As {@link Rows#chunked}. */
        public Options<E, K, M> chunked(ChunkOptions options) {
            return new Options<>(draft.chunk(Objects.requireNonNull(options, "options")), keyType);
        }

        /** What the insert does to the persistence context afterwards, over the executor's configured mode (D-62). */
        public Options<E, K, M> persistenceContext(PersistenceContextMode mode) {
            return new Options<>(draft.mode(Objects.requireNonNull(mode, "mode")), keyType);
        }

        /**
         * Checks the definition, reads every row once into it, and returns it (INV-9, R-WRT-30).
         *
         * @throws ModelQueryDefinitionException as {@link Rows#build()}, and {@code MQ1801} for {@code lockKeys()}
         */
        public ValuesInsert<E, K, M> build() {
            return new ValuesInsert<>(draft.build(), keyType);
        }
    }
}
