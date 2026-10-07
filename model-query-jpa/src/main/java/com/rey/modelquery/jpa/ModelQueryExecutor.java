package com.rey.modelquery.jpa;

import com.rey.modelquery.annotations.Incubating;
import com.rey.modelquery.core.CountMode;
import com.rey.modelquery.core.ExportOptions;
import com.rey.modelquery.core.KeysetSlice;
import com.rey.modelquery.core.KeysetSpec;
import com.rey.modelquery.core.Limit;
import com.rey.modelquery.core.ModelDelete;
import com.rey.modelquery.core.ModelInsert;
import com.rey.modelquery.core.ModelPersist;
import com.rey.modelquery.core.ModelQuery;
import com.rey.modelquery.core.ModelUpdate;
import com.rey.modelquery.core.PageSpec;
import com.rey.modelquery.core.Slice;
import com.rey.modelquery.core.ValuesInsert;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Runs model queries against a JPA {@code EntityManager}. An executor holds no mutable state beyond its
 * {@code EntityManager}, so it is as thread-safe as that is. The first execution of a {@code ModelQuery}, by any
 * executor, checks that its customizer narrows every phase alike (R-QRY-09, D-21); on a query with
 * {@code primaryKeyFirst(...)} a mismatch throws {@code MQ2206} before any query runs, whatever the method (R-PAG-15).
 * A customizer that changes a statement's ordering or grouping throws {@code MQ1205} (R-QRY-11).
 *
 * @param <E> the root entity type
 * @implSpec R-QRY-10, R-QRY-09, R-QRY-11, R-EXE-01, R-EXE-02, R-EXE-03, R-EXE-04, R-EXE-07, R-EXE-09, R-PAG-01,
 *     R-PAG-02, R-PAG-03, R-PAG-07, R-PAG-08, R-PAG-09, R-PAG-10, R-PAG-11, R-PAG-12, R-PAG-13, R-PAG-14, R-PAG-15,
 *     R-AGG-09, R-WRT-01, R-WRT-07, R-WRT-08, R-WRT-15, R-WRT-16, R-WRT-17, R-WRT-18, R-WRT-19, R-WRT-20,
 *     R-WRT-23, R-WRT-24, R-WRT-26, R-WRT-33, R-WRT-39, R-WRT-41, R-WRT-48, R-VND-14, D-61
 */
@Incubating
public interface ModelQueryExecutor<E> {

    /**
     * An executor over {@code em} for queries rooted at {@code rootEntity}; enough to use the library without Spring
     * (INV-8). The vendor profile of {@code em}'s factory is resolved on the first call for that factory and reused
     * after (R-VND-02).
     *
     * @throws com.rey.modelquery.core.ModelQueryConfigurationException {@code MQ4002} when two discovered
     *     {@code VendorProfile}s serve one vendor
     */
    static <E> ModelQueryExecutor<E> create(EntityManager em, Class<E> rootEntity, ModelQueryConfig config) {
        return new DefaultModelQueryExecutor<>(em, rootEntity, config);
    }

    /**
     * Runs {@code q} once and returns the mapped rows in order. {@code Limit.of(0)} returns an empty list without
     * querying (R-EXE-01).
     */
    <M> List<M> list(ModelQuery<E, ?, M> q, Limit limit);

    /**
     * Reads one page of {@code q}: an exact total, a {@code hasNext} probe, or the total alone (R-EXE-02). An invalid
     * page never reaches here: {@link PageSpec} rejects it when built (R-EXE-06). With {@code primaryKeyFirst(...)}, a
     * page whose offset is above its threshold is read in two steps, the page's primary keys in the query's order and
     * then the rows of those keys, in statements of at most the vendor's IN-list and bind-parameter limits; it holds
     * the same rows in the same order as the one-step page (R-PAG-07, R-PAG-08).
     *
     * @throws com.rey.modelquery.core.ModelQueryExecutionException for a two-step page: {@code MQ2204} for a query
     *     selecting a column through a to-many join, before any query runs (R-PAG-13), and {@code MQ2201} when a
     *     row's primary key is {@code null} (R-PAG-03); on any page of a query with {@code primaryKeyFirst(...)},
     *     {@code MQ2206} when its customizer narrows the phases differently, before any query runs (R-PAG-15)
     */
    <M> Slice<M> page(ModelQuery<E, ?, M> q, PageSpec page, CountMode mode);

