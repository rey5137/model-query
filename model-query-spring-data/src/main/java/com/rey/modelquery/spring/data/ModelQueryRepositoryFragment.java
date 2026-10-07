package com.rey.modelquery.spring.data;

import com.rey.modelquery.core.ChunkOptions;
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
import com.rey.modelquery.jpa.ModelQueryExecutor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.function.SingletonSupplier;

/**
 * The implementation of {@link ModelQueryRepository} over one executor. Not named {@code ModelQueryRepositoryImpl},
 * which Spring Data would detect as a custom implementation in a scanned package.
 *
 * @implSpec R-SPR-01, R-SPR-03, R-SPR-04, R-SPR-07, R-SPR-10
 */
final class ModelQueryRepositoryFragment<E> implements ModelQueryRepository<E> {

    /** Built at startup, or on the first call when the repository is initialised lazily. */
    private final Supplier<ModelQueryExecutor<E>> executor;
    /**
     * Resolved on the first {@code stream}, {@code update} or {@code delete}, not at startup, so the transaction
     * manager need not exist yet when the repository is created.
     */
    private final Supplier<TransactionOperations> readOnlyTransactions;
    private final Supplier<TransactionOperations> writeTransactions;

    ModelQueryRepositoryFragment(Supplier<ModelQueryExecutor<E>> executor,
            Supplier<PlatformTransactionManager> transactions) {
        this.executor = executor;
        this.readOnlyTransactions = SingletonSupplier.of(() -> required(transactions.get(), true));
        this.writeTransactions = SingletonSupplier.of(() -> required(transactions.get(), false));
    }

    private static TransactionOperations required(PlatformTransactionManager transactionManager, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setReadOnly(readOnly);
        return template;
    }

    @Override
    public <M> ModelPage<M> findPage(ModelQuery<E, ?, M> q, Pageable pageable, CountMode mode) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(pageable, "pageable");
        Objects.requireNonNull(mode, "mode");
        PageSpec page = SpringPaging.pageSpec(pageable);
        ModelQuery<E, ?, M> sorted = q.orderedBy(SpringPaging.sortSpec(pageable.getSort()));
        Slice<M> slice = executor.get().page(sorted, page, mode);
        Long total = slice.total().isPresent() ? slice.total().getAsLong() : null;
        return new DefaultModelPage<>(slice.content(), pageable, slice.hasNext(), total);
    }

    @Override
    public <M> KeysetSlice<M> findKeysetPage(ModelQuery<E, ?, M> q, KeysetSpec keyset, Sort sort) {
        Objects.requireNonNull(q, "q");
        Objects.requireNonNull(keyset, "keyset");
        Objects.requireNonNull(sort, "sort");
        return executor.get().page(q.orderedBy(SpringPaging.sortSpec(sort)), keyset);
    }

    @Override
    public <M> List<M> findAll(ModelQuery<E, ?, M> q, Limit limit) {
        return executor.get().list(q, limit);
    }

    @Override
    public long count(ModelQuery<E, ?, ?> q) {
        return executor.get().count(q);
    }

    @Override
    public <M, R> R stream(ModelQuery<E, ?, M> q, Limit limit, Function<Stream<M>, R> body) {
        // The executor runs body and closes the stream before it returns, so the transaction covers all of the
        // caller's reading: PostgreSQL's cursor lives only inside one (R-SPR-03, R-EXE-08).
        return readOnlyTransactions.get().execute(status -> executor.get().stream(q, limit, body));
    }

    @Override
    public <M, S> long export(ModelQuery<E, ?, M> q, ExportOptions options,
            Function<List<M>, List<S>> pageTransformer, Consumer<S> sink) {
        return executor.get().export(q, options, pageTransformer, sink);
    }

    @Override
    public long update(ModelUpdate<E, ?> u) {
        Objects.requireNonNull(u, "u");
        return write(u.chunkOptions(), () -> executor.get().update(u));
    }

    @Override
    public long delete(ModelDelete<E, ?> d) {
        Objects.requireNonNull(d, "d");
        return write(d.chunkOptions(), () -> executor.get().delete(d));
    }

    @Override
    public long insert(ModelInsert<E, ?> i) {
        Objects.requireNonNull(i, "i");
        return write(i.chunkOptions(), () -> executor.get().insert(i));
    }

    @Override
    public <K> List<K> insertReturningKeys(ValuesInsert<E, K, ?> i) {
        Objects.requireNonNull(i, "i");
        // A commitEachChunk() definition fails MQ1801 in the executor, so there is always one transaction here.
        return inTransaction(() -> executor.get().insertReturningKeys(i));
    }

    @Override
    public <K> K persist(ModelPersist<E, K, ?> p) {
        Objects.requireNonNull(p, "p");
        return inTransaction(() -> executor.get().persist(p));
    }

    @Override
    public <R> R persist(ModelPersist<E, ?, ?> persist, ModelQuery<E, ?, R> returning) {
        Objects.requireNonNull(persist, "persist");
        Objects.requireNonNull(returning, "returning");
        return inTransaction(() -> executor.get().persist(persist, returning));
    }

    /**
     * Runs {@code write} in a transaction joined or opened on the repository's manager, unless {@code chunk} commits
     * each chunk: that write opens none, since each chunk commits on its own (R-SPR-10, R-WRT-19).
     */
    private long write(Optional<ChunkOptions> chunk, Supplier<Long> write) {
        if (chunk.map(ChunkOptions::commitsEachChunk).orElse(false)) {
            return write.get();
        }
        return inTransaction(write);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return writeTransactions.get().execute(status -> work.get());
    }
}