    /**
     * Reads one keyset page of {@code q}: the first page, the rows after a cursor or the rows before one, of
     * {@code keyset.size()} rows, with the cursors that reach the neighbouring pages and no total (R-PAG-16). A cursor
     * is opaque, carries the boundary row's keyset values readably and is only understood by the order that issued it
     * (R-PAG-17, R-PAG-19); {@code before} reverses each key and its null rule and returns the page in the query's
     * order (R-PAG-20). The flags need no second statement (R-PAG-21), and a page through a cursor whose rows hold
     * that cursor's own primary key throws {@code MQ2205} (R-PAG-24). It needs a {@code keyset()} query and a fetch
     * plan runs on the page's content (R-PAG-16, R-PAG-23).
     *
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2207} for a query without
     *     {@code keyset()}, {@code MQ2204} for a selection read through a to-many join, {@code MQ2206} for a
     *     {@code primaryKeyFirst(...)} query whose customizer narrows the phases differently, {@code MQ2210} for a
     *     key column no codec carries, {@code MQ2209} for a cursor of another order and {@code MQ2208} for a decoded
     *     value of the wrong type or a NULL in a refusing or primary-key column, all before any query runs; after
     *     reading, {@code MQ2202} for a NULL in a refusing keyset column and {@code MQ2205} for the cursor's own key
     * @implSpec R-PAG-16, R-PAG-17, R-PAG-18, R-PAG-19, R-PAG-20, R-PAG-21, R-PAG-22, R-PAG-23, R-PAG-24
     */
    @Incubating
    <M> KeysetSlice<M> page(ModelQuery<E, ?, M> q, KeysetSpec keyset);

    /**
     * The number of rows {@code q} returns: the number of groups for a grouped query (R-EXE-03), the number of
     * distinct roots when a to-many join would inflate it, and the number of rows when a selected column is read
     * through one (R-EXE-04).
     */
    long count(ModelQuery<E, ?, ?> q);

    /**
     * Passes the rows of {@code q} to {@code body} as a stream read one at a time, and closes the stream when
     * {@code body} returns or throws, so an early exit releases the connection too (R-EXE-07, R-EXE-09). No method
     * returns an open stream, so a caller cannot leak a result set. {@code Limit.of(0)} passes an empty stream without
     * querying (R-EXE-06). For a single pass over a large result inside one transaction; keyset {@code export} is the
     * default for very large ones (R-EXE-10).
     *
     * @param body reads the stream and returns the result; it must not let the stream escape
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2101} on PostgreSQL outside a
     *     transaction, before any statement runs (R-EXE-08)
     */
    <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body);

    /**
     * Visits every row of {@code q} exactly once, one page of {@code options.pageSize()} rows at a time, {@code
     * ModelQueryConfig.exportPageSize()} when the options leave the size open (R-QRY-15), with memory bounded by one
     * page (INV-4). Offset pages are read in a stable order, the primary key appended to the query's order (R-PAG-01).
     * A row whose key the previous page or the same page already held is dropped: with a stable order a row can repeat
     * only across a page boundary, or within a page through a to-many join used only by predicates (R-PAG-02). A {@code
     * keyset()} query reads each page after the last row of the one before, its order closed by the primary key in the
     * direction of its last order column (R-PAG-04); it drops a key repeated within a page, and throws on a key of the
     * page before, which only a cursor value that does not compare equal once bound, or a row whose keyset value moved
     * after the cursor, can bring back (R-PAG-14); a {@code Float} or {@code Double} keyset column is refused at build
     * (R-QRY-13). A NULL in a keyset column needs an explicit {@code nullsFirst()} or {@code nullsLast()} (R-PAG-05).
     * An offset page past the {@code primaryKeyFirst(...)} threshold reads its primary keys first, then only the rows
     * of keys not already exported (R-PAG-07). A grouped query visits every group exactly once, offset-paged in an
     * order closed by its group keys, and dedupes on the group-key tuple; it needs no primary key and ignores one
     * (R-PAG-11, R-PAG-12, R-AGG-09). Each page's remaining models go whole to {@code pageTransformer}, and its items
     * one at a time to {@code sink} until {@code options.limit()} is reached; neither is called for an empty page
     * (R-PAG-09). {@code Limit.of(0)} exports nothing without querying.
     *
     * @param pageTransformer receives each page's models, to batch the caller's own lookups; returns the items to sink
     * @param sink receives the items one at a time
     * @return the number of items passed to {@code sink}, which differs from the rows read when
     *     {@code pageTransformer} expands or filters (R-PAG-10)
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2203} for an ungrouped query without a
     *     primary key, and {@code MQ2204} for an ungrouped one selecting a column through a to-many join (R-PAG-13),
     *     both before any query runs;
     *     {@code MQ2201} when a row's primary key is {@code null} (R-PAG-03); {@code MQ2202} when a keyset column
     *     without explicit null precedence is NULL (R-PAG-05); {@code MQ2205} when a keyset page repeats a row of
     *     the page before (R-PAG-14); {@code MQ2206}, before any query runs, when the customizer of a query with
     *     {@code primaryKeyFirst(...)} narrows the phases differently (R-PAG-15)
     */
    <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink);

    /**
     * Writes {@code u}'s assignments to the rows it chooses, in one {@code CriteriaUpdate} per run of keys, and returns
     * the rows affected. It loads no entity and runs no lifecycle callback, cascade, Bean Validation or Envers audit:
     * those stay with JPA entity writes (R-WRT-01). {@code whereKeys} writes each distinct key once, splitting the keys
     * across statements to the vendor's limits, and returns the summed count (R-WRT-08). An update that assigns nothing
     * and expects no version, or whose {@code whereKeys} received no key, runs no SQL and returns 0 (R-WRT-07). The
     * first execution of a definition per {@code EntityManagerFactory} checks it against the JPA metamodel, before any
     * statement (D-61).
     *
     * <p>Where the profile's {@code targetTableInSubquery()} is false (MySQL) and the update would read its own table
     * in a sub-query, through a {@code where} that needs a join, an {@code exists(...)} path back to the root entity
     * type, or a root in an inheritance hierarchy, it runs key-first: it selects the matching keys in key order with
     * the query engine, then writes them in runs sized to the vendor's limits with only the root predicates
     * re-applied, the top-level {@code AND} terms that need no join and no sub-query. A row that stopped matching on
     * its own columns in between is not written; a change to a joined row in between is not re-checked. With
     * {@code chunked(ChunkOptions...lockKeys())} the keys are selected with {@code PESSIMISTIC_WRITE}, so a
     * concurrent change to a selected row waits for the write, and on MySQL the select reads current rows rather than
     * the transaction's snapshot (R-WRT-11).
     *
     * <p>With {@code chunked(...)} it runs in rounds on every vendor: each selects the next keys in key order, after
     * the last round's or the {@code startAfter} key, and writes them, re-applying the whole {@code where} tree, or
     * only its root predicates where it would run key-first. A round takes the options' size, else
     * {@link ModelQueryConfig#bulkWriteChunkSize()}, within the vendor's limits less the statement's own binds. The
     * rounds stop on a key select returning fewer keys than that, so an update that leaves its rows matching
     * terminates and writes each row once (R-WRT-17). They run in the caller's transaction, unless the options say
     * {@code commitEachChunk()}: then each round, its key select and its write, runs in a new transaction through
     * {@link ModelQueryConfig#chunkTransactions()}, with no transaction needed on the caller's {@code EntityManager},
     * and the rounds committed before a failed one stay committed (R-WRT-19, R-WRT-20).
     *
     * <p>Pending entity changes are flushed first, when the {@code EntityManager} is joined to a transaction.
     * Afterwards the persistence context is cleared, unless the write's {@code persistenceContext(...)}, else
     * {@link ModelQueryConfig#persistenceContextMode}, is {@code KEEP}, and the root entity is evicted from the
     * second-level cache (R-WRT-15). Clearing detaches every managed entity, not only the root's, so a later change
     * to any of them is silently not written; with {@code KEEP} the root's entities stay managed but stale.
     *
     * <p>An update {@code throughEntities()} runs those rounds, then loads each round's entities with one query, sets
     * the assignments on them and flushes, so callbacks and listeners run; it returns the rows matched. It evicts
     * nothing, and clears after each round's flush unless the mode is {@code KEEP}, and the caller's
     * {@code EntityManager} once more after the last round with {@code commitEachChunk()}. The query timeout applies
     * to the key selects and the loads, not to the flushes (R-WRT-41 to R-WRT-47).
     *
     * @throws com.rey.modelquery.core.ModelQueryDefinitionException on first execution: {@code MQ1608} when the
     *     definition's primary key is not the root entity's id, {@code MQ1605} when a column writes an id or the
     *     {@code @Version} attribute, {@code MQ1606} for {@code expectVersion} on a root with no {@code @Version}
     *     attribute or with a value of another type, and for an update of a root whose {@code @Version} type cannot
     *     be incremented, unless {@code keepVersion()}, {@code MQ1001} for a to-one column of the wrong id type
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2501}, before any statement, when the
     *     {@code EntityManager} is not joined to a transaction and the write is not {@code commitEachChunk()}
     *     (R-WRT-18); key-first or chunked, {@code MQ2205} when a key select returns a key the round before already
     *     wrote (R-WRT-17)
     * @throws com.rey.modelquery.core.ChunkedWriteException {@code MQ2502} when a round of a
     *     {@code commitEachChunk()} write fails, carrying the committed rows, the last committed key and the keys of a
     *     round whose commit failed (R-WRT-20)
     * @throws com.rey.modelquery.core.ModelQueryConfigurationException {@code MQ4004}, before any statement, the
     *     flush included, for {@code commitEachChunk()} with no {@link ChunkTransactions} or one that cannot serve
     *     the {@code EntityManager}'s factory (R-WRT-19)
     * @throws jakarta.persistence.OptimisticLockException when {@code expectVersion} was given and no row was
     *     written: the row's version moved, or the row no longer matches (R-WRT-16); or, {@code throughEntities()}
     *     and not {@code commitEachChunk()}, when a flush finds a loaded entity's version moved (R-WRT-46)
     */
    @Incubating
    long update(ModelUpdate<E, ?> u);

    /**
     * Deletes the rows {@code d} chooses, in one {@code CriteriaDelete} per run of keys, and returns the rows affected.
     * It loads no entity and runs no lifecycle callback, cascade, Bean Validation or Envers audit: those stay with JPA
     * entity writes (R-WRT-01). {@code whereKeys} deletes each distinct key once, splitting the keys across statements
     * to the vendor's limits, and returns the summed count (R-WRT-08). A delete whose {@code whereKeys} received no key
     * runs no SQL and returns 0 (R-WRT-12). The first execution of a definition per {@code EntityManagerFactory} checks
     * it against the JPA metamodel, before any statement (D-61). The persistence context and the second-level cache are
     * handled as {@link #update} handles them (R-WRT-15), and so is a delete that runs key-first (R-WRT-11) or
     * chunked (R-WRT-17, R-WRT-19); a row a foreign key protects surfaces the provider's constraint exception
     * (R-WRT-18), as the cause of {@code MQ2502} with {@code commitEachChunk()}.
     *
     * <p>A delete {@code throughEntities()} runs those rounds, then loads each round's entities with one query, removes
     * each through the {@code EntityManager} and flushes, so callbacks, listeners, cascades ({@code REMOVE},
     * {@code orphanRemoval}) and the mapping's {@code @SQLDelete} run. It returns the entities matched, so it may
     * remove more rows than it counts. It evicts nothing, and clears after each round's flush unless the mode is
     * {@code KEEP}, and the caller's {@code EntityManager} once more after the last round with
     * {@code commitEachChunk()}. The query timeout applies to the key selects and the loads, not to the flushes
     * (R-WRT-41 to R-WRT-47).
     *
     * @throws com.rey.modelquery.core.ModelQueryDefinitionException on first execution: {@code MQ1608} when the
     *     definition's primary key is not the root entity's id
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2501}, before any statement, when the
     *     {@code EntityManager} is not joined to a transaction and the delete is not {@code commitEachChunk()}
     *     (R-WRT-18); key-first or chunked, {@code MQ2205} when a key select returns a key the round before already
     *     wrote (R-WRT-17)
     * @throws com.rey.modelquery.core.ChunkedWriteException {@code MQ2502} when a round of a
     *     {@code commitEachChunk()} delete fails (R-WRT-20), a {@code throughEntities()} flush's
     *     {@code OptimisticLockException} included (R-WRT-46)
     * @throws jakarta.persistence.OptimisticLockException {@code throughEntities()} and not {@code commitEachChunk()},
     *     when a flush finds a loaded entity's version moved (R-WRT-46)
     * @throws com.rey.modelquery.core.ModelQueryConfigurationException {@code MQ4004}, before any statement, as
     *     {@link #update} throws it (R-WRT-19)
     */
    @Incubating
    long delete(ModelDelete<E, ?> d);

    /**
     * Inserts {@code i}'s rows, an insert-select's or an insert-values', and returns the rows affected. It loads no
     * entity and runs no lifecycle callback, cascade, Bean Validation or Envers audit: {@link #persist} is the write
     * that runs them (R-WRT-24). It needs the provider's {@link com.rey.modelquery.jpa.spi.InsertSupport}, which
     * {@code model-query-hibernate} supplies. The first execution of a definition per {@code EntityManagerFactory}
     * checks it against the JPA metamodel and the root's generator, before any statement (D-61). The persistence
     * context and the second-level cache are handled as {@link #update} handles them (R-WRT-38).
     *
     * <p>An insert-select writes exactly the rows the equivalent read returns, a to-many source join's repeats
     * included: its select is built as the read path builds it. With {@code chunked} it runs key-first over the
     * distinct source-root ids, each chunk in the caller's transaction or, with {@code commitEachChunk()}, its own
     * (R-WRT-27, R-WRT-28).
     *
     * <p>With a conflict clause the count is the provider's, rows inserted plus rows updated, where the profile's
     * {@code conflictTargetHonoured()}; elsewhere the clause needs {@code anyUniqueKey()}, which accepts the vendor's
     * count: on MySQL a conflicting row counts 1 when skipped, filtered out or left unchanged and 2 when changed. A
     * {@code MERGE} vendor raises a unique violation for a key another transaction inserts at the same time
     * (R-WRT-35, R-WRT-36).
     *
     * @throws com.rey.modelquery.core.ModelQueryDefinitionException on first execution: {@code MQ1801} for an
     *     insert-select {@code map} between attributes of different types or reading a column off the source root,
     *     {@code MQ1802} for an id the model names against the root's generator, {@code MQ1805} for a generator or a
     *     mapping of the root that the insert cannot write, {@code MQ1806} for a chunked insert-select whose target
     *     is of one entity hierarchy with, or shares a table with, the source root or an entity the select joins, or
     *     whose tables the provider does not name, or whose source joins through a link or collection table, {@code MQ1804} for conflict columns that are not exactly a unique
     *     key the mapping declares, a conflict update assigning an id or the {@code @Version}, a vendor detecting a
     *     conflict on any unique key without {@code anyUniqueKey()}, a {@code doNothing()} the provider does not
     *     render, or a conflict update whose {@code where} reads two or more of the columns it assigns, the version
     *     increment included, unless {@code ModelQueryConfig.conflictUpdateWhereOnAssignedColumns(true)} on a vendor
     *     whose profile's {@code conflictWhereSeesEarlierAssignments()} is false (R-WRT-26, R-WRT-27, R-WRT-28,
     *     R-WRT-34, R-WRT-36)
     * @throws com.rey.modelquery.core.ChunkedWriteException {@code MQ2502} when a chunk of a
     *     {@code commitEachChunk()} insert-select fails; its keys are source-root ids (R-WRT-20, R-WRT-32)
     * @throws com.rey.modelquery.core.ModelQueryConfigurationException {@code MQ4009}, before any statement, the flush
     *     included, when no {@code InsertSupport} serves the {@code EntityManager}'s factory (R-VND-14);
     *     {@code MQ4004}, before any statement, as {@link #update} throws it (R-WRT-19)
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2501}, before any statement, when the
     *     {@code EntityManager} is not joined to a transaction and the insert is not {@code commitEachChunk()}
     *     (R-WRT-18)
     */
    @Incubating
    long insert(ModelInsert<E, ?> i);

    /**
     * Inserts {@code i}'s rows, as {@link #insert} does, and returns their keys in row order: the keys are drawn from
     * the root's generator before the statement, so a sequence, table or UUID generator can return them, and an
     * {@code IDENTITY} or assigned id cannot (R-WRT-33).
     *
     * @throws com.rey.modelquery.core.ModelQueryDefinitionException {@code MQ1801}, before any statement, for a
     *     definition with {@code commitEachChunk()}, whose failure would lose the committed rows' keys; on first
     *     execution, the codes of {@link #insert} and {@code MQ1807} when the key type is not the root's id type;
     *     {@code MQ1807} for an {@code IDENTITY} or assigned id (R-WRT-33, D-117)
     * @throws com.rey.modelquery.core.ModelQueryConfigurationException {@code MQ4009}, as {@link #insert} throws it
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2501}, as {@link #insert} throws it
     */
    @Incubating
    <K> List<K> insertReturningKeys(ValuesInsert<E, K, ?> i);

    /**
     * Writes {@code p}'s row as a new entity through JPA and returns its key. Entity attributes the model does not
     * name are written as the root's no-arg constructor leaves them, often NULL, not as the database default (unless
     * the mapping is {@code insertable = false}, generated, or the provider's dynamic insert), and a domain
     * constructor's invariants do not run.
     *
     * <p>The root is instantiated with its no-arg constructor and each model column set through the attribute's
     * metamodel member, the field or the setter beside the getter, after the column's converter; an embeddable on the
     * way is instantiated with its no-arg constructor, and a to-one column binds {@link EntityManager#getReference}.
     * Constructors, fields and setters are reached with {@code setAccessible}, so a modular application
     * {@code opens} its entity package to the library. Then {@code persist}, {@code flush},
     * {@code PersistenceUnitUtil#getIdentifier} and {@code detach} run, in that order.
     *
     * <p>It is an entity write: lifecycle callbacks, Bean Validation, Envers and the provider's insert run, with any
     * generator, {@code IDENTITY} included, the provider maintains the second-level cache, and it needs no provider
     * support. The flush writes the caller's pending changes too. {@code detach} cascades as the mapping says: a
     * to-one with {@code CascadeType.ALL} or {@code DETACH} detaches the instance {@code getReference} returned, which
     * is the caller's own managed entity when there is one, as {@code CLEAR} would; an entity a callback persisted
     * stays managed. It takes no {@code set}, conflict clause or chunking: many rows are a loop over {@code persist},
     * one statement each, or {@link #insert} and {@link #insertReturningKeys} (R-WRT-39, R-WRT-40).
     *
     * @throws com.rey.modelquery.core.ModelQueryDefinitionException on first execution, before any statement,
     *     {@code MQ1807} when the key type is not the root's id type, {@code MQ1805} when a column is set on a
     *     record or an embeddable with no no-arg constructor, and {@code MQ1802} when the model writes part of the id,
     *     or, where the provider reports the root's generator, an id it generates; {@code MQ1308}, before the
     *     statement, for a {@code null} set on a primitive attribute (R-WRT-39, D-117)
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2501}, before any statement, when the
     *     {@code EntityManager} is not joined to a transaction (R-WRT-39)
     */
    @Incubating
    <K> K persist(ModelPersist<E, K, ?> p);

    /**
     * Writes {@code persist}'s row as {@link #persist(ModelPersist)} does, up to its flush, then returns
     * {@code returning}'s model built from the managed entity before it is detached, with no further statement. The
     * query supplies only the selection, the mapper, {@code afterMap} and the finisher; each selected column is read
     * through its attribute's metamodel member and the column's converter. It can fill root attributes, embeddable
     * paths and the id of a to-one association, read without initializing the target; through an {@code INNER} join
     * field whose foreign key is null it fills {@code null}.
     *
     * <p>The model holds what JPA knows after the flush: a value the database fills, such as a column default or a
     * trigger's, is present only where the mapping has the provider read it back ({@code @Generated}). Everything
     * {@link #persist(ModelPersist)} documents about callbacks, the flush and {@code detach} holds (R-WRT-48).
     *
     * @param persist the row to write
     * @param returning the query whose model to return; its root is this executor's
     * @param <R> the returned model
     * @throws com.rey.modelquery.core.ModelQueryDefinitionException on first execution per
     *     {@code EntityManagerFactory}, before any statement, {@code MQ1809} for a query with {@code where},
     *     {@code having}, {@code groupBy}, a fetch plan, {@code customize}, {@code orderBy}, {@code keyset} or
     *     {@code primaryKeyFirst}, or a selected column the entity alone cannot fill (a join beyond a to-one id, a join
     *     with {@code on(...)}, an expression, an aggregate); and what {@link #persist(ModelPersist)} throws
     * @throws com.rey.modelquery.core.ModelQueryExecutionException {@code MQ2501}, before any statement, when the
     *     {@code EntityManager} is not joined to a transaction (R-WRT-39)
     */
    @Incubating
    <R> R persist(ModelPersist<E, ?, ?> persist, ModelQuery<E, ?, R> returning);
}
